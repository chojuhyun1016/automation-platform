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
 * 비밀번호 암호화/복호화 서비스 — AWS KMS + AES-256-GCM (Envelope Encryption)
 *
 * <p><b>Envelope Encryption 흐름:</b>
 * <pre>
 * [암호화]
 *   1. KMS.generateDataKey(KMS_KEY_ID) → plainDataKey(32byte) + encryptedDataKey
 *   2. SecureRandom → IV(12byte) 생성
 *   3. AES-256-GCM(plainDataKey, IV) → password 암호화 → ciphertext
 *   4. 저장 포맷: base64(encryptedDataKey) + "." + base64(IV) + "." + base64(ciphertext)
 *   5. plainDataKey는 메모리에서 즉시 제거 (zero-fill)
 *
 * [복호화]
 *   1. 저장 포맷 파싱 → encryptedDataKey, IV, ciphertext 분리
 *   2. KMS.decrypt(encryptedDataKey) → plainDataKey 복원
 *   3. AES-256-GCM(plainDataKey, IV) → ciphertext 복호화 → plaintext
 * </pre>
 *
 * <p><b>보안 특성:</b>
 * <ul>
 *   <li>KMS Key 없이는 복호화 불가 — Secrets Manager 접근만으로 평문 취득 불가</li>
 *   <li>CloudTrail에 평문 비밀번호가 기록되지 않음</li>
 *   <li>Lambda IAM Role에 kms:GenerateDataKey + kms:Decrypt 권한만 부여</li>
 *   <li>GCM 모드로 데이터 무결성(인증 태그) 보장 — 저장된 값 위변조 탐지 가능</li>
 *   <li>IV는 암호화마다 SecureRandom으로 재생성 — 동일 비밀번호도 다른 암호문 생성</li>
 * </ul>
 *
 * <p><b>Lambda 환경변수:</b>
 * <pre>
 *   KMS_KEY_ID  AWS KMS Key ID 또는 ARN
 *               예: arn:aws:kms:ap-northeast-2:123456789012:key/xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
 *               또는 alias: alias/automation-password-key
 * </pre>
 *
 * <p><b>레거시 평문 호환:</b>
 * 기존에 평문으로 저장된 비밀번호(ENC: 접두사 없음)는 복호화 시
 * 평문 그대로 반환하고 경고 로그를 남긴다.
 * 다음 upsert() 시점에 자동으로 암호화 포맷으로 마이그레이션된다.
 */
@Slf4j
public class PasswordEncryptionService {

    /**
     * 암호화된 비밀번호 식별 접두사.
     * Secrets Manager에 저장될 때 이 접두사로 평문과 구분한다.
     */
    private static final String ENC_PREFIX = "ENC:";

    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;  // bits
    private static final int IV_LENGTH = 12;         // bytes (GCM 권장값)
    private static final String KEY_SPEC = "AES";
    private static final String KMS_KEY_ENV = "KMS_KEY_ID";

    /**
     * KmsClient static 공유 인스턴스.
     * Lambda warm 상태에서 재사용 — 초기화 비용(~200ms) 콜드스타트 1회만 지불.
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

    // =========================================================================
    // 공개 API
    // =========================================================================

    /**
     * 비밀번호 암호화 후 저장 포맷 반환
     *
     * <p>저장 포맷: {@code ENC:base64(encryptedDataKey).base64(IV).base64(ciphertext)}
     *
     * @param plainPassword 평문 비밀번호
     * @return 암호화된 비밀번호 문자열 (ENC: 접두사 포함)
     */
    public String encrypt(String plainPassword) {
        if (plainPassword == null || plainPassword.isBlank()) {
            return plainPassword;
        }

        byte[] plainDataKey = null;
        try {
            // 1. KMS DataKey 생성 (32byte = AES-256)
            GenerateDataKeyResponse dataKeyResp = kmsClient.generateDataKey(
                    GenerateDataKeyRequest.builder()
                            .keyId(kmsKeyId)
                            .keySpec(DataKeySpec.AES_256)
                            .build());

            plainDataKey = dataKeyResp.plaintext().asByteArray();
            byte[] encryptedDataKey = dataKeyResp.ciphertextBlob().asByteArray();

            // 2. IV 생성 (12byte, SecureRandom)
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            // 3. AES-256-GCM 암호화
            SecretKey secretKey = new SecretKeySpec(plainDataKey, KEY_SPEC);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(
                    plainPassword.getBytes(StandardCharsets.UTF_8));

            // 4. 저장 포맷 조합
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
            // 5. plainDataKey 메모리 제거 (zero-fill)
            if (plainDataKey != null) {
                java.util.Arrays.fill(plainDataKey, (byte) 0);
            }
        }
    }

    /**
     * 암호화된 비밀번호 복호화
     *
     * <p>ENC: 접두사가 없는 값(레거시 평문)은 그대로 반환하고 경고 로그를 남긴다.
     * 다음 upsert() 호출 시 자동으로 암호화 포맷으로 마이그레이션된다.
     *
     * @param storedPassword 저장된 비밀번호 값 (ENC: 접두사 포함 또는 레거시 평문)
     * @return 복호화된 평문 비밀번호
     */
    public String decrypt(String storedPassword) {
        if (storedPassword == null || storedPassword.isBlank()) {
            return storedPassword;
        }

        // 레거시 평문 호환: ENC: 접두사 없으면 평문 그대로 반환
        if (!storedPassword.startsWith(ENC_PREFIX)) {
            log.warn("[PasswordEncryptionService] 레거시 평문 비밀번호 감지 — 다음 upsert 시 자동 암호화됨");
            return storedPassword;
        }

        byte[] plainDataKey = null;
        try {
            // 저장 포맷 파싱: ENC:encDataKey.IV.ciphertext
            String encoded = storedPassword.substring(ENC_PREFIX.length());
            String[] parts = encoded.split("\\.", 3);
            if (parts.length != 3) {
                throw new IllegalArgumentException("저장 포맷 불일치: parts=" + parts.length);
            }

            Base64.Decoder b64 = Base64.getDecoder();
            byte[] encryptedDataKey = b64.decode(parts[0]);
            byte[] iv = b64.decode(parts[1]);
            byte[] ciphertext = b64.decode(parts[2]);

            // KMS로 DataKey 복호화
            plainDataKey = kmsClient.decrypt(
                    DecryptRequest.builder()
                            .ciphertextBlob(SdkBytes.fromByteArray(encryptedDataKey))
                            .keyId(kmsKeyId)
                            .build()
            ).plaintext().asByteArray();

            // AES-256-GCM 복호화
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
     * 이미 암호화된 값인지 확인
     */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENC_PREFIX);
    }

    // =========================================================================
    // 내부
    // =========================================================================

    private static String maskKeyId(String keyId) {
        if (keyId == null || keyId.length() <= 8) return "***";
        return keyId.substring(0, 8) + "...";
    }
}
