# automation-platform — Claude Code 프로젝트 초기 설정 가이드

> 최종 업데이트: 2026-04-09
> 대상 환경: macOS, Claude Code
> 기술 스택: Java 17 (Gradle) + Python 3.11 (groupware-bot)

---

## 설치 위치 범례

이 가이드의 모든 항목에 아래 아이콘으로 Scope를 표기합니다.

```
🔵 개인 전체 (user-level)
   경로: ~/.claude/ 또는 ~/.claude.json
   범위: 내 머신의 모든 프로젝트에 적용
   git:  커밋 안 됨 (개인 환경)
   설치: 팀원 각자 1회

🟢 프로젝트 공유 (project-level)
   경로: <프로젝트>/.claude/ 또는 <프로젝트>/.mcp.json
   범위: 이 프로젝트에서만, 팀 전원 적용
   git:  커밋 대상 (팀 공유)
   설치: 최초 설정자 1회 → git push → 팀 전원 자동 적용

🟡 프로젝트 개인 (local)
   경로: ~/.claude.json [project] 또는 .claude/settings.local.json
   범위: 이 프로젝트에서만, 나만 적용
   git:  커밋 안 됨 (개인)
   설치: 팀원 각자 1회
```

Claude Code 설정 탐색 순서: `🟢 프로젝트 .claude/` → `🔵 ~/.claude/`
양쪽에 같은 파일이 있으면 프로젝트 쪽이 우선합니다.

---

## 1. 🔵 사전 준비 (팀원 각자 — 터미널)

```bash
# ── 1-1. Claude Code 설치 ─────────────────────────────────
npm install -g @anthropic-ai/claude-code

# ── 1-2. GitHub CLI 설치 및 인증 ──────────────────────────
# Git/GitHub 작업은 MCP가 아닌 gh CLI 사용 (Anthropic 공식 권장)
# 개인 설치: 커밋 author + GitHub 인증 = 반드시 개인 계정
brew install gh
gh auth login
# → 프로토콜: HTTPS
# → 권한: repo, workflow, gist 선택

# git 사용자 정보 설정 (커밋 author로 기록됨)
git config --global user.name "이름"
git config --global user.email "name@company.com"

# ── 1-3. Java 17 확인 ─────────────────────────────────────
java -version   # Java 17+ 필수
# 미설치 시: brew install openjdk@17

# 인증 확인:
gh auth status
```

---

## 2. ECC 설치 (Everything Claude Code)

> ECC = 에이전트 + 스킬 + 보안 + 커맨드 하네스 시스템
> 448개 파일 (전 언어 rules, 에이전트 ~50개, 커맨드 ~70개, 스킬 ~40개)
>
> ⚠️ `/plugin marketplace add`는 SSH/HTTPS clone 실패가 빈번합니다.
> git clone 수동 설치를 권장합니다.

### ⚠️ install.sh의 실제 동작

```
install.sh 실행 시 출력:
  Adapter: claude-home
  Install root: /Users/<user>/.claude
```

**`install.sh`는 `~/.claude/`(🔵 개인 전체)에 설치됩니다. 프로젝트에 설치되지 않습니다.**
언어 인자(typescript, python 등)와 무관하게 전 언어 rules가 설치됩니다.

### 권장 전략

```
┌───────────────────────────────────────────────────────────────────────┐
│  Step 1. 🔵 install.sh → ~/.claude/ 에 전체 설치 (팀원 각자 1회)    │
│  Step 2. 🟢 프로젝트 .claude/ 에는 프로젝트 특화 파일만 작성        │
│           (이미 구성 완료 — 별도 복사 불필요)                        │
└───────────────────────────────────────────────────────────────────────┘

이유:
• ECC 범용 rules/agents/skills/commands는 ~/.claude/ 전역 설치로 충분
• Claude Code가 프로젝트 → user 순서로 탐색하므로 양쪽이 합쳐져 동작
• 프로젝트에 범용 파일을 복사하면 전역과 100% 중복 → 컨텍스트 낭비
• 프로젝트 .claude/에는 이 프로젝트만의 특화 규칙만 유지
```

