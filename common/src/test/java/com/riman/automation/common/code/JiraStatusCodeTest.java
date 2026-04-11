package com.riman.automation.common.code;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class JiraStatusCodeTest {

    // ── fromCategoryKey ─────────────────────────────────────────────────────

    @Test
    @DisplayName("fromCategoryKey — 'done'이면 DONE")
    void fromCategoryKey_done_returnsDone() {
        assertThat(JiraStatusCode.fromCategoryKey("done")).isEqualTo(JiraStatusCode.DONE);
    }

    @Test
    @DisplayName("fromCategoryKey — 대소문자 무관 'DONE' → DONE")
    void fromCategoryKey_caseInsensitive() {
        assertThat(JiraStatusCode.fromCategoryKey("DONE")).isEqualTo(JiraStatusCode.DONE);
        assertThat(JiraStatusCode.fromCategoryKey("Done")).isEqualTo(JiraStatusCode.DONE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"indeterminate", "new", "unknown"})
    @DisplayName("fromCategoryKey — done 외의 값이면 IN_PROGRESS")
    void fromCategoryKey_notDone_returnsInProgress(String key) {
        assertThat(JiraStatusCode.fromCategoryKey(key)).isEqualTo(JiraStatusCode.IN_PROGRESS);
    }

    // ── fromStatusName ──────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "done", "Done", "DONE",
            "answered", "Answered",
            "duplicated", "DUPLICATED",
            "grit 이관",
            "listed", "reject",
            "monitoring in progress", "Monitoring In Progress",
            "완료", "반려", "취소"
    })
    @DisplayName("fromStatusName — DONE_STATUS_NAMES에 포함되면 DONE (대소문자 무관)")
    void fromStatusName_doneStatusNames_returnsDone(String statusName) {
        assertThat(JiraStatusCode.fromStatusName(statusName)).isEqualTo(JiraStatusCode.DONE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"In Progress", "To Do", "Open", "  "})
    @DisplayName("fromStatusName — 매칭 안 되면 IN_PROGRESS")
    void fromStatusName_nonDoneNames_returnsInProgress(String statusName) {
        assertThat(JiraStatusCode.fromStatusName(statusName)).isEqualTo(JiraStatusCode.IN_PROGRESS);
    }

    // ── isActive ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("isActive — IN_PROGRESS는 true, DONE은 false")
    void isActive() {
        assertThat(JiraStatusCode.IN_PROGRESS.isActive()).isTrue();
        assertThat(JiraStatusCode.DONE.isActive()).isFalse();
    }
}
