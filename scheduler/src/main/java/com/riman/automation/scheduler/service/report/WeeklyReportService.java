package com.riman.automation.scheduler.service.report;

import com.riman.automation.clients.confluence.ConfluenceClient;
import com.riman.automation.common.exception.ExternalApiException;
import com.riman.automation.scheduler.dto.report.WeeklyReportData;
import com.riman.automation.scheduler.service.excel.WeeklyExcelGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 주간(실적) 보고 서비스 v3
 *
 * <p><b>확정 페이지 계층:</b>
 * <pre>
 *   실적 (rootParentPageId)
 *     └─ {year}년 주간
 *         └─ {year}년 주간 Q{q}
 *             └─ {year}년 주간 {m}월
 *                 └─ {year} {m}월 W{w} {team} 실적   ← 있으면 덮어쓰기
 * </pre>
 *
 * <p>연도/분기/월 title에 "주간" 포함 → space 전역 unique 보장,
 * 충돌 회피 재시도 로직 불필요.
 */
@Slf4j
@RequiredArgsConstructor
public class WeeklyReportService {

    // 배포 버전 확인용 — 로그에서 이 메시지가 보이면 신버전 배포 확인
    private static final String VERSION = "v3";

    private final ConfluenceClient confluenceClient;
    private final WeeklyExcelGenerator excelGenerator;

    // =========================================================================
    // 주간보고 페이지 게시
    // =========================================================================

    public String publishWeeklyPage(
            WeeklyReportData data,
            String pageHtml,
            String rootParentPageId,
            String teamName) {

        String team = resolveTeamName(teamName);
        log.info("[WeeklyReportService] {} 시작: year={}, week=W{}, team={}",
                VERSION, data.getYear(), data.getWeekNumber(), team);

        // 1) 연도 페이지
        String yearTitle = data.getYear() + "년 주간";
        String yearPageId = ensurePage(rootParentPageId, yearTitle,
                "<p>" + data.getYear() + "년 " + team + " 주간 실적 보고 목록입니다.</p>");
        log.info("[WeeklyReportService] 연도 확보: title={}, id={}", yearTitle, yearPageId);

        // 2) 분기 페이지 — "주간" 포함으로 space 전역 unique
        String quarterTitle = data.getYear() + "년 주간 Q" + data.getQuarter();
        String quarterPageId = ensurePage(yearPageId, quarterTitle,
                "<p>" + data.getYear() + " Q" + data.getQuarter() + " " + team
                        + " 주간 실적 보고 목록입니다.</p>");
        log.info("[WeeklyReportService] 분기 확보: title={}, id={}", quarterTitle, quarterPageId);

        // 3) 월 페이지 — "주간" 포함으로 space 전역 unique
        int month = data.getWeekStart().getMonthValue();
        String monthTitle = data.getYear() + "년 주간 " + month + "월";
        String monthPageId = ensurePage(quarterPageId, monthTitle,
                "<p>" + data.getYear() + "년 " + month + "월 " + team
                        + " 주간 실적 보고 목록입니다.</p>");
        log.info("[WeeklyReportService] 월 확보: title={}, id={}", monthTitle, monthPageId);

        // 4) 주간보고 페이지 — 있으면 덮어쓰기, 없으면 생성
        String weeklyTitle = buildWeeklyTitle(data, team);
        String weeklyPageId = upsertPage(monthPageId, weeklyTitle, pageHtml);
        log.info("[WeeklyReportService] 주간보고 완료: title={}, id={}", weeklyTitle, weeklyPageId);

        return weeklyPageId;
    }

    public String buildPageUrl(String pageId) {
        return confluenceClient.getWikiBase()
                + "/spaces/" + confluenceClient.getSpaceKey()
                + "/pages/" + pageId;
    }

    // =========================================================================
    // 엑셀 생성 + 첨부
    // =========================================================================

    /**
     * 엑셀 생성 후 Confluence 페이지에 첨부.
     *
     * <p>파일명 = 페이지 제목 + ".xlsx"
     *
     * @param pageId    첨부 대상 페이지 ID
     * @param pageTitle 페이지 제목 (파일명 기준)
     * @param data      주간보고 데이터
     */
    public void attachExcel(String pageId, String pageTitle, WeeklyReportData data) {
        log.info("[WeeklyReportService] 엑셀 생성 시작: pageId={}, title={}", pageId, pageTitle);
        byte[] excelBytes = excelGenerator.generate(data);
        String fileName = pageTitle + ".xlsx";
        confluenceClient.attachFile(pageId, fileName, excelBytes);
        log.info("[WeeklyReportService] 엑셀 첨부 완료: pageId={}, file={}", pageId, fileName);
    }

    // =========================================================================
    // upsertPage — 생성 또는 덮어쓰기 (주간보고 최종 페이지용)
    // =========================================================================

