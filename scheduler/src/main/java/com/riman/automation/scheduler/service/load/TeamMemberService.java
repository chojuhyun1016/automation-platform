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
 * 팀원 목록 로더
 *
 * <p>S3의 team-members.json을 읽어 {@link TeamMember} 목록을 반환한다.
 *
 * <p><b>실제 파일 구조:</b>
 * <pre>
 * {
 *   "version": "1.1",
 *   "members": [ { "name":"조주현", "slack_user_id":"U...", "active":true, ... }, ... ],
 *   "bot": { ... },
 *   "channels": { ... }
 * }
 * </pre>
 *
 * <p>루트가 배열이 아니라 객체이므로 root.path("members") 로 배열을 추출한다.
 *
 * <p><b>캐시:</b> Lambda cold start 시 1회 로드, warm 상태에서는 캐시 재사용.
 * 팀원 변경 시 S3 파일만 수정 → Lambda 재시작(cold start) 후 반영.
 */
@Slf4j
public class TeamMemberService {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String S3_KEY = "team-members.json";

    private final S3Client s3Client;
    private final String bucket;

    /**
     * cold start 시 로드된 팀원 목록 캐시
     */
    private List<TeamMember> cached;

    public TeamMemberService(S3Client s3Client, String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    /**
     * active=true 인 팀원 목록 반환 (캐시 사용)
     *
     * @return 활성 팀원 목록 (JSON 파일 순서 그대로)
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
     * 캐시 무효화 (테스트 / 수동 갱신용)
     */
    public void invalidateCache() {
        cached = null;
        log.info("[TeamMemberService] 캐시 무효화");
    }

    // =========================================================================
    // 내부
    // =========================================================================

    private List<TeamMember> load() {
        try {
            log.info("[TeamMemberService] S3 로드: {}/{}", bucket, S3_KEY);
            byte[] bytes = s3Client.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(S3_KEY).build()
            ).readAllBytes();
            String json = new String(bytes, StandardCharsets.UTF_8);

            // 루트는 객체 { "members": [...] } — 배열이 아님
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
