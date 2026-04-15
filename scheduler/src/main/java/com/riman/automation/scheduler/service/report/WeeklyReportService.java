package com.riman.automation.scheduler.service.report;

import com.riman.automation.clients.confluence.ConfluenceClient;
import com.riman.automation.common.exception.ExternalApiException;
import com.riman.automation.scheduler.dto.report.WeeklyReportData;
import com.riman.automation.scheduler.service.excel.WeeklyExcelGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 주간 실적 보고 서비스이다. Confluence 페이지 계층을 {year}년 주간 → Q{q} → {m}월 → 주간 페이지 순으로 구성한다.
 * 모든 계층 title에 "주간"을 포함하여 space 전역 unique를 보장하므로 타 트리와 충돌하지 않는다.
 */
@Slf4j
@RequiredArgsConstructor
public class WeeklyReportService {

  /** 배포 버전 확인용 식별자이다. 로그에 이 값이 보이면 신버전 배포를 확인할 수 있다. */
  private static final String VERSION = "v3";

  private final ConfluenceClient confluenceClient;
  private final WeeklyExcelGenerator excelGenerator;

  public String publishWeeklyPage(
      WeeklyReportData data,
      String pageHtml,
      String rootParentPageId,
      String teamName) {

    String team = resolveTeamName(teamName);
    log.info("[WeeklyReportService] {} 시작: year={}, week=W{}, team={}",
        VERSION, data.getYear(), data.getWeekNumber(), team);

    String yearTitle = data.getYear() + "년 주간";
    String yearPageId = ensurePage(rootParentPageId, yearTitle,
        "<p>" + data.getYear() + "년 " + team + " 주간 실적 보고 목록입니다.</p>");
    log.info("[WeeklyReportService] 연도 확보: title={}, id={}", yearTitle, yearPageId);

    String quarterTitle = data.getYear() + "년 주간 Q" + data.getQuarter();
    String quarterPageId = ensurePage(yearPageId, quarterTitle,
        "<p>" + data.getYear() + " Q" + data.getQuarter() + " " + team
            + " 주간 실적 보고 목록입니다.</p>");
    log.info("[WeeklyReportService] 분기 확보: title={}, id={}", quarterTitle, quarterPageId);

    int month = data.getWeekStart().getMonthValue();
    String monthTitle = data.getYear() + "년 주간 " + month + "월";
    String monthPageId = ensurePage(quarterPageId, monthTitle,
        "<p>" + data.getYear() + "년 " + month + "월 " + team
            + " 주간 실적 보고 목록입니다.</p>");
    log.info("[WeeklyReportService] 월 확보: title={}, id={}", monthTitle, monthPageId);

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

  /**
   * 엑셀 파일을 생성하여 Confluence 페이지에 첨부한다. 파일명은 페이지 제목에 .xlsx 확장자를 붙인다.
   *
   * @param pageId    첨부 대상 페이지 ID
   * @param pageTitle 페이지 제목(파일명 기준)
   * @param data      주간보고 데이터
   */
  public void attachExcel(String pageId, String pageTitle, WeeklyReportData data) {
    log.info("[WeeklyReportService] 엑셀 생성 시작: pageId={}, title={}", pageId, pageTitle);
    byte[] excelBytes = excelGenerator.generate(data);
    String fileName = pageTitle + ".xlsx";
    confluenceClient.attachFile(pageId, fileName, excelBytes);
    log.info("[WeeklyReportService] 엑셀 첨부 완료: pageId={}, file={}", pageId, fileName);
  }

  /**
   * 지정된 parent 하위에 페이지를 생성하거나 기존 페이지를 덮어쓴다.
   * 먼저 직계 자식에서 탐색 후 덮어쓰기를 시도하고, 없으면 생성하며,
   * 생성이 400으로 실패하면 동일 space의 다른 위치에서 찾아 현재 parent로 이동하며 덮어쓴다.
   */
  private String upsertPage(String parentPageId, String title, String html) {
    String found = confluenceClient.findChildPageId(parentPageId, title);
    if (found != null) {
      int ver = confluenceClient.getPageVersion(found);
      confluenceClient.updatePage(found, title, html, ver + 1, parentPageId);
      log.info("[WeeklyReportService] upsert — 덮어쓰기(직계): title={}, id={}", title, found);
      return found;
    }

    try {
      String newId = confluenceClient.createPage(parentPageId, title, html);
      log.info("[WeeklyReportService] upsert — 신규 생성: title={}, id={}", title, newId);
      return newId;
    } catch (ExternalApiException e) {
      if (e.getStatusCode() != 400) throw e;

      log.warn("[WeeklyReportService] upsert 400 — space 전체 검색 후 위치 이동: title={}, targetParent={}",
          title, parentPageId);
      String anyId = confluenceClient.findPageId(null, title);
      if (anyId != null) {
        int ver = confluenceClient.getPageVersion(anyId);
        confluenceClient.updatePage(anyId, title, html, ver + 1, parentPageId);
        log.info("[WeeklyReportService] upsert — 위치이동+덮어쓰기: title={}, id={}, newParent={}",
            title, anyId, parentPageId);
        return anyId;
      }

      log.error("[WeeklyReportService] upsert 완전 실패: parentId={}, title={}", parentPageId, title);
      throw e;
    }
  }

  /**
   * 부모 하위에 페이지를 확보한다. 이미 존재하면 재사용하고 없으면 생성한다.
   * 생성이 400(인덱싱 지연)으로 실패하면 ancestors 직접 검증으로 재탐색한다.
   * title에 "주간"이 포함되어 space 전역 unique가 보장되므로 타 트리와 충돌하지 않는다.
   */
  private String ensurePage(String parentPageId, String title, String placeholder) {
    String found = confluenceClient.findChildPageId(parentPageId, title);
    if (found != null) {
      log.debug("[WeeklyReportService] ensurePage 재사용: title={}, id={}", title, found);
      return found;
    }

    try {
      String newId = confluenceClient.createPage(parentPageId, title, placeholder);
      log.info("[WeeklyReportService] ensurePage 생성: title={}, id={}", title, newId);
      return newId;
    } catch (ExternalApiException e) {
      if (e.getStatusCode() != 400) throw e;

      log.warn("[WeeklyReportService] ensurePage 400(인덱싱 지연) — ancestors 재탐색: parentId={}, title={}",
          parentPageId, title);
      String retried = confluenceClient.findPageByTitleAndParent(parentPageId, title);
      if (retried != null) {
        log.info("[WeeklyReportService] ensurePage ancestors 재탐색 성공: title={}, id={}", title, retried);
        return retried;
      }

      log.error("[WeeklyReportService] ensurePage 완전 실패: parentId={}, title={}", parentPageId, title);
      throw e;
    }
  }

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
