package com.riman.automation.scheduler.service.excel;

import com.riman.automation.common.code.JiraPriorityCode;
import com.riman.automation.scheduler.dto.report.MonthlyReportData;
import com.riman.automation.scheduler.dto.report.MonthlyReportData.MonthlyTicketItem;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 월간보고 엑셀 생성기
 *
 * <p><b>WeeklyExcelGenerator 와의 대응:</b>
 * <pre>
 *   WeeklyExcelGenerator  — 주간 실적 엑셀
 *   MonthlyExcelGenerator — 월간 실적 엑셀  (이 클래스)
 * </pre>
 *
 * <p><b>구조 (WeeklyExcelGenerator 와 동일):</b>
 * <pre>
 *   시트: 전체 / 주문 / 회원 / 수당 / 포인트 / ABO / RBO
 *   컬럼: 제목 | 담당자 | 시작 | 기한 | 중요도 | 이슈키 | 상태
 *   상태: 완료 / 진행 / 이슈
 *   필터: 모든 컬럼에 자동 필터 활성화
 * </pre>
 */
@Slf4j
public class MonthlyExcelGenerator {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MM/dd(E)", Locale.KOREAN);

    private static final String STATUS_DONE = "완료";
    private static final String STATUS_IN_PROGRESS = "진행";
    private static final String STATUS_ISSUE = "이슈";
    private static final String SHEET_ALL = "전체";

    private static final int COL_SUMMARY = 0;
    private static final int COL_ASSIGNEE = 1;
    private static final int COL_START = 2;
    private static final int COL_DUE_DATE = 3;
    private static final int COL_PRIORITY = 4;
    private static final int COL_ISSUE_KEY = 5;
    private static final int COL_STATUS = 6;
    private static final int COL_CATEGORY = 7;

    private static final int WIDTH_SUMMARY = 256 * 50;
    private static final int WIDTH_ASSIGNEE = 256 * 10;
    private static final int WIDTH_START = 256 * 12;
    private static final int WIDTH_DUE_DATE = 256 * 12;
    private static final int WIDTH_PRIORITY = 256 * 10;
    private static final int WIDTH_ISSUE_KEY = 256 * 13;
    private static final int WIDTH_STATUS = 256 * 8;
    private static final int WIDTH_CATEGORY = 256 * 8;

    // =========================================================================
    // 공개 API
    // =========================================================================

    /**
     * 월간보고 엑셀 파일 생성.
     *
     * @param data 월간보고 데이터 (카테고리별 완료/진행/이슈 티켓)
     * @return xlsx 바이트 배열
     */
    public byte[] generate(MonthlyReportData data) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles styles = new Styles(wb);

            buildAllSheet(wb, styles, data);