### 2-1. 🔵 ECC 개인 설치 — git clone → install.sh (팀원 각자 1회)

> **⚠️ ECC는 npm에 등록되어 있지 않습니다.**
> `npx ecc`, `npx ecc-install` 등은 동작하지 않습니다.
> 유일한 설치 방법은 아래 git clone → install.sh 입니다.

#### Step A. ECC 클론

```bash
# ── 보관 디렉토리 생성 ───────────────────────────────────
mkdir -p ~/Work/tools
cd ~/Work/tools

# ── git clone ────────────────────────────────────────────
git clone https://github.com/affaan-m/everything-claude-code.git

# 클론 확인
ls everything-claude-code/
# AGENTS.md  CHANGELOG.md  CLAUDE.md  agents/  commands/  install.sh  rules/  skills/ ...
```

#### Step B. install.sh 실행

```bash
cd ~/Work/tools/everything-claude-code

# ── install.sh 실행 ─────────────────────────────────────
# 인자로 언어를 지정하지만, 실제로는 전 언어 rules가 설치됨
./install.sh java

# 출력 예시:
#   Adapter: claude-home
#   Install root: /Users/<사용자>/.claude
#   ...
#   ✅ Installation complete!
```

> **install.sh 실행 시 에러가 나면:**
> - `Permission denied` → `chmod +x install.sh` 후 재실행
> - `command not found: bash` → `bash install.sh java`로 실행
> - 기존 `~/.claude/` 파일과 충돌 경고 → Y 입력하여 덮어쓰기

#### Step C. 설치 확인

```bash
# 아래 디렉토리들이 생성되었는지 확인
ls ~/.claude/rules/          # 전 언어 rules
ls ~/.claude/agents/         # 에이전트 (~50개)
ls ~/.claude/commands/       # 슬래시 커맨드 (~70개)
ls ~/.claude/skills/         # 스킬 (~40개)

# 파일 수 확인
echo "agents: $(ls ~/.claude/agents/*.md 2>/dev/null | wc -l | tr -d ' ')개"
echo "commands: $(ls ~/.claude/commands/*.md 2>/dev/null | wc -l | tr -d ' ')개"
echo "skills: $(ls -d ~/.claude/skills/*/ 2>/dev/null | wc -l | tr -d ' ')개"
# 예상: agents ~47개, commands ~79개, skills ~50개 이상
```

#### Step D. Claude Code에서 동작 확인

```bash
# 프로젝트 디렉토리에서 Claude Code 실행
cd ~/Work/workspace/automation-platform
claude

# Claude Code 내에서 스킬 목록 확인
/skills
# → ECC 스킬들이 목록에 표시되면 설치 성공
```

---

### 2-2. 🟢 프로젝트 .claude/ — 프로젝트 특화 파일만 (이미 구성 완료)

> **ECC 범용 파일을 프로젝트에 복사하지 않습니다.**
> 범용 rules/agents/skills/commands는 Step 2-1의 전역 설치로 충분합니다.
> 프로젝트 `.claude/`에는 **이 프로젝트만의 특화 규칙**만 유지합니다.

#### ⚠️ 왜 ECC 범용 파일을 프로젝트에 복사하면 안 되는가?

```
~/.claude/rules/common/coding-style.md   ← 전역에 이미 존재
.claude/rules/coding-style.md            ← 프로젝트에 복사하면 100% 중복

문제점:
• 동일 내용이 2번 로딩되어 컨텍스트 윈도우 낭비
• 프로젝트 특화 rules와 범용 rules가 한 폴더에 섞여 관리 혼란
• 범용 rules 업데이트 시 프로젝트 복사본은 자동 갱신 안 됨 (구버전 고착)
```

#### 프로젝트 .claude/ 현재 구성 (팀 공유, git 커밋 대상)

