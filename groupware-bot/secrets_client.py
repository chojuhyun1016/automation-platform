"""
Secrets Manager에서 그룹웨어 계정 조회 및 KMS 복호화.

Secret 구조 (실제 저장 구조):
{
  "employees": [
    {
      "slack_user_id":      "U0627755JP7",
      "slack_name":         "juhyun.cho",
      "groupware_id":       "R00365",
      "groupware_password": "ENC:base64(encDataKey).base64(IV).base64(ciphertext)"
    }
  ]
}

암호화 포맷: Java PasswordEncryptionService와 동일한 KMS+AES-256-GCM Envelope Encryption.
"""

import boto3
import json
import base64
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def get_groupware_credentials(secret_name: str, slack_user_id: str) -> dict:
    """
    Secrets Manager에서 slack_user_id로 그룹웨어 ID/PW 조회 및 복호화.

    Args:
        secret_name:   Secrets Manager Secret 이름
        slack_user_id: Slack 사용자 ID

    Returns:
        {"id": "R00365", "password": "복호화된 평문"}

    Raises:
        ValueError: 해당 slack_user_id 계정 미등록 시
    """
    sm  = boto3.client("secretsmanager")
    kms = boto3.client("kms")

    resp   = sm.get_secret_value(SecretId=secret_name)
    secret = json.loads(resp["SecretString"])

    # 실제 저장 구조: employees 배열 + snake_case 필드명
    accounts = secret.get("employees", [])
    target = next(
        (a for a in accounts if a.get("slack_user_id") == slack_user_id),
        None
    )
    if not target:
        raise ValueError(
            f"그룹웨어 계정 미등록: slackUserId={slack_user_id}\n"
            f"등록된 계정 수: {len(accounts)}"
        )

    groupware_id = target["groupware_id"]
    encrypted_pw = target["groupware_password"]

    # KMS Envelope Decryption
    # 포맷: ENC:base64(encDataKey).base64(IV).base64(ciphertext)
    if encrypted_pw.startswith("ENC:"):
        parts = encrypted_pw[4:].split(".")
        if len(parts) != 3:
            raise ValueError(f"암호화 포맷 오류: {encrypted_pw[:20]}...")

        enc_data_key = base64.b64decode(parts[0])
        iv           = base64.b64decode(parts[1])
        ciphertext   = base64.b64decode(parts[2])

        # KMS로 데이터 키 복호화
        dec_resp  = kms.decrypt(CiphertextBlob=enc_data_key)
        plain_key = dec_resp["Plaintext"]

        # AES-256-GCM 복호화
        aesgcm   = AESGCM(plain_key)
        password = aesgcm.decrypt(iv, ciphertext, None).decode("utf-8")
    else:
        # 레거시 평문 (미암호화)
        print(f"[secrets_client] 경고: 평문 비밀번호 감지 — 암호화 권장: user={slack_user_id}")
        password = encrypted_pw

    return {"id": groupware_id, "password": password}


def get_slack_token(secret_name: str) -> str:
    """
    Secrets Manager에서 Slack Bot Token 조회.

    저장 형식 두 가지 모두 지원:
    - JSON: {"token": "xoxb-..."}
    - 평문: xoxb-...

    Args:
        secret_name: Secrets Manager Secret 이름

    Returns:
        Slack Bot Token 문자열 (xoxb-... 형태)
    """
    sm   = boto3.client("secretsmanager")
    resp = sm.get_secret_value(SecretId=secret_name)
    raw  = resp["SecretString"].strip()

    # JSON 형태인 경우 파싱
    try:
        parsed = json.loads(raw)
        if isinstance(parsed, dict):
            # {"token": "xoxb-..."} 형태
            token = parsed.get("token") or parsed.get("slack_token") or parsed.get("value")
            if token:
                return token.strip()
    except (json.JSONDecodeError, TypeError):
        pass

    # 평문 토큰 (xoxb-... 형태)
    return raw
