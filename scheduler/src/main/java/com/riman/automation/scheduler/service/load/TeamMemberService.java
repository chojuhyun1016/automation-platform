package com.riman.automation.scheduler.service.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.riman.automation.common.exception.ConfigException;
import com.riman.automation.scheduler.dto.s3.TeamMember;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 팀원 목록 로더이다. S3의 team-members.json을 읽어 TeamMember 목록을 반환한다.
 * 루트는 객체 형식이므로 root.path("members")로 배열을 추출한다.
 * Lambda cold start 시 1회 로드하여 캐싱하고 warm 상태에서는 캐시를 재사용한다.
 */
@Slf4j
public class TeamMemberService {

  private static final ObjectMapper OM = new ObjectMapper();
  private static final String S3_KEY = "team-members.json";

  private final S3Client s3Client;
  private final String bucket;

  /** cold start 시 로드된 팀원 목록 캐시이다. */
  private List<TeamMember> cached;

  public TeamMemberService(S3Client s3Client, String bucket) {
    this.s3Client = s3Client;
    this.bucket = bucket;
  }

  /**
   * active=true인 팀원 목록을 반환한다. 캐시를 사용하며 JSON 파일 순서를 유지한다.
   */
  public List<TeamMember> loadEnabled() {
    if (cached == null) {
      cached = load();
    }
    List<TeamMember> active = cached.stream()
        .filter(TeamMember::isActive)
        .toList();
    log.info("[TeamMemberService] 활성 팀원: {}명", active.size());
    return active;
  }

  /**
   * 캐시를 무효화한다. 테스트 또는 수동 갱신 시 사용한다.
   */
  public void invalidateCache() {
    cached = null;
    log.info("[TeamMemberService] 캐시 무효화");
  }

  private List<TeamMember> load() {
    try {
      log.info("[TeamMemberService] S3 로드: {}/{}", bucket, S3_KEY);
      byte[] bytes = s3Client.getObject(
          GetObjectRequest.builder().bucket(bucket).key(S3_KEY).build()
      ).readAllBytes();
      String json = new String(bytes, StandardCharsets.UTF_8);

      JsonNode root = OM.readTree(json);
      JsonNode members = root.path("members");

      if (members.isMissingNode() || !members.isArray()) {
        throw new ConfigException(
            "team-members.json 에 'members' 배열이 없습니다: "
                + bucket + "/" + S3_KEY);
      }

      List<TeamMember> list = OM.convertValue(members, new TypeReference<>() {
      });
      log.info("[TeamMemberService] 로드 완료: 전체 {}명", list.size());
      return list;

    } catch (ConfigException e) {
      throw e;
    } catch (Exception e) {
      throw new ConfigException(
          "team-members.json S3 로드 실패: " + bucket + "/" + S3_KEY, e);
    }
  }
}
