package com.riman.automation.ingest.facade;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CurrentTicketFacade의 period 관련 private 메서드 단위 테스트.
 * 외부 의존성(Slack, S3, Calendar)을 필요로 하지 않는 순수 로직만 검증한다.
 */
class CurrentTicketFacadeTest {

    /**
     * 생성자 호출 없이 인스턴스 생성 (외부 의존성 초기화 회피)
     */
    private static CurrentTicketFacade createBareInstance() throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Unsafe unsafe = (Unsafe) f.get(null);
        return (CurrentTicketFacade) unsafe.allocateInstance(CurrentTicketFacade.class);
    }

    private String invokeBuildPeriodTitle(String period) throws Exception {
        CurrentTicketFacade facade = createBareInstance();
        Method method = CurrentTicketFacade.class
                .getDeclaredMethod("buildPeriodTitle", String.class);
        method.setAccessible(true);
        return (String) method.invoke(facade, period);
    }

    private String invokeBuildPeriodDetail(String period, LocalDate today,
                                           int quarter, LocalDate quarterStart,
                                           LocalDate quarterEnd) throws Exception {
        CurrentTicketFacade facade = createBareInstance();
        Method method = CurrentTicketFacade.class
                .getDeclaredMethod("buildPeriodDetail",
                        String.class, LocalDate.class, int.class,
                        LocalDate.class, LocalDate.class);
        method.setAccessible(true);
        return (String) method.invoke(facade, period, today, quarter, quarterStart, quarterEnd);
    }

    @Nested
    @DisplayName("buildPeriodTitle")
    class BuildPeriodTitle {

        @ParameterizedTest
        @CsvSource({
                "daily,    일별",
                "weekly,   주별",
                "monthly,  월별",
                "quarterly,분기별"
        })
        @DisplayName("period별 올바른 제목 반환")
        void returnCorrectTitle(String period, String expectedKeyword) throws Exception {
            String title = invokeBuildPeriodTitle(period);
            assertThat(title).contains(expectedKeyword);
            assertThat(title).contains("미완료 티켓 조회");
        }
    }

    @Nested
    @DisplayName("buildPeriodDetail")
    class BuildPeriodDetail {

        private final LocalDate today = LocalDate.of(2026, 4, 14);
        private final int quarter = 2;
        private final LocalDate quarterStart = LocalDate.of(2026, 4, 1);
        private final LocalDate quarterEnd = LocalDate.of(2026, 6, 30);

        @Test
        @DisplayName("monthly — 이번달 1일 ~ 말일 범위 표시")
        void monthly_showsMonthRange() throws Exception {
            String detail = invokeBuildPeriodDetail("monthly", today, quarter,
                    quarterStart, quarterEnd);

            assertThat(detail).contains("기준월");
            assertThat(detail).contains("이하 마감");
        }

        @Test
        @DisplayName("daily — 기준일 포함")
        void daily_showsToday() throws Exception {
            String detail = invokeBuildPeriodDetail("daily", today, quarter,
                    quarterStart, quarterEnd);

            assertThat(detail).contains("기준일");
            assertThat(detail).contains("이하 마감");
        }

        @Test
        @DisplayName("weekly — 기준주 포함")
        void weekly_showsWeekRange() throws Exception {
            String detail = invokeBuildPeriodDetail("weekly", today, quarter,
                    quarterStart, quarterEnd);

            assertThat(detail).contains("기준주");
            assertThat(detail).contains("이하 마감");
        }

        @Test
        @DisplayName("quarterly — 분기 범위 표시")
        void quarterly_showsQuarterRange() throws Exception {
            String detail = invokeBuildPeriodDetail("quarterly", today, quarter,
                    quarterStart, quarterEnd);

            assertThat(detail).contains("Q2");
        }
    }
}