```
.claude/
├── rules/                    ← 프로젝트 특화 규칙 (9개, 파일 경로 매칭 시 자동 로딩)
│   ├── common-clients.md     ← 예외, Enum, SlackBlockBuilder, TokenProvider, API 클라이언트
│   ├── ingest.md             ← SlackFacade 라우팅, 병렬 초기화, SQS 위임
│   ├── worker.md             ← 메시지 디스패치, CalendarService, DynamoDB
│   ├── scheduler.md          ← 보고서 파이프라인, Collector, Confluence
│   ├── groupware.md          ← ECS 오케스트레이션, KMS 봉투 암호화
│   ├── lambda-patterns.md    ← 3초 제한, pre-warm, static volatile 캐싱
│   ├── calendar-model.md     ← Google Calendar 이벤트 모델
│   ├── env-vars.md           ← 모듈별 환경변수 참조 (필수/선택)
│   └── agents.md             ← 에이전트 사용 가이드 (전역 ECC 참조)
├── commands/                 ← 워크플로우 커맨드 (4개: resolve-issue, create-issue, feature-breakdown, resolve-conflict)
├── skills/                   ← 프로젝트 특화 스킬 (2개: lambda-deployment, slack-modal-patterns)
├── settings.json             ← 팀 공유 Hooks (컴파일, 린트, 푸시 차단, 민감 파일 보호)
└── settings.local.json       ← 개인 오버라이드 (.gitignore)
```

이 파일들은 이미 git에 커밋되어 있으므로, 프로젝트를 클론하면 자동 적용됩니다.
새 규칙 추가가 필요한 경우에만 직접 작성 후 git push.

### 2-3. [참고] 🔵 Java 보완: developer-kit 플러그인

> ECC의 Java 스킬이 부족하다면, Java/Spring Boot 전용 플러그인을 추가로 설치할 수 있습니다.

```bash
# ── developer-kit (Java/Spring Boot 특화 플러그인) ────────
# 역할: Spring Boot, LangChain4J, AWS SDK, GraalVM Native Image
#        spec → tasks 변환, 아키텍처 리뷰, 코드 리뷰 에이전트
#
# ⚠️ developer-kit-core는 /plugin install로 인식되지 않음 (메타데이터 누락)
#    developer-kit-java만 설치하면 Java 관련 스킬 전체 사용 가능

# 마켓플레이스 설치 (Claude Code 내부):
/plugin marketplace add giuseppe-trisciuoglio/developer-kit
/plugin install developer-kit-java@developer-kit
/reload-plugins   # 설치 후 활성화

# 실패 시 git clone:
git clone https://github.com/giuseppe-trisciuoglio/developer-kit.git
cp -r developer-kit/plugins/developer-kit-java ~/.claude/plugins/
```

---

## 3. MCP 서버 설치

### 3-1. 🟢 팀 공유 MCP (--scope project)

> .mcp.json 에 기록 → git 커밋 대상 → 팀 전원 동일 환경
> 프로젝트 루트(automation-platform/)에서 실행

```bash
# ── Context7: 최신 라이브러리 문서 조회 ───────────────────
# 역할: 버전별 정확한 문서/코드 예시, AI 할루시네이션 방지
# 주요 활용: AWS SDK v2, Jackson, Google Calendar API, Slack API 문서
claude mcp add context7 --scope project -- \
  npx -y @anthropic-ai/mcp-context7

# ── Sequential Thinking: 복잡한 로직 단계별 분석 ──────────
# 역할: 복잡한 문제를 단계별로 분해하여 분석
# 주요 활용: Lambda 동시성 문제, SQS 메시지 흐름 분석
claude mcp add sequential-thinking --scope project -- \
  npx -y @anthropic-ai/mcp-sequential-thinking

# ── Playwright: 브라우저 자동화 테스트 ────────────────────
# 역할: 웹 UI 테스트, 스크린샷, 브라우저 시나리오 자동화
# 주요 활용: groupware-bot 시나리오 검증
claude mcp add playwright --scope project -- \
  npx -y @anthropic-ai/mcp-playwright
```

### 3-2. 🔵 개인 전역 MCP (--scope user) — 선택사항

> ~/.claude.json 에 기록 → 모든 프로젝트에서 사용, git 커밋 안 함
> 개인 API 키가 필요한 도구 → 팀원 각자 1회 실행

