# automation-platform — PRD (Product Requirements Document)

**문서 버전**: v1.0
**작성일**: 2026년 4월 7일
**상태**: Active

---

## 1. 개요 (Overview)

### 1.1 제품 한 줄 요약

> Slack 슬래시 커맨드, Jira 웹훅, EventBridge 스케줄 기반으로 팀의 일정·부재·보고서를 자동화하는 AWS 서버리스 플랫폼

### 1.2 문서 범위

본 PRD는 automation-platform의 전체 기능 범위를 정의한다.
Java 17 (Lambda) + Python 3.11 (Fargate) 하이브리드 아키텍처.

### 1.3 기술 스택 요약

| 구분 | 스택 |
|------|------|
| 언어 | Java 17 (Gradle 멀티모듈), Python 3.11 |
| 런타임 | AWS Lambda (Java), ECS Fargate (Python Docker) |
| 인프라 | API Gateway, SQS, S3, DynamoDB, Secrets Manager, KMS, EventBridge, ECR |
| 외부 API | Slack, Jira Cloud, Google Calendar, Confluence, Anthropic Claude |
| 리전 | ap-northeast-2 (서울) |

---

## 2. 문제 정의 (Problem Statement)

### 2.1 현재 상황

팀의 일정 관리, 부재 등록, 보고서 생성이 수동 프로세스로 진행되어:
- **일정 동기화 부재**: Jira 티켓 마감일이 변경되면 캘린더에 수동 반영 필요
- **부재 등록 이중 작업**: 캘린더 + 그룹웨어(EKP) 각각 등록
- **보고서 수동 작성**: 일일/주간/월간 실적 보고서를 매번 수동 작성
- **정보 분산**: Jira, Google Calendar, Slack, Confluence에 데이터가 분산

### 2.2 핵심 문제

| 문제 | 영향 |
|------|------|
| Jira ↔ Calendar 수동 동기화 | 마감일 변경 시 캘린더 미반영 → 일정 누락 |
| 부재 이중 등록 | 캘린더 + 그룹웨어 각각 → 시간 낭비 + 누락 |
| 보고서 수동 작성 | 주간 2시간+ 소요 → 반복 작업 |
| 재택근무 수동 등록 | 매일 Slack + 캘린더 각각 → 번거로움 |

---

## 3. 제안 해결책 (Proposed Solution)

### 3.1 솔루션 개요

Slack을 단일 인터페이스로 사용하여 모든 업무 자동화를 수행한다.

```
사용자 → Slack 슬래시 커맨드 → Lambda → SQS → Worker Lambda
                                                 ├→ Google Calendar
                                                 ├→ DynamoDB
                                                 ├→ Jira
                                                 └→ 그룹웨어 (ECS Fargate)
```

### 3.2 핵심 원칙

1. **Slack 단일 인터페이스**: 모든 조작은 Slack 슬래시 커맨드로 시작
2. **3초 응답**: Slack 모달 제출 → 3초 내 HTTP 200 → 무거운 작업은 SQS 위임
3. **멱등성**: 동일 요청 재전송 시 DedupeService로 중복 방지
4. **선택적 기능**: 환경변수 미설정 시 해당 기능만 비활성화 (에러 아님)

---

## 4. 기능 명세 (Feature Specification)

### 4.1 Slack 슬래시 커맨드

| 커맨드 | 기능 | 흐름 |
|--------|------|------|
| `/부재등록` | 부재 캘린더 등록 + 그룹웨어 연동 | ingest → SQS → worker → Calendar + groupware SQS → Fargate |
| `/재택근무` | 재택근무 캘린더 등록 | ingest → SQS → worker → Calendar |
| `/계정관리` | 그룹웨어 계정 암호화 관리 | ingest (KMS 암호화, Secrets Manager 저장) |
| `/일정등록` | 일정 캘린더 CRUD + DynamoDB 매핑 | ingest → SQS → worker → Calendar + DynamoDB |
| `/현재티켓` | 담당 Jira 티켓 현황 조회 | ingest (Calendar + Jira 조회 → Slack DM) |

### 4.2 Jira 웹훅 자동 동기화

```
Jira 이슈 변경 → API Gateway → ingest → SQS → worker
                                                 ├→ Calendar 이벤트 생성/수정/삭제
                                                 ├→ DynamoDB 매핑 저장
                                                 └→ Slack 알림 (채널 + DM)
```

- CREATE: 마감일 있는 팀원 이슈 → 캘린더 종일 이벤트 생성
- UPDATE: 마감일/담당자/상태 변경 반영
- DELETE: 이슈 삭제 시 캘린더 이벤트 삭제

### 4.3 EventBridge 스케줄 보고서

