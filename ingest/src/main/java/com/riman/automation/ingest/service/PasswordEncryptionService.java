package com.riman.automation.ingest.service;

import com.riman.automation.common.exception.ConfigException;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;
import software.amazon.awssdk.services.kms.model.DataKeySpec;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 비밀번호 암호화/복호화 서비스.
 * AWS KMS + AES-256-GCM Envelope Encryption 방식을 사용한다.
 *
 * 암호화 절차: KMS DataKey 생성 → SecureRandom IV 생성 → AES-256-GCM 암호화 →
 *   "ENC:base64(encryptedDataKey).base64(IV).base64(ciphertext)" 포맷으로 직렬화.
 * 복호화 절차: 포맷 파싱 → KMS 로 DataKey 복호화 → AES-256-GCM 복호화.
 * 평문 DataKey 는 사용 직후 메모리에서 zero-fill 된다.
 *
 * 보안 특성: Secrets Manager 만으로 평문 취득 불가, CloudTrail 에 평문 미기록,
 * GCM 무결성 검증으로 위변조 탐지, IV 재생성으로 동일 비밀번호에도 서로 다른 암호문 생성.
 *
 * 필수 환경변수: KMS_KEY_ID (ARN 또는 alias).
 * 레거시 호환: ENC: 접두사가 없는 값은 평문으로 간주되어 그대로 반환되고,
 * 다음 upsert 시점에 자동으로 암호화 포맷으로 마이그레이션된다.
 */
@Slf4j
public class PasswordEncryptionService {

  /**
   * 암호화된 비밀번호를 식별하는 접두사. Secrets Manager 저장 시 평문과 구분하는 역할.
   */
  private static final String ENC_PREFIX = "ENC:";

  private static final String AES_GCM = "AES/GCM/NoPadding";
  private static final int GCM_TAG_LENGTH = 128;
  private static final int IV_LENGTH = 12;
  private static final String KEY_SPEC = "AES";
  private static final String KMS_KEY_ENV = "KMS_KEY_ID";

  /**
   * KmsClient 공유 인스턴스. Lambda warm 재사용으로 초기화 비용(~200ms)을 콜드스타트 1회로 한정한다.
   */
  private static final KmsClient SHARED_KMS_CLIENT = KmsClient.builder().build();

  private final KmsClient kmsClient;
  private final String kmsKeyId;

  public PasswordEncryptionService() {
    this.kmsClient = SHARED_KMS_CLIENT;
    String keyId = System.getenv(KMS_KEY_ENV);
    if (keyId == null || keyId.isBlank()) {
      throw new ConfigException(
          "필수 환경변수 미설정: " + KMS_KEY_ENV
              + " (예: arn:aws:kms:ap-northeast-2:ACCOUNT:key/KEY-ID 또는 alias/KEY-ALIAS)");
    }
    this.kmsKeyId = keyId;
    log.info("[PasswordEncryptionService] initialized: kmsKeyId={}", maskKeyId(kmsKeyId));
  }

