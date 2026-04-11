package com.riman.automation.worker.service;

import com.riman.automation.worker.dto.s3.TeamMember;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeamMemberServiceTest {

    private static final String BUCKET = "test-bucket";
    private static final String KEY = "team-members.json";

    private static final String MEMBERS_JSON = """
            {
              "members": [
                {
                  "name": "홍길동",
                  "name_en": "Hong Gildong",
                  "email": "hong@example.com",
                  "jira_account_id": "jira-001",
                  "slack_user_id": "U001",
                  "active": true,
                  "team": "CCE",
                  "role": "Engineer"
                },
                {
                  "name": "김철수",
                  "name_en": "Kim Cheolsu",
                  "email": "kim@example.com",
                  "jira_account_id": "jira-002",
                  "slack_user_id": "U002",
                  "active": true,
                  "team": "CCE",
                  "role": "Manager"
                },
                {
                  "name": "이영희",
                  "name_en": "Lee Younghee",
                  "email": "lee@example.com",
                  "jira_account_id": "jira-003",
                  "slack_user_id": "U003",
                  "active": false,
                  "team": "CCE",
                  "role": "Designer"
                }
              ]
            }
            """;

    @Mock
    private S3Client s3Client;

    private TeamMemberService teamMemberService;

    @BeforeEach
    void setUp() {
        teamMemberService = new TeamMemberService(s3Client, BUCKET, KEY);
        stubS3Response(MEMBERS_JSON);
    }

    private void stubS3Response(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ResponseInputStream<GetObjectResponse> stream = new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                new ByteArrayInputStream(bytes));
        lenient().when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(stream);
    }

    // =========================================================================
    // findByAccountId
    // =========================================================================

    @Nested
    @DisplayName("findByAccountId")
    class FindByAccountIdTest {

        @Test
        @DisplayName("존재하는 accountId로 TeamMember를 찾는다")
        void findByAccountId_existing_returnsMember() {
            TeamMember result = teamMemberService.findByAccountId("jira-001");

            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("홍길동");
            assertThat(result.getSlackUserId()).isEqualTo("U001");
        }

        @Test
        @DisplayName("존재하지 않는 accountId면 null 반환")
        void findByAccountId_notFound_returnsNull() {
            TeamMember result = teamMemberService.findByAccountId("jira-999");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("null accountId면 null 반환")
        void findByAccountId_null_returnsNull() {
            TeamMember result = teamMemberService.findByAccountId(null);

            assertThat(result).isNull();
            verify(s3Client, never()).getObject(any(GetObjectRequest.class));
        }
    }

    // =========================================================================
    // findBySlackUserId
    // =========================================================================

    @Nested
    @DisplayName("findBySlackUserId")
    class FindBySlackUserIdTest {

        @Test
        @DisplayName("존재하는 slackUserId로 TeamMember를 찾는다")
        void findBySlackUserId_existing_returnsMember() {
            TeamMember result = teamMemberService.findBySlackUserId("U002");

            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("김철수");
            assertThat(result.getJiraAccountId()).isEqualTo("jira-002");
        }

        @Test
        @DisplayName("존재하지 않는 slackUserId면 null 반환")
        void findBySlackUserId_notFound_returnsNull() {
            TeamMember result = teamMemberService.findBySlackUserId("U999");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("null slackUserId면 null 반환")
        void findBySlackUserId_null_returnsNull() {
            TeamMember result = teamMemberService.findBySlackUserId(null);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("blank slackUserId면 null 반환")
        void findBySlackUserId_blank_returnsNull() {
            TeamMember result = teamMemberService.findBySlackUserId("  ");

            assertThat(result).isNull();
        }
    }

    // =========================================================================
    // findByAccountIds (배치)
    // =========================================================================

    @Nested
    @DisplayName("findByAccountIds")
    class FindByAccountIdsTest {

        @Test
        @DisplayName("여러 accountId로 일치하는 팀원들을 반환한다")
        void findByAccountIds_multiple_returnsMatches() {
            List<TeamMember> result = teamMemberService
                    .findByAccountIds(List.of("jira-001", "jira-003"));

            assertThat(result).hasSize(2);
            assertThat(result).extracting(TeamMember::getName)
                    .containsExactlyInAnyOrder("홍길동", "이영희");
        }

        @Test
        @DisplayName("빈 리스트면 빈 리스트 반환")
        void findByAccountIds_emptyList_returnsEmpty() {
            List<TeamMember> result = teamMemberService.findByAccountIds(List.of());

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("null이면 빈 리스트 반환")
        void findByAccountIds_null_returnsEmpty() {
            List<TeamMember> result = teamMemberService.findByAccountIds(null);

            assertThat(result).isEmpty();
        }
    }

    // =========================================================================
    // 캐싱
    // =========================================================================

    @Nested
    @DisplayName("S3 캐싱")
    class CachingTest {

        @Test
        @DisplayName("S3를 한 번만 호출한다 (영구 캐시)")
        void loadMembers_cachedAfterFirstCall() {
            teamMemberService.findByAccountId("jira-001");
            teamMemberService.findByAccountId("jira-002");
            teamMemberService.findBySlackUserId("U003");

            verify(s3Client, times(1)).getObject(any(GetObjectRequest.class));
        }
    }

    // =========================================================================
    // 에러 케이스
    // =========================================================================

    @Nested
    @DisplayName("에러 처리")
    class ErrorTest {

        @Test
        @DisplayName("S3 실패 시 빈 리스트 반환 (예외 삼킴)")
        void loadMembers_s3Failure_returnsEmpty() {
            // 새 서비스 인스턴스 (cachedMembers=null, lazy loading)
            TeamMemberService failService = new TeamMemberService(s3Client, BUCKET, KEY);
            // 기존 lenient stub을 예외로 재설정 — 이 테스트에서만 적용
            when(s3Client.getObject(any(GetObjectRequest.class)))
                    .thenThrow(S3Exception.builder().message("access denied").build());

            TeamMember result = failService.findByAccountId("jira-001");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("bucket 미설정 시 빈 멤버 목록 반환")
        void loadMembers_noBucket_returnsEmpty() {
            TeamMemberService noBucketService = new TeamMemberService(s3Client, null, KEY);

            TeamMember result = noBucketService.findByAccountId("jira-001");

            assertThat(result).isNull();
            verify(s3Client, never()).getObject(any(GetObjectRequest.class));
        }
    }
}
