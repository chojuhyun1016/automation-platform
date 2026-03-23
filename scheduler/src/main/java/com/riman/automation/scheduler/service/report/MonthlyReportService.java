package com.riman.automation.scheduler.service.report;

import com.riman.automation.clients.confluence.ConfluenceClient;
import com.riman.automation.common.exception.ExternalApiException;
import com.riman.automation.scheduler.dto.report.MonthlyReportData;
import com.riman.automation.scheduler.service.excel.MonthlyExcelGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 월간(실적) 보고 서비스
 *
 * <p><b>WeeklyReportService 와의 대응:</b>
 * <pre>
 *   WeeklyReportService — 주간 실적 (분기-주 계층)
 *   MonthlyReportService — 월간 실적 (분기-월 계층)  (이 클래스)
 * </pre>
 *
 * <p><b>확정 페이지 계층:</b>
 * <pre>
 *   실적보고 (rootParentPageId)
 *     └─ {year}년 월간
 *         └─ {year}년 월간 Q{q}
 *             └─ {year} Q{q} {m}월 - {team} 실적   ← 있으면 덮어쓰기
 * </pre>
 *
 * <p>연도/분기 title에 "월간" 포함 → space 전역 unique 보장,
 * 주간("주간") 페이지와 충돌 없음.
 */
@Slf4j
@RequiredArgsConstructor
public class MonthlyReportService {

    private static final String VERSION = "v1";

    private final ConfluenceClient confluenceClient;
    private final MonthlyExcelGenerator excelGenerator;

    // =========================================================================
    // 월간보고 페이지 게시
    // =========================================================================

    public String publishMonthlyPage(
            MonthlyReportData data,
            String pageHtml,
            String rootParentPageId,
            String teamName) {

        String team = resolveTeamName(teamName);
        log.info("[MonthlyReportService] {} 시작: year={}, month={}월, quarter=Q{}, team={}",
                VERSION, data.getYear(), data.getMonth(), data.getQuarter(), team);

        // 1) 연도 페이지 — "2026년 월간"
        String yearTitle = data.yearDirTitle();
        String yearPageId = ensurePage(rootParentPageId, yearTitle,
                "<p>" + data.getYear() + "년 " + team + " 월간 실적 보고 목록입니다.</p>");
        log.info("[MonthlyReportService] 연도 확보: title={}, id={}", yearTitle, yearPageId);

        // 2) 분기 페이지 — "2026년 월간 Q1"
        String quarterTitle = data.quarterDirTitle();
        String quarterPageId = ensurePage(yearPageId, quarterTitle,
                "<p>" + data.getYear() + " Q" + data.getQuarter() + " " + team
                        + " 월간 실적 보고 목록입니다.</p>");
        log.info("[MonthlyReportService] 분기 확보: title={}, id={}", quarterTitle, quarterPageId);

        // 3) 월간보고 페이지 — "2026 Q1 1월 - 보상코어 개발팀 실적"
        String monthlyTitle = buildMonthlyTitle(data, team);
        String monthlyPageId = upsertPage(quarterPageId, monthlyTitle, pageHtml);
        log.info("[MonthlyReportService] 월간보고 완료: title={}, id={}", monthlyTitle, monthlyPageId);

        return monthlyPageId;
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
     * <br>예: "2026 Q1 1월 - 보상코어 개발팀 실적.xlsx"
     *
     * @param pageId    첨부 대상 페이지 ID
     * @param pageTitle 페이지 제목 (파일명 기준)
     * @param data      월간보고 데이터
     */
    public void attachExcel(String pageId, String pageTitle, MonthlyReportData data) {
        log.info("[MonthlyReportService] 엑셀 생성 시작: pageId={}, title={}", pageId, pageTitle);
        byte[] excelBytes = excelGenerator.generate(data);
        String fileName = pageTitle + ".xlsx";
        confluenceClient.attachFile(pageId, fileName, excelBytes);
        log.info("[MonthlyReportService] 엑셀 첨부 완료: pageId={}, file={}", pageId, fileName);
    }

    // =========================================================================
    // upsertPage — 생성 또는 덮어쓰기 (월간보고 최종 페이지용)
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
        String found = confluenceClient.findChildPageId(parentPageId, title);
        if (found != null) {
            int ver = confluenceClient.getPageVersion(found);
            confluenceClient.updatePage(found, title, html, ver + 1, parentPageId);
            log.info("[MonthlyReportService] upsert — 덮어쓰기(직계): title={}, id={}", title, found);
            return found;
        }

        try {
            String newId = confluenceClient.createPage(parentPageId, title, html);
            log.info("[MonthlyReportService] upsert — 신규 생성: title={}, id={}", title, newId);
            return newId;
        } catch (ExternalApiException e) {
            if (e.getStatusCode() != 400) throw e;

            log.warn("[MonthlyReportService] upsert 400 — space 전체 검색 후 위치 이동: title={}, targetParent={}",
                    title, parentPageId);
            String anyId = confluenceClient.findPageId(null, title);
            if (anyId != null) {
                int ver = confluenceClient.getPageVersion(anyId);
                confluenceClient.updatePage(anyId, title, html, ver + 1, parentPageId);
                log.info("[MonthlyReportService] upsert — 위치이동+덮어쓰기: title={}, id={}, newParent={}",
                        title, anyId, parentPageId);
                return anyId;
            }

            log.error("[MonthlyReportService] upsert 완전 실패: parentId={}, title={}", parentPageId, title);
            throw e;
        }
    }

