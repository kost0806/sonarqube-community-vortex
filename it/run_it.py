"""End-to-end test: real SonarQube servers, real scanner analysis, the server plugin and the MCP server.

Usage (from the repository root, after `./mvnw package`):
    docker compose -f it/docker-compose.yml up -d
    python it/run_it.py
    docker compose -f it/docker-compose.yml down -v

Standard library only. Admin passwords are random per run; nothing is persisted.
"""
from __future__ import annotations

import base64
import json
import os
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SAMPLE = ROOT / "it" / "sample"
MCP_JAR = ROOT / "vortex-mcp" / "target" / "vortex-mcp-all.jar"
SCANNER_IMAGE = "sonarsource/sonar-scanner-cli:12.2.0.4256_8.1.0"
NETWORK = "vortex-it_it"
PROJECT = "vortex-it"
SECRET_PROJECT = "vortex-it-secret"
BRANCH = "feature/cart"


@dataclass(frozen=True)
class Server:
    name: str
    local_url: str
    network_host: str
    branch_support: bool


SERVERS = [
    Server("community-26.10", "http://127.0.0.1:9001", "sq-community", False),
    Server("branch-plugin-26.5", "http://127.0.0.1:9002", "sq-branch", True),
]

failures: list[str] = []


def check(label: str, condition: bool, detail: object = "") -> None:
    print(("  PASS " if condition else "  FAIL ") + label + ("" if condition else f"  -> {detail}"))
    if not condition:
        failures.append(label)


def http(method: str, url: str, auth: tuple[str, str] | str | None = None,
         params: dict[str, str | list[str]] | None = None) -> tuple[int, dict]:
    data = urllib.parse.urlencode(params or {}, doseq=True)
    if method == "GET" and data:
        url, body = url + "?" + data, None
    else:
        body = data.encode() if data else None
    request = urllib.request.Request(url, data=body, method=method)
    if isinstance(auth, tuple):
        request.add_header("Authorization", "Basic " + base64.b64encode(f"{auth[0]}:{auth[1]}".encode()).decode())
    elif isinstance(auth, str):
        request.add_header("Authorization", "Bearer " + auth)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read().decode()
            return response.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as error:
        raw = error.read().decode()
        try:
            return error.code, json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            return error.code, {"raw": raw[:200]}


def wait_until_up(server: Server, timeout_s: int = 900) -> None:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            status, body = http("GET", server.local_url + "/api/system/status")
            if status == 200 and body.get("status") == "UP":
                return
        except (urllib.error.URLError, ConnectionError, TimeoutError):
            pass
        time.sleep(5)
    raise SystemExit(f"{server.name} did not start within {timeout_s}s")


def bootstrap(server: Server) -> tuple[str, str]:
    """Sets a random admin password, creates the projects and policies.

    Returns (admin token used by the scanner, token of a least-privileged 'agent' user with Browse on PROJECT only).
    """
    password = "It-" + secrets.token_urlsafe(18) + "9!"
    status, _ = http("POST", server.local_url + "/api/users/change_password", ("admin", "admin"),
                     {"login": "admin", "previousPassword": "admin", "password": password})
    if status not in (200, 204):
        raise SystemExit(f"{server.name}: cannot set admin password (HTTP {status}); recreate the containers")
    auth = ("admin", password)
    _, token = http("POST", server.local_url + "/api/user_tokens/generate", auth, {"name": "vortex-it"})
    for key, visibility in ((PROJECT, "private"), (SECRET_PROJECT, "private")):
        http("POST", server.local_url + "/api/projects/create", auth, {"project": key, "name": key, "visibility": visibility})
    # the default permission template lets every logged-in user browse new projects: lock the secret one down
    for permission in ("user", "codeviewer"):
        http("POST", server.local_url + "/api/permissions/remove_group", auth,
             {"groupName": "sonar-users", "permission": permission, "projectKey": SECRET_PROJECT})
    setup_quality_profiles(server, auth)
    http("POST", server.local_url + "/api/users/create", auth,
         {"login": "agent", "name": "Agent", "password": "Ag-" + secrets.token_urlsafe(18) + "9!"})
    http("POST", server.local_url + "/api/permissions/add_user", auth,
         {"login": "agent", "permission": "user", "projectKey": PROJECT})
    _, agent_token = http("POST", server.local_url + "/api/user_tokens/generate", auth, {"name": "agent-it", "login": "agent"})
    settings = {
        "sonar.vortex.architecture.rules": {"value": "# keep the domain pure\ndeny com.acme.domain.** -> com.acme.infra.**"},
        "sonar.vortex.dependencies.denied": {"values": ["maven:log4j:log4j", "npm:moment"]},
        "sonar.vortex.guidelines": {"value": "Use constructor injection."},
    }
    for key, value in settings.items():
        status, body = http("POST", server.local_url + "/api/settings/set", auth, {"key": key, "component": PROJECT, **value})
        check(f"set {key}", status in (200, 204), body)
    return token["token"], agent_token["token"]


