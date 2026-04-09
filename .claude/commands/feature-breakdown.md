
# ═══════════════════════════════════════════════════════════
# 파일 1: .claude/commands/feature-breakdown.md
# ═══════════════════════════════════════════════════════════


# Feature Breakdown

기능 요구사항을 실행 가능한 Phase 단위로 분해하여 SPEC.md에 기록한다.

**기능 요구사항**: $ARGUMENTS

---

## 사용 방법

```
/feature-breakdown 반복 일정 등록 기능 추가
/feature-breakdown Sprint 변경 이벤트 웹훅 처리
```

완료 후 → `/create-issue Phase N` → GitHub 이슈 #번호 생성 → `source scripts/create-worktree.sh #번호` → `/resolve-issue #번호`

> **번호 체계**: Phase 번호(N1, N2)와 GitHub 이슈 번호(#12, #13)는 다르다.
> `/create-issue`가 Phase → GitHub 이슈로 변환하며, SPEC.md에 `Phase N1 (#12)` 형태로 매핑을 기록한다.

---

## 프로세스

### 1. 문제 이해

- **목표**: 요구사항의 최종 상태
- **현재 상태**: CLAUDE.md, SPEC.md, 관련 소스 코드를 읽어라
- **제약 조건**: 기술 스택, 기존 컨벤션, 영향 범위

정보가 부족하면 질문해라. 추측하지 마라.

### 2. 코드 탐색

**Explore 서브에이전트를 context fork로 실행해라** (메인 컨텍스트 절약).

- 변경/영향 파일 목록
- 재사용 가능한 기존 패턴
- CLAUDE.md 컨벤션 확인

외부 라이브러리 관련 시 **Context7 MCP로 최신 문서를 확인해라.** 추측 금지.

### 3. Phase 분해

**Sequential Thinking MCP를 사용하여 단계별로 분석해라.**

각 Phase 기준:
- **독립 실행 권장**: 가능하면 이 Phase만으로 빌드 통과
- **독립 불가 시**: SPEC.md에 실행 순서와 주의사항을 명시
- **충분한 문맥**: 다른 세션의 Claude가 이 Phase만 읽고 작업 완수 가능
- **적정 크기**: 하나의 Claude 세션에서 완료 가능한 범위

분해 시 고려:
- **수직 분해**: 설계 → 구현 순서
- **수평 분해**: 병렬 가능한 Phase는 명시
- **의존성**: Phase 간 선행 조건 명확히 기술
- **독립 불가 Phase**: 반드시 순서와 이유를 기술
- **충돌 예방**: 병렬 Phase는 수정 파일이 겹치지 않도록 분리할 것. common/clients 수정은 직렬 처리 권장

### 4. Phase 형식

```markdown
## Phase N: [제목]

- [ ] Phase N 완료

### 오버뷰
[작업 이유 + 변경 내용 1-2문장]

### 메타
- **라벨**: feature / bug / refactor / enhancement / chore
- **우선순위**: high / medium / low
- **병렬 가능**: 예 / 아니오

### 전제조건
- [ ] Phase N-1 완료 (또는 "없음")

### 수정/개선
- [ ] **`파일 경로`** — 변경 내용
    - [ ] 세부 작업

### 검증
- [ ] 빌드 성공 (CLAUDE.md 빌드 커맨드 참조)
- [ ] [기능별 검증 항목]

### 주의사항 (독립 실행 불가 시)
- [이 Phase 단독으로 빌드 불가한 이유]
- [반드시 Phase N-1 이후에 실행해야 하는 이유]
- [병렬 진행 시 충돌 가능성]

### 리스크
- [있으면 기술. 없으면 생략]
```

### 5. SPEC.md 기록

기존 Phase 번호 다음부터 이어서 추가.

### 6. 후속 안내

- `/create-issue Phase N` 으로 이슈 생성
- `source scripts/create-worktree.sh 이슈번호` 로 워크트리 생성 + Claude 실행
- 워크트리 Claude에서 `/resolve-issue 이슈번호` 로 구현
- 병렬 가능 Phase는 동시 진행 가능
- **컨텍스트가 쌓였으면 `/compact` 권장**

---

## 원칙

1. **점진적 개선** — 각 Phase 후 프로젝트 정상 상태 유지 (불가 시 SPEC.md에 명시)
2. **과도한 추상화 금지** — 지금 필요한 만큼만
3. **파일 경로 필수** — 모든 변경 항목에 경로 포함
4. **검증 필수** — CLAUDE.md 빌드 커맨드 + 기능별 확인

## 출력

분해 결과를 요약 보고 → SPEC.md 업데이트 승인 → 기록.
