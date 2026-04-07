# automation-platform — SPEC (Implementation Specification)

## Context

Slack 슬래시 커맨드, Jira 웹훅, EventBridge 스케줄 기반 AWS 서버리스 업무 자동화 플랫폼.
Java 17 (Gradle 멀티모듈) + Python 3.11 (groupware-bot).
PRD: `automation-platform-prd.md` 참조.

### 현재 상태

- **인프라**: Lambda 4개(ingest, worker, scheduler, groupware) + ECS Fargate 1개(groupware-bot)
- **Slack 커맨드**: /부재등록, /재택근무, /계정관리, /일정등록, /현재티켓 (5개 완료)
- **Jira 동기화**: CREATE/UPDATE/DELETE → Calendar 자동 반영
- **보고서**: Daily(Slack DM), Weekly/Monthly(Confluence + Excel)
- **그룹웨어**: Playwright 브라우저 자동화 (EKP 부재 신청)
- **테스트**: 유닛 테스트 미구성, `make build` 컴파일 검증만

---

## Implemented Features (완료)

### F1. Slack 슬래시 커맨드 (5개)

- [x] `/부재등록` — 부재 캘린더 + 그룹웨어 연동
- [x] `/재택근무` — 재택근무 캘린더 등록/취소
- [x] `/계정관리` — 그룹웨어 계정 KMS 암호화 관리
- [x] `/일정등록` — 일정 캘린더 CRUD + DynamoDB 매핑
- [x] `/현재티켓` — 담당 Jira 티켓 현황 조회 (daily/weekly/quarterly)

### F2. Jira 웹훅 동기화

- [x] Jira 이슈 CREATE → Calendar 종일 이벤트 생성
- [x] Jira 이슈 UPDATE → Calendar 이벤트 수정 (마감일, 담당자, 상태)
- [x] Jira 이슈 DELETE → Calendar 이벤트 삭제
- [x] DynamoDB 2계층 조회 (매핑 + extendedProperties 폴백)
- [x] startDate 정규화 (null → 오늘, startDate > dueDate → dueDate)
- [x] Slack 알림 (채널 + 담당자 DM)

### F3. EventBridge 보고서

- [x] Daily 보고서 → Slack DM (팀원별)
- [x] Weekly 보고서 → Confluence + Slack + Excel
- [x] Monthly 보고서 → Confluence + Slack + Excel
- [x] AI 요약 (Anthropic Claude, 선택적)
- [x] 6개 Collector (Calendar, Jira, DynamoDB 데이터 수집)
- [x] S3 규칙 파일 기반 AI 프롬프트 (ReportRulesService)

### F4. 그룹웨어 부재 자동화

- [x] Java Lambda 오케스트레이터 → ECS Fargate 호출
- [x] Python Playwright 브라우저 자동화 (Chromium headless)
- [x] KMS 봉투 암호화 (AES-256-GCM)
- [x] cancel은 자동화 불가 → Slack DM 수동 안내

### F5. Lambda 최적화

- [x] 병렬 초기화 (SlackFacade, ExecutorService(5))
- [x] Static volatile 캐싱 (S3Client, CalendarClient)
- [x] Pre-warm 데몬 스레드 (CurrentTicketFacade)
- [x] SQS 위임 + join() 패턴
- [x] DynamoDB Pre-warm 연결 (ScheduleMappingQueryService)
- [x] ConfigService 5분 TTL 캐시

---

## Phase N1: 유닛 테스트 도입

- [ ] Phase N1 시작

### 오버뷰

common, clients 모듈부터 유닛 테스트를 도입한다. JUnit 5 + Mockito.

### 수정/개선

- [ ] `build.gradle` (root) — JUnit 5, Mockito 의존성 추가
- [ ] `common/build.gradle` — testImplementation 추가
- [ ] common/exception — 예외 생성, errorCode 검증 테스트
- [ ] common/code — Enum 팩토리 메서드 테스트 (경계값, null 처리)
- [ ] common/util — DateTimeUtil KST 변환 테스트
- [ ] common/slack — SlackBlockBuilder 체이닝 테스트
- [ ] clients/http — BaseHttpClient.requireSuccess() 테스트

### 검증

- [ ] `./gradlew :common:test` 통과
- [ ] `./gradlew :clients:test` 통과

---

## Phase N2: worker 서비스 테스트

- [ ] Phase N2 시작

### 전제조건

- [ ] Phase N1 완료

### 수정/개선

- [ ] CalendarService — processJiraEvent() CREATE/UPDATE/DELETE 분기 테스트
- [ ] ConfigService — TTL 캐시 만료/갱신 테스트
- [ ] DedupeService — 중복 감지 + prefix 키 테스트
- [ ] TeamMemberService — findByAccountId/findBySlackUserId 테스트

### 검증

- [ ] `./gradlew :worker:test` 통과

---

## Phase N3: Confluence 페이지 계층 안정화

- [ ] Phase N3 시작

### 오버뷰

ConfluenceClient 3단계 검색의 인덱싱 지연 대응을 강화하고, 중복 페이지 처리를 개선한다.

### 수정/개선

- [ ] ConfluenceClient — retry + 지수 백오프 추가
- [ ] 중복 페이지 자동 정리 (자식 없는 중복 삭제)
- [ ] 주간/월간 보고서 페이지 생성 실패 시 Slack 알림

### 검증

- [ ] Weekly/Monthly 보고서 Confluence 정상 생성

---

## Phase N4: 모니터링 & 알림 강화

- [ ] Phase N4 시작

### 수정/개선

- [ ] Lambda 에러 → Sentry 연동
- [ ] SQS DLQ 메시지 → Slack 알림
- [ ] Calendar API 쿼타 초과 → 경고 DM
- [ ] groupware-bot 실패 → S3 스크린샷 + Slack DM (현재 구현) 검증

---

## Phase N5: 보고서 커스터마이징

- [ ] Phase N5 시작

### 수정/개선

- [ ] 팀원별 보고서 포맷 커스터마이징 (scheduler-config.json)
- [ ] 프로젝트별 보고서 분리 (현재 전체 통합)
- [ ] 보고서 히스토리 아카이빙 (S3)

---

## Bugfix Log

### BF-1: Slack 재시도 감지 강화

- [x] 수정 완료
- **원인**: X-Slack-Retry-Num만 확인하면 다른 Retry-Reason도 무시됨
- **수정**: `X-Slack-Retry-Num` + `X-Slack-Retry-Reason == "http_timeout"` **둘 다** 확인

### BF-2: startDate 정규화

- [x] 수정 완료
- **원인**: startDate null인 이벤트가 Calendar에서 표시 안 됨
- **수정**: null → 오늘, startDate > dueDate → dueDate 자동 보정
- **1회용 도구**: `CalendarStartDateFixer`로 기존 데이터 일괄 보정 완료

### BF-3: DynamoDB 매핑 미등록 (레거시)

- [x] 수정 완료
- **원인**: DynamoDB 도입 전 생성된 Calendar 이벤트는 매핑이 없음
- **수정**: extendedProperties 폴백 검색 → 성공 시 DynamoDB 자동 등록

---

**문서 끝**