IT_RULES = {"java": ["java:S106", "java:S1068"], "py": ["python:S1481"]}


def setup_quality_profiles(server: Server, auth: tuple[str, str]) -> None:
    """Copies the default profiles, activates the rules the sample violates, and assigns them to the project.

    Default profiles differ between versions (26.10's 'Sonar way core' lacks these rules), and this also
    proves that profile customisations on the server reach the local analysis.
    """
    _, defaults = http("GET", server.local_url + "/api/qualityprofiles/search", auth, {"defaults": "true"})
    for language, rules in IT_RULES.items():
        default = next(p for p in defaults["profiles"] if p["language"] == language)
        _, copy = http("POST", server.local_url + "/api/qualityprofiles/copy", auth,
                       {"fromKey": default["key"], "toName": "Vortex IT"})
        for rule in rules:
            status, body = http("POST", server.local_url + "/api/qualityprofiles/activate_rule", auth,
                                {"key": copy["key"], "rule": rule})
            check(f"activate {rule}", status in (200, 204), body)
        status, body = http("POST", server.local_url + "/api/qualityprofiles/add_project", auth,
                            {"language": language, "qualityProfile": "Vortex IT", "project": PROJECT})
        check(f"assign 'Vortex IT' {language} profile", status in (200, 204), body)


def server_issue_rules(server: Server, token: str) -> dict[str, set[str]]:
    """Rule keys of the open issues the CI (scanner) analysis raised, per file of the main branch."""
    _, body = http("GET", server.local_url + "/api/issues/search", token,
                   {"componentKeys": PROJECT, "resolved": "false", "ps": "500"})
    by_file: dict[str, set[str]] = {}
    for issue in body.get("issues", []):
        path = issue["component"].split(":", 1)[1]
        by_file.setdefault(path, set()).add(issue["rule"])
    return by_file


def scan(server: Server, token: str, workspace: Path, branch: str | None) -> None:
    # the token is passed through the environment, not on the command line
    args = ["docker", "run", "--rm", "--network", NETWORK, "-e", "SONAR_TOKEN",
            "-e", f"SONAR_HOST_URL=http://{server.network_host}:9000", "-v", f"{workspace}:/usr/src", SCANNER_IMAGE,
            f"-Dsonar.projectKey={PROJECT}", "-Dsonar.sources=src,scripts", "-Dsonar.java.binaries=target/classes",
            "-Dsonar.scm.disabled=true", "-Dsonar.working.directory=/tmp/scannerwork"]
    if branch:
        args.append(f"-Dsonar.branch.name={branch}")
    result = subprocess.run(args, capture_output=True, text=True, env=dict(os.environ, SONAR_TOKEN=token))
    check(f"scanner analysis ({branch or 'main'})", result.returncode == 0, result.stdout[-1500:] + result.stderr[-500:])
    deadline = time.time() + 300
    while time.time() < deadline:
        _, body = http("GET", server.local_url + "/api/ce/component", token, {"component": PROJECT})
        if not body.get("queue") and body.get("current", {}).get("status") in ("SUCCESS", "FAILED"):
            check(f"compute engine task ({branch or 'main'})", body["current"]["status"] == "SUCCESS", body["current"])
            return
        time.sleep(3)
    check(f"compute engine task ({branch or 'main'})", False, "timeout")