```bash
# ── Sentry: 프로덕션 에러 모니터링 (선택) ────────────────
# 역할: 스택 트레이스, 브레드크럼 조회, 디버깅
# Sentry 계정이 있는 경우에만 설치
claude mcp add sentry --scope user \
  --transport http https://mcp.sentry.dev/mcp
# → 헤더 없이 추가 후, 첫 Sentry 도구 호출 시 브라우저에서 OAuth 인증이 트리거됨
```

---

## 4. 🔵 플러그인 설치 (팀원 각자 — Claude Code 내부)

> 플러그인은 ~/.claude/plugins/에 설치됨 (🔵 개인 전체)
>
> ⚠️ 플러그인 마켓플레이스 clone이 실패할 경우
> git clone 수동 설치 방법을 함께 기재합니다.

### 4-1. LSP 플러그인 (코드 자동 진단)

```bash
# ── 마켓플레이스 설치 (Claude Code 내부) ──────────────────
# 역할: 파일 편집 후 타입 에러, 미사용 임포트, 누락 리턴 자동 감지

# Java (Eclipse JDT Language Server) — 필수
/plugin install jdtls@claude-code-lsps

# ── 실패 시 git clone 수동 설치 ──────────────────────────
git clone https://github.com/anthropics/claude-code-lsps.git
cp -r claude-code-lsps/jdtls ~/.claude/plugins/
```

### 4-2. dx 플러그인 (개발자 경험 도구) — 선택사항

```bash
# ── 마켓플레이스 설치 (Claude Code 내부) ──────────────────
# 역할: /dx:clone, /dx:handoff, /dx:gha 커맨드
#        CLAUDE.md 자동 개선 스킬 포함
/plugin install dx@ykdojo

# ── 실패 시 setup.sh로 설치 ───────────────────────────────
# jq 필요: brew install jq
mkdir -p ~/Work/tools && cd ~/Work/tools
git clone https://github.com/ykdojo/claude-code-tips.git
cd claude-code-tips
bash scripts/setup.sh
# dx 플러그인 + 상태바 + alias 등 일괄 설정 (항목별 선택 가능)
```

---

## 5. 🟢 Hooks 설정 (최초 설정자 1회 → git push)

> 파일: .claude/settings.json (프로젝트 루트)
> git 커밋 대상 → 팀 전원 동일 Hook 적용
> CLAUDE.md는 ~80% 따르지만, Hooks는 100% 보장 (결정론적)
>
> ※ ECC가 ~/.claude/hooks/hooks.json 에 기본 hooks를 설치했을 수 있음
>   아래는 🟢 프로젝트 팀 공유용이며, ECC hooks와 별도로 동작함

```jsonc
// .claude/settings.json (팀 공유 — git 커밋)
{
  "hooks": {

    // ── 빌드 검증 (Java 컴파일 + Python 구문 체크) ─────────
    // Java: 모듈 감지 → gradlew compileJava
    // Python: groupware-bot/*.py → py_compile
    "PostToolUse": [
      {
        "matcher": "Edit|Write",
        "hooks": [
          {
            "type": "command",
            "command": "if echo \"$CLAUDE_FILE_PATH\" | grep -q 'groupware-bot/.*\\.py$'; then python3 -m py_compile \"$CLAUDE_FILE_PATH\" 2>&1; elif MODULE=... (모듈 감지 후 컴파일)"
          }
        ]
      }
    ],

    // ── main 브랜치 직접 푸시 차단 ───────────────────────
    "PreToolUse": [
      {
        "matcher": "Bash",
        "hooks": [{ "type": "command", "command": "git push main 감지 시 차단" }]
      },

      // ── 민감 파일 보호 (google-credentials.json) ─────────
      {
        "matcher": "Read|Edit|Write",
        "hooks": [{ "type": "command", "command": "google-credentials.json 접근 시 차단" }]
      }
    ]
  }
}
```

> 실제 커맨드는 `.claude/settings.json` 파일을 직접 참조하세요 (위는 구조 설명용 요약).