    // =========================================================================
    // ensurePage — 연도/분기 계층 확보 (없으면 생성, 있으면 재사용)
    // =========================================================================

    /**
     * 부모 하위에 페이지 확보.
     *
     * <pre>
     * ① findChildPageId → 있으면 반환
     * ② createPage → 성공하면 반환
     * ③ createPage 400 (인덱싱 지연) → findPageByTitleAndParent → 반환
     * ④ 모두 실패 → 예외 전파
     * </pre>
     *
     * <p>title에 "월간" 포함으로 space 전역 unique 보장.
     */
    private String ensurePage(String parentPageId, String title, String placeholder) {
        String found = confluenceClient.findChildPageId(parentPageId, title);
        if (found != null) {
            log.debug("[MonthlyReportService] ensurePage 재사용: title={}, id={}", title, found);
            return found;
        }

        try {
            String newId = confluenceClient.createPage(parentPageId, title, placeholder);
            log.info("[MonthlyReportService] ensurePage 생성: title={}, id={}", title, newId);
            return newId;
        } catch (ExternalApiException e) {
            if (e.getStatusCode() != 400) throw e;

            log.warn("[MonthlyReportService] ensurePage 400(인덱싱 지연) — ancestors 재탐색: parentId={}, title={}",
                    parentPageId, title);
            String retried = confluenceClient.findPageByTitleAndParent(parentPageId, title);
            if (retried != null) {
                log.info("[MonthlyReportService] ensurePage ancestors 재탐색 성공: title={}, id={}", title, retried);
                return retried;
            }

            log.error("[MonthlyReportService] ensurePage 완전 실패: parentId={}, title={}", parentPageId, title);
            throw e;
        }
    }

    // =========================================================================
    // 제목 빌더
    // =========================================================================

    /**
     * 월간보고 페이지 제목 생성.
     *
     * <p>예: "2026 Q1 1월 - 보상코어 개발팀 실적"
     *
     * @param data     월간보고 데이터 (year, quarter, month 포함)
     * @param teamName 팀명 (미설정 시 resolveTeamName 에서 기본값 사용)
     * @return Confluence 페이지 제목
     */
    public String buildMonthlyTitle(MonthlyReportData data, String teamName) {
        return data.getYear() + " Q" + data.getQuarter()
                + " " + data.getMonth() + "월 - "
                + teamName + " 실적";
    }

    private String resolveTeamName(String teamName) {
        return (teamName != null && !teamName.isBlank()) ? teamName : "보상코어 개발팀";
    }
}
