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

/**
 * 그룹웨어 계정(ID/비밀번호) 저장소 서비스.
 * AWS Secrets Manager 에 보관된 JSON 을 읽고 쓰며 비밀번호는 KMS 로 암호화/복호화한다.
 * 조회는 5분 메모리 캐시로 처리하여 Secrets Manager 호출을 최소화한다.
 * KMS_KEY_ID 환경변수가 미설정된 경우 평문 저장 fallback 으로 동작한다 (레거시 호환).
 */
@Slf4j
public class GroupwareCredentialService {

  private static final String SECRET_NAME_ENV = "GROUPWARE_CREDENTIALS_SECRET";
  private static final String DEFAULT_SECRET = "automation-groupware-credentials";
  private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

  private static final ObjectMapper OM = new ObjectMapper();

  /**
   * SecretsManagerClient 공유 인스턴스. Lambda warm 재사용으로 초기화 비용(~200ms)을 1회로 한정한다.
   */
  private static final SecretsManagerClient SHARED_SECRETS_CLIENT =
      SecretsManagerClient.builder().build();

  private final SecretsManagerClient secretsClient;
  private final String secretName;

  /**
   * 비밀번호 암호화 서비스. KMS_KEY_ID 미설정 시 null 로 두어 평문 fallback 으로 동작한다.
   */
  private final PasswordEncryptionService encryptionService;

  private List<GroupwareAccountInfo> cache;
  private long lastLoadTime = 0L;

  public GroupwareCredentialService() {
    this.secretsClient = SHARED_SECRETS_CLIENT;
    String env = System.getenv(SECRET_NAME_ENV);
    this.secretName = (env != null && !env.isBlank()) ? env : DEFAULT_SECRET;

    // KMS_KEY_ID 설정 여부로 암호화 사용 여부를 결정한다. 미설정 시 평문 모드.
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

  /**
   * Slack User ID 로 계정 정보를 조회한다. 미등록 시 null 을 반환한다.
   */
  public GroupwareAccountInfo findBySlackUserId(String slackUserId) {
    if (slackUserId == null || slackUserId.isBlank()) return null;
    return loadCredentials().stream()
        .filter(c -> slackUserId.equals(c.getSlackUserId()))
        .findFirst()
        .orElse(null);
  }

  /**
   * 계정을 등록/변경한다 (upsert).
   * 평문 비밀번호는 암호화 활성 시 저장 전에 KMS 로 암호화된다.
   * 기존 항목이 있으면 비밀번호/ID/이름만 갱신하고 registeredAt 은 유지한다.
   */
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
        // slackName 이 null/blank 이면 기존 값을 유지한다.
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
   * slackName 만 저장/갱신한다.
   * handleCommand() 단계에서 사용자 이름을 먼저 저장해 두는 용도이며 계정 정보는 건드리지 않는다.
   * 기존 항목이 없으면 slackUserId + slackName 만 가진 임시 항목이 생성되고
   * 이후 upsert() 호출에서 나머지 필드가 채워진다.
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
            slackName,
            existing.getGroupwareId(),
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
      // 계정 미등록 상태에서 이름만 먼저 저장하는 케이스. 나머지는 null 로 둔다.
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
   * 레거시 호출부 호환 오버로드 (slackName 생략).
   */
  public void upsert(String slackUserId, String groupwareId, String groupwarePassword) {
    upsert(slackUserId, null, groupwareId, groupwarePassword);
  }

  /**
   * ID/비밀번호 일치를 확인한 뒤 계정을 삭제한다. 불일치 시 false 를 반환한다.
   */
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

    // 암호화 활성 모드에서는 저장된 비밀번호를 복호화해 비교한다.
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

  /**
   * Secrets Manager 에서 계정 목록을 로드한다 (5분 TTL 메모리 캐시 적용).
   * 시크릿 부재 시 빈 목록을 캐시에 저장하여 반복 조회 비용을 낮춘다.
   * 로드 실패 시 기존 캐시 값을 유지하여 일시적 장애에 대응한다.
   */
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

  /**
   * Secrets Manager JSON 의 단일 항목 바인딩용 DTO.
   */
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
