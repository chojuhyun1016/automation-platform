package com.riman.automation.clients.confluence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riman.automation.clients.http.ApiResponse;
import com.riman.automation.clients.http.BaseHttpClient;
import com.riman.automation.common.auth.TokenProvider;
import com.riman.automation.common.exception.ExternalApiClientException;
import lombok.extern.slf4j.Slf4j;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Confluence REST API v1 클라이언트
 *
 * <p><b>책임:</b> HTTP 요청 전송 + 응답 반환만.
 * 페이지 계층 구성, 제목 규칙, 보고서 포맷 — 모두 상위 계층이 결정한다.
 *
 * <p><b>기존 클라이언트와의 대응:</b>
 * <pre>
 *   SlackClient          → Slack API
 *   JiraClient           → Jira REST API
 *   ConfluenceClient     → Confluence REST API  (이 클래스)
 *   GoogleCalendarClient → Google Calendar SDK
 * </pre>
 *
 * <p><b>PUT 구현:</b>
 * {@link BaseHttpClient}는 GET / POST 만 제공한다.
 * Confluence 페이지 업데이트(PUT)는 JDK 내장 {@link HttpURLConnection}으로 구현한다.
 * (clients 모듈 build.gradle 에 apache httpclient5 가 있지만,
 * scheduler 모듈 build.gradle 에는 직접 의존성이 없으므로 JDK 방식으로 통일한다.)
 *
 * <p><b>인증:</b>
 * Jira와 동일한 Atlassian 계정 — BasicTokenProvider("JIRA_EMAIL", "JIRA_API_TOKEN") 공유.
 *
 * <p><b>주입 환경변수 (SchedulerHandler):</b>
 * <pre>
 *   CONFLUENCE_BASE_URL   — https://xxx.atlassian.net
 *   CONFLUENCE_SPACE_KEY  — 예: IT
 * </pre>
 */
@Slf4j
public class ConfluenceClient extends BaseHttpClient {

    private static final ObjectMapper OM = new ObjectMapper();

    /**
     * https://xxx.atlassian.net/wiki
     */
    private final String wikiBase;
    private final String spaceKey;
    private final TokenProvider token;

    /**
     * @param confluenceBaseUrl https://xxx.atlassian.net (/wiki 포함 여부 무관)
     * @param spaceKey          Confluence Space Key (예: "IT")
     * @param token             BasicTokenProvider — Jira와 동일 계정 공유
     */
    public ConfluenceClient(String confluenceBaseUrl, String spaceKey, TokenProvider token) {
        super("Confluence");
        this.wikiBase = confluenceBaseUrl.endsWith("/wiki")
                ? confluenceBaseUrl
                : confluenceBaseUrl.replaceAll("/+$", "") + "/wiki";
        this.spaceKey = spaceKey;
        this.token = token;
        log.info("[ConfluenceClient] 초기화: wikiBase={}, space={}", wikiBase, spaceKey);
    }

    // =========================================================================
    // 페이지 조회
    // =========================================================================

    /**
     * 부모 페이지의 직접 자식 목록에서 title 로 페이지 ID 조회.
     *
     * <p><b>children API 방식 채택 이유:</b>
     * Confluence Cloud에서 {@code GET /rest/api/content?title=...} 의 ancestors 필드가
     * 빈 배열로 반환되는 경우가 있어 title+spaceKey 검색만으로는 부모 매칭이 불안정하다.
     * children API는 정확히 직접 자식만 반환하므로 부모 관계가 보장된다.
     *
     * <p>parentPageId 가 null 이면 space 전체에서 title 검색(기존 방식) 으로 폴백.
     *
     * @return pageId, 없으면 null
     */
    public String findPageId(String parentPageId, String title) {
        if (parentPageId != null) {
            return findChildPageId(parentPageId, title);
        }
        // parentPageId 미지정 → space 전체 title 검색 (폴백)
        try {
            String encoded = URLEncoder.encode(title, StandardCharsets.UTF_8);
            String url = wikiBase + "/rest/api/content"
                    + "?spaceKey=" + spaceKey
                    + "&title=" + encoded
                    + "&limit=1";
            ApiResponse response = get(url, defaultHeaders());
            requireSuccess(response, "findPage");
            JsonNode results = parse(response.getBody()).path("results");
            if (!results.isArray() || results.isEmpty()) return null;
            return results.get(0).path("id").asText();
        } catch (Exception e) {
            log.warn("[ConfluenceClient] 페이지 조회 실패: title={}, err={}", title, e.getMessage());
            return null;
        }
    }