  /**
   * 비밀번호를 암호화하여 저장 포맷 문자열을 반환한다.
   * 입력이 null/blank 이면 그대로 반환한다.
   */
  public String encrypt(String plainPassword) {
    if (plainPassword == null || plainPassword.isBlank()) {
      return plainPassword;
    }

    byte[] plainDataKey = null;
    try {
      // KMS DataKey 생성 (32byte = AES-256).
      GenerateDataKeyResponse dataKeyResp = kmsClient.generateDataKey(
          GenerateDataKeyRequest.builder()
              .keyId(kmsKeyId)
              .keySpec(DataKeySpec.AES_256)
              .build());

      plainDataKey = dataKeyResp.plaintext().asByteArray();
      byte[] encryptedDataKey = dataKeyResp.ciphertextBlob().asByteArray();

      // GCM 권장 12byte IV 를 SecureRandom 으로 생성.
      byte[] iv = new byte[IV_LENGTH];
      new SecureRandom().nextBytes(iv);

      // AES-256-GCM 암호화.
      SecretKey secretKey = new SecretKeySpec(plainDataKey, KEY_SPEC);
      Cipher cipher = Cipher.getInstance(AES_GCM);
      cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
      byte[] ciphertext = cipher.doFinal(
          plainPassword.getBytes(StandardCharsets.UTF_8));

      // 저장 포맷으로 조합한다.
      Base64.Encoder b64 = Base64.getEncoder();
      String result = ENC_PREFIX
          + b64.encodeToString(encryptedDataKey) + "."
          + b64.encodeToString(iv) + "."
          + b64.encodeToString(ciphertext);

      log.debug("[PasswordEncryptionService] 암호화 완료");
      return result;

    } catch (Exception e) {
      log.error("[PasswordEncryptionService] 암호화 실패", e);
      throw new RuntimeException("비밀번호 암호화 실패", e);
    } finally {
      // 평문 DataKey 를 메모리에서 즉시 제거한다.
      if (plainDataKey != null) {
        java.util.Arrays.fill(plainDataKey, (byte) 0);
      }
    }
  }

  /**
   * 암호화된 비밀번호를 복호화하여 평문을 반환한다.
   * ENC: 접두사가 없는 레거시 평문은 그대로 반환되며 다음 upsert 시 자동 암호화된다.
   */
  public String decrypt(String storedPassword) {
    if (storedPassword == null || storedPassword.isBlank()) {
      return storedPassword;
    }

    // 레거시 평문 호환 처리.
    if (!storedPassword.startsWith(ENC_PREFIX)) {
      log.warn("[PasswordEncryptionService] 레거시 평문 비밀번호 감지 — 다음 upsert 시 자동 암호화됨");
      return storedPassword;
    }

    byte[] plainDataKey = null;
    try {
      // 저장 포맷 파싱: ENC:encDataKey.IV.ciphertext.
      String encoded = storedPassword.substring(ENC_PREFIX.length());
      String[] parts = encoded.split("\\.", 3);
      if (parts.length != 3) {
        throw new IllegalArgumentException("저장 포맷 불일치: parts=" + parts.length);
      }

      Base64.Decoder b64 = Base64.getDecoder();
      byte[] encryptedDataKey = b64.decode(parts[0]);
      byte[] iv = b64.decode(parts[1]);
      byte[] ciphertext = b64.decode(parts[2]);

      // KMS 로 DataKey 복호화.
      plainDataKey = kmsClient.decrypt(
          DecryptRequest.builder()
              .ciphertextBlob(SdkBytes.fromByteArray(encryptedDataKey))
              .keyId(kmsKeyId)
              .build()
      ).plaintext().asByteArray();

      // AES-256-GCM 복호화.
      SecretKey secretKey = new SecretKeySpec(plainDataKey, KEY_SPEC);
      Cipher cipher = Cipher.getInstance(AES_GCM);
      cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
      byte[] plainBytes = cipher.doFinal(ciphertext);

      log.debug("[PasswordEncryptionService] 복호화 완료");
      return new String(plainBytes, StandardCharsets.UTF_8);

    } catch (Exception e) {
      log.error("[PasswordEncryptionService] 복호화 실패", e);
      throw new RuntimeException("비밀번호 복호화 실패", e);
    } finally {
      if (plainDataKey != null) {
        java.util.Arrays.fill(plainDataKey, (byte) 0);
      }
    }
  }

  /**
   * 이미 암호화 포맷 문자열인지 확인한다.
   */
  public static boolean isEncrypted(String value) {
    return value != null && value.startsWith(ENC_PREFIX);
  }

  private static String maskKeyId(String keyId) {
    if (keyId == null || keyId.length() <= 8) return "***";
    return keyId.substring(0, 8) + "...";
  }
}
