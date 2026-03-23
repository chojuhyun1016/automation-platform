package com.riman.automation.scheduler.service.format;

import com.riman.automation.scheduler.dto.report.MonthlyReportData;
import com.riman.automation.scheduler.dto.report.MonthlyReportData.MonthlyTicketItem;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 월간보고 Confluence Storage Format HTML 포맷터
 *
 * <p><b>WeeklyReportFormatter 와의 대응:</b>
 * <pre>
 *   WeeklyReportFormatter  — 주간 실적 (전주 완료 + 분기 진행중)
 *   MonthlyReportFormatter — 월간 실적 (대상 월 완료 + 분기 진행중)  (이 클래스)
 * </pre>
 *
 * <p><b>구조:</b>
 * <pre>
 *   [헤더 패널] 기간 / 분기 / 전체 건수
 *
 *   ▼ [주문]  완료 0 / 진행 2      ← expand 매크로 (접기/펼치기 가능)
 *     ✅ 완료 (0건)
 *       | 제목 480px | 담당자 70px | 완료일 95px | 이슈키 115px |
 *     🔵 진행 (2건)
 *       | 제목 480px | 담당자 70px | 기한   95px | 이슈키 115px |
 *
 *   ▼ [회원]  완료 1 / 진행 4
 * </pre>
 *
 * <p>WeeklyReportFormatter 와 동일한 렌더링 규칙을 적용한다.
 * 줄바꿈 방지: 날짜/이슈키 셀을 {@code <span style="display:inline-block">}으로 감쌈.
 */
public class MonthlyReportFormatter {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MM/dd(E)", Locale.KOREAN);

    // =========================================================================
    // 공개 API
    // =========================================================================

    public String format(MonthlyReportData data) {
        StringBuilder sb = new StringBuilder();
        sb.append(buildHeaderPanel(data));
        sb.append("\n");

        for (String category : MonthlyReportData.CATEGORY_ORDER) {
            List<MonthlyTicketItem> done =
                    data.getDoneByCategory().getOrDefault(category, List.of());
            List<MonthlyTicketItem> inProgress =
                    data.getInProgressByCategory().getOrDefault(category, List.of());
            List<MonthlyTicketItem> issues =
                    data.getIssuesByCategory().getOrDefault(category, List.of());
            sb.append(buildCategorySection(category, done, inProgress, issues));
        }

        sb.append(buildAttachmentsSection());
        return sb.toString();
    }

    // =========================================================================
    // 첨부파일 섹션
    // =========================================================================

    private String buildAttachmentsSection() {
        return "\n<ac:structured-macro ac:name=\"attachments\" ac:schema-version=\"1\">\n"
                + "  <ac:parameter ac:name=\"upload\">false</ac:parameter>\n"
                + "  <ac:parameter ac:name=\"old\">false</ac:parameter>\n"
                + "</ac:structured-macro>\n";
    }

    // =========================================================================
    // 헤더 패널
    // =========================================================================

    private String buildHeaderPanel(MonthlyReportData data) {
        int totalDone = countAll(data.getDoneByCategory());
        int totalInProgress = countAll(data.getInProgressByCategory());
        int totalIssues = countAll(data.getIssuesByCategory());

        return "<ac:structured-macro ac:name=\"panel\" ac:schema-version=\"1\">\n"
                + "  <ac:parameter ac:name=\"title\">" + esc(data.pageMetaLabel()) + "</ac:parameter>\n"
                + "  <ac:parameter ac:name=\"borderColor\">#0052CC</ac:parameter>\n"
                + "  <ac:parameter ac:name=\"titleBGColor\">#0052CC</ac:parameter>\n"
                + "  <ac:parameter ac:name=\"titleColor\">#FFFFFF</ac:parameter>\n"
                + "  <ac:rich-text-body>\n"
                + "    <p><strong>📅 기간</strong>: " + esc(data.monthRangeLabel()) + "</p>\n"
                + "    <p><strong>🗓 분기</strong>: " + esc(data.quarterLabel()) + "</p>\n"
                + "    <p><strong>📊 전체</strong>: "
                + "✅ 완료 <strong>" + totalDone + "</strong>건&nbsp;/&nbsp;"
                + "🔵 진행 <strong>" + totalInProgress + "</strong>건&nbsp;/&nbsp;"
                + "🚨 이슈 <strong>" + totalIssues + "</strong>건</p>\n"
                + "  </ac:rich-text-body>\n"
                + "</ac:structured-macro>\n";
    }

    // =========================================================================
    // 카테고리 섹션 — expand 매크로
    // =========================================================================

