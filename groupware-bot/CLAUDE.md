# groupware-bot

Python 3.11 Playwright 브라우저 자동화. Gradle 미포함, 독립 프로젝트.
ECS Fargate에서 Docker 컨테이너로 실행된다.

## 기술 스택

- Python 3.11, Playwright (Chromium headless), boto3, cryptography
- **asyncio 미사용** (동기 방식)
- Base: `mcr.microsoft.com/playwright/python:v1.42.0-jammy`
- Platform: `linux/amd64` (Fargate x86_64 필수)

## 파일 구조

```
groupware-bot/
├── main.py                 진입점 (환경변수 파싱 → 자격증명 → 자동화 → 결과 알림)
├── groupware_client.py     Playwright EKP 부재 신청 자동화 (~909줄)
├── secrets_client.py       Secrets Manager + KMS 봉투 복호화
├── slack_notifier.py       Slack DM 전송 (urllib만 사용, requests 없음)
├── Dockerfile
└── requirements.txt
```

## 실행 흐름 (main.py)

1. 환경변수 파싱
2. Secrets Manager → Slack 토큰
3. Secrets Manager → 그룹웨어 자격증명 (KMS 복호화)
4. Playwright → 부재 신청 자동화
5. 성공/실패 Slack DM
6. `sys.exit(0)` 또는 `sys.exit(1)`

## KMS 봉투 암호화 (secrets_client.py)

```
저장: "ENC:base64(encDataKey).base64(IV).base64(ciphertext)"
복호화: KMS decrypt(encDataKey) → AES-256-GCM decrypt(plainKey, IV, ciphertext)
레거시: ENC: 접두사 없으면 평문 그대로 반환
```

## 부재 유형 분류

- Group A (단일 날짜): 오전 반차, 오후 반차, 오전 반반차, 오후 반반차, 보건 휴가
- Group B (시작~종료): 연차, 병가 등 기타

## 빌드/배포

```bash
make build-bot    # Docker 빌드 (linux/amd64)
make push-bot     # ECR 푸시
```

## Playwright 셀렉터 (groupware_client.py)

| 요소 | 셀렉터 |
|------|--------|
| 로그인 ID | `input#empNo` |
| 로그인 PW | `input#empNoPassword` |
| 로그인 버튼 | `button#btnLogin` |
| 날짜 입력 | `.ui-dialog input[placeholder="yyyy-mm-dd"]` |
| 결재자 체크박스 | 키워드 검색 후 체크박스 클릭 |

- 날짜: jQuery UI datepicker → 달력에서 직접 클릭 (Tab/Enter는 onSelect 미트리거)
- 결재선: 체크박스 → 하단 자동 추가 (드래그 불필요)

## 디버깅

- `_shot(page, label)`: S3에 스크린샷 저장
- 파일명: `{prefix}{label}_{timestamp}.png`
- `PYTHONUNBUFFERED=1`: CloudWatch 즉시 로깅
