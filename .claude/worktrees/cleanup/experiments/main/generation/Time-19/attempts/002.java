package org.joda.time;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;

@RunWith(JQF.class)
public class JQF_TestDateTimeZoneCutover {

    static int lastSunday(int year, int month) {
        return LocalDate.of(year, month, 1)
                .with(TemporalAdjusters.lastInMonth(DayOfWeek.SUNDAY)).getDayOfMonth();
    }

    static boolean validDate(int year, int month, int day) {
        return year >= 1997 && year <= 2037
                && month >= 1 && month <= 12
                && day >= 1 && day <= YearMonth.of(year, month).lengthOfMonth();
    }

    /** Years in which the last Sunday of October is the 30th are favoured. */
    public static class YearGen extends Generator<Integer> {
        private static final int[] YEARS = {2005, 2011, 2016, 2022, 2033};
        public YearGen() { super(Integer.class); }
        @Override public Integer generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextInt(0, 3) < 3) {
                return YEARS[r.nextInt(0, YEARS.length - 1)];
            }
            return r.nextInt(1997, 2037);
        }
    }

    public static class MonthGen extends Generator<Integer> {
        public MonthGen() { super(Integer.class); }
        @Override public Integer generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextInt(0, 3) < 3) {
                int c = r.nextInt(0, 3);
                return c == 0 ? 3 : 10;
            }
            return r.nextInt(1, 12);
        }
    }

    public static class DayGen extends Generator<Integer> {
        public DayGen() { super(Integer.class); }
        @Override public Integer generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextInt(0, 3) < 3) {
                int c = r.nextInt(0, 4);
                if (c == 0) return 27;
                if (c == 1) return 31;
                return 30;
            }
            return r.nextInt(1, 31);
        }
    }

    public static class HourGen extends Generator<Integer> {
        public HourGen() { super(Integer.class); }
        @Override public Integer generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextInt(0, 3) < 3) {
                int c = r.nextInt(0, 4);
                if (c == 0) return 0;
                if (c == 1) return 2;
                return 1;
            }
            return r.nextInt(0, 23);
        }
    }

    @Fuzz
    public void testBugReproduction(@From(YearGen.class) int year,
                                    @From(MonthGen.class) int month,
                                    @From(DayGen.class) int day,
                                    @From(HourGen.class) int hour,
                                    @InRange(minInt = 0, maxInt = 60) int minute,
                                    @InRange(minInt = 0, maxInt = 60) int second,
                                    @InRange(minInt = 0, maxInt = 1000) int millis) {
        boolean validIf = validDate(year, month, day)
                && hour >= 0 && hour <= 23
                && minute >= 0 && minute <= 59
                && second >= 0 && second <= 59
                && millis >= 0 && millis <= 999
                && !(month == 3 && day == lastSunday(year, 3) && hour == 1);
        boolean buggyIf = validDate(year, month, day)
                && month == 10 && day == lastSunday(year, 10) && hour == 1;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        DateTimeZone london = DateTimeZone.forID("Europe/London");
        DateTime dt = new DateTime(year, month, day, hour, minute, second, millis, london);
        long resultMillis = dt.getMillis();
        int offset = london.getOffset(resultMillis);

        Log.logOutIf(true, () -> new Object[]{ dt.toString(), resultMillis, offset,
                dt.getHourOfDay(), dt.getMinuteOfHour(), dt.getSecondOfMinute(),
                dt.getMillisOfSecond() });
        long expectedMillis = new DateTime(year, month, day, hour, minute, second, millis,
                DateTimeZone.UTC).getMillis() - 3600000L;
        assertEquals(3600000, offset);
        assertEquals(expectedMillis, resultMillis);
    }
}
