# Update Documentation (automation-platform 프로젝트 로컬)

이 프로젝트 고유의 문서(SPEC.md, 모듈별 CLAUDE.md, .claude/rules/)를 코드베이스 상태에 맞게 동기화한다.
전역 `~/.claude/commands/update-docs.md`를 오버라이드한다.

---

## Step 0 (Project-specific): SPEC.md Phase 체크박스 동기화

**관례**: Phase 완료 시 해당 섹션의 모든 체크박스는 `[x]`여야 한다 (루트 `CLAUDE.md` "SPEC.md Phase 체크박스 완료 규칙" 참조).

### 실행 절차

1. SPEC.md에서 Phase 목록과 이슈 번호 추출:
   ```bash
   grep -nE "^## Phase N[0-9.]+:.*\(#([0-9]+)\)" SPEC.md
   ```

2. 각 Phase에 대해 이슈 번호로 PR 머지 상태 확인:
   ```bash
   gh pr list --search "linked:#<이슈번호>" --state merged --json number -q '.[0].number'
   # 또는
   gh issue view <이슈번호> --json state --jq .state
   ```

3. 조건별 처리:
   - **이슈 CLOSED + PR MERGED**: 해당 Phase 섹션 내 모든 `- [ ]`를 `- [x]`로 변경
     - 최상위 `- [x] Phase N 완료 (PR #N)`
     - `### 수정/개선` 내 모든 체크박스 (중첩 포함)
     - `### 검증` 내 모든 체크박스
   - **이슈 OPEN 또는 PR 미머지**: 현재 상태 유지 (건드리지 말 것)

4. 변경사항 요약:
   ```
   SPEC.md Phase 동기화
   ──────────────────────────────
   Phase N9 (#23)  → PR #33 머지 확인, 16개 체크박스 [x] 처리
   Phase N10 (#24) → 이미 완료 상태, skip
   ...
   ```

**주의**:
- Phase 섹션의 경계를 정확히 지켜라 (다음 `## Phase N` 이전까지). 다른 섹션의 `[ ]`를 건드리면 안 된다.
- Phase별로 Edit 호출하여 안전하게 블록 단위 교체할 것.

---

## Step 1: 모듈별 CLAUDE.md 동기화

Java 패키지/클래스 구조가 변경됐으면 해당 모듈 `CLAUDE.md` 업데이트:

- `common/CLAUDE.md` — common 모듈 클래스 목록, 패키지 구조
- `clients/CLAUDE.md` — 외부 API 클라이언트 목록
- `ingest/CLAUDE.md` — Facade/Service/DTO 구조
- `worker/CLAUDE.md` — SQS Facade, Service 목록
- `scheduler/CLAUDE.md` — Collector/Formatter/Service 구조
- `groupware/CLAUDE.md` — ECS 태스크 관련
- `groupware-bot/CLAUDE.md` — Python 모듈 구조
- `config/CLAUDE.md` — S3 설정 파일 구조

**검증**: `find <module>/src/main/java -name "*.java" | wc -l` 결과와 CLAUDE.md 클래스 목록 수 비교.

---

## Step 2: Slack 커맨드 테이블 동기화

새 Slack 커맨드 추가 시:
- 루트 `CLAUDE.md` "Slack 슬래시 커맨드" 테이블에 행 추가
- `ingest/CLAUDE.md` 요청 흐름 라우팅 섹션 업데이트

**검증**: `grep -r "callback_id" ingest/src/main/java`로 커맨드 목록 추출 후 테이블과 비교.

---

## Step 3: 환경변수 문서 동기화

환경변수 추가 시 `.claude/rules/env-vars.md` 업데이트:
- 모듈별(ingest/worker/scheduler/groupware) 분류
- 필수/선택 분류
- Lambda 설정값 예시

**검증**: `grep -rE 'System.getenv\("[A-Z_]+"\)' <module>/src/main/java`로 사용 중인 환경변수 추출 후 문서와 비교.

---

## Step 4: .claude/rules 동기화

새 Facade/Service 추가 시 해당 `.claude/rules/<module>.md` 업데이트:
- ingest.md — Facade 라우팅, 특수 패턴
- worker.md — 메시지 디스패치, Service 로직
- scheduler.md — Collector, Formatter 파이프라인
- groupware.md — ECS 오케스트레이션
- common-clients.md — 공통 라이브러리
- lambda-patterns.md — Lambda 제약사항
- calendar-model.md — Calendar 이벤트 모델
- env-vars.md — 환경변수 참조

---

## Step 5: 변경 요약 출력

```
Documentation Update
──────────────────────────────
Updated:  SPEC.md (Phase 체크박스 동기화 - N개)
Updated:  <module>/CLAUDE.md (클래스 N개 추가/삭제 반영)
Updated:  .claude/rules/<file>.md (패턴 N개 추가)
Skipped:  <file> (변경 없음)
Flagged:  <file> (90일 이상 미갱신 - 수동 검토 필요)
──────────────────────────────
```

---

## Rules

- **단일 진실의 원천**: SPEC.md Phase 상태는 GitHub 이슈/PR 상태에서 파생 (수동 편집 금지)
- **수동 작성 보존**: 자동 생성 외 서술 영역은 건드리지 말 것
- **범위 한정**: 이 커맨드는 문서 동기화만 수행한다. 새 기능/리팩터링 금지.
- **Phase 경계 준수**: 다음 `## Phase N` 이전까지가 해당 Phase 범위. 넘지 말 것.
- **PR 상태 신뢰**: PR이 MERGED가 아니면 해당 Phase 체크박스는 건드리지 말 것.
