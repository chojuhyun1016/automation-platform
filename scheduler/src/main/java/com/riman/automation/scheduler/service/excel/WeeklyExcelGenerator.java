package com.riman.automation.scheduler.service.excel;

import com.riman.automation.scheduler.dto.report.WeeklyReportData;
import com.riman.automation.scheduler.dto.report.WeeklyReportData.WeeklyTicketItem;
import com.riman.automation.common.code.JiraPriorityCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 주간보고 엑셀 생성기
 *
 * <p><b>구조:</b>
 * <pre>
 *   시트: 전체 / 주문 / 회원 / 수당 / 포인트 / ABO / RBO
 *   컬럼: 제목 | 담당자 | 시작 | 기한 | 중요도 | 이슈키 | 상태
 *   상태: 완료 / 진행 / 이슈
 *   필터: 모든 컬럼에 자동 필터 활성화
 * </pre>
 *
 * <p><b>시트 설명:</b>
 * <ul>
 *   <li>전체: 모든 카테고리 합산, 기한 오름차순 → 카테고리 순 정렬</li>
 *   <li>카테고리별: 주문/회원/수당/포인트/ABO/RBO</li>
 * </ul>
 *
 * <p><b>컬럼 추가 내역:</b>
 * <ul>
 *   <li>시작(startDate): extendedProperties["jiraStartDate"] 또는 description "Start Date:" 파싱.
 *       FIX-4 이전 생성 이벤트는 값 없음 → 빈 칸 표시.</li>
 *   <li>중요도(priority): description "Priority:" 파싱. 없으면 빈 칸.</li>
 * </ul>
 *
 * <p><b>상태 분류 기준:</b>
 * <ul>
 *   <li>완료: doneByCategory 에 포함된 티켓</li>
 *   <li>이슈: issuesByCategory 에 포함된 티켓 (진행 중 [이슈] 태그)</li>
 *   <li>진행: inProgressByCategory 에 포함되고 이슈가 아닌 티켓</li>
 * </ul>
 */
@Slf4j
public class WeeklyExcelGenerator {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MM/dd(E)", Locale.KOREAN);

    // 상태 레이블
    private static final String STATUS_DONE = "완료";
    private static final String STATUS_IN_PROGRESS = "진행";
    private static final String STATUS_ISSUE = "이슈";

    // 전체 합산 시트 이름
    private static final String SHEET_ALL = "전체";

    // 컬럼 인덱스 (시작/기한/중요도 추가)
    private static final int COL_SUMMARY = 0;
    private static final int COL_ASSIGNEE = 1;
    private static final int COL_START = 2;   // 시작 (신규)
    private static final int COL_DUE_DATE = 3;   // 기한
    private static final int COL_PRIORITY = 4;   // 중요도 (신규)
    private static final int COL_ISSUE_KEY = 5;
    private static final int COL_STATUS = 6;
    private static final int COL_CATEGORY = 7;   // 전체 탭 전용

    // 컬럼 너비 (1/256 단위)
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
     * 주간보고 엑셀 파일 생성.
     *
     * @param data 주간보고 데이터 (카테고리별 완료/진행/이슈 티켓)
     * @return xlsx 바이트 배열
     */
    public byte[] generate(WeeklyReportData data) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {

            // 공통 스타일 미리 생성
            Styles styles = new Styles(wb);

            // 전체 합산 탭 (첫 번째)
            buildAllSheet(wb, styles, data);

            // 카테고리별 탭
            for (String category : WeeklyReportData.CATEGORY_ORDER) {
                buildSheet(wb, styles, category, data);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            log.info("[WeeklyExcelGenerator] 엑셀 생성 완료: {} 시트 (전체 탭 포함)",
                    WeeklyReportData.CATEGORY_ORDER.size() + 1);
            return out.toByteArray();

        } catch (Exception e) {
            throw new RuntimeException("[WeeklyExcelGenerator] 엑셀 생성 실패", e);
        }
    }