> **macOS 알림 (Stop 훅)**은 OS 의존적이므로 `settings.local.json`(개인용)에 배치:
> ```jsonc
> // .claude/settings.local.json (개인용 — .gitignore)
> {
>   "hooks": {
>     "Stop": [{
>       "hooks": [{ "type": "command",
>         "command": "osascript -e 'display notification \"Claude 작업 완료\" with title \"Claude Code\"'" }]
>     }]
>   }
> }
> ```

---

## 6. 🟢 CLAUDE.md (이미 구성됨)

> 파일: 프로젝트 루트 /CLAUDE.md
> git 커밋 대상 → 팀 전원 동일 규칙 적용

이 프로젝트는 이미 CLAUDE.md, `.claude/rules/`, `.claude/commands/`, WORKFLOW.md 등이 구성되어 있습니다.
프로젝트를 클론하면 자동 적용됩니다.

```
CLAUDE.md                        ← 프로젝트 전체 개요, 빌드, 코딩 컨벤션
WORKFLOW.md                      ← 개발 워크플로우 (feature-breakdown → create-issue → resolve-issue)
SPEC.md                          ← 구현 상태, Phase별 개선 계획
automation-platform-prd.md       ← 제품 요구사항 문서
scripts/create-worktree.sh       ← git worktree 생성 + Claude 실행

.claude/rules/ (9개, 파일 경로 매칭 시 자동 로딩)
├── common-clients.md            ← 예외, Enum, SlackBlockBuilder, TokenProvider, API 클라이언트
├── ingest.md                    ← SlackFacade 라우팅, 병렬 초기화, SQS 위임
├── worker.md                    ← 메시지 디스패치, CalendarService, DynamoDB
├── scheduler.md                 ← 보고서 파이프라인, Collector, Confluence
├── groupware.md                 ← ECS 오케스트레이션, KMS 봉투 암호화
├── lambda-patterns.md           ← Lambda 아키텍처 패턴 (3초 제한, static volatile)
├── calendar-model.md            ← Google Calendar 이벤트 모델
├── env-vars.md                  ← 모듈별 환경변수 참조 (필수/선택 40개+)
└── agents.md                    ← 에이전트 사용 가이드 (전역 ECC 참조)

.claude/commands/ (4개, 사용자가 /커맨드로 호출)
├── feature-breakdown.md         ← 기능 → Phase 분해 → SPEC.md
├── create-issue.md              ← Phase → GitHub 이슈 생성
├── resolve-issue.md             ← 이슈 분석 → 구현 → PR
└── resolve-conflict.md          ← rebase + 충돌 자동 해결

.claude/skills/ (2개, Claude가 맥락 관련 시 자동 참조)
├── lambda-deployment/           ← Lambda 배포 절차 가이드
└── slack-modal-patterns/        ← Slack Modal 구현 패턴 가이드
```

> **ECC 범용 rules (coding-style, testing, security 등)는 여기에 없습니다.**
> 전역 `~/.claude/rules/common/`에서 자동 로딩됩니다.

수정이 필요한 경우 직접 편집 후 git push.

---

## 7. 프로젝트 디렉토리 구조

```
automation-platform/                  ← 프로젝트 루트
├── CLAUDE.md                         ← 🟢 git 커밋 (팀 규칙)
├── .mcp.json                         ← 🟢 git 커밋 (팀 공유 MCP)
├── .claude/
│   ├── settings.json                 ← 🟢 git 커밋 (팀 공유 Hooks)
│   ├── settings.local.json           ← 🟡 .gitignore (개인 오버라이드)
│   ├── rules/*.md                    ← 🟢 git 커밋 (프로젝트 특화 규칙 9개)
│   ├── commands/*.md                 ← 🟢 git 커밋 (워크플로우 커맨드 3개)
│   └── skills/                       ← 🟢 git 커밋 (프로젝트 특화 스킬 2개)
├── common/                           ← 공통 라이브러리
├── clients/                          ← 외부 API 클라이언트
├── ingest/                           ← Lambda 진입점
├── worker/                           ← SQS 소비자
├── scheduler/                        ← EventBridge 스케줄러
├── groupware/                        ← Lambda 오케스트레이터
├── groupware-bot/                    ← Python Playwright (Gradle 미포함)
├── config/                           ← S3 업로드용 런타임 설정
├── scripts/                          ← 개발 스크립트 (create-worktree.sh)
└── .gitignore                        ← .claude/settings.local.json + .claude/plans/ 추가

~/.claude/                            ← 🔵 개인 환경 (git 대상 아님)
├── rules/                            ← ECC install.sh 전체 rules (전 언어)
├── agents/                           ← ECC 전체 에이전트 (~50개)
├── commands/                         ← ECC 전체 커맨드 (~70개)
├── skills/                           ← ECC 전체 스킬 (~40개)
├── .agents/skills/                   ← ECC 추가 스킬
├── hooks/                            ← ECC 기본 hooks
├── scripts/                          ← ECC 스크립트
└── plugins/                          ← 플러그인 (LSP, developer-kit)

~/.claude.json                        ← 🔵 개인 전역 MCP (Sentry 등)
```