    private String buildCategorySection(
            String category,
            List<MonthlyTicketItem> done,
            List<MonthlyTicketItem> inProgress,
            List<MonthlyTicketItem> issues) {

        String expandTitle = "[" + category + "]"
                + "  완료 " + done.size()
                + " / 진행 " + inProgress.size()
                + (issues.isEmpty() ? "" : " / 🚨 이슈 " + issues.size());

        StringBuilder sb = new StringBuilder();
        sb.append("<ac:structured-macro ac:name=\"expand\" ac:schema-version=\"1\">\n");
        sb.append("  <ac:parameter ac:name=\"title\">")
                .append(esc(expandTitle))
                .append("</ac:parameter>\n");
        sb.append("  <ac:rich-text-body>\n");

        boolean hasAny = !done.isEmpty() || !inProgress.isEmpty() || !issues.isEmpty();
        if (!hasAny) {
            sb.append("    <p><em>해당 티켓 없음</em></p>\n");
        } else {
            sb.append(buildSubSection("✅ 완료", done.size(), "#E3FCEF", "#006644", done, true));
            sb.append(buildSubSection("🔵 진행", inProgress.size(), "#DEEBFF", "#0747A6", inProgress, false));
            if (!issues.isEmpty()) {
                sb.append(buildSubSection("🚨 이슈", issues.size(), "#FFEBE6", "#BF2600", issues, false));
            }
        }

        sb.append("  </ac:rich-text-body>\n");
        sb.append("</ac:structured-macro>\n");
        return sb.toString();
    }

    // =========================================================================
    // 서브섹션
    // =========================================================================

    private String buildSubSection(
            String label, int count,
            String bgColor, String textColor,
            List<MonthlyTicketItem> items, boolean isDone) {

        StringBuilder sb = new StringBuilder();
        sb.append("<p style=\"background-color:").append(bgColor)
                .append(";padding:5px 10px;border-radius:3px;margin:8px 0 4px 0;\">\n")
                .append("  <strong style=\"color:").append(textColor).append(";\">")
                .append(esc(label)).append("</strong>")
                .append(" <em>(").append(count).append("건)</em>\n")
                .append("</p>\n");
        sb.append(buildTicketTable(items, isDone));
        return sb.toString();
    }

    // =========================================================================
    // 티켓 테이블
    // =========================================================================

    private String buildTicketTable(List<MonthlyTicketItem> items, boolean isDone) {
        String dateLabel = isDone ? "완료일" : "기한";

        StringBuilder sb = new StringBuilder();
        sb.append("<table data-layout=\"default\">\n")
                .append("  <colgroup>\n")
                .append("    <col style=\"width: 480.0px;\"/>\n")
                .append("    <col style=\"width: 70.0px;\"/>\n")
                .append("    <col style=\"width: 95.0px;\"/>\n")
                .append("    <col style=\"width: 115.0px;\"/>\n")
                .append("  </colgroup>\n")
                .append("  <thead><tr>\n")
                .append("    <th>제목</th>\n")
                .append("    <th>담당자</th>\n")
                .append("    <th>").append(dateLabel).append("</th>\n")
                .append("    <th>이슈키</th>\n")
                .append("  </tr></thead>\n")
                .append("  <tbody>\n");

        if (items.isEmpty()) {
            sb.append("    <tr><td colspan=\"4\" style=\"text-align:center;color:#888;\">"
                    + "<em>해당 없음</em></td></tr>\n");
        } else {
            for (MonthlyTicketItem item : items) {
                String rowBg = item.isIssue() ? " style=\"background-color:#FFF0F0;\"" : "";

                String dateStr = "-";
                if (item.getDueDate() != null) {
                    String formatted = item.getDueDate().format(DATE_FMT);
                    dateStr = "<span style=\"display:inline-block;\">" + esc(formatted) + "</span>";
                }

                String issueKeySpan = "<span style=\"display:inline-block;\">"
                        + esc(item.getIssueKey()) + "</span>";

                sb.append("    <tr").append(rowBg).append(">\n")
                        .append("      <td>").append(esc(item.getSummary())).append("</td>\n")
                        .append("      <td>").append(esc(item.getAssigneeName())).append("</td>\n")
                        .append("      <td>").append(dateStr).append("</td>\n")
                        .append("      <td>")
                        .append("<a href=\"").append(esc(item.getUrl())).append("\">")
                        .append(issueKeySpan).append("</a>")
                        .append("</td>\n")
                        .append("    </tr>\n");
            }
        }

        sb.append("  </tbody>\n</table>\n");
        return sb.toString();
    }

    // =========================================================================
    // 유틸
    // =========================================================================

    private int countAll(Map<String, List<MonthlyTicketItem>> map) {
        if (map == null) return 0;
        return map.values().stream().mapToInt(List::size).sum();
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
