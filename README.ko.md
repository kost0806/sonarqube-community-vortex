# Community Vortex

[English](README.md) | **한국어**

[![CI](https://github.com/kost0806/sonarqube-community-vortex/actions/workflows/ci.yml/badge.svg)](https://github.com/kost0806/sonarqube-community-vortex/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/kost0806/sonarqube-community-vortex?include_prereleases)](https://github.com/kost0806/sonarqube-community-vortex/releases)
[![License: LGPL v3](https://img.shields.io/badge/license-LGPL--3.0-blue)](COPYING.LESSER)

**SonarQube Community Build**에서 코딩 에이전트에게 가이드와 검증을 제공하는 프로젝트입니다.
Sonar Vortex의 "Guide and Verify"와 같은 목적을 가지며, 다음 자료만을 바탕으로 클린룸 방식으로 만들었습니다.

- 공개 문서
- LGPL로 공개된 SonarQube Community 소스
- LGPL로 공개된 `sonarlint-core` 분석 엔진

> **비공식 프로젝트입니다.** SonarSource와 제휴하거나 승인받은 것이 아닙니다. "SonarQube"와
> "Sonar Vortex"는 SonarSource의 상표입니다. SonarSource의 상용 코드나 바이너리는 사용하거나
> 복제하지 않았고, 라이선스 검사도 우회하지 않습니다. Sonar Vortex는 SonarQube Server
> Enterprise/Data Center와 별도의 Vortex 구독이 있어야 쓸 수 있으며, 이 프로젝트가 그 기능을
> 잠금 해제해 주지는 않습니다.

## 제공 기능

| Sonar Vortex 기능 (공개 문서 기준) | Community Vortex | 비고 |
|---|---|---|
| Vortex context: 규칙, 아키텍처, 의존성 정책 | `api/vortex/context` (서버 플러그인) + `vortex_get_context`, `vortex_get_rules` (MCP) | 정책은 직접 정의하는 프로젝트 설정입니다 (아래 참고) |
| Vortex analysis: 에이전트가 작성한 파일을 몇 초 안에 검증 | `vortex_verify_files` (MCP), **공식 SonarQube CLI** (`sonar analyze`, `sonar analyze agentic`) | `sonarlint-core`로 **서버에 설치된 분석기와 활성 규칙 그대로** 분석하고, 아키텍처·의존성 정책도 함께 검사합니다. [SonarQube CLI](#공식-sonarqube-cli-사용) 참고 |
| 시맨틱 코드 탐색 | `vortex_search_symbols`, `vortex_file_outline`, `vortex_find_references`, `vortex_get_dependencies` | Java 참조는 JavaParser 심볼 해석으로 처리하고, JS/TS/Python은 구문 수준으로 인덱싱합니다 |
| Taint 분석 (인젝션) | **제공하지 않음** | 보안 엔진이 상용이라 Community 분석기에 포함되어 있지 않습니다 |
| 사용량 대시보드 / 과금 | 구현하지 않음 | |

## 호환성

| | 테스트 범위 |
|---|---|
| SonarQube Community Build | 26.5 – 26.10 (플러그인은 26.5의 plugin API 13.5로 컴파일) |
| [mc1arke/sonarqube-community-branch-plugin](https://github.com/mc1arke/sonarqube-community-branch-plugin) | SonarQube 26.5 + 플러그인 26.5.0 (`mc1arke/sonarqube-with-community-branch-plugin:26.5.0.122743-community`) |
| MCP 서버 | Java 21 이상, 로컬 stdio 서버를 지원하는 MCP 클라이언트 (Claude Code, Codex, Cursor 등) |

branch plugin의 최신 릴리스는 SonarQube **26.5**까지 지원합니다. 26.10 지원은 upstream에서 진행
중입니다(작성 시점 기준 PR #1306, 이슈 #1305). Community Vortex 자체는 두 버전 모두에서 동작합니다.
branch 지원이 있을 때는 다음과 같이 동작합니다.

* 모든 context 호출이 `branch` / `pullRequest`를 받아, 브랜치를 지원하는 웹 서비스로 그대로 전달합니다.
* MCP 서버는 SonarQube가 분석한 적이 있는 브랜치라면 **현재 체크아웃된 git 브랜치**를 자동으로
  사용합니다. 그렇지 않으면 메인 브랜치를 씁니다. 선택한 이유는 `resolvedScope`에 나옵니다.
* branch 지원이 없으면 같은 호출이 일반 Community Build와 똑같이 동작합니다(모르는 브랜치는 404).

## 다운로드

각 [릴리스](https://github.com/kost0806/sonarqube-community-vortex/releases)에는 다음 파일이 들어 있습니다.

| 파일 | 내용 |
|---|---|
| `community-vortex-plugin-<version>.jar` | SonarQube 서버 플러그인 |
| `community-vortex-mcp-<version>.jar` | MCP 서버 (의존성 포함, `java -jar`로 실행) |
| `community-vortex-server-<version>.jar` | SonarQube CLI용 분석 서비스 (의존성 포함, `java -jar`로 실행) |
| `SHA256SUMS`, `THIRD-PARTY.txt` | 체크섬, 포함된 라이브러리의 라이선스 목록 |

다운로드한 파일과 GitHub 빌드 출처(provenance)를 검증하는 방법:

```bash
sha256sum -c SHA256SUMS --ignore-missing
gh attestation verify community-vortex-mcp-<version>.jar --repo kost0806/sonarqube-community-vortex
```

## 서버 플러그인 설치

```bash
cp community-vortex-plugin-<version>.jar $SONARQUBE_HOME/extensions/plugins/
# SonarQube 재시작
```

직접 빌드할 수도 있습니다: `./mvnw package` → `vortex-plugin/target/vortex-plugin.jar`

정책은 **Project Settings → General Settings → Vortex (Community)**에서 정의합니다. 전체 기본값은
Administration에서 정할 수 있습니다.

| 설정 | 예시 |
|---|---|
| `sonar.vortex.architecture.rules` | `deny com.acme.domain.** -> com.acme.infra.**, java.sql.**`<br>`deny src/ui/** -> src/db/**` |
| `sonar.vortex.dependencies.denied` | `maven:log4j:log4j`, `npm:moment`, `pypi:pycrypto` |
| `sonar.vortex.dependencies.allowed` | 선택 사항인 허용 목록이며 문법은 같습니다. 목록에 적힌 생태계 안에서 목록에 없는 의존성을 보고합니다. `ecosystem:` 없이 쓴 항목은 모든 생태계에 적용됩니다 |
| `sonar.vortex.guidelines` | 에이전트에게 전달할 자유 형식 텍스트 |

아키텍처 패턴은 점으로 구분하는 패키지 glob 또는 슬래시로 구분하는 경로 glob입니다. `**`는 여러
세그먼트에 걸쳐 매칭되고, `*`는 한 세그먼트 안에서만 매칭됩니다. 규칙은 다음 대상에 적용됩니다.

- Java: 패키지와 import
- Python: 모듈
- JS/TS: 파일 경로 (상대 import는 경로로 해석)

Web API는 프로젝트에 *Browse* 권한이 필요하며, 호출자의 권한으로 실행됩니다.

* `GET api/vortex/context?project=KEY[&branch=B|&pullRequest=ID]`
* `GET api/vortex/policy?project=KEY`

## 코딩 에이전트 연결 (MCP)

릴리스의 `community-vortex-mcp-<version>.jar`를 사용합니다. 직접 빌드하면
`./mvnw package` → `vortex-mcp/target/vortex-mcp-all.jar`입니다.

Claude Code에서 한 프로젝트에만 등록하려면 해당 프로젝트 디렉터리 안에서 다음을 실행합니다.

```bash
claude mcp add --scope local community-vortex \
  -e SONAR_HOST_URL=https://sonarqube.example.com \
  -e SONAR_TOKEN=squ_xxx \
  -e SONAR_PROJECT_KEY=my_project \
  -- java -jar /path/to/community-vortex-mcp-<version>.jar
```

| 환경 변수 | 필수 | 의미 |
|---|---|---|
| `SONAR_HOST_URL` | 예 | SonarQube 기본 URL |
| `SONAR_TOKEN` | 예 | 프로젝트에 Browse 권한이 있는 **사용자** 토큰 (명령행에 넘기거나 로그에 남기지 않음) |
| `SONAR_PROJECT_KEY` | 예 | 프로젝트 키 |
| `VORTEX_WORKSPACE` | 아니요 | 프로젝트 디렉터리. 기본값은 작업 디렉터리 |
| `SONAR_BRANCH` / `SONAR_PULL_REQUEST` | 아니요 | git을 따르지 않고 브랜치 범위를 고정 |
| `VORTEX_CACHE_DIR` | 아니요 | 분석기 캐시. 기본값은 `~/.vortex` |
| `VORTEX_NODE` | 아니요 | JS/TS 분석에 쓸 Node.js. 기본값은 `PATH`의 `node` (절대 경로 항목만 사용) |
| `VORTEX_ALLOW_INSECURE_HTTP` | 아니요 | `true`면 로컬이 아닌 `http://` 서버를 허용 (권장하지 않음) |

도구 (모두 읽기 전용):

| 도구 | 용도 |
|---|---|
| `vortex_get_context` | 품질 프로파일, 품질 게이트, 정책, 가이드라인, 브랜치, 로컬 모듈 지도 |
| `vortex_get_rules` | 활성 규칙 (키, 이름, 심각도, 파라미터), 필터와 페이징 |
| `vortex_verify_files` | 변경 파일에 대한 로컬 분석 + 아키텍처·의존성 검사. 아무것도 보고되지 않을 때만 `passed` |
| `vortex_search_symbols` / `vortex_file_outline` | 파일 전체를 읽지 않고 선언 조회 |
| `vortex_find_references` | 사용처. `RESOLVED`(시맨틱 해석), `UNRESOLVED`(이름은 같지만 해석으로 확인하지 못함), `TEXTUAL` |
| `vortex_get_dependencies` | 파일의 import 목록과 그 파일을 import하는 파일 |

어떤 언어를 처음 검증할 때는 서버의 분석기를 캐시로 내려받습니다(`api/plugins/download`,
SonarQube for IDE가 쓰는 것과 같은 jar). 이후에는 캐시를 재사용합니다. 활성 규칙은 5분마다,
정책은 1분마다 새로 가져옵니다. 서버의 분석기 버전이 바뀌면 MCP 서버를 재시작해야 합니다.

## 공식 SonarQube CLI 사용

[SonarQube CLI](https://github.com/SonarSource/sonarqube-cli)(LGPL-3.0)는 Vortex 분석 요청을 서버의
`POST /api/v2/a3s/analyses`로 보냅니다. Community Build에서는 플러그인이 이 엔드포인트를 받아
**Community Vortex 분석 서비스**로 전달합니다. 분석 서비스는 같은 요청·응답 형식을 독자적으로 구현한
것입니다.

```
sonar analyze ──► SonarQube (사용자 인증) ──► 플러그인: /api/v2/a3s/* ──► vortex-server
                                                                            │ 사용자 권한 확인,
                                                                            │ 규칙·분석기·정책 조회
                                            SonarQube Web API ◄─────────────┘ (사용자 본인의 토큰으로)
```

1. SonarQube 옆에서 분석 서비스를 실행합니다. `vortex-server/Dockerfile`로 이미지를 만들거나,
   `java -jar community-vortex-server-<version>.jar`로 실행합니다(Java 21 이상).

   | 환경 변수 | 기본값 | 의미 |
   |---|---|---|
   | `SONAR_HOST_URL` | 필수 | 분석 서비스에서 접근할 SonarQube 주소. 예: `http://sonarqube:9000` |
   | `VORTEX_PORT` / `VORTEX_BIND` | `9100` / `0.0.0.0` | 수신 주소. 내부 네트워크에만 두세요 |
   | `VORTEX_CACHE_DIR` | `~/.vortex-server` | 분석기 캐시와 요청별 임시 파일 |
   | `VORTEX_NODE` | `PATH`의 `node` | JavaScript/TypeScript 분석용 Node.js (이미지에 포함) |
   | `VORTEX_MAX_FILES` | `100` | 요청당 파일 수. 큰 변경분은 CLI가 나눠서 보냅니다 |

2. SonarQube에 분석 서비스 주소를 지정하고 재시작합니다. 환경 변수
   `SONAR_VORTEX_ANALYSIS_URL=http://vortex-server:9100`을 쓰거나, `sonar.properties`에
   `sonar.vortex.analysis.url`을 설정합니다. SonarQube 앞에 리버스 프록시가 있다면 수 MB 크기의 요청
   본문을 허용해야 합니다.

```yaml
services:
  sonarqube:
    image: sonarqube:26.10.0.133078-community
    environment:
      SONAR_VORTEX_ANALYSIS_URL: http://vortex-server:9100
    volumes:
      - ./community-vortex-plugin-<version>.jar:/opt/sonarqube/extensions/plugins/community-vortex-plugin.jar:ro
  vortex-server:
    build: ./vortex-server   # 또는 vortex-server/Dockerfile로 만든 이미지
    environment:
      SONAR_HOST_URL: http://sonarqube:9000
```

3. CLI는 평소처럼 사용합니다.

```bash
sonar auth login --server https://sonarqube.example.com
sonar analyze --file src/main/java/com/acme/Order.java --project my_project
sonar analyze --project my_project            # git 변경분
sonar analyze agentic --staged --project my_project
```

결과에는 분석기 규칙 키(`java:S106` 등)와 함께, 프로젝트 정책 위반을 나타내는 `vortex:architecture`,
`vortex:dependency`가 나옵니다. 종료 코드 51은 이슈가 발견됐다는 뜻입니다.

**Claude Code 루프.** `sonar integrate claude`는 서버가 SonarSource의 독점 context 엔진도 제공할 때만
Vortex 훅을 설치합니다. Community Vortex는 그 엔진을 흉내 내지 않으므로, 대신 함께 제공하는 훅을
사용합니다. 이 훅은 파일을 수정할 때마다 `sonar analyze`를 실행하고 결과를 Claude에게 돌려줍니다
(`jq` 필요).

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Edit|Write|MultiEdit",
        "hooks": [{ "type": "command", "command": "/path/to/integrations/claude-code/sonar-analyze-hook.sh" }]
      }
    ]
  }
}
```

**제공하지 않는 기능**
- `sonar context` / Vortex Context: SonarSource의 독점 `sonar-context-augmentation` 엔진입니다.
  컨텍스트는 위의 MCP 도구를 사용하세요.
- `sonar analyze dependency-risks`: SCA 기능이며 Enterprise 전용입니다.
- `sonar remediate`: SonarQube Cloud 전용입니다.

CAG entitlement 엔드포인트에는 응답하지 않습니다. 따라서 CLI는 해당 기능을 실행하다 실패하는 대신
"사용할 수 없음"으로 안내합니다.

## 빌드와 테스트

```bash
./mvnw verify                # 단위 테스트 + 모듈별 라인 커버리지 80% 기준
```

`LocalAnalyzerTest`는 실제 SonarLint 엔진에 실제 Python 분석기를 붙여 실행합니다. 분석기는 Maven
Central에서 `target/test-analyzers`로 복사하며, 테스트 classpath에는 올리지 않습니다.

실제 서버를 상대로 한 E2E 테스트 (Docker):

```bash
./mvnw package
docker compose -f it/docker-compose.yml up -d
python it/run_it.py
docker compose -f it/docker-compose.yml down -v
```

이 테스트는 다음을 수행합니다.

1. SonarQube 26.10(branch 지원 없음)과 SonarQube 26.5 + community branch plugin을, 각각의 분석 서비스와 함께 띄웁니다.
2. `it/sample`을 공식 scanner로 분석합니다(메인 브랜치와 `feature/cart`).
3. 웹 서비스를 검증합니다.
4. stdio로 MCP 서버를 구동해 검증합니다. 로컬 검증 결과가 CI 분석 결과와 일치하는지도 확인합니다.
5. 공식 SonarQube CLI(`SONAR_CLI` 또는 `PATH`의 `sonar`)로 두 서버 모두에서 Vortex 분석을 검증합니다.

## 릴리스

CI(`.github/workflows/ci.yml`)는 push와 pull request마다 단위 테스트와 E2E를 실행합니다.
릴리스를 배포하려면 시맨틱 버전 태그를 push합니다.

```bash
git tag v0.1.0 && git push origin v0.1.0     # v0.2.0-rc.1 같은 태그는 pre-release로 배포
```

`.github/workflows/release.yml`은 다음 순서로 진행합니다.

1. 태그의 버전으로 빌드합니다.
2. 같은 단위 테스트와 E2E를 실행합니다.
3. 모두 통과한 경우에만 GitHub Release를 만듭니다. 릴리스에는 jar, `SHA256SUMS`, `THIRD-PARTY.txt`,
   빌드 출처 증명(attestation)이 첨부됩니다.

*Actions → Release → Run workflow*로 dry run을 실행하면 배포하지 않고 산출물만 워크플로 아티팩트로
보관합니다.

## 보안 참고 사항

* **토큰 보호**
  - 토큰은 환경 변수로만 읽습니다.
  - HTTP 리다이렉트를 따라가지 않으므로 다른 호스트로 새지 않습니다.
  - 로그, 명령행, 자식 프로세스 어디에도 전달하지 않습니다.
  - 로컬이 아닌 서버는 `https://`를 써야 하며, 인증 정보가 포함된 URL은 거부합니다.
* **분석기 jar 검증**: 분석기 jar는 MCP 서버의 JVM에 로드되므로, 사용하기 전에 서버가
  `api/plugins/installed`로 공개하는 MD5와 대조합니다. 캐시된 jar도 매번 검증합니다.
* **경로 제한**: 도구 호출로 들어온 모든 경로는 작업 공간 안으로 제한됩니다. 심볼릭 링크를 통한
  우회도 막습니다.
* **입력 처리**: 작업 공간 파일은 신뢰하지 않는 입력으로 취급합니다. 인덱서와 매니페스트 파서는
  선형 시간 패턴을 쓰고 줄·파일 크기에 상한을 두므로, 조작된 파일로 서버를 멈출 수 없습니다.
  회귀 테스트로 확인합니다.
* **종료**: stdin이 닫히면 서버 프로세스가 종료됩니다.
* **권한**: 플러그인의 웹 서비스는 같은 프로세스 안에서 호출을 전달하므로 SonarQube 자체의 권한
  검사가 그대로 적용됩니다. 예상하지 못한 내부 오류는 클라이언트에 그대로 노출하지 않습니다.
* **stdout**: MCP의 stdout에는 프로토콜만 실리고, 그 밖의 출력은 stderr로 보냅니다.
* **분석 서비스**: 누구도 신뢰하지 않습니다.
  - 플러그인이 요청을 전달하기 전에 SonarQube가 호출자를 인증합니다.
  - 분석 서비스는 호출자 본인의 토큰으로 프로젝트 접근 권한을 다시 확인합니다.
  - 전달받은 파일 경로를 검증(상대 경로, `..` 금지)한 뒤 요청마다 별도 디렉터리에 쓰고, 처리 후 삭제합니다.
  - 요청 크기와 파일 수에 상한이 있습니다.

## 디렉터리 구조

```
vortex-policy/   아키텍처 규칙 DSL과 의존성 정책 (공용)
vortex-plugin/   SonarQube 서버 플러그인: 설정 + api/vortex/*
vortex-mcp/      stdio MCP 서버: 컨텍스트, 로컬 검증, 코드 탐색
vortex-server/   SonarQube CLI 분석 요청에 응답하는 분석 서비스 (+ Dockerfile)
integrations/    수정 후 SonarQube CLI를 실행하는 Claude Code 훅
it/              docker-compose + E2E 테스트
```

라이선스: LGPL-3.0 (SonarQube Community Build, sonarlint-core와 같음)