`.gitignore`에 이미 `.claude/settings.local.json`과 `.claude/plans/`가 등록되어 있습니다.

---

## 8. 슬래시 커맨드 & 단축키 (빌트인, 설치 불필요)

```
┌──────────────────────┬──────────────────────────────────────────────┬────────────┐
│ 커맨드               │ 용도                                         │ 사용 시점  │
├──────────────────────┼──────────────────────────────────────────────┼────────────┤
│ /init                │ CLAUDE.md 자동 생성                           │ 프로젝트 초기│
│ /clear               │ 컨텍스트 완전 리셋 (토큰 절약)               │ 새 작업 시 │
│ /compact             │ 대화 요약 압축                               │ 50% 이전   │
│ /simplify            │ 변경 파일 자동 리뷰 (3개 병렬 에이전트)      │ 커밋 전    │
│ /review              │ 코드 리뷰                                    │ PR 전      │
│ /batch               │ 여러 파일 일괄 작업                           │ 마이그레이션│
│ /loop                │ 반복 작업 로컬 스케줄링                       │ 자동화     │
│ /debug               │ 디버깅 워크플로우                             │ 에러 분석  │
│ /config              │ 출력 스타일 설정                              │ 초기 설정  │
│ /install-github-app  │ Claude PR 자동 리뷰 활성화                   │ 최초 1회   │
│ /skills              │ 사용 가능한 스킬 목록 확인                    │ 수시       │
├──────────────────────┼──────────────────────────────────────────────┼────────────┤
│ 프로젝트 커맨드 (WORKFLOW.md 참조)                                                │
├──────────────────────┼──────────────────────────────────────────────┼────────────┤
│ /feature-breakdown   │ 기능 → Phase 분해 → SPEC.md                  │ 기능 계획  │
│ /create-issue        │ Phase → GitHub 이슈 생성                     │ 이슈 생성  │
│ /resolve-issue       │ 이슈 분석 → 구현 → PR 생성                   │ 기능 구현  │
│ /resolve-conflict    │ rebase + 충돌 자동 해결                      │ 충돌 발생  │
├──────────────────────┼──────────────────────────────────────────────┼────────────┤
│ Esc + Esc            │ 체크포인트 되감기 (코드/대화 별도 복원)      │ 실험 실패  │
│ !명령어              │ 즉시 쉘 실행 (!git status, !make build)      │ 수시       │
│ Shift + Tab          │ 권한 모드 전환 (Plan ↔ Auto-Accept ↔ Default)│ 수시       │
│ Ctrl + T             │ 태스크 리스트 토글                            │ 수시       │
│ #메모                │ CLAUDE.md에 직접 메모 저장                    │ 규칙 추가  │
└──────────────────────┴──────────────────────────────────────────────┴────────────┘
```

---

## 9. Scope 참고

```
--scope project → .mcp.json (프로젝트 루트) → 🟢 팀 전원 공유 (git 커밋)
--scope local   → ~/.claude.json [project]  → 🟡 현재 프로젝트, 개인만 (git 커밋 안 함)
--scope user    → ~/.claude.json            → 🔵 모든 프로젝트, 개인만 (git 커밋 안 함)
```

