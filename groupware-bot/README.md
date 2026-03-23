# Groupware Bot 모듈

Playwright 기반 브라우저 자동화로 그룹웨어(EKP) 부재 신청을 수행하는 Python Docker 컨테이너입니다.

## 모듈 개요

AWS Fargate에서 실행되며, groupware Lambda 모듈에 의해 기동됩니다. Headless Chromium으로 그룹웨어 웹 UI를 조작하여 부재 신청 및 결재 상신을 자동화합니다.

- **그룹웨어 로그인**: Secrets Manager에서 자격 증명 로드 + KMS 복호화
- **부재 신청 자동화**: 부재 유형 선택 → 날짜 입력 → 사유 입력 → 저장
- **결재 상신**: 결재선 선택 → DynaTree 드래그 앤 드롭 → 상신 완료
- **디버깅**: 각 단계별 스크린샷 S3 저장
- **결과 알림**: Slack Webhook으로 성공/실패 통지

## 파일 구조

```
groupware-bot/
├── Dockerfile              # Python 3.11 + Playwright Chromium
├── requirements.txt        # Python 의존성
├── main.py                 # 진입점 (환경변수 파싱 → 로그인 → 부재 신청)
├── groupware_client.py     # Playwright 기반 그룹웨어 자동화 로직
├── secrets_client.py       # AWS Secrets Manager/KMS 자격 증명 로드
└── slack_notifier.py       # Slack Webhook 결과 알림
```

## 주요 파일 설명

### main.py

진입점으로, 환경변수에서 파라미터를 파싱하고 전체 흐름을 오케스트레이션합니다:
1. Secrets Manager에서 그룹웨어 계정 정보 로드
2. S3에서 설정 로드 (groupware-config.json)
3. `groupware_client.apply_absence()` 호출
4. 결과를 Slack으로 통지

### groupware_client.py

Playwright(Chromium)로 그룹웨어 웹 UI를 조작하는 핵심 로직:
- 16단계 자동화 프로세스 (로그인 → 결재 상신)
- 부재 유형 그룹 분류 (A그룹: 날짜 1개, B그룹: 기간형)
- DynaTree 라이브러리 드래그 앤 드롭 처리
- 각 단계별 스크린샷 저장 (`_shot()`)
- jQuery datepicker 날짜 입력 자동화

### secrets_client.py

- AWS Secrets Manager에서 직원 계정 정보 조회
- KMS 암호화된 비밀번호 복호화 (`ENC:` 접두사)
- AES-256-GCM Envelope Encryption 지원

### slack_notifier.py

- Slack Incoming Webhook으로 결과 알림 전송
- 성공/실패 메시지 포맷팅

## 의존성

### Python 패키지 (requirements.txt)

| 패키지 | 용도 |
|--------|------|
| `playwright` | Headless Chromium 브라우저 자동화 |
| `boto3` | AWS SDK (Secrets Manager, S3, KMS) |

### 내부 모듈 의존성

- **없음** (독립적 Python 프로젝트, Gradle 멀티모듈에 포함되지 않음)
- groupware Lambda 모듈이 Fargate 태스크로 이 컨테이너를 실행

## 주요 환경변수

| 변수명 | 설명 | 필수 |
|--------|------|:----:|
| `GROUPWARE_CREDENTIALS_SECRET` | Secrets Manager 시크릿명 | O |
| `GROUPWARE_CONFIG_BUCKET` | S3 버킷 (그룹웨어 설정) | O |
| `GROUPWARE_CONFIG_KEY` | S3 키 (groupware-config.json) | O |
| `SLACK_USER_ID` | 요청 사용자 Slack ID | O |
| `ABSENCE_TYPE` | 부재 유형 (연차, 반차 등) | O |
| `START_DATE` | 시작일 (yyyy-MM-dd) | O |
| `END_DATE` | 종료일 (yyyy-MM-dd) | O |
| `REASON` | 부재 사유 | - |
| `APPROVER_KEYWORD` | 결재자 검색 키워드 | O |
| `SCREENSHOT_BUCKET` | S3 버킷 (스크린샷 저장) | - |
| `SLACK_WEBHOOK_URL` | Slack Webhook URL (결과 알림) | - |

## 핵심 흐름

### 부재 신청 자동화 (16단계)

```
[1]  Chromium 브라우저 실행 (Headless)
[2]  그룹웨어 로그인 페이지 접속
[3]  ID/PW 입력 + 로그인
[4]  근태 메뉴 이동
[5]  부재 신청 메뉴 클릭
[6]  부재 유형 선택 (연차/반차/병가 등)
[7]  + 버튼 클릭 (날짜 행 추가)
[8]  날짜 입력 (jQuery datepicker)
     ├─ A그룹 (반차/보건휴가): 날짜 1개
     └─ B그룹 (연차/병가 등): 시작일 + 종료일
[9]  사유 입력
[10] 저장 클릭 → 결재 작성 팝업
[11] 문서 제목 입력
[12] 결재선 탭 클릭
[13] 결재자 검색 + 체크박스 선택
[14] DynaTree 노드 드래그 → 결재 슬롯
[15] 결재상신 클릭
[16] "상신하겠습니까?" 확인
```

### Docker 빌드 및 배포

```bash
# 빌드
make build-bot

# ECR 푸시
make push-bot
```