def check_plugin(server: Server, token: str) -> None:
    _, plugins = http("GET", server.local_url + "/api/plugins/installed", token)
    keys = {p["key"] for p in plugins.get("plugins", [])}
    check("Community Vortex plugin installed", "communityvortex" in keys, sorted(keys))
    if server.branch_support:
        check("community branch plugin installed alongside", "communityBranchPlugin" in keys, sorted(keys))

    status, _ = http("GET", server.local_url + "/api/vortex/context", None, {"project": PROJECT})
    check("anonymous context call is rejected", status in (401, 403), status)

    status, context = http("GET", server.local_url + "/api/vortex/context", token, {"project": PROJECT})
    check("context returns 200", status == 200, context)
    languages = {p["language"] for p in context.get("qualityProfiles") or []}
    check("context lists java and py quality profiles", {"java", "py"} <= languages, languages)
    policy = context.get("policy", {})
    check("context has the architecture rule", [r["text"] for r in policy.get("architectureRules", [])]
          == ["deny com.acme.domain.** -> com.acme.infra.**"], policy)
    check("context has denied dependencies", policy.get("deniedDependencies") == ["maven:log4j:log4j", "npm:moment"], policy)
    check("context has measures", (context.get("size") or {}).get("ncloc", 0) > 0, context.get("size"))
    check("context has quality gate status", (context.get("qualityGate") or {}).get("status") in ("OK", "ERROR"), context.get("qualityGate"))

    status, branch_context = http("GET", server.local_url + "/api/vortex/context", token, {"project": PROJECT, "branch": BRANCH})
    if server.branch_support:
        check("branch context returns 200", status == 200, branch_context)
        check("branch context is scoped", (branch_context.get("scope") or {}).get("branch") == BRANCH, branch_context.get("scope"))
        check("branches include the analysed branch", BRANCH in [b["name"] for b in branch_context.get("branches") or []],
              branch_context.get("branches"))
        check("branch measures available", (branch_context.get("size") or {}).get("ncloc", 0) > 0, branch_context.get("size"))
    else:
        check("unknown branch is a 404 without branch support", status == 404, (status, branch_context))

    platform_status, _ = http("GET", server.local_url + "/api/components/show", token, {"component": SECRET_PROJECT})
    check("platform itself refuses the secret project", platform_status in (403, 404), platform_status)
    for endpoint in ("context", "policy"):
        status, body = http("GET", server.local_url + f"/api/vortex/{endpoint}", token, {"project": SECRET_PROJECT})
        check(f"{endpoint}: same answer as the platform for a project without Browse permission",
              status == platform_status, (status, body))

    status, policy_only = http("GET", server.local_url + "/api/vortex/policy", token, {"project": PROJECT})
    check("policy endpoint", status == 200 and policy_only["policy"]["guidelines"] == "Use constructor injection.", policy_only)

    # what the MCP server's RuleRepository sees, straight from the REST API
    for profile in context.get("qualityProfiles") or []:
        if profile["language"] in ("java", "py"):
            _, rules = http("GET", server.local_url + "/api/rules/search", token, {
                "qprofile": profile["key"], "activation": "true", "ps": "1",
                "f": "name,lang,severity,params,templateKey,internalKey,actives"})
            total = rules.get("paging", {}).get("total", rules.get("total"))
            check(f"REST: active {profile['language']} rules in '{profile['name']}' ({total})", (total or 0) > 0, rules)
    _, installed = http("GET", server.local_url + "/api/plugins/installed", token)
    print("  info analyzers:", sorted(f"{p['key']}:{p.get('version')}:ide={p.get('sonarLintSupported')}"
                                     for p in installed.get("plugins", []) if p["key"] in ("java", "python", "javascript")))


def mcp_session(server: Server, token: str, workspace: Path, calls: list[tuple[str, dict]]) -> list[dict]:
    env = dict(os.environ, SONAR_HOST_URL=server.local_url, SONAR_TOKEN=token, SONAR_PROJECT_KEY=PROJECT,
               VORTEX_WORKSPACE=str(workspace), VORTEX_CACHE_DIR=str(workspace.parent / "vortex-cache"))
    log = (workspace.parent / f"mcp-{server.name}.log").open("w", encoding="utf-8")
    proc = subprocess.Popen(["java", "-jar", str(MCP_JAR)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=log, env=env, text=True, encoding="utf-8")

    def send(message: dict) -> None:
        proc.stdin.write(json.dumps(message) + "\n")
        proc.stdin.flush()

    def receive(expected_id: int) -> dict:
        while True:
            line = proc.stdout.readline()
            if not line:
                raise SystemExit("MCP server exited unexpectedly")
            message = json.loads(line)  # any non-JSON stdout line would be a protocol violation
            if message.get("id") == expected_id:
                return message

    try:
        send({"jsonrpc": "2.0", "id": 1, "method": "initialize",
              "params": {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "it", "version": "1"}}})
        receive(1)
        send({"jsonrpc": "2.0", "method": "notifications/initialized"})
        results = []
        for i, (name, arguments) in enumerate(calls, start=2):
            send({"jsonrpc": "2.0", "id": i, "method": "tools/call", "params": {"name": name, "arguments": arguments}})
            result = receive(i)["result"]
            text = result["content"][0]["text"]
            results.append({"isError": result.get("isError"), "text": text,
                            "json": None if result.get("isError") else json.loads(text)})
        return results
    finally:
        proc.stdin.close()
        proc.wait(timeout=30)  # closing stdin must end the server on its own
        log.close()
        lines = (workspace.parent / f"mcp-{server.name}.log").read_text(encoding="utf-8").splitlines()
        noteworthy = [line for line in lines if "WARN" in line or "ERROR" in line or "skipped" in line.lower()]
        print("  info MCP stderr (warn/error):", *noteworthy[-15:], sep="\n    ")