| 보고서 | 주기 | 채널 | 첨부 |
|--------|------|------|------|
| Daily | 매일 오전 | Slack DM (팀원별) | 없음 |
| Weekly | 매주 월요일 | Confluence + Slack | Excel |
| Monthly | 매월 1일 | Confluence + Slack | Excel |

데이터 파이프라인: Load (S3) → Collect (Calendar + Jira + DynamoDB) → Format (Slack/HTML) → Report (전송)

### 4.4 그룹웨어 부재 자동 신청

```
/부재등록 (apply) → worker → groupware SQS → groupware Lambda → ECS Fargate
                                                                   └→ Playwright 브라우저 자동화
                                                                      └→ EKP 부재 신청 완료
                                                                         └→ Slack DM 결과 알림
```

- 자격증명: KMS 봉투 암호화 (AES-256-GCM)
- 비밀번호는 SQS/환경변수에 **절대 포함 금지** → Fargate가 Secrets Manager 직접 조회

---

## 5. 아키텍처

### 5.1 모듈 구조

```
automation-platform/
├── common/          예외, Enum, 유틸리티, SlackBlockBuilder
├── clients/         Slack, Jira, Calendar, Confluence, Anthropic 클라이언트
├── ingest/          Lambda 진입점 (Slack, Jira 웹훅 수신)
├── worker/          SQS 소비자 (비즈니스 로직)
├── scheduler/       EventBridge 보고서 생성
├── groupware/       Lambda 오케스트레이터 (ECS 태스크 호출)
├── groupware-bot/   Python Playwright 브라우저 자동화 (Gradle 미포함)
└── config/          S3 업로드용 런타임 설정
```

### 5.2 의존성

```
common ← clients ← ingest / worker / scheduler / groupware
```

### 5.3 Lambda 핸들러

| 모듈 | 입력 타입 | 트리거 |
|------|----------|--------|
| ingest | `Map<String, Object>` | API Gateway + EventBridge |
| worker | `SQSEvent` | SQS |
| scheduler | `Map<String, Object>` | EventBridge Scheduler |
| groupware | `SQSEvent` | SQS |

---

## 6. 데이터 모델

### 6.1 DynamoDB 테이블

| 테이블 | PK | SK | 용도 |
|--------|----|----|------|
| Dedupe | eventId | timestamp | Jira 이벤트 중복 방지 |
| CalendarMapping | issueKey | calendarId | Jira↔Calendar 매핑 |
| ScheduleMapping | slackUserId | eventId | 일정↔Calendar 매핑 |

### 6.2 S3 설정 파일

| 파일 | 용도 |
|------|------|
| `config.json` | Worker/Ingest 라우팅, 캘린더 ID, 알림 설정 |
| `scheduler-config.json` | 보고서 설정 |
| `team-members.json` | 팀원 정보 (Jira↔Slack 매핑) |
| `groupware-config.json` | 그룹웨어 URL, 결재자 규칙 |
| `google-credentials.json` | Google Calendar 서비스 계정 (**민감 정보**) |

### 6.3 Google Calendar extendedProperties

| 키 | 값 예시 | 용도 |
|---|--------|------|
| `jiraIssueKey` | `CCE-2339` | 이슈 식별자 |
| `assigneeName` | `홍길동` | 최종 담당자 |
| `isTeamMember` | `true` | 팀원 여부 |
| `jiraStartDate` | `2026-02-10` | Jira 시작일 |

---

## 7. 보안 요구사항

| 항목 | 구현 |
|------|------|
| Slack 요청 검증 | HMAC-SHA256 서명 (5분 유효) |
| 그룹웨어 비밀번호 | KMS 봉투 암호화 (AES-256-GCM) |
| API 토큰 | Secrets Manager + 환경변수 |
| ECS 자격증명 | Secret 이름만 전달, Fargate가 직접 조회 |
| Slack 응답 | 항상 HTTP 200 (500 → 재시도 루프 방지) |

---

## 8. 빌드/배포

```bash
make build              # 전체 shadowJar
make build-{module}     # 모듈별 (ingest, worker, scheduler, groupware)
make build-bot          # groupware-bot Docker
make deploy-all         # 전체 배포 (Lambda 4 + Docker)
make push-bot           # ECR 푸시
```

---

## 9. 리스크 및 의존성

| 리스크 | 영향도 | 완화 방안 |
|--------|--------|----------|
| Slack 3초 제한 초과 | 높음 | SQS 위임 + static volatile 캐싱 + pre-warm |
| Google Calendar API 쿼타 | 중간 | 배치 조회, 5분 TTL 캐시 |
| Lambda Cold Start | 중간 | Shadow JAR 최적화, 병렬 초기화 |
| 그룹웨어 UI 변경 | 중간 | Playwright 셀렉터 유지보수, S3 스크린샷 디버깅 |
| DynamoDB 매핑 미등록 (레거시) | 낮음 | extendedProperties 폴백 + 자동 등록 |

---

**문서 끝**