    /**
     * 주어진 parentPageId 하위에 페이지 생성 또는 기존 페이지 내용 덮어쓰기.
     *
     * <pre>
     * ① parentPageId 직계 자식에서 탐색 → 있으면 updatePage (덮어쓰기)
     * ② 없으면 createPage 시도
     * ③ createPage 400 (다른 위치에 동명 페이지 존재) → findPageId(null, title) space 전체 검색 → updatePage
     * ④ space 전체에도 없으면 예외 전파
     * </pre>
     */
    private String upsertPage(String parentPageId, String title, String html) {
        // ① 직계 자식 탐색
        String found = confluenceClient.findChildPageId(parentPageId, title);
        if (found != null) {
            int ver = confluenceClient.getPageVersion(found);
            // parentPageId 전달 → 이미 올바른 위치이므로 위치 이동 없이 내용만 업데이트
            confluenceClient.updatePage(found, title, html, ver + 1, parentPageId);
            log.info("[WeeklyReportService] upsert — 덮어쓰기(직계): title={}, id={}", title, found);
            return found;
        }

        // ② createPage
        try {
            String newId = confluenceClient.createPage(parentPageId, title, html);
            log.info("[WeeklyReportService] upsert — 신규 생성: title={}, id={}", title, newId);
            return newId;
        } catch (ExternalApiException e) {
            if (e.getStatusCode() != 400) throw e;

            // ③ 400 = 다른 위치에 이미 존재 → space 전체 검색 후 올바른 위치로 이동하며 덮어쓰기
            log.warn("[WeeklyReportService] upsert 400 — space 전체 검색 후 위치 이동: title={}, targetParent={}",
                    title, parentPageId);
            String anyId = confluenceClient.findPageId(null, title);
            if (anyId != null) {
                int ver = confluenceClient.getPageVersion(anyId);
                // parentPageId 전달 → 페이지를 올바른 위치(monthPageId 아래)로 이동
                confluenceClient.updatePage(anyId, title, html, ver + 1, parentPageId);
                log.info("[WeeklyReportService] upsert — 위치이동+덮어쓰기: title={}, id={}, newParent={}",
                        title, anyId, parentPageId);
                return anyId;
            }

            // ④ 찾을 수 없음
            log.error("[WeeklyReportService] upsert 완전 실패: parentId={}, title={}", parentPageId, title);
            throw e;
        }
    }

    // =========================================================================
    // ensurePage — 연도/분기/월 계층 확보 (없으면 생성, 있으면 재사용)
    // =========================================================================

    /**
     * 부모 하위에 페이지 확보.
     *
     * <pre>
     * ① findChildPageId → 있으면 반환
     * ② createPage → 성공하면 반환
     * ③ createPage 400 (인덱싱 지연) → findPageByTitleAndParent (ancestors 직접 검증) → 반환
     * ④ 모두 실패 → 예외 전파
     * </pre>
     * <p>
     * title에 "주간" 포함으로 space 전역 unique 보장 → 타 트리와 충돌 없음.
     */
    private String ensurePage(String parentPageId, String title, String placeholder) {
        // ① 직계 자식 탐색 (CQL + ancestors 검증 + children 3단계)
        String found = confluenceClient.findChildPageId(parentPageId, title);
        if (found != null) {
            log.debug("[WeeklyReportService] ensurePage 재사용: title={}, id={}", title, found);
            return found;
        }

        // ② 생성
        try {
            String newId = confluenceClient.createPage(parentPageId, title, placeholder);
            log.info("[WeeklyReportService] ensurePage 생성: title={}, id={}", title, newId);
            return newId;
        } catch (ExternalApiException e) {
            if (e.getStatusCode() != 400) throw e;

            // ③ 400 = 인덱싱 지연 (title에 "주간" 포함이므로 타 트리와 충돌 없음)
            //    ancestors 직접 검증으로 재탐색
            log.warn("[WeeklyReportService] ensurePage 400(인덱싱 지연) — ancestors 재탐색: parentId={}, title={}",
                    parentPageId, title);
            String retried = confluenceClient.findPageByTitleAndParent(parentPageId, title);
            if (retried != null) {
                log.info("[WeeklyReportService] ensurePage ancestors 재탐색 성공: title={}, id={}", title, retried);
                return retried;
            }

            // ④ 실패
            log.error("[WeeklyReportService] ensurePage 완전 실패: parentId={}, title={}", parentPageId, title);
            throw e;
        }
    }

    // =========================================================================
    // 제목 빌더
    // =========================================================================

    public String buildWeeklyTitle(WeeklyReportData data, String teamName) {
        return data.getYear() + " "
                + data.getWeekStart().getMonthValue() + "월 "
                + "W" + data.getWeekNumber() + " - "
                + teamName + " 실적";
    }

    private String resolveTeamName(String teamName) {
        return (teamName != null && !teamName.isBlank()) ? teamName : "보상코어 개발팀";
    }
}
