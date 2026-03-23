package com.riman.automation.worker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riman.automation.worker.dto.s3.TeamMember;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class TeamMemberService {

    private static final String BUCKET_ENV = "CONFIG_BUCKET";
    private static final String MEMBERS_KEY_ENV = "TEAM_MEMBERS_KEY";
    private static final String DEFAULT_MEMBERS_KEY = "team-members.json";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final S3Client s3Client;
    private final String bucket;
    private final String membersKey;

    private List<TeamMember> cachedMembers = null;

    public TeamMemberService() {
        this.s3Client = S3Client.builder().build();
        this.bucket = System.getenv(BUCKET_ENV);
        String keyEnv = System.getenv(MEMBERS_KEY_ENV);
        this.membersKey = (keyEnv != null && !keyEnv.isBlank()) ? keyEnv : DEFAULT_MEMBERS_KEY;
        log.info("TeamMemberService initialized");
    }

    public TeamMember findByAccountId(String accountId) {
        if (accountId == null) return null;
        return loadMembers().stream()
                .filter(m -> accountId.equals(m.getJiraAccountId()))
                .findFirst()
                .orElse(null);
    }

    public List<TeamMember> findByAccountIds(List<String> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) return List.of();
        return loadMembers().stream()
                .filter(m -> m.getJiraAccountId() != null
                        && accountIds.contains(m.getJiraAccountId()))
                .collect(Collectors.toList());
    }

    public TeamMember findBySlackUserId(String slackUserId) {
        if (slackUserId == null || slackUserId.isBlank()) return null;
        return loadMembers().stream()
                .filter(m -> slackUserId.equals(m.getSlackUserId()))
                .findFirst()
                .orElse(null);
    }

    private List<TeamMember> loadMembers() {
        if (cachedMembers != null) {
            return cachedMembers;
        }
        try {
            if (bucket == null || bucket.isBlank()) {
                log.warn("CONFIG_BUCKET 환경변수 미설정 — 빈 멤버 목록 반환");
                cachedMembers = new ArrayList<>();
                return cachedMembers;
            }

            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(membersKey)
                    .build();

            ResponseInputStream<GetObjectResponse> response = s3Client.getObject(request);
            byte[] bytes = response.readAllBytes();

            // ★ { "members": [...] } 구조에서 members 배열만 추출
            JsonNode root = objectMapper.readTree(bytes);
            JsonNode membersNode = root.path("members");

            if (membersNode.isMissingNode() || !membersNode.isArray()) {
                log.warn("team-members.json에 'members' 배열 없음");
                cachedMembers = new ArrayList<>();
                return cachedMembers;
            }

            cachedMembers = objectMapper.readValue(
                    membersNode.toString(),
                    new TypeReference<List<TeamMember>>() {
                    });

            long activeCount = cachedMembers.stream()
                    .filter(m -> Boolean.TRUE.equals(m.getActive())).count();
            long withSlackId = cachedMembers.stream()
                    .filter(m -> m.getSlackUserId() != null).count();

            log.info("Team members loaded: {} active members, {} with Slack userId",
                    activeCount, withSlackId);

            return cachedMembers;

        } catch (Exception e) {
            log.error("팀 멤버 로드 실패: bucket={}, key={}", bucket, membersKey, e);
            cachedMembers = new ArrayList<>();
            return cachedMembers;
        }
    }
}