### Scope 결정 기준

```
┌────────────────────────────────────────┬───────────┐
│ 조건                                   │ Scope     │
├────────────────────────────────────────┼───────────┤
│ 인증/키 불필요 + 팀 전원 사용          │ 🟢 project│
│ 개인 인증 필요 + 이 프로젝트에서만     │ 🟡 local  │
│ 개인 키 필요 + 모든 프로젝트에서       │ 🔵 user   │
└────────────────────────────────────────┴───────────┘
```

### 전체 Scope 배치표

```
┌─────────────────────┬───────────┬─────────────────────────────────────┐
│ 도구                │ Scope     │ 이유                                │
├─────────────────────┼───────────┼─────────────────────────────────────┤
│ gh CLI              │ 🔵 brew   │ 커밋 author + GitHub 인증 = 개인    │
│ ECC (install.sh)    │ 🔵 user   │ ~/.claude/ 에 전체 설치 (개인 머신) │
│ 프로젝트 .claude/   │ 🟢 project│ 프로젝트 특화 rules/commands/skills  │
│ Context7            │ 🟢 project│ 인증 불필요, 팀 전원 자동 적용      │
│ Sequential Thinking │ 🟢 project│ 인증 불필요, 팀 전원 자동 적용      │
│ Playwright          │ 🟢 project│ 인증 불필요, 팀 전원 자동 적용      │
│ Sentry (선택)       │ 🔵 user   │ 개인 Sentry 인증 필요               │
│ jdtls LSP           │ 🔵 user   │ ~/.claude/plugins/ (개인 환경)      │
│ dx 플러그인 (선택)  │ 🔵 user   │ ~/.claude/plugins/ (개인 환경)      │
│ developer-kit (선택)│ 🔵 user   │ ~/.claude/plugins/ (개인 환경)      │
└─────────────────────┴───────────┴─────────────────────────────────────┘
```

---

## 10. 설치 실패 대응: git clone 수동 설치

> 아래 도구들은 npx / 마켓플레이스 설치 시 SSH/HTTPS clone 실패,
> 1Password SSH 에이전트 충돌, 캐시 미갱신 등의 문제가 빈번합니다.
> 실패 시 git clone 수동 설치를 사용하세요.

### ECC (Everything Claude Code)

```bash
# ⚠️ npx ecc / npx ecc-install은 npm 미등록 — 동작하지 않음
# 유일한 설치 방법:
mkdir -p ~/Work/tools && cd ~/Work/tools
git clone https://github.com/affaan-m/everything-claude-code.git
cd everything-claude-code
chmod +x install.sh          # 실행 권한 부여 (필요 시)
./install.sh java            # → ~/.claude/ 에 설치됨
# 상세 절차는 섹션 2-1 참조
```

### 플러그인 (LSP, developer-kit 등)

```bash
# /plugin install 실패 시:

# Java LSP (jdtls):
git clone https://github.com/anthropics/claude-code-lsps.git
cp -r claude-code-lsps/jdtls ~/.claude/plugins/

# dx 플러그인:
git clone https://github.com/ykdojo/claude-code-tips.git
cd claude-code-tips && bash scripts/setup.sh

# developer-kit (Java/Spring Boot):
git clone https://github.com/giuseppe-trisciuoglio/developer-kit.git
cp -r developer-kit/plugins/developer-kit-java ~/.claude/plugins/
# ※ developer-kit-core는 /plugin install로 인식 불가 — java만 설치
```

### 플러그인 업데이트 미반영 시

```bash
# 캐시 삭제 후 재설치 (업데이트가 반영 안 될 때)
rm -rf ~/.claude/plugins/cache/<marketplace-name>/
rm -rf ~/.claude/plugins/marketplaces/<marketplace-name>/
# 이후 다시 /plugin install 또는 git clone
```

---

## 11. 전체 설치 요약

