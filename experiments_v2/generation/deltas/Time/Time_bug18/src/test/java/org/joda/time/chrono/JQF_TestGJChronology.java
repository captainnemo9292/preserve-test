package org.joda.time.chrono;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.joda.time.DateTime;

@RunWith(JQF.class)
public class JQF_TestGJChronology {

    public static class YearGen extends Generator<Integer> {
        public YearGen() { super(Integer.class); }
        @Override public Integer generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextInt(100) < 45) {
                // century years before cutover
                return 100 * (r.nextInt(15) + 1);
            }
            return r.nextInt(1, 2400);
        }
    }

    public static class MonthGen extends Generator<Integer> {
        public MonthGen() { super(Integer.class); }
        @Override public Integer generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextInt(100) < 50) return 2;
            return r.nextInt(1, 12);
        }
    }

    public static class DayGen extends Generator<Integer> {
        public DayGen() { super(Integer.class); }
        @Override public Integer generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextInt(100) < 50) return 29;
            return r.nextInt(1, 31);
        }
    }

    private static int daysInMonth(int year, int month) {
        boolean leap;
        if (year < 1582) {
            leap = year % 4 == 0;
        } else {
            leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
        }
        switch (month) {
            case 2: return leap ? 29 : 28;
            case 4: case 6: case 9: case 11: return 30;
            default: return 31;
        }
    }

    @Fuzz
    public void testBugReproduction(@From(YearGen.class) Integer year,
                                    @From(MonthGen.class) Integer month,
                                    @From(DayGen.class) Integer day) {
        boolean validIf = year >= 1 && year <= 2400 && year != 1582
                && month >= 1 && month <= 12
                && day >= 1 && day <= daysInMonth(year, month);
        boolean buggyIf = year < 1582 && year % 100 == 0 && year % 400 != 0
                && month == 2 && day == 29;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        String exc = "none";
        int gotYear = -1, gotMonth = -1, gotDay = -1;
        try {
            DateTime dt = new DateTime(year, month, day, 0, 0, 0, 0, GJChronology.getInstanceUTC());
            gotYear = dt.getYear();
            gotMonth = dt.getMonthOfYear();
            gotDay = dt.getDayOfMonth();
        } catch (Exception e) {
            exc = e.getClass().getName();
        }

        final String fexc = exc;
        final int fy = gotYear, fm = gotMonth, fd = gotDay;
        Log.logOutIf(true, () -> new String[]{ fexc, String.valueOf(fy), String.valueOf(fm), String.valueOf(fd) });
        assertEquals("none", fexc);
        assertEquals(year.intValue(), fy);
        assertEquals(month.intValue(), fm);
        assertEquals(day.intValue(), fd);
    }

    @Fuzz
    public void testLeapYearRulesConstruction(@From(YearGen.class) Integer year,
                                    @From(MonthGen.class) Integer month,
                                    @From(DayGen.class) Integer day) {
        boolean validIf = year >= 1 && year <= 2400 && year != 1582
                && month >= 1 && month <= 12
                && day >= 1 && day <= daysInMonth(year, month);
        boolean buggyIf = year < 1582 && year % 100 == 0 && year % 400 != 0
                && month == 2 && day == 29;
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            String exc = "none";
            int gotYear = -1, gotMonth = -1, gotDay = -1;
            try {
                DateTime dt = new DateTime(year, month, day, 0, 0, 0, 0, GJChronology.getInstanceUTC());
                gotYear = dt.getYear();
                gotMonth = dt.getMonthOfYear();
                gotDay = dt.getDayOfMonth();
            } catch (Exception e) {
                exc = e.getClass().getName();
            }

            final String fexc = exc;
            final int fy = gotYear, fm = gotMonth, fd = gotDay;
            final int iy = year.intValue(), im = month.intValue(), id = day.intValue();
            final boolean match = fy == iy && fm == im && fd == id;
            Log.logOutIf(preserveIf, () -> new String[]{ fexc, String.valueOf(fy), String.valueOf(fm), String.valueOf(fd),
                    "in=" + iy + "-" + im + "-" + id, "match=" + match });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            final String ein = year + "-" + month + "-" + day;
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString(), __e.getClass().getName(), "in=" + ein });
        }
    }
}
