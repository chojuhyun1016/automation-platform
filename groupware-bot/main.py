"""
ECS Fargate Task 진입점.

환경변수에서 파라미터를 수신하고, 그룹웨어 부재 신청 자동화를 실행한다.
성공/실패 결과를 Slack DM으로 전송한다.
"""

import os
import sys
import traceback
import logging

# ── 로깅 설정 (stdout으로 즉시 출력) ─────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    stream=sys.stdout,
    force=True
)
log = logging.getLogger(__name__)

# stdout 버퍼 즉시 flush
sys.stdout.reconfigure(line_buffering=True)

from secrets_client import get_groupware_credentials, get_slack_token
from groupware_client import run_absence_registration
from slack_notifier import send_dm


def main():
    log.info("=" * 60)
    log.info("groupware-bot 시작")
    log.info("=" * 60)
    sys.stdout.flush()

    # ── 환경변수 수신 ────────────────────────────────────────────────────────
    slack_user_id     = os.environ["SLACK_USER_ID"]
    member_name       = os.environ["MEMBER_NAME"]
    absence_type      = os.environ["ABSENCE_TYPE"]
    start_date        = os.environ["START_DATE"]
    end_date          = os.environ["END_DATE"]
    reason            = os.environ.get("REASON", "개인사유")
    approver_name     = os.environ.get("APPROVER_NAME", "")
    approver_keyword  = os.environ.get("APPROVER_KEYWORD", approver_name)
    secret_name       = os.environ["GROUPWARE_CREDENTIALS_SECRET"]
    slack_secret_name = os.environ.get("SLACK_BOT_TOKEN_SECRET_NAME", "")
    slack_dm_user_id  = os.environ.get("SLACK_USER_ID_FOR_DM", slack_user_id)

    log.info(f"파라미터 확인:")
    log.info(f"  user={member_name} ({slack_user_id})")
    log.info(f"  type={absence_type}")
    log.info(f"  period={start_date} ~ {end_date}")
    log.info(f"  reason={reason}")
    log.info(f"  approver={approver_name}")
    log.info(f"  secret={secret_name}")
    sys.stdout.flush()

    # ── Slack 토큰 로드 ──────────────────────────────────────────────────────
    slack_token = None
    if slack_secret_name:
        try:
            log.info(f"Slack 토큰 로드 중: {slack_secret_name}")
            slack_token = get_slack_token(slack_secret_name)
            log.info("Slack 토큰 로드 완료")
        except Exception as e:
            log.warning(f"Slack 토큰 로드 실패 (DM 불가): {e}")
    sys.stdout.flush()

    # ── 자동화 실행 ──────────────────────────────────────────────────────────
    try:
        # 1. 그룹웨어 계정 조회 (ID/PW)
        log.info(f"그룹웨어 계정 조회 중: secret={secret_name}, userId={slack_user_id}")
        sys.stdout.flush()
        credentials = get_groupware_credentials(secret_name, slack_user_id)
        log.info(f"계정 조회 완료: groupwareId={credentials['id']}")
        sys.stdout.flush()

        # 2. Playwright 자동화 실행
        log.info("Playwright 자동화 시작")
        sys.stdout.flush()
        params = {
            "absence_type":     absence_type,
            "start_date":       start_date,
            "end_date":         end_date,
            "reason":           reason,
            "approver_name":    approver_name,
            "approver_keyword": approver_keyword,
        }
        run_absence_registration(credentials, params)
        log.info("Playwright 자동화 완료")
        sys.stdout.flush()

        # 3. 성공 Slack DM
        if slack_token:
            send_dm(
                slack_dm_user_id,
                f"✅ *그룹웨어 부재 신청 완료*\n"
                f"> 부재명: {absence_type}\n"
                f"> 기간: {start_date} ~ {end_date}\n"
                f"> 사유: {reason}\n"
                f"> *{approver_name}*님께 결재 요청했습니다.",
                slack_token
            )
        log.info("완료")
        sys.stdout.flush()
        sys.exit(0)

    except Exception as e:
        log.error(f"실패: {e}")
        traceback.print_exc()
        sys.stdout.flush()

        # 실패 Slack DM
        if slack_token:
            try:
                send_dm(
                    slack_dm_user_id,
                    f"❌ *그룹웨어 부재 신청 실패*\n"
                    f"> 부재명: {absence_type} / 기간: {start_date} ~ {end_date}\n"
                    f"> 오류: {str(e)[:300]}\n"
                    f"> 그룹웨어에서 직접 신청해 주세요: <https://gw.riman.com>",
                    slack_token
                )
            except Exception as dm_err:
                log.error(f"실패 DM 전송도 실패: {dm_err}")

        sys.exit(1)


if __name__ == "__main__":
    main()