            for (String category : MonthlyReportData.CATEGORY_ORDER) {
                buildSheet(wb, styles, category, data);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            log.info("[MonthlyExcelGenerator] 엑셀 생성 완료: {} 시트 (전체 탭 포함)",
                    MonthlyReportData.CATEGORY_ORDER.size() + 1);
            return out.toByteArray();

        } catch (Exception e) {
            throw new RuntimeException("[MonthlyExcelGenerator] 엑셀 생성 실패", e);
        }
    }

    // =========================================================================
    // 전체 합산 시트
    // =========================================================================

    private void buildAllSheet(XSSFWorkbook wb, Styles styles, MonthlyReportData data) {
        XSSFSheet sheet = wb.createSheet(SHEET_ALL);

        sheet.setColumnWidth(COL_SUMMARY, WIDTH_SUMMARY);
        sheet.setColumnWidth(COL_ASSIGNEE, WIDTH_ASSIGNEE);
        sheet.setColumnWidth(COL_START, WIDTH_START);
        sheet.setColumnWidth(COL_DUE_DATE, WIDTH_DUE_DATE);
        sheet.setColumnWidth(COL_PRIORITY, WIDTH_PRIORITY);
        sheet.setColumnWidth(COL_ISSUE_KEY, WIDTH_ISSUE_KEY);
        sheet.setColumnWidth(COL_STATUS, WIDTH_STATUS);
        sheet.setColumnWidth(COL_CATEGORY, WIDTH_CATEGORY);

        Row header = sheet.createRow(0);
        writeCell(header, COL_SUMMARY, "제목", styles.header);
        writeCell(header, COL_ASSIGNEE, "담당자", styles.header);
        writeCell(header, COL_START, "시작", styles.header);
        writeCell(header, COL_DUE_DATE, "기한", styles.header);
        writeCell(header, COL_PRIORITY, "중요도", styles.header);
        writeCell(header, COL_ISSUE_KEY, "이슈키", styles.header);
        writeCell(header, COL_STATUS, "상태", styles.header);
        writeCell(header, COL_CATEGORY, "분류", styles.header);

        sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, COL_CATEGORY));

        Map<String, Integer> categoryOrder = new HashMap<>();
        for (int i = 0; i < MonthlyReportData.CATEGORY_ORDER.size(); i++) {
            categoryOrder.put(MonthlyReportData.CATEGORY_ORDER.get(i), i);
        }

        List<MonthlyTicketItem> allItems = collectAllItems(data, categoryOrder);

        int rowIdx = 1;
        for (MonthlyTicketItem item : allItems) {
            String statusLabel = resolveStatusLabel(item, data);
            rowIdx = writeDataRow(sheet, styles, rowIdx, item, statusLabel, true);
        }

        sheet.createFreezePane(0, 1);
        log.debug("[MonthlyExcelGenerator] 전체 탭: {}건", allItems.size());
    }

    private List<MonthlyTicketItem> collectAllItems(MonthlyReportData data,
                                                    Map<String, Integer> categoryOrder) {
        Set<String> issueTaggedKeys = MonthlyReportData.CATEGORY_ORDER.stream()
                .flatMap(cat -> data.getIssuesByCategory()
                        .getOrDefault(cat, List.of()).stream())
                .map(MonthlyTicketItem::getIssueKey)
                .collect(Collectors.toSet());

        Map<String, MonthlyTicketItem> dedupMap = new LinkedHashMap<>();

        for (String cat : MonthlyReportData.CATEGORY_ORDER) {
            for (MonthlyTicketItem item : data.getDoneByCategory().getOrDefault(cat, List.of())) {
                dedupMap.putIfAbsent(item.getIssueKey(), item);
            }
        }
        for (String cat : MonthlyReportData.CATEGORY_ORDER) {
            for (MonthlyTicketItem item : data.getInProgressByCategory().getOrDefault(cat, List.of())) {
                dedupMap.putIfAbsent(item.getIssueKey(), item);
            }
        }

        return dedupMap.values().stream()
                .sorted(Comparator
                        .comparing(MonthlyTicketItem::getDueDate,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingInt(t -> categoryOrder.getOrDefault(t.getCategory(), 99)))
                .collect(Collectors.toList());
    }

    private String resolveStatusLabel(MonthlyTicketItem item, MonthlyReportData data) {
        String key = item.getIssueKey();
        String cat = item.getCategory();
        if (cat != null) {
            boolean isIssue = data.getIssuesByCategory()
                    .getOrDefault(cat, List.of()).stream()
                    .anyMatch(t -> key.equals(t.getIssueKey()));
            if (isIssue) return STATUS_ISSUE;
            boolean isDone = data.getDoneByCategory()
                    .getOrDefault(cat, List.of()).stream()
                    .anyMatch(t -> key.equals(t.getIssueKey()));
            if (isDone) return STATUS_DONE;
        }
        return STATUS_IN_PROGRESS;
    }

    // =========================================================================
    // 카테고리별 시트
    // =========================================================================

    private void buildSheet(XSSFWorkbook wb, Styles styles, String category, MonthlyReportData data) {
        XSSFSheet sheet = wb.createSheet(category);

        sheet.setColumnWidth(COL_SUMMARY, WIDTH_SUMMARY);
        sheet.setColumnWidth(COL_ASSIGNEE, WIDTH_ASSIGNEE);
        sheet.setColumnWidth(COL_START, WIDTH_START);
        sheet.setColumnWidth(COL_DUE_DATE, WIDTH_DUE_DATE);
        sheet.setColumnWidth(COL_PRIORITY, WIDTH_PRIORITY);
        sheet.setColumnWidth(COL_ISSUE_KEY, WIDTH_ISSUE_KEY);
        sheet.setColumnWidth(COL_STATUS, WIDTH_STATUS);

        Row header = sheet.createRow(0);
        writeCell(header, COL_SUMMARY, "제목", styles.header);
        writeCell(header, COL_ASSIGNEE, "담당자", styles.header);
        writeCell(header, COL_START, "시작", styles.header);
        writeCell(header, COL_DUE_DATE, "기한", styles.header);
        writeCell(header, COL_PRIORITY, "중요도", styles.header);
        writeCell(header, COL_ISSUE_KEY, "이슈키", styles.header);
        writeCell(header, COL_STATUS, "상태", styles.header);

        sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, COL_STATUS));

        List<MonthlyTicketItem> done = data.getDoneByCategory()
                .getOrDefault(category, List.of());
        List<MonthlyTicketItem> inProgress = data.getInProgressByCategory()
                .getOrDefault(category, List.of());
        List<MonthlyTicketItem> issues = data.getIssuesByCategory()
                .getOrDefault(category, List.of());

        Set<String> issueKeys = issues.stream()
                .map(MonthlyTicketItem::getIssueKey)
                .collect(Collectors.toSet());

        int rowIdx = 1;

        for (MonthlyTicketItem item : done) {
            rowIdx = writeDataRow(sheet, styles, rowIdx, item, STATUS_DONE, false);
        }
        for (MonthlyTicketItem item : issues) {
            rowIdx = writeDataRow(sheet, styles, rowIdx, item, STATUS_ISSUE, false);
        }
        for (MonthlyTicketItem item : inProgress) {
            if (!issueKeys.contains(item.getIssueKey())) {
                rowIdx = writeDataRow(sheet, styles, rowIdx, item, STATUS_IN_PROGRESS, false);
            }
        }

        sheet.createFreezePane(0, 1);
        log.debug("[MonthlyExcelGenerator] 시트 '{}': 완료={}건, 진행={}건, 이슈={}건",
                category, done.size(), inProgress.size() - issues.size(), issues.size());
    }

    // =========================================================================
    // 행 쓰기
    // =========================================================================

    private int writeDataRow(XSSFSheet sheet, Styles styles,
                             int rowIdx, MonthlyTicketItem item,
                             String statusLabel, boolean showCategory) {
        Row row = sheet.createRow(rowIdx);

        CellStyle bodyStyle = switch (statusLabel) {
            case STATUS_DONE -> styles.done;
            case STATUS_ISSUE -> styles.issue;
            default -> styles.inProgress;
        };
        CellStyle linkStyle = switch (statusLabel) {
            case STATUS_DONE -> styles.linkDone;
            case STATUS_ISSUE -> styles.linkIssue;
            default -> styles.linkInProgress;
        };

        String startStr = item.getStartDate() != null
                ? item.getStartDate().format(DATE_FMT) : "";
        String dueStr = item.getDueDate() != null
                ? item.getDueDate().format(DATE_FMT) : "";
        String priorityStr = "";
        if (item.getPriority() != null && item.getPriority() != JiraPriorityCode.UNKNOWN) {
            priorityStr = item.getPriority().getDisplayName();
        }

        writeCell(row, COL_SUMMARY, item.getSummary(), bodyStyle);
        writeCell(row, COL_ASSIGNEE, item.getAssigneeName(), bodyStyle);
        writeCell(row, COL_START, startStr, bodyStyle);
        writeCell(row, COL_DUE_DATE, dueStr, bodyStyle);
        writeCell(row, COL_PRIORITY, priorityStr, bodyStyle);
        writeLinkCell(sheet, row, COL_ISSUE_KEY, item.getIssueKey(), item.getUrl(), linkStyle);
        writeCell(row, COL_STATUS, statusLabel, bodyStyle);

        if (showCategory) {
            writeCell(row, COL_CATEGORY, item.getCategory() != null ? item.getCategory() : "", bodyStyle);
        }

        return rowIdx + 1;
    }

    private void writeLinkCell(XSSFSheet sheet, Row row, int col,
                               String label, String url, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(label != null ? label : "");
        cell.setCellStyle(style);
        if (url != null && !url.isBlank()) {
            XSSFCreationHelper helper = sheet.getWorkbook().getCreationHelper();
            XSSFHyperlink link = helper.createHyperlink(HyperlinkType.URL);
            link.setAddress(url);
            cell.setHyperlink(link);
        }
    }

    private void writeCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(style);
    }

    // =========================================================================
    // 스타일 — WeeklyExcelGenerator 와 동일한 팔레트
    // =========================================================================

    private static class Styles {
        final CellStyle header;
        final CellStyle done;
        final CellStyle inProgress;
        final CellStyle issue;
        final CellStyle linkDone;
        final CellStyle linkInProgress;
        final CellStyle linkIssue;

        Styles(XSSFWorkbook wb) {
            Font baseFont = wb.createFont();
            baseFont.setFontName("맑은 고딕");
            baseFont.setFontHeightInPoints((short) 10);

            Font headerFont = wb.createFont();
            headerFont.setFontName("맑은 고딕");
            headerFont.setFontHeightInPoints((short) 10);
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());

            header = wb.createCellStyle();
            header.setFont(headerFont);
            header.setFillForegroundColor(new XSSFColor(new byte[]{0, 82, (byte) 204}, null));
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            setBorder(header);
            header.setWrapText(false);

            done = wb.createCellStyle();
            done.setFont(baseFont);
            done.setFillForegroundColor(
                    new XSSFColor(new byte[]{(byte) 227, (byte) 252, (byte) 239}, null));
            done.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            done.setVerticalAlignment(VerticalAlignment.CENTER);
            setBorder(done);
            done.setWrapText(false);

            inProgress = wb.createCellStyle();
            inProgress.setFont(baseFont);
            inProgress.setVerticalAlignment(VerticalAlignment.CENTER);
            setBorder(inProgress);
            inProgress.setWrapText(false);

            issue = wb.createCellStyle();
            issue.setFont(baseFont);
            issue.setFillForegroundColor(
                    new XSSFColor(new byte[]{(byte) 255, (byte) 235, (byte) 230}, null));
            issue.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            issue.setVerticalAlignment(VerticalAlignment.CENTER);
            setBorder(issue);
            issue.setWrapText(false);

            Font linkFont = wb.createFont();
            linkFont.setFontName("맑은 고딕");
            linkFont.setFontHeightInPoints((short) 10);
            linkFont.setUnderline(Font.U_SINGLE);
            linkFont.setColor(IndexedColors.BLUE.getIndex());

            linkDone = wb.createCellStyle();
            linkDone.cloneStyleFrom(done);
            linkDone.setFont(linkFont);

            linkInProgress = wb.createCellStyle();
            linkInProgress.cloneStyleFrom(inProgress);
            linkInProgress.setFont(linkFont);

            linkIssue = wb.createCellStyle();
            linkIssue.cloneStyleFrom(issue);
            linkIssue.setFont(linkFont);
        }

        private void setBorder(CellStyle style) {
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
            style.setTopBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            style.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            style.setLeftBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            style.setRightBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        }
    }
}
