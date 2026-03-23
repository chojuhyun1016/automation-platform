package com.riman.automation.scheduler.service.format;

import com.riman.automation.scheduler.dto.report.WeeklyReportData;
import com.riman.automation.scheduler.dto.report.WeeklyReportData.WeeklyTicketItem;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 주간보고 Confluence Storage Format HTML 포맷터 v3
 *
 * <p><b>구조:</b>
 * <pre>
 *   [헤더 패널] 기간 / 분기 / 전체 건수
 *
 *   ▼ [주문]  완료 0 / 진행 2      ← expand 매크로 (제목만, 접기/펼치기 가능)
 *     ✅ 완료 (0건)
 *       | 제목 480px | 담당자 70px | 완료일 95px | 이슈키 115px |
 *     🔵 진행 (2건)
 *       | 제목 480px | 담당자 70px | 기한   95px | 이슈키 115px |
 *
 *   ▼ [회원]  완료 1 / 진행 4
 * </pre>
 *
 * <p><b>테이블 너비:</b> data-layout="default" + colgroup px 단위 (% 는 Confluence에서 무시됨)
 * <p><b>줄바꿈 방지:</b> 날짜/이슈키 셀 내용을 &lt;span style="display:inline-block"&gt; 으로 감쌈
 * — white-space:nowrap / &#40; 엔티티 / &#8209; non-breaking hyphen 모두 Confluence가 무시하므로
 * inline-block span 이 유일하게 동작하는 방법
 */
public class WeeklyReportFormatter {

    // "03/06(화)" 형식 — span 으로 감싸서 줄바꿈 방지
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MM/dd(E)", Locale.KOREAN);

    // =========================================================================
    // 공개 API
    // =========================================================================

    public String format(WeeklyReportData data) {
        StringBuilder sb = new StringBuilder();
        sb.append(buildHeaderPanel(data));
        sb.append("\n");

        for (String category : WeeklyReportData.CATEGORY_ORDER) {
            List<WeeklyTicketItem> done =
                    data.getDoneByCategory().getOrDefault(category, List.of());
            List<WeeklyTicketItem> inProgress =
                    data.getInProgressByCategory().getOrDefault(category, List.of());
            List<WeeklyTicketItem> issues =
                    data.getIssuesByCategory().getOrDefault(category, List.of());
            sb.append(buildCategorySection(category, done, inProgress, issues));
        }

        // 첨부파일 섹션 — Confluence Cloud는 자동 표시 안 함, 매크로로 명시
        sb.append(buildAttachmentsSection());

        return sb.toString();
    }

    // =========================================================================
    // 첨부파일 섹션
    // =========================================================================

    /**
     * Confluence attachments 매크로.
     * Confluence Cloud는 첨부파일을 페이지 하단에 자동 표시하지 않으므로
     * 본문에 이 매크로를 삽입해야 파일이 페이지에서 보인다.
     */
    private String buildAttachmentsSection() {
        return "\n<ac:structured-macro ac:name=\"attachments\" ac:schema-version=\"1\">\n"
                + "  <ac:parameter ac:name=\"upload\">false</ac:parameter>\n"
                + "  <ac:parameter ac:name=\"old\">false</ac:parameter>\n"
                + "</ac:structured-macro>\n";
    }

    // =========================================================================
    // 헤더 패널
    // =========================================================================

    private String buildHeaderPanel(WeeklyReportData data) {
        int totalDone = countAll(data.getDoneByCategory());
        int totalInProgress = countAll(data.getInProgressByCategory());
        int totalIssues = countAll(data.getIssuesByCategory());

        return "<ac:structured-macro ac:name=\"panel\" ac:schema-version=\"1\">\n"
                + "  <ac:parameter ac:name=\"title\">" + esc(data.pageTitle()) + "</ac:parameter>\n"
                + "  <ac:parameter ac:name=\"borderColor\">#0052CC</ac:parameter>\n"
                + "  <ac:parameter ac:name=\"titleBGColor\">#0052CC</ac:parameter>\n"
                + "  <ac:parameter ac:name=\"titleColor\">#FFFFFF</ac:parameter>\n"
                + "  <ac:rich-text-body>\n"
                + "    <p><strong>📅 기간</strong>: " + esc(data.weekRangeLabel()) + "</p>\n"
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
            List<WeeklyTicketItem> done,
            List<WeeklyTicketItem> inProgress,
            List<WeeklyTicketItem> issues) {

        // expand 제목: "[주문]  완료 0 / 진행 2" — 이슈는 있을 때만 표시
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
            // 서브섹션: label = emoji + 한글 한 번씩만
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

    /**
     * @param label "✅ 완료" / "🔵 진행" / "🚨 이슈"  (중복 없이 한 번씩만)
     *              렌더링: "✅ 완료 (3건)"
     */
    private String buildSubSection(
            String label, int count,
            String bgColor, String textColor,
            List<WeeklyTicketItem> items, boolean isDone) {

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

    /**
     * 컬럼: 제목(480px) | 담당자(70px) | 완료일/기한(95px) | 이슈키(115px) — 합계 760px
     * 줄바꿈 방지: 날짜·이슈키를 <span style="display:inline-block"> 으로 감쌈
     */
    private String buildTicketTable(List<WeeklyTicketItem> items, boolean isDone) {
        String dateLabel = isDone ? "완료일" : "기한";

        StringBuilder sb = new StringBuilder();
        // 760px 기준 — 이슈키 여유 확보: 480 + 70 + 95 + 115 = 760
        sb.append("<table data-layout=\"default\">\n")
                .append("  <colgroup>\n")
                .append("    <col style=\"width: 480.0px;\"/>\n")   // 제목
                .append("    <col style=\"width: 70.0px;\"/>\n")    // 담당자 (한글 4자)
                .append("    <col style=\"width: 95.0px;\"/>\n")    // 날짜 "03/18(수)"
                .append("    <col style=\"width: 115.0px;\"/>\n")   // 이슈키 (여유 확보)
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
            for (WeeklyTicketItem item : items) {
                String rowBg = item.isIssue() ? " style=\"background-color:#FFF0F0;\"" : "";

                // 날짜: span(display:inline-block) 으로 감싸서 줄바꿈 완전 차단
                String dateStr = "-";
                if (item.getDueDate() != null) {
                    String formatted = item.getDueDate().format(DATE_FMT);
                    dateStr = "<span style=\"display:inline-block;\">" + esc(formatted) + "</span>";
                }

                // 이슈키: span(display:inline-block) 으로 감싸서 줄바꿈 완전 차단
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

    private int countAll(Map<String, List<WeeklyTicketItem>> map) {
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
