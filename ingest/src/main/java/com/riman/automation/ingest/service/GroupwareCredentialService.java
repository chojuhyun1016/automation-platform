package com.riman.automation.ingest.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riman.automation.common.exception.ExternalApiClientException;
import com.riman.automation.common.model.GroupwareAccountInfo;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class GroupwareCredentialService {

    private static final String SECRET_NAME_ENV = "GROUPWARE_CREDENTIALS_SECRET";
    private static final String DEFAULT_SECRET = "automation-groupware-credentials";
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private static final ObjectMapper OM = new ObjectMapper();

    /**
     * SecretsManagerClient static 공유 인스턴스.
     * Lambda warm 상태에서 재사용 — 초기화 비용(~200ms) 콜드스타트 1회만 지불.
     */
    private static final SecretsManagerClient SHARED_SECRETS_CLIENT =
            SecretsManagerClient.builder().build();

    private final SecretsManagerClient secretsClient;
    private final String secretName;
    /**
     * 비밀번호 암호화/복호화 서비스 — KMS_KEY_ID 환경변수 미설정 시 null (평문 fallback)
     */
    private final PasswordEncryptionService encryptionService;

    private List<GroupwareAccountInfo> cache;
    private long lastLoadTime = 0L;

    public GroupwareCredentialService() {
        this.secretsClient = SHARED_SECRETS_CLIENT;
        String env = System.getenv(SECRET_NAME_ENV);
        this.secretName = (env != null && !env.isBlank()) ? env : DEFAULT_SECRET;

        // KMS_KEY_ID 설정 시 암호화 활성, 미설정 시 비활성 (레거시 호환)
        PasswordEncryptionService enc = null;
        try {
            enc = new PasswordEncryptionService();
            log.info("[GroupwareCredentialService] 비밀번호 암호화 활성 (KMS)");
        } catch (Exception e) {
            log.warn("[GroupwareCredentialService] KMS_KEY_ID 미설정 — 비밀번호 평문 저장 (보안 취약): {}", e.getMessage());
        }
        this.encryptionService = enc;

        log.info("[GroupwareCredentialService] initialized: secretName={}", secretName);
    }

    // =========================================================================
    // 조회
    // =========================================================================

    public GroupwareAccountInfo findBySlackUserId(String slackUserId) {
        if (slackUserId == null || slackUserId.isBlank()) return null;
        return loadCredentials().stream()
                .filter(c -> slackUserId.equals(c.getSlackUserId()))
                .findFirst()
                .orElse(null);
    }

    // =========================================================================
    // 등록 / 변경 (Upsert)
    // =========================================================================

    public void upsert(String slackUserId, String slackName,
                       String groupwareId, String groupwarePassword) {
        log.info("[GroupwareCredentialService] upsert: slackUserId={}, slackName={}, groupwareId={}",
                slackUserId, slackName, groupwareId);

        String passwordToStore;
        if (encryptionService != null && !PasswordEncryptionService.isEncrypted(groupwarePassword)) {
            passwordToStore = encryptionService.encrypt(groupwarePassword);
        } else {
            passwordToStore = groupwarePassword;
        }

        String now = GroupwareAccountInfo.nowKst();
        List<GroupwareAccountInfo> current = new ArrayList<>(loadCredentials());

        boolean found = false;
        for (int i = 0; i < current.size(); i++) {
            if (slackUserId.equals(current.get(i).getSlackUserId())) {
                GroupwareAccountInfo existing = current.get(i);
                // slackName: null이거나 blank면 기존 값 유지
                String nameToStore = (slackName != null && !slackName.isBlank())
                        ? slackName : existing.getSlackName();
                current.set(i, new GroupwareAccountInfo(
                        slackUserId,
                        nameToStore,
                        groupwareId,
                        passwordToStore,
                        existing.getRegisteredAt() != null ? existing.getRegisteredAt() : now,
                        now
                ));
                found = true;
                log.info("[GroupwareCredentialService] 기존 계정 변경: slackUserId={}", slackUserId);
                break;
            }
        }

        if (!found) {
            current.add(new GroupwareAccountInfo(
                    slackUserId, slackName, groupwareId, passwordToStore,
                    now, now
            ));
            log.info("[GroupwareCredentialService] 신규 계정 등록: slackUserId={}", slackUserId);
        }

        saveToSecret(current);
        invalidateCache();
    }

    /**
     * slackName만 저장/갱신 — handleCommand() 시점에 이름을 미리 저장할 때 사용.
     * 계정 정보(groupwareId, password)는 변경하지 않는다.
     * 기존 항목이 없으면(미등록 상태) slackUserId + slackName만 가진 임시 항목 생성.
     */
    public void upsertSlackName(String slackUserId, String slackName) {
        if (slackName == null || slackName.isBlank()) {
            log.debug("[GroupwareCredentialService] upsertSlackName: slackName 비어있음, skip");
            return;
        }

        log.info("[GroupwareCredentialService] upsertSlackName: slackUserId={}, slackName={}",
                slackUserId, slackName);

        String now = GroupwareAccountInfo.nowKst();
        List<GroupwareAccountInfo> current = new ArrayList<>(loadCredentials());

        boolean found = false;
        for (int i = 0; i < current.size(); i++) {
            if (slackUserId.equals(current.get(i).getSlackUserId())) {
                GroupwareAccountInfo existing = current.get(i);
                current.set(i, new GroupwareAccountInfo(
                        slackUserId,
                        slackName,                    // 이름 갱신
                        existing.getGroupwareId(),    // 나머지는 기존 값 유지
                        existing.getGroupwarePassword(),
                        existing.getRegisteredAt() != null ? existing.getRegisteredAt() : now,
                        now
                ));
                found = true;
                log.info("[GroupwareCredentialService] slackName 갱신: slackUserId={}, name={}",
                        slackUserId, slackName);
                break;
            }
        }

        if (!found) {
            // 아직 계정 등록 전 — slackName만 가진 임시 항목 생성
            // groupwareId/password는 이후 upsert()로 채워짐
            current.add(new GroupwareAccountInfo(
                    slackUserId, slackName, null, null, now, now
            ));
            log.info("[GroupwareCredentialService] slackName 임시 항목 생성: slackUserId={}, name={}",
                    slackUserId, slackName);
        }

        saveToSecret(current);
        invalidateCache();
    }

    /**
     * 레거시 호환 오버로드 — slackName 없는 기존 호출부 대응
     */
    public void upsert(String slackUserId, String groupwareId, String groupwarePassword) {
        upsert(slackUserId, null, groupwareId, groupwarePassword);
    }

    // =========================================================================
    // 삭제
    // =========================================================================

    public boolean deleteWithVerification(
            String slackUserId, String groupwareId, String groupwarePassword) {

        List<GroupwareAccountInfo> current = new ArrayList<>(loadCredentials());

        GroupwareAccountInfo target = current.stream()
                .filter(c -> slackUserId.equals(c.getSlackUserId()))
                .findFirst()
                .orElse(null);

        if (target == null) {
            log.warn("[GroupwareCredentialService] 삭제 대상 없음: slackUserId={}", slackUserId);
            return false;
        }

        // 저장된 비밀번호 복호화 후 비교 (암호화 활성 시)
        String storedPassword = (encryptionService != null)
                ? encryptionService.decrypt(target.getGroupwarePassword())
                : target.getGroupwarePassword();

        if (!groupwareId.equals(target.getGroupwareId())
                || !groupwarePassword.equals(storedPassword)) {
            log.warn("[GroupwareCredentialService] ID/비밀번호 불일치: slackUserId={}", slackUserId);
            return false;
        }

        current.removeIf(c -> slackUserId.equals(c.getSlackUserId()));
        saveToSecret(current);
        invalidateCache();
        log.info("[GroupwareCredentialService] 계정 삭제 완료: slackUserId={}", slackUserId);
        return true;
    }

    // =========================================================================
    // 내부 — Secret 읽기/쓰기
    // =========================================================================

    private List<GroupwareAccountInfo> loadCredentials() {
        if (cache != null && (System.currentTimeMillis() - lastLoadTime) < CACHE_TTL_MS) {
            return cache;
        }

        try {
            String json = getSecretValue();
            cache = parseCredentials(json);
            lastLoadTime = System.currentTimeMillis();
            log.info("[GroupwareCredentialService] 시크릿 로드 완료: {}명", cache.size());
            return cache;

        } catch (ResourceNotFoundException e) {
            log.warn("[GroupwareCredentialService] 시크릿 없음, 빈 목록 반환: secretName={}", secretName);
            cache = new ArrayList<>();
            lastLoadTime = System.currentTimeMillis();
            return cache;

        } catch (Exception e) {
            log.error("[GroupwareCredentialService] 시크릿 로드 실패: secretName={}", secretName, e);
            return cache != null ? cache : new ArrayList<>();
        }
    }

    private String getSecretValue() {
        GetSecretValueRequest request = GetSecretValueRequest.builder()
                .secretId(secretName)
                .build();
        return secretsClient.getSecretValue(request).secretString();
    }

    private void saveToSecret(List<GroupwareAccountInfo> credentials) {
        try {
            String json = serializeCredentials(credentials);
            PutSecretValueRequest request = PutSecretValueRequest.builder()
                    .secretId(secretName)
                    .secretString(json)
                    .build();
            secretsClient.putSecretValue(request);
            log.info("[GroupwareCredentialService] 시크릿 업데이트 완료: {}명", credentials.size());
        } catch (Exception e) {
            log.error("[GroupwareCredentialService] 시크릿 저장 실패", e);
            throw new ExternalApiClientException("SecretsManager", "그룹웨어 계정 저장 실패", e);
        }
    }

    private void invalidateCache() {
        cache = null;
        lastLoadTime = 0L;
        log.debug("[GroupwareCredentialService] 캐시 무효화");
    }

    // =========================================================================
    // 내부 — JSON 직렬화/역직렬화
    // =========================================================================

    private List<GroupwareAccountInfo> parseCredentials(String json) throws Exception {
        ObjectNode root = (ObjectNode) OM.readTree(json);
        ArrayNode employees = (ArrayNode) root.path("employees");

        List<GroupwareAccountInfo> result = new ArrayList<>();
        if (employees == null || employees.isMissingNode()) return result;

        List<CredentialJson> raw = OM.readValue(
                employees.toString(), new TypeReference<List<CredentialJson>>() {
                });

        for (CredentialJson item : raw) {
            result.add(new GroupwareAccountInfo(
                    item.slackUserId,
                    item.slackName,
                    item.groupwareId,
                    item.groupwarePassword,
                    item.registeredAt,
                    item.updatedAt));
        }
        return result;
    }

    private String serializeCredentials(List<GroupwareAccountInfo> credentials) throws Exception {
        ArrayNode employees = OM.createArrayNode();
        for (GroupwareAccountInfo c : credentials) {
            ObjectNode node = OM.createObjectNode();
            node.put("slack_user_id", c.getSlackUserId());
            String name = c.getSlackName();
            node.put("slack_name", (name != null && !name.isBlank()) ? name : "-");
            node.put("groupware_id", c.getGroupwareId() != null ? c.getGroupwareId() : "");
            node.put("groupware_password", c.getGroupwarePassword() != null ? c.getGroupwarePassword() : "");
            node.put("registered_at", c.getRegisteredAt() != null ? c.getRegisteredAt() : "-");
            node.put("updated_at", c.getUpdatedAt() != null ? c.getUpdatedAt() : "-");
            employees.add(node);
        }
        ObjectNode root = OM.createObjectNode();
        root.set("employees", employees);
        return OM.writeValueAsString(root);
    }

    private static class CredentialJson {
        @JsonProperty("slack_user_id")
        public String slackUserId;
        @JsonProperty("slack_name")
        public String slackName;
        @JsonProperty("groupware_id")
        public String groupwareId;
        @JsonProperty("groupware_password")
        public String groupwarePassword;
        @JsonProperty("registered_at")
        public String registeredAt;
        @JsonProperty("updated_at")
        public String updatedAt;
    }
}