    /**
     * 부모 페이지의 직접 자식 중 title 일치 페이지 ID 조회.
     *
     * <p><b>탐색 순서 (3단계):</b>
     * <ol>
     *   <li>CQL {@code parent={id} AND title="..." AND space=...}
     *       — 인덱싱 지연으로 누락 가능</li>
     *   <li>{@link #findPageByTitleAndParent} — title 검색 + ancestors 직접 검증
     *       — 인덱싱 지연 없음, 가장 안정적</li>
     *   <li>children API — CQL/title 검색 모두 실패 시 최후 수단</li>
     * </ol>
     *
     * @return pageId, 없으면 null
     */
    public String findChildPageId(String parentPageId, String title) {
        // 1단계: CQL
        try {
            String cql = "parent=" + parentPageId
                    + " AND title=" + Character.toString((char) 34) + title + Character.toString((char) 34)
                    + " AND space=" + spaceKey;
            String encoded = URLEncoder.encode(cql, StandardCharsets.UTF_8);
            String url = wikiBase + "/rest/api/content/search"
                    + "?cql=" + encoded
                    + "&limit=5";
            ApiResponse response = get(url, defaultHeaders());
            requireSuccess(response, "findChildPageCql");
            JsonNode results = parse(response.getBody()).path("results");
            if (results.isArray() && !results.isEmpty()) {
                String pageId = results.get(0).path("id").asText();
                log.info("[ConfluenceClient] findChildPageId(CQL): 발견 title={}, id={}", title, pageId);
                return pageId;
            }
            log.debug("[ConfluenceClient] findChildPageId(CQL): 미발견 parentId={}, title={}", parentPageId, title);
        } catch (Exception e) {
            log.warn("[ConfluenceClient] findChildPageId(CQL) 실패: parentId={}, title={}, err={}",
                    parentPageId, title, e.getMessage());
        }

        // 2단계: title 검색 + ancestors 직접 검증 (인덱싱 지연 없음)
        String ancestorId = findPageByTitleAndParent(parentPageId, title);
        if (ancestorId != null) {
            return ancestorId;
        }

        // 3단계: children API (최후 수단)
        return findChildPageIdFallback(parentPageId, title);
    }