def check_mcp(server: Server, token: str, workspace: Path) -> None:
    context, rules, verify = mcp_session(server, token, workspace, [
        ("vortex_get_context", {}),
        ("vortex_get_rules", {"language": "java", "query": "S106"}),
        ("vortex_verify_files", {"paths": ["src/main/java/com/acme/domain/Order.java", "scripts/report.py", "pom.xml"]}),
    ])
    check("MCP get_context succeeds", not context["isError"], context["text"][:300])
    scope = context["json"]["resolvedScope"] if context["json"] else {}
    expected_source = "GIT" if server.branch_support else "MAIN"
    check(f"MCP resolves git branch scope to {expected_source}", scope.get("source") == expected_source, scope)

    check("MCP get_rules finds java:S106", not rules["isError"]
          and "java:S106" in [r["key"] for r in rules["json"]["rules"]], rules["text"][:300])

    check("MCP verify succeeds", not verify["isError"], verify["text"][:500])
    report = verify["json"] or {}
    print("  info verify warnings:", report.get("warnings"), "unsupported:", report.get("unsupportedFiles"),
          "failed:", report.get("failedFiles"))
    issue_rules = {i["ruleKey"] for i in report.get("issues", [])}
    check("verify finds Java issues of the project's profile", {"java:S106", "java:S1068"} <= issue_rules, issue_rules)
    check("verify finds Python issues of the project's profile", "python:S1481" in issue_rules, issue_rules)

    local: dict[str, set[str]] = {}
    for issue in report.get("issues", []):
        local.setdefault(issue["file"], set()).add(issue["ruleKey"])
    ci = server_issue_rules(server, token)
    for file in ("src/main/java/com/acme/domain/Order.java", "scripts/report.py"):
        check(f"local verification matches the CI analysis for {file}", local.get(file, set()) == ci.get(file, set()),
              {"local": sorted(local.get(file, set())), "ci": sorted(ci.get(file, set()))})
    check("verify reports the architecture violation",
          [(v["target"], v["line"]) for v in report.get("architectureViolations", [])] == [("com.acme.infra.Db", 3)],
          report.get("architectureViolations"))
    check("verify reports the denied dependency",
          [d["dependency"] for d in report.get("dependencyViolations", [])] == ["log4j:log4j"], report.get("dependencyViolations"))
    check("verify does not pass", report.get("passed") is False, report.get("passed"))


def prepare_workspace(base: Path) -> Path:
    workspace = base / "sample"
    shutil.copytree(SAMPLE, workspace)
    (workspace / "target" / "classes").mkdir(parents=True)
    subprocess.run(["git", "init", "-q", "-b", BRANCH, str(workspace)], check=True)
    return workspace


def main() -> int:
    if not MCP_JAR.is_file():
        raise SystemExit("Build first: ./mvnw package")
    with tempfile.TemporaryDirectory(prefix="vortex-it-", ignore_cleanup_errors=True) as tmp:
        workspace = prepare_workspace(Path(tmp))
        for server in SERVERS:
            print(f"== {server.name} ({server.local_url})")
            wait_until_up(server)
            admin_token, agent_token = bootstrap(server)
            scan(server, admin_token, workspace, None)
            if server.branch_support:
                scan(server, admin_token, workspace, BRANCH)
            # everything an agent does runs with the least-privileged user
            check_plugin(server, agent_token)
            check_mcp(server, agent_token, workspace)
    print(f"\n{'FAILED: ' + str(len(failures)) + ' check(s)' if failures else 'ALL CHECKS PASSED'}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
