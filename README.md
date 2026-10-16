# Community Vortex

[![CI](https://github.com/kost0806/sonarqube-community-vortex/actions/workflows/ci.yml/badge.svg)](https://github.com/kost0806/sonarqube-community-vortex/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/kost0806/sonarqube-community-vortex?include_prereleases)](https://github.com/kost0806/sonarqube-community-vortex/releases)
[![License: LGPL v3](https://img.shields.io/badge/license-LGPL--3.0-blue)](COPYING.LESSER)

Agent guidance and verification for **SonarQube Community Build**, in the spirit of Sonar Vortex
("guide and verify"), built clean-room from the public documentation, the LGPL SonarQube Community
sources and the LGPL `sonarlint-core` analysis engine.

> **Unofficial.** Not affiliated with or endorsed by SonarSource. "SonarQube" and "Sonar Vortex" are
> trademarks of SonarSource. No SonarSource commercial code, binaries or license checks are used,
> copied or bypassed. Sonar Vortex itself requires SonarQube Server Enterprise/Data Center plus a
> Vortex subscription; this project does not unlock it.

## What you get

| Sonar Vortex capability (public docs) | Community Vortex | Notes |
|---|---|---|
| Vortex context: rules, architecture, dependency policies | `api/vortex/context` (server plugin) + `vortex_get_context`, `vortex_get_rules` (MCP) | Policies are project settings you define (see below) |
| Vortex analysis: verify agent-written files in seconds | `vortex_verify_files` (MCP) | Runs the **server's own analyzers and active rules** locally via `sonarlint-core`, plus architecture and dependency checks |
| Semantic code navigation | `vortex_search_symbols`, `vortex_file_outline`, `vortex_find_references`, `vortex_get_dependencies` | Java references are resolved with the JavaParser symbol solver; JS/TS/Python use syntax-level indexing |
| Taint analysis (injection) | **Not available** | The security engine is commercial; Community analyzers do not ship it |
| Usage dashboard / metering | Not implemented | |

## Compatibility

| | Tested |
|---|---|
| SonarQube Community Build | 26.5 – 26.10 (plugin compiled against plugin API 13.5, the API of 26.5) |
| [mc1arke/sonarqube-community-branch-plugin](https://github.com/mc1arke/sonarqube-community-branch-plugin) | 26.5.0 on SonarQube 26.5 (`mc1arke/sonarqube-with-community-branch-plugin:26.5.0.122743-community`) |
| MCP server | Java 21+; any MCP client that supports local stdio servers (Claude Code, Codex, Cursor, ...) |

The branch plugin's latest release supports SonarQube **26.5**; support for 26.10 is pending upstream
(PR #1306, issue #1305 at the time of writing). Community Vortex itself runs on both versions. With
branch support:

* every context call accepts `branch` / `pullRequest`, forwarded to the branch-aware web services;
* the MCP server automatically uses the **checked-out git branch** when SonarQube has analysed it,
  and falls back to the main branch otherwise (`resolvedScope` explains the choice);
* without branch support the same calls behave exactly like plain Community Build (unknown branch → 404).

## Download

Each [release](https://github.com/kost0806/sonarqube-community-vortex/releases) contains:

| Asset | What |
|---|---|
| `community-vortex-plugin-<version>.jar` | SonarQube server plugin |
| `community-vortex-mcp-<version>.jar` | MCP server (self-contained, `java -jar`) |
| `SHA256SUMS`, `THIRD-PARTY.txt` | checksums, licenses of bundled libraries |

Verify a download (and its GitHub build provenance):

```bash
sha256sum -c SHA256SUMS --ignore-missing
gh attestation verify community-vortex-mcp-<version>.jar --repo kost0806/sonarqube-community-vortex
```

## Install the server plugin

```bash
cp community-vortex-plugin-<version>.jar $SONARQUBE_HOME/extensions/plugins/
# restart SonarQube
```

(or build it: `./mvnw package` → `vortex-plugin/target/vortex-plugin.jar`)

Define the policies under **Project Settings → General Settings → Vortex (Community)** (or globally under
Administration):

| Setting | Example |
|---|---|
| `sonar.vortex.architecture.rules` | `deny com.acme.domain.** -> com.acme.infra.**, java.sql.**`<br>`deny src/ui/** -> src/db/**` |
| `sonar.vortex.dependencies.denied` | `maven:log4j:log4j`, `npm:moment`, `pypi:pycrypto` |
| `sonar.vortex.dependencies.allowed` | optional allow list, same syntax; within the ecosystems it names (all, for entries without `ecosystem:`), anything else is reported |
| `sonar.vortex.guidelines` | free text handed to agents |

Architecture patterns are dotted package globs or slash path globs: `**` spans any number of segments,
`*` stays inside one. Rules are checked against Java packages and imports, Python modules, and
JS/TS file paths (relative imports are resolved).

Web API (requires *Browse* on the project; calls run with the caller's permissions):

* `GET api/vortex/context?project=KEY[&branch=B|&pullRequest=ID]`
* `GET api/vortex/policy?project=KEY`

## Connect a coding agent (MCP)

Use `community-vortex-mcp-<version>.jar` from a release (or `./mvnw package` →
`vortex-mcp/target/vortex-mcp-all.jar`). Claude Code, registered for one project only (run inside the project directory):

```bash
claude mcp add --scope local community-vortex \
  -e SONAR_HOST_URL=https://sonarqube.example.com \
  -e SONAR_TOKEN=squ_xxx \
  -e SONAR_PROJECT_KEY=my_project \
  -- java -jar /path/to/community-vortex-mcp-<version>.jar
```

| Variable | Required | Meaning |
|---|---|---|
| `SONAR_HOST_URL` | yes | SonarQube base URL |
| `SONAR_TOKEN` | yes | **user** token with Browse on the project (never passed on the command line or logged) |
| `SONAR_PROJECT_KEY` | yes | project key |
| `VORTEX_WORKSPACE` | no | project directory, defaults to the working directory |
| `SONAR_BRANCH` / `SONAR_PULL_REQUEST` | no | pin the branch scope instead of following git |
| `VORTEX_CACHE_DIR` | no | analyzer cache, defaults to `~/.vortex` |
| `VORTEX_NODE` | no | Node.js for JS/TS analysis, defaults to `node` on `PATH` (absolute entries only) |
| `VORTEX_ALLOW_INSECURE_HTTP` | no | `true` to allow a non-local `http://` server (not recommended) |

Tools (all read-only):

| Tool | Purpose |
|---|---|
| `vortex_get_context` | profiles, quality gate, policies, guidelines, branches, local module map |
| `vortex_get_rules` | active rules (key, name, severity, params), filter/paging |
| `vortex_verify_files` | local analysis + architecture + dependency checks on changed files; `passed` only when nothing is reported |
| `vortex_search_symbols` / `vortex_file_outline` | declarations without reading whole files |
| `vortex_find_references` | usages; `RESOLVED` (semantic), `UNRESOLVED` (name match the solver could not confirm), `TEXTUAL` |
| `vortex_get_dependencies` | imports of a file and the files importing it |

The first verification of a language downloads the server's analyzer (`api/plugins/download`, the same
jars SonarQube for IDE uses) into the cache; later runs reuse it. Active rules are refreshed every
5 minutes and policies every minute; a new analyzer version on the server needs an MCP server restart.

## Build and test

```bash
./mvnw verify                # unit tests + 80% line coverage gate per module
```

`LocalAnalyzerTest` runs the real SonarLint engine with the real Python analyzer (copied from Maven
Central into `target/test-analyzers`, never on the test classpath).

End-to-end against real servers (Docker):

```bash
./mvnw package
docker compose -f it/docker-compose.yml up -d
python it/run_it.py
docker compose -f it/docker-compose.yml down -v
```

It starts SonarQube 26.10 (no branch support) and 26.5 with the community branch plugin, analyses
`it/sample` with the official scanner (main branch and `feature/cart`), checks the web services and
drives the MCP server over stdio.

## Releasing

CI (`.github/workflows/ci.yml`) runs the unit tests and the end-to-end suite on every push and pull
request. To publish a release, push a semantic-version tag:

```bash
git tag v0.1.0 && git push origin v0.1.0     # v0.2.0-rc.1 publishes a pre-release
```

`.github/workflows/release.yml` builds that exact version, runs the same unit and end-to-end tests,
and only then creates the GitHub Release with the jars, `SHA256SUMS`, `THIRD-PARTY.txt` and build
provenance attestations. *Actions → Release → Run workflow* does a dry run that keeps the assets as a
workflow artifact without publishing.

## Security notes

* The token is read from the environment only; HTTP redirects are not followed so it cannot leak to
  another host; it is never logged, never put on a command line and not passed to child processes.
  Non-local servers must use `https://`; URLs with embedded credentials are rejected.
* Analyzer jars are loaded into the MCP server's JVM, so each one is checked against the MD5 the server
  publishes in `api/plugins/installed` before use (cached jars too).
* Every path from a tool call is confined to the workspace, including through symlinks.
* Workspace files are untrusted input: the indexers and manifest parsers use linear-time patterns with
  line and file size limits, so a crafted file cannot stall the server (regression-tested).
* Closing stdin ends the server process.
* The plugin's web services forward calls in-process, so SonarQube's own permission checks apply;
  unexpected upstream errors are not echoed to clients.
* MCP stdout carries only the protocol; anything else is redirected to stderr.

## Layout

```
vortex-policy/   architecture rule DSL and dependency policy (shared)
vortex-plugin/   SonarQube server plugin: settings + api/vortex/*
vortex-mcp/      stdio MCP server: context, local verification, navigation
it/              docker-compose + end-to-end test
```

License: LGPL-3.0 (same as SonarQube Community Build and sonarlint-core).