    /**
     * title + spaceKey 검색 후 ancestors 직접 조회로 부모 검증.
     *
     * <p><b>왜 이 방식인가:</b><br>
     * CQL {@code parent=} 조건과 children API 모두 Confluence Cloud 에서
     * 인덱싱 지연으로 방금 생성된 페이지를 누락하는 버그가 있다.<br>
     * 반면 {@code /rest/api/content?title=...&spaceKey=...} 단순 title 검색은
     * 인덱싱 지연 없이 즉시 반영된다.<br>
     * 검색 결과의 각 페이지에 대해 {@code ?expand=ancestors} 로 직계 부모를 확인하여
     * {@code parentPageId} 와 일치하는 것만 후보로 수집한다.
     *
     * <p><b>중복 페이지 처리 (삭제 없이 올바른 것 선택):</b><br>
     * 같은 title + 같은 parent 인 페이지가 여러 개 존재할 경우
     * (이전 코드 버그로 인해 중복 생성된 경우 포함),
     * 아래 우선순위로 올바른 페이지를 선택한다:
     * <ol>
     *   <li>자식 페이지가 있는 것 (= 실제 계층 구조가 구성된, 사용 중인 페이지)</li>
     *   <li>자식 수가 동일하면 id 가 낮은 것 (더 먼저 생성된 것)</li>
     * </ol>
     *
     * @return pageId (parentPageId 의 직계 자식 중 최적), 없으면 null
     */
    public String findPageByTitleAndParent(String parentPageId, String title) {
        try {
            // 1) space 내 title 검색 (인덱싱 지연 없음)
            String encoded = URLEncoder.encode(title, StandardCharsets.UTF_8);
            String searchUrl = wikiBase + "/rest/api/content"
                    + "?spaceKey=" + spaceKey
                    + "&title=" + encoded
                    + "&limit=20";
            ApiResponse searchResp = get(searchUrl, defaultHeaders());
            requireSuccess(searchResp, "findPageByTitleAndParent-search");
            JsonNode results = parse(searchResp.getBody()).path("results");

            if (!results.isArray() || results.isEmpty()) {
                log.debug("[ConfluenceClient] findPageByTitleAndParent: 검색 결과 없음 title={}", title);
                return null;
            }

            // 2) ancestors 검증 — 직계 부모가 parentPageId 인 것만 수집
            List<String> candidates = new java.util.ArrayList<>();
            for (JsonNode page : results) {
                String pageId = page.path("id").asText();
                try {
                    String detailUrl = wikiBase + "/rest/api/content/" + pageId + "?expand=ancestors";
                    ApiResponse detailResp = get(detailUrl, defaultHeaders());
                    requireSuccess(detailResp, "findPageByTitleAndParent-detail");
                    JsonNode ancestors = parse(detailResp.getBody()).path("ancestors");
                    if (!ancestors.isArray() || ancestors.isEmpty()) continue;
                    String directParentId = ancestors.get(ancestors.size() - 1).path("id").asText();
                    if (parentPageId.equals(directParentId)) {
                        candidates.add(pageId);
                        log.debug("[ConfluenceClient] findPageByTitleAndParent: 후보 title={}, id={}", title, pageId);
                    }
                } catch (Exception inner) {
                    log.warn("[ConfluenceClient] findPageByTitleAndParent: 개별 조회 실패 pageId={}, err={}",
                            pageId, inner.getMessage());
                }
            }

            if (candidates.isEmpty()) {
                log.debug("[ConfluenceClient] findPageByTitleAndParent: parentId={} 의 자식 없음 title={}",
                        parentPageId, title);
                return null;
            }

            // 3) 후보가 1개면 바로 반환
            if (candidates.size() == 1) {
                log.info("[ConfluenceClient] findPageByTitleAndParent: 발견 title={}, id={}", title, candidates.get(0));
                return candidates.get(0);
            }

            // 4) 중복 후보가 여러 개: 자식 있는 것 우선, 같으면 id 낮은 것(오래된 것)
            //    children API 는 Confluence Cloud 버그로 빈 배열을 반환하는 경우가 있으므로
            //    CQL(parent={id}) 로 자식 유무를 확인한다. CQL 은 인덱싱된 기존 페이지에 대해 신뢰성이 높다.
            log.warn("[ConfluenceClient] findPageByTitleAndParent: 중복 {} 개 발견 title={} — CQL 자식 확인 후 선택",
                    candidates.size(), title);

            String best = null;
            boolean bestHasChild = false;
            long bestIdNum = Long.MAX_VALUE;

            for (String candidateId : candidates) {
                boolean hasChild = false;
                try {
                    // CQL: parent={id} — 자식이 하나라도 있으면 results 비어있지 않음
                    String cql = "parent=" + candidateId;
                    String cqlEncoded = URLEncoder.encode(cql, StandardCharsets.UTF_8);
                    String cqlUrl = wikiBase + "/rest/api/content/search?cql=" + cqlEncoded + "&limit=1";
                    ApiResponse cqlResp = get(cqlUrl, defaultHeaders());
                    requireSuccess(cqlResp, "findPageByTitleAndParent-cqlChild");
                    JsonNode cqlResults = parse(cqlResp.getBody()).path("results");
                    hasChild = cqlResults.isArray() && !cqlResults.isEmpty();
                    log.debug("[ConfluenceClient] findPageByTitleAndParent: 후보 id={}, hasChild(CQL)={}", candidateId, hasChild);
                } catch (Exception ignored) {
                    // CQL 실패 시 자식 없음으로 처리
                }

                long idNum = Long.MAX_VALUE;
                try {
                    idNum = Long.parseLong(candidateId);
                } catch (NumberFormatException ignored) {
                }

                // 자식 있는 것 우선, 같으면 id 낮은 것(오래된 것)
                boolean betterByChild = hasChild && !bestHasChild;
                boolean sameChildButOlder = (hasChild == bestHasChild) && (idNum < bestIdNum);

                if (best == null || betterByChild || sameChildButOlder) {
                    best = candidateId;
                    bestHasChild = hasChild;
                    bestIdNum = idNum;
                }
            }

            log.info("[ConfluenceClient] findPageByTitleAndParent: 중복 중 선택 title={}, id={}, hasChild={}",
                    title, best, bestHasChild);
            return best;

        } catch (Exception e) {
            log.warn("[ConfluenceClient] findPageByTitleAndParent 실패: title={}, err={}", title, e.getMessage());
            return null;
        }
    }

