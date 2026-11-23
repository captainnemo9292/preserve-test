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

    /** Generates {year, month, day, hour, minute, second}. */
    public static class LocalFieldsGen extends Generator<int[]> {
        public LocalFieldsGen() { super(int[].class); }

        @Override
        public int[] generate(SourceOfRandomness r, GenerationStatus status) {
            int year = r.nextInt(1997, 2037);
            int month;
            int day;
            int hour;
            int choice = r.nextInt(0, 9);
            if (choice < 4) {
                // London autumn overlap hour
                month = 10;
                day = lastSunday(year, 10);
                hour = 1;
            } else if (choice < 6) {
                // near misses: same day, other hours, or spring transition day
                boolean spring = r.nextBoolean();
                month = spring ? 3 : 10;
                day = lastSunday(year, month);
                hour = r.nextInt(0, 23);
            } else if (choice < 7) {
                month = 10;
                day = r.nextInt(1, YearMonth.of(year, 10).lengthOfMonth());
                hour = r.nextInt(0, 23);
            } else {
                month = r.nextInt(1, 12);
                day = r.nextInt(1, YearMonth.of(year, month).lengthOfMonth());
                hour = r.nextInt(0, 23);
            }
            int minute = r.nextInt(0, 59);
            int second = r.nextInt(0, 59);
            return new int[] {year, month, day, hour, minute, second};
        }
    }

    @Fuzz
    public void testBugReproduction(@From(LocalFieldsGen.class) int[] f) {
        int year = f[0];
        int month = f[1];
        int day = f[2];
        int hour = f[3];
        int minute = f[4];
        int second = f[5];

        boolean validIf = year >= 1997 && year <= 2037
                && month >= 1 && month <= 12
                && day >= 1 && day <= YearMonth.of(year, month).lengthOfMonth()
                && hour >= 0 && hour <= 23
                && minute >= 0 && minute <= 59
                && second >= 0 && second <= 59
                // local times inside the spring DST gap do not exist (illegal)
                && !(month == 3 && day == lastSunday(year, 3) && hour == 1);
        boolean buggyIf = month == 10 && day == lastSunday(year, 10) && hour == 1;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        DateTimeZone london = DateTimeZone.forID("Europe/London");
        DateTime dt = new DateTime(year, month, day, hour, minute, second, 0, london);
        long millis = dt.getMillis();
        int offset = dt.getZone().getOffset(dt.getMillis());

        Log.logOutIf(true, () -> new Object[]{ dt.toString(), millis, offset,
                dt.getHourOfDay(), dt.getMinuteOfHour(), dt.getSecondOfMinute() });
        long expectedMillis = new DateTime(year, month, day, hour, minute, second, 0, DateTimeZone.UTC)
                .getMillis() - 3600000L;
        assertEquals(3600000, offset);
        assertEquals(expectedMillis, millis);
    }
}