```
┌─────────────────────┬──────────┬───────────┬──────────────┬──────────────────┐
│ 도구                │ 분류     │ Scope     │ 설치 방법    │ 비고             │
├─────────────────────┼──────────┼───────────┼──────────────┼──────────────────┤
│ gh CLI              │ CLI      │ 🔵 brew   │ brew install │ Git/GitHub 전담  │
│ ECC (install.sh)    │ 하네스   │ 🔵 user   │ git clone    │ ~/.claude/ 전체  │
│ 프로젝트 .claude/   │ 하네스   │ 🟢 project│ 직접 작성    │ 프로젝트 특화만  │
│ Context7            │ MCP      │ 🟢 project│ claude mcp   │ 라이브러리 문서  │
│ Sequential Thinking │ MCP      │ 🟢 project│ claude mcp   │ 복잡 로직 분석   │
│ Playwright          │ MCP      │ 🟢 project│ claude mcp   │ 브라우저 자동화  │
│ Sentry (선택)       │ MCP      │ 🔵 user   │ claude mcp   │ 에러 모니터링    │
│ jdtls LSP           │ Plugin   │ 🔵 user   │ /plugin      │ Java 코드 진단   │
│ dx (선택)           │ Plugin   │ 🔵 user   │ /plugin      │ 개발자 경험 도구 │
│ developer-kit (선택)│ Plugin   │ 🔵 user   │ /plugin      │ Java/Spring 특화 │
└─────────────────────┴──────────┴───────────┴──────────────┴──────────────────┘
```

---

## 12. 팀 온보딩 체크리스트

```bash
# ══════════════════════════════════════════════════════════
# automation-platform 신규 팀원 설정 (순서대로 실행)
# ══════════════════════════════════════════════════════════

# ── Step 1. 🔵 사전 도구 설치 (개인) ─────────────────────
npm install -g @anthropic-ai/claude-code
brew install gh
gh auth login
git config --global user.name "이름"
git config --global user.email "name@company.com"
java -version   # Java 17+ 확인

# ── Step 2. 🔵 ECC 개인 설치 (→ ~/.claude/) ─────────────
# ⚠️ npx ecc / npx ecc-install은 npm 미등록 — git clone이 유일한 방법
mkdir -p ~/Work/tools
cd ~/Work/tools
git clone https://github.com/affaan-m/everything-claude-code.git
cd everything-claude-code
./install.sh java   # 실행 권한 오류 시: chmod +x install.sh
# 설치 확인:
echo "agents: $(ls ~/.claude/agents/*.md 2>/dev/null | wc -l | tr -d ' ')개"
# → 47개 이상이면 성공

# ── Step 3. 🟢 프로젝트 클론 ─────────────────────────────
# .mcp.json       → Context7, Sequential Thinking, Playwright 자동 적용
# .claude/rules/  → 프로젝트 특화 규칙 9개 자동 적용 (범용은 ~/.claude/ 에서)
# CLAUDE.md       → 프로젝트 규칙, 빌드 방법, 코딩 컨벤션 자동 적용
# ~/.claude/(전역 ECC) + .claude/(프로젝트 특화) 가 합쳐져 동작
git clone <repo-url>
cd automation-platform

# ── Step 4. 🔵 플러그인 설치 (Claude Code 내부) ─────────
/plugin install jdtls@claude-code-lsps             # Java LSP (필수)
# ※ 실패 시 섹션 10의 git clone 방법 사용

# ── Step 4-1. [선택] developer-kit 추가 설치 ──────────────
/plugin marketplace add giuseppe-trisciuoglio/developer-kit
/plugin install developer-kit-java@developer-kit   # core는 인식 불가, java만 설치
/reload-plugins

# ── Step 5. 🔵 개인 MCP 설치 (선택) ─────────────────────
# Sentry (Sentry 계정 보유 시):
claude mcp add sentry --scope user \
  --transport http https://mcp.sentry.dev/mcp

# ── Step 6. 설치 확인 ────────────────────────────────────
claude mcp list
/skills
gh auth status
make build   # Java 빌드 확인
# 개발 워크플로우: WORKFLOW.md 참조

# ── Step 7. PR 자동 리뷰 활성화 (최초 1회) ───────────────
/install-github-app
```