    // =========================================================================
    // 전체 합산 시트
    // =========================================================================

    /**
     * 전체 합산 탭 생성 — 모든 카테고리 티켓 합산.
     *
     * <p><b>정렬 기준:</b>
     * <ol>
     *   <li>기한(dueDate) 오름차순 — null은 맨 뒤</li>
     *   <li>카테고리 순 (CATEGORY_ORDER 기준)</li>
     * </ol>
     * <p>카테고리 컬럼 포함 (8번째 컬럼).
     */
    private void buildAllSheet(XSSFWorkbook wb, Styles styles, WeeklyReportData data) {
        XSSFSheet sheet = wb.createSheet(SHEET_ALL);

        // 컬럼 너비
        sheet.setColumnWidth(COL_SUMMARY, WIDTH_SUMMARY);
        sheet.setColumnWidth(COL_ASSIGNEE, WIDTH_ASSIGNEE);
        sheet.setColumnWidth(COL_START, WIDTH_START);
        sheet.setColumnWidth(COL_DUE_DATE, WIDTH_DUE_DATE);
        sheet.setColumnWidth(COL_PRIORITY, WIDTH_PRIORITY);
        sheet.setColumnWidth(COL_ISSUE_KEY, WIDTH_ISSUE_KEY);
        sheet.setColumnWidth(COL_STATUS, WIDTH_STATUS);
        sheet.setColumnWidth(COL_CATEGORY, WIDTH_CATEGORY);

        // 헤더
        Row header = sheet.createRow(0);
        writeCell(header, COL_SUMMARY, "제목", styles.header);
        writeCell(header, COL_ASSIGNEE, "담당자", styles.header);
        writeCell(header, COL_START, "시작", styles.header);
        writeCell(header, COL_DUE_DATE, "기한", styles.header);
        writeCell(header, COL_PRIORITY, "중요도", styles.header);
        writeCell(header, COL_ISSUE_KEY, "이슈키", styles.header);
        writeCell(header, COL_STATUS, "상태", styles.header);
        writeCell(header, COL_CATEGORY, "분류", styles.header);

        // 자동 필터
        sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, COL_CATEGORY));

        // 카테고리 순서 맵 (정렬 2순위)
        Map<String, Integer> categoryOrder = new HashMap<>();
        for (int i = 0; i < WeeklyReportData.CATEGORY_ORDER.size(); i++) {
            categoryOrder.put(WeeklyReportData.CATEGORY_ORDER.get(i), i);
        }

        // 전체 티켓 수집 — 완료 + 이슈 + 진행(이슈 제외)
        List<WeeklyTicketItem> allItems = collectAllItems(data, categoryOrder);

        // 데이터 행 쓰기 (카테고리 컬럼 포함)
        int rowIdx = 1;
        for (WeeklyTicketItem item : allItems) {
            String statusLabel = resolveStatusLabel(item, data);
            rowIdx = writeDataRow(sheet, styles, rowIdx, item, statusLabel, true);
        }

        // 헤더 고정
        sheet.createFreezePane(0, 1);

        log.debug("[WeeklyExcelGenerator] 전체 탭: {}건", allItems.size());
    }

    /**
     * 전체 합산용 티켓 목록 수집 및 정렬.
     *
     * <p>완료 → 이슈 → 진행 순으로 수집 후
     * 기한 오름차순 → 카테고리 순으로 정렬.
     */
    private List<WeeklyTicketItem> collectAllItems(WeeklyReportData data,
                                                   Map<String, Integer> categoryOrder) {
        // 이슈 키 집합 (카테고리별로 다를 수 있으므로 전체 수집)
        Set<String> issueTaggedKeys = WeeklyReportData.CATEGORY_ORDER.stream()
                .flatMap(cat -> data.getIssuesByCategory()
                        .getOrDefault(cat, List.of()).stream())
                .map(WeeklyTicketItem::getIssueKey)
                .collect(Collectors.toSet());

        // 완료 + 진행(이슈 포함) 합산 — 이슈 키 중복 제거
        Map<String, WeeklyTicketItem> dedupMap = new LinkedHashMap<>();

        for (String cat : WeeklyReportData.CATEGORY_ORDER) {
            for (WeeklyTicketItem item : data.getDoneByCategory().getOrDefault(cat, List.of())) {
                dedupMap.putIfAbsent(item.getIssueKey(), item);
            }
        }
        for (String cat : WeeklyReportData.CATEGORY_ORDER) {
            for (WeeklyTicketItem item : data.getInProgressByCategory().getOrDefault(cat, List.of())) {
                dedupMap.putIfAbsent(item.getIssueKey(), item);
            }
        }

        // 정렬: 기한 오름차순(null 맨 뒤) → 카테고리 순
        return dedupMap.values().stream()
                .sorted(Comparator
                        .comparing(WeeklyTicketItem::getDueDate,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingInt(t -> categoryOrder.getOrDefault(t.getCategory(), 99)))
                .collect(Collectors.toList());
    }

    /**
     * 티켓의 상태 레이블 결정 (전체 탭용).
     * issuesByCategory 에 있으면 이슈, doneByCategory 에 있으면 완료, 나머지 진행.
     */
    private String resolveStatusLabel(WeeklyTicketItem item, WeeklyReportData data) {
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
    // 카테고리별 시트 생성
    // =========================================================================

    private void buildSheet(XSSFWorkbook wb, Styles styles, String category, WeeklyReportData data) {
        XSSFSheet sheet = wb.createSheet(category);

        // 컬럼 너비
        sheet.setColumnWidth(COL_SUMMARY, WIDTH_SUMMARY);
        sheet.setColumnWidth(COL_ASSIGNEE, WIDTH_ASSIGNEE);
        sheet.setColumnWidth(COL_START, WIDTH_START);
        sheet.setColumnWidth(COL_DUE_DATE, WIDTH_DUE_DATE);
        sheet.setColumnWidth(COL_PRIORITY, WIDTH_PRIORITY);
        sheet.setColumnWidth(COL_ISSUE_KEY, WIDTH_ISSUE_KEY);
        sheet.setColumnWidth(COL_STATUS, WIDTH_STATUS);

        // 헤더
        Row header = sheet.createRow(0);
        writeCell(header, COL_SUMMARY, "제목", styles.header);
        writeCell(header, COL_ASSIGNEE, "담당자", styles.header);
        writeCell(header, COL_START, "시작", styles.header);
        writeCell(header, COL_DUE_DATE, "기한", styles.header);
        writeCell(header, COL_PRIORITY, "중요도", styles.header);
        writeCell(header, COL_ISSUE_KEY, "이슈키", styles.header);
        writeCell(header, COL_STATUS, "상태", styles.header);

        // 자동 필터 (헤더 행 기준) — COL_STATUS 까지
        sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, COL_STATUS));

        // 데이터 수집
        List<WeeklyTicketItem> done = data.getDoneByCategory()
                .getOrDefault(category, List.of());
        List<WeeklyTicketItem> inProgress = data.getInProgressByCategory()
                .getOrDefault(category, List.of());
        List<WeeklyTicketItem> issues = data.getIssuesByCategory()
                .getOrDefault(category, List.of());

        // 이슈 키 집합 (중복 판별용)
        Set<String> issueKeys = issues.stream()
                .map(WeeklyTicketItem::getIssueKey)
                .collect(Collectors.toSet());

        int rowIdx = 1;

        // 완료
        for (WeeklyTicketItem item : done) {
            rowIdx = writeDataRow(sheet, styles, rowIdx, item, STATUS_DONE, false);
        }

        // 이슈 (진행중 중 [이슈] 태그)
        for (WeeklyTicketItem item : issues) {
            rowIdx = writeDataRow(sheet, styles, rowIdx, item, STATUS_ISSUE, false);
        }

        // 진행 (이슈 제외)
        for (WeeklyTicketItem item : inProgress) {
            if (!issueKeys.contains(item.getIssueKey())) {
                rowIdx = writeDataRow(sheet, styles, rowIdx, item, STATUS_IN_PROGRESS, false);
            }
        }

        // 행 고정 (헤더 고정)
        sheet.createFreezePane(0, 1);

        log.debug("[WeeklyExcelGenerator] 시트 '{}': 완료={}건, 진행={}건, 이슈={}건",
                category, done.size(), inProgress.size() - issues.size(), issues.size());
    }

    // =========================================================================
    // 행 쓰기
    // =========================================================================

    /**
     * 데이터 행 쓰기.
     *
     * @param showCategory true이면 COL_CATEGORY(분류) 컬럼도 작성 (전체 탭 전용)
     */
    private int writeDataRow(XSSFSheet sheet, Styles styles,
                             int rowIdx, WeeklyTicketItem item,
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

        // 시작일 — FIX-4 이전 이벤트는 null → 빈 칸
        String startStr = item.getStartDate() != null
                ? item.getStartDate().format(DATE_FMT) : "";

        // 기한 — null이면 빈 칸 (이전 티켓 호환)
        String dueStr = item.getDueDate() != null
                ? item.getDueDate().format(DATE_FMT) : "";

        // 중요도 — UNKNOWN 또는 null이면 빈 칸 (이전 이벤트 호환)
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

        // 분류 컬럼 — 전체 탭 전용
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
    // 스타일
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
            // 공통 폰트
            Font baseFont = wb.createFont();
            baseFont.setFontName("맑은 고딕");
            baseFont.setFontHeightInPoints((short) 10);

            Font headerFont = wb.createFont();
            headerFont.setFontName("맑은 고딕");
            headerFont.setFontHeightInPoints((short) 10);
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());

            // 헤더 스타일
            header = wb.createCellStyle();
            header.setFont(headerFont);
            header.setFillForegroundColor(new XSSFColor(new byte[]{0, 82, (byte) 204}, null)); // #0052CC
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            setBorder(header);
            header.setWrapText(false);

            // 완료 — 연한 초록
            done = wb.createCellStyle();
            done.setFont(baseFont);
            done.setFillForegroundColor(new XSSFColor(new byte[]{(byte) 227, (byte) 252, (byte) 239}, null)); // #E3FCEF
            done.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            done.setVerticalAlignment(VerticalAlignment.CENTER);
            setBorder(done);
            done.setWrapText(false);

            // 진행 — 흰색
            inProgress = wb.createCellStyle();
            inProgress.setFont(baseFont);
            inProgress.setVerticalAlignment(VerticalAlignment.CENTER);
            setBorder(inProgress);
            inProgress.setWrapText(false);

            // 이슈 — 연한 빨강
            issue = wb.createCellStyle();
            issue.setFont(baseFont);
            issue.setFillForegroundColor(new XSSFColor(new byte[]{(byte) 255, (byte) 235, (byte) 230}, null)); // #FFEBE6
            issue.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            issue.setVerticalAlignment(VerticalAlignment.CENTER);
            setBorder(issue);
            issue.setWrapText(false);

            // 링크 폰트 (파란색 밑줄)
            Font linkFont = wb.createFont();
            linkFont.setFontName("맑은 고딕");
            linkFont.setFontHeightInPoints((short) 10);
            linkFont.setUnderline(Font.U_SINGLE);
            linkFont.setColor(IndexedColors.BLUE.getIndex());

            // 링크 스타일 — 완료
            linkDone = wb.createCellStyle();
            linkDone.cloneStyleFrom(done);
            linkDone.setFont(linkFont);

            // 링크 스타일 — 진행
            linkInProgress = wb.createCellStyle();
            linkInProgress.cloneStyleFrom(inProgress);
            linkInProgress.setFont(linkFont);

            // 링크 스타일 — 이슈
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
