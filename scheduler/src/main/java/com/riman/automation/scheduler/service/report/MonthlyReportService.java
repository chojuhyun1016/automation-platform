package com.riman.automation.scheduler.service.report;

import com.riman.automation.clients.confluence.ConfluenceClient;
import com.riman.automation.common.exception.ExternalApiException;
import com.riman.automation.scheduler.dto.report.MonthlyReportData;
import com.riman.automation.scheduler.service.excel.MonthlyExcelGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 월간 실적 보고 서비스이다. Confluence 페이지 계층을 {year}년 월간 → Q{q} → 월간 페이지 순으로 구성한다.
 * 연도 및 분기 title에 "월간"을 포함하여 space 전역 unique를 보장하므로 주간 페이지와 충돌하지 않는다.
 */
@Slf4j
@RequiredArgsConstructor
public class MonthlyReportService {

  private static final String VERSION = "v1";

  private final ConfluenceClient confluenceClient;
  private final MonthlyExcelGenerator excelGenerator;

  public String publishMonthlyPage(
      MonthlyReportData data,
      String pageHtml,
      String rootParentPageId,
      String teamName) {

    String team = resolveTeamName(teamName);
    log.info("[MonthlyReportService] {} 시작: year={}, month={}월, quarter=Q{}, team={}",
        VERSION, data.getYear(), data.getMonth(), data.getQuarter(), team);

    String yearTitle = data.yearDirTitle();
    String yearPageId = ensurePage(rootParentPageId, yearTitle,
        "<p>" + data.getYear() + "년 " + team + " 월간 실적 보고 목록입니다.</p>");
    log.info("[MonthlyReportService] 연도 확보: title={}, id={}", yearTitle, yearPageId);

    String quarterTitle = data.quarterDirTitle();
    String quarterPageId = ensurePage(yearPageId, quarterTitle,
        "<p>" + data.getYear() + " Q" + data.getQuarter() + " " + team
            + " 월간 실적 보고 목록입니다.</p>");
    log.info("[MonthlyReportService] 분기 확보: title={}, id={}", quarterTitle, quarterPageId);

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

  /**
   * 엑셀 파일을 생성하여 Confluence 페이지에 첨부한다. 파일명은 페이지 제목에 .xlsx 확장자를 붙인다.
   * 예: "2026 Q1 1월 - 보상코어 개발팀 실적.xlsx"
   *
   * @param pageId    첨부 대상 페이지 ID
   * @param pageTitle 페이지 제목(파일명 기준)
   * @param data      월간보고 데이터
   */
  public void attachExcel(String pageId, String pageTitle, MonthlyReportData data) {
    log.info("[MonthlyReportService] 엑셀 생성 시작: pageId={}, title={}", pageId, pageTitle);
    byte[] excelBytes = excelGenerator.generate(data);
    String fileName = pageTitle + ".xlsx";
    confluenceClient.attachFile(pageId, fileName, excelBytes);
    log.info("[MonthlyReportService] 엑셀 첨부 완료: pageId={}, file={}", pageId, fileName);
  }

  /**
   * 지정된 parent 하위에 페이지를 생성하거나 기존 페이지를 덮어쓴다.
   * 직계 자식 탐색 후 덮어쓰기를 시도하고, 없으면 생성하며,
   * 생성이 400으로 실패하면 동일 space의 다른 위치에서 찾아 현재 parent로 이동하며 덮어쓴다.
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

  /**
   * 부모 하위에 페이지를 확보한다. 이미 존재하면 재사용하고 없으면 생성한다.
   * 생성이 400(인덱싱 지연)으로 실패하면 ancestors 직접 검증으로 재탐색한다.
   * title에 "월간"이 포함되어 space 전역 unique가 보장된다.
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

  /**
   * 월간보고 페이지 제목을 생성한다. 예: "2026 Q1 1월 - 보상코어 개발팀 실적".
   *
   * @param data     월간보고 데이터(year, quarter, month 포함)
   * @param teamName 팀명(미설정 시 resolveTeamName에서 기본값 사용)
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
