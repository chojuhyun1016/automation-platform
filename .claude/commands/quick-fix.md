
# Quick Fix

이슈 없이 현재 브랜치에서 바로 분석 + 수정하는 경량 커맨드.
급한 버그 수정, 코드 분석, 임시 반영, hotfix 등에 사용한다.

**요구사항**: $ARGUMENTS

---

## 사용 방법

```
# 버그 수정
/quick-fix 점심카드 모달에서 날짜 변경 시 카운트가 0으로 표시됨

# 분석만 요청
/quick-fix CalendarService.listEvents에서 NPE 발생, 로그 첨부...

# 리팩터링
/quick-fix AbsenceFacade 메서드가 너무 길다, 분리해줘

# 빌드 에러
/quick-fix gradlew build 실패, 에러 로그 첨부...
```

**이슈/브랜치 제약 없음** — main, feature, fix 어디서든 실행 가능.

---

## 프로세스

### 1. 요구사항 파악

$ARGUMENTS에서 파악:
- **유형**: 버그 수정 / 분석 요청 / 리팩터링 / 빌드 에러 / 기타
- **긴급도**: 즉시 수정 필요 vs 분석만 필요

### 2. 코드 탐색

**Explore 서브에이전트**로 관련 코드 탐색 (context fork).
- 영향 범위가 명확하면 1개, 불명확하면 2개 병렬
- 외부 라이브러리 관련 시 **Context7 MCP** 사용

빌드 에러인 경우: **java-build-resolver 에이전트 즉시 실행** → Step 3으로 건너뛰어도 됨.

### 3. 분석 보고

사용자에게 보고:
```
## 분석 결과

### 원인
[근본 원인 1-2문장]

### 영향 범위
- `파일 경로` — 역할

### 수정 방안
[구체적 수정 내용]

수정을 진행할까요?
```

**분석만 필요한 경우**: 여기서 종료. 사용자가 수정을 요청하면 Step 4로 진행.

### 4. 수정 구현

- **최소 변경 원칙** — 요청된 범위만
- **검증 루프** (빌드 → 테스트 순서로 반복):
  1. 빌드 검증 (`./gradlew :모듈:compileJava`)
  2. 빌드 실패 시 → **java-build-resolver 에이전트 실행** → 수정 → 1로 복귀
  3. 빌드 성공 시 → 관련 테스트 실행 (`./gradlew :모듈:test`)
  4. 테스트 실패 시 → 실패 원인 분석 + 수정 → 1로 복귀
  5. 빌드 + 테스트 모두 통과 → Step 5로 진행
- **테스트 생성은 하지 않음** — 기존 테스트 실행만 (TDD가 필요한 수준이면 `/resolve-issue` 사용)

### 5. 자체 리뷰 (선택)

변경 범위에 따라:
- **1-2파일 소규모**: 리뷰 스킵
- **3파일+ 또는 로직 변경**: **code-reviewer** + **java-reviewer** 병렬 실행
- **보안 관련**: **security-reviewer** 추가

### 6. 커밋 여부 확인

변경 파일 목록(`git status`)을 보여주고 사용자에게 확인:
```
변경 파일:
  M src/main/java/.../SomeService.java
  M src/test/java/.../SomeServiceTest.java

커밋할까요?
- 커밋합니다
- 아직 안 합니다 (로컬 변경 유지)
```

커밋 시:
```bash
git add {변경 파일}
git commit -m "{타입}: {간결한 설명}"
```
커밋 메시지 타입: `fix`, `refactor`, `chore`, `hotfix` 등 (이슈 번호 없음).

### 7. push 여부 확인

사용자에게 확인:
```
✅ 수정 완료 — 커밋됨.

push할까요?
- push 하겠습니다 → rebase 후 push
- 아직 안 합니다 → 로컬에만 유지
```

**push 시 rebase 포함**:
```bash
git fetch origin
git rebase origin/{현재 브랜치의 upstream 또는 main}
git push
```
충돌 발생 시 `/resolve-conflict` 안내.

---

## 원칙

1. **이슈 불필요** — 이슈 생성/SPEC.md 연동 없음
2. **분석만으로 끝날 수 있음** — 수정 여부는 사용자 선택
3. **최소 변경** — 급한 수정에 집중, 추가 개선 금지
4. **커밋 전 확인** — 사용자 승인 후 커밋
5. **push 전 rebase** — push 선택 시 origin과 동기화 후 push
