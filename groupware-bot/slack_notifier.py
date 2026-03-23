"""
Slack DM 전송 유틸리티.
외부 라이브러리 없이 urllib만 사용.
"""

import json
import urllib.request
import urllib.error


def send_dm(slack_user_id: str, text: str, token: str) -> None:
    """
    Slack DM 채널을 열고 메시지를 전송한다.

    Args:
        slack_user_id: 수신자 Slack User ID
        text:          전송할 메시지 텍스트
        token:         Slack Bot Token
    """
    # 1. DM 채널 열기
    dm_resp    = _call_slack("conversations.open", {"users": slack_user_id}, token)
    channel_id = dm_resp["channel"]["id"]

    # 2. 메시지 전송
    _call_slack("chat.postMessage", {
        "channel": channel_id,
        "text":    text
    }, token)


def _call_slack(method: str, payload: dict, token: str) -> dict:
    """
    Slack Web API 호출.

    Raises:
        RuntimeError: Slack API ok:false 응답 시
    """
    url  = f"https://slack.com/api/{method}"
    data = json.dumps(payload).encode("utf-8")
    req  = urllib.request.Request(
        url,
        data=data,
        headers={
            "Content-Type":  "application/json",
            "Authorization": f"Bearer {token}"
        }
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            result = json.loads(resp.read())
    except urllib.error.URLError as e:
        raise RuntimeError(f"Slack API 네트워크 오류 [{method}]: {e}")

    if not result.get("ok"):
        raise RuntimeError(f"Slack API 실패 [{method}]: {result.get('error')} / {result}")

    return result