    /**
     * children API 폴백.
     * CQL 실패 시 또는 WeeklyReportService 에서 인덱싱 지연 대응 재탐색 시 사용.
     */
    public String findChildPageIdFallback(String parentPageId, String title) {
        try {
            String url = wikiBase + "/rest/api/content/" + parentPageId
                    + "/child/page?limit=50&start=0";
            ApiResponse response = get(url, defaultHeaders());
            requireSuccess(response, "findChildPageFallback");
            JsonNode results = parse(response.getBody()).path("results");
            if (!results.isArray()) return null;
            for (JsonNode page : results) {
                if (title.equals(page.path("title").asText())) {
                    String pageId = page.path("id").asText();
                    log.info("[ConfluenceClient] findChildPageIdFallback: 발견 title={}, id={}", title, pageId);
                    return pageId;
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("[ConfluenceClient] findChildPageIdFallback 실패: err={}", e.getMessage());
            return null;
        }
    }

    /**
     * 페이지 현재 버전 번호 조회.
     */
    public int getPageVersion(String pageId) {
        String url = wikiBase + "/rest/api/content/" + pageId + "?expand=version";
        ApiResponse response = get(url, defaultHeaders());
        requireSuccess(response, "getPageVersion");
        return parse(response.getBody()).path("version").path("number").asInt(1);
    }

    // =========================================================================
    // 페이지 생성 (POST)
    // =========================================================================

    /**
     * 새 페이지 생성.
     *
     * @param parentPageId 부모 페이지 ID (null 이면 space 루트)
     * @param title        페이지 제목
     * @param storageHtml  본문 (Confluence Storage Format HTML)
     * @return 생성된 pageId
     */
    public String createPage(String parentPageId, String title, String storageHtml) {
        ObjectNode body = OM.createObjectNode();
        body.put("type", "page");
        body.put("title", title);
        body.putObject("space").put("key", spaceKey);
        body.putObject("body").putObject("storage")
                .put("value", storageHtml)
                .put("representation", "storage");
        if (parentPageId != null) {
            body.putArray("ancestors").addObject().put("id", parentPageId);
        }

        String url = wikiBase + "/rest/api/content";
        ApiResponse response = post(url, defaultHeaders(), toJson(body));
        requireSuccess(response, "createPage");
        String pageId = parse(response.getBody()).path("id").asText();
        log.info("[ConfluenceClient] 페이지 생성: title={}, id={}", title, pageId);
        return pageId;
    }

    // =========================================================================
    // 페이지 업데이트 (PUT — JDK HttpURLConnection)
    // =========================================================================

    /**
     * 기존 페이지 업데이트.
     *
     * <p>{@link BaseHttpClient}는 PUT 을 제공하지 않으므로
     * JDK 내장 {@link HttpURLConnection}으로 직접 구현.
     *
     * @param pageId      업데이트할 페이지 ID
     * @param title       페이지 제목
     * @param storageHtml 새 본문 (Confluence Storage Format HTML)
     * @param newVersion  현재 버전 + 1
     */
    /**
     * 페이지 내용 + 위치(ancestors) 동시 업데이트.
     *
     * @param pageId       업데이트할 페이지 ID
     * @param title        페이지 제목
     * @param storageHtml  새 본문 (Confluence Storage Format)
     * @param newVersion   현재버전 + 1
     * @param parentPageId 이동할 부모 페이지 ID. null 이면 위치 변경 없이 내용만 업데이트.
     */
    public void updatePage(String pageId, String title, String storageHtml, int newVersion, String parentPageId) {
        ObjectNode body = OM.createObjectNode();
        body.put("type", "page");
        body.put("title", title);
        body.putObject("version").put("number", newVersion);
        body.putObject("body").putObject("storage")
                .put("value", storageHtml)
                .put("representation", "storage");
        // ancestors 포함 시 Confluence가 페이지를 해당 parent 아래로 이동
        if (parentPageId != null) {
            body.putArray("ancestors").addObject().put("id", parentPageId);
        }

        String urlStr = wikiBase + "/rest/api/content/" + pageId;
        String jsonBody = toJson(body);

        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PUT");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("Authorization", token.toBasicHeader());
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                String errBody = conn.getErrorStream() != null
                        ? new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)
                        : "(no error body)";
                throw new ExternalApiClientException(apiName,
                        "PUT 실패: status=" + status + ", url=" + urlStr
                                + ", body=" + errBody.substring(0, Math.min(300, errBody.length())));
            }
            log.info("[ConfluenceClient] 페이지 업데이트: id={}, version={}", pageId, newVersion);
        } catch (ExternalApiClientException e) {
            throw e;
        } catch (Exception e) {
            throw new ExternalApiClientException(apiName, "PUT 실패: " + urlStr, e);
        }
    }

    /**
     * 위치 변경 없이 내용만 업데이트 (하위 호환 오버로드).
     */
    public void updatePage(String pageId, String title, String storageHtml, int newVersion) {
        updatePage(pageId, title, storageHtml, newVersion, null);
    }

    // =========================================================================
    // 편의 메서드
    // =========================================================================

    /**
     * title 검색 API 로 페이지를 찾되, parentPageId 의 직접 자식인 것만 반환.
     *
     * <p>children API({@link #findChildPageId}) 가 Confluence 인덱싱 지연으로
     * 신규 생성 페이지를 즉시 반환하지 못할 때 사용하는 보조 탐색.
     * title 검색 결과 각 페이지에 대해 개별 조회로 직접 부모(ancestors 마지막)를 확인한다.
     *
     * @return pageId (parentPageId 직계 자식), 없으면 null
     */
    public String findPageByTitleUnderParent(String parentPageId, String title) {
        return findChildPageId(parentPageId, title);
    }

    /**
     * 페이지 생성 또는 업데이트.
     * 주간보고 최종 페이지(weeklyTitle)에서 사용.
     * findChildPageId (3단계 탐색) 로 기존 페이지 검색.
     */
    public String createOrUpdate(String parentPageId, String title, String storageHtml) {
        String existing = findChildPageId(parentPageId, title);
        if (existing != null) {
            int version = getPageVersion(existing);
            updatePage(existing, title, storageHtml, version + 1, parentPageId);
            return existing;
        }
        return createPage(parentPageId, title, storageHtml);
    }

    // =========================================================================
    // 파일 첨부
    // =========================================================================

    /**
     * 페이지에 파일 첨부 (Confluence REST API v1 — multipart/form-data).
     *
     * <p>같은 이름의 첨부파일이 이미 있으면 update API(/{attachmentId}/data)로 덮어쓰고,
     * 없으면 create API로 신규 첨부한다.
     * (Confluence Cloud는 allowDuplicated=true 를 무시하므로 이 방식이 필수)
     *
     * @param pageId   첨부할 대상 페이지 ID
     * @param fileName 첨부 파일명 (예: "2026 3월 W10 보상코어팀 실적.xlsx")
     * @param content  파일 바이트 배열
     */
    public void attachFile(String pageId, String fileName, byte[] content) {
        try {
            // 1. 기존 첨부파일 ID 조회
            String existingAttachmentId = findAttachmentId(pageId, fileName);

            // 2. 있으면 update, 없으면 create
            String urlStr;
            if (existingAttachmentId != null) {
                urlStr = wikiBase + "/rest/api/content/" + pageId
                        + "/child/attachment/" + existingAttachmentId + "/data";
                log.info("[ConfluenceClient] 기존 첨부파일 덮어쓰기: pageId={}, attachmentId={}, file={}",
                        pageId, existingAttachmentId, fileName);
            } else {
                urlStr = wikiBase + "/rest/api/content/" + pageId + "/child/attachment";
                log.info("[ConfluenceClient] 신규 첨부파일 생성: pageId={}, file={}", pageId, fileName);
            }

            doMultipartUpload(urlStr, pageId, fileName, content);

        } catch (ExternalApiClientException e) {
            throw e;
        } catch (Exception e) {
            throw new ExternalApiClientException(apiName,
                    "첨부 실패: pageId=" + pageId + ", file=" + fileName, e);
        }
    }

    /**
     * 페이지의 첨부파일 목록에서 fileName 과 일치하는 첨부파일 ID 반환.
     * 없으면 null.
     *
     * <p>filename 쿼리 파라미터를 사용하지 않는다.
     * URLEncoder.encode() 가 공백을 '+' 로 인코딩하여 Confluence가 파일을 찾지 못하는 버그가 있음.
     * 대신 전체 목록(최대 50건)을 받아 Java 에서 직접 비교한다.
     */
    private String findAttachmentId(String pageId, String fileName) {
        // filename 파라미터 없이 전체 목록 조회 — Java 에서 직접 비교
        String urlStr = wikiBase + "/rest/api/content/" + pageId
                + "/child/attachment?limit=50&expand=title";
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("Authorization", token.toBasicHeader());
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-Atlassian-Token", "no-check");

            int status = conn.getResponseCode();
            if (status != 200) {
                log.warn("[ConfluenceClient] 첨부파일 목록 조회 실패: status={}, pageId={}", status, pageId);
                return null;
            }
            String body = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode root = parse(body);
            JsonNode results = root.path("results");
            if (results.isArray()) {
                for (JsonNode att : results) {
                    String title = att.path("title").asText("");
                    if (fileName.equals(title)) {
                        String id = att.path("id").asText(null);
                        log.info("[ConfluenceClient] 기존 첨부파일 발견: id={}, file={}", id, fileName);
                        return id;
                    }
                }
            }
            log.info("[ConfluenceClient] 기존 첨부파일 없음 — 신규 생성: file={}", fileName);
        } catch (Exception e) {
            log.warn("[ConfluenceClient] 첨부파일 조회 실패 — 신규 생성 시도: {}", e.getMessage());
        }
        return null;
    }

    /**
     * multipart/form-data 업로드 공통 처리.
     */
    private void doMultipartUpload(String urlStr, String pageId, String fileName, byte[] content) {
        String boundary = "----FormBoundary" + System.currentTimeMillis();
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("Authorization", token.toBasicHeader());
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-Atlassian-Token", "no-check");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            try (OutputStream os = conn.getOutputStream()) {
                String fileHeader = "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\""
                        + fileName.replace("\"", "_") + "\"\r\n"
                        + "Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n"
                        + "\r\n";
                os.write(fileHeader.getBytes(StandardCharsets.UTF_8));
                os.write(content);
                os.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int status = conn.getResponseCode();
            String respBody = "";
            try {
                java.io.InputStream is = (status < 300) ? conn.getInputStream() : conn.getErrorStream();
                if (is != null) respBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }

            log.info("[ConfluenceClient] 첨부 응답: status={}, pageId={}, file={}, body(200자)={}",
                    status, pageId, fileName,
                    respBody.substring(0, Math.min(200, respBody.length())));

            if (status < 200 || status >= 300) {
                throw new ExternalApiClientException(apiName,
                        "첨부 실패: status=" + status + ", pageId=" + pageId
                                + ", file=" + fileName
                                + ", body=" + respBody.substring(0, Math.min(300, respBody.length())));
            }

            if (!respBody.contains("\"id\"")) {
                log.warn("[ConfluenceClient] 첨부 응답에 id 없음 — 실제 저장 여부 불확실: body={}",
                        respBody.substring(0, Math.min(300, respBody.length())));
            } else {
                log.info("[ConfluenceClient] 파일 첨부 완료: pageId={}, file={}, size={}bytes",
                        pageId, fileName, content.length);
            }

        } catch (ExternalApiClientException e) {
            throw e;
        } catch (Exception e) {
            throw new ExternalApiClientException(apiName,
                    "첨부 업로드 실패: pageId=" + pageId + ", file=" + fileName, e);
        }
    }

    // =========================================================================
    // Getter (상위 계층에서 URL 조합용)
    // =========================================================================

    public String getWikiBase() {
        return wikiBase;
    }

    public String getSpaceKey() {
        return spaceKey;
    }

    // =========================================================================
    // 내부 유틸
    // =========================================================================

    private Map<String, String> defaultHeaders() {
        return Map.of(
                "Authorization", token.toBasicHeader(),
                "Accept", "application/json",
                "Content-Type", "application/json"
        );
    }

    private JsonNode parse(String body) {
        try {
            return OM.readTree(body);
        } catch (Exception e) {
            throw new ExternalApiClientException(apiName,
                    "JSON 파싱 실패: " + body.substring(0, Math.min(200, body.length())), e);
        }
    }

    private String toJson(Object obj) {
        try {
            return OM.writeValueAsString(obj);
        } catch (Exception e) {
            throw new ExternalApiClientException(apiName, "JSON 직렬화 실패", e);
        }
    }
}
