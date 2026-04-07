# config 디렉토리

S3에 업로드되어 Lambda 런타임에 사용되는 설정 파일.
상세 구조는 `config/README.md` 참조.

## 파일 목록

| 파일 | 사용 모듈 | 용도 |
|------|----------|------|
| `config.json` | worker, ingest | Jira 라우팅, 캘린더 ID, 알림 설정 |
| `scheduler-config.json` | scheduler | 보고서 대상, 기간, 포맷 설정 |
| `team-members.json` | worker, scheduler, ingest | 팀원 정보 (Jira↔Slack 매핑) |
| `groupware-config.json` | groupware | EKP URL, 결재 규칙 |
| `announcements.json` | scheduler | 팀 공지 |
| `google-credentials.json` | 전체 | Google Calendar 서비스 계정 |

## 주의사항

- `google-credentials.json`은 **민감 정보** — 내용을 출력하거나 수정하지 말 것
- 파일 구조 변경 시 관련 모듈 코드도 함께 수정할 것
- S3 키 네이밍: `kebab-case.json`
- AI 규칙 파일: `rules/DAILY_REPORT_RULES.md`, `rules/WEEKLY_REPORT_RULES.md`
