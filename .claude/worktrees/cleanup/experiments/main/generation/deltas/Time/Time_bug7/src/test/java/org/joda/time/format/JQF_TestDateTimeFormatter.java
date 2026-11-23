package org.joda.time.format;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

import java.util.Locale;

import org.joda.time.DateTimeZone;
import org.joda.time.MutableDateTime;

@RunWith(JQF.class)
public class JQF_TestDateTimeFormatter {

    static int daysIn(int year, int month) {
        switch (month) {
            case 2:
                boolean leap = (year % 4 == 0) && (year % 100 != 0 || year % 400 == 0);
                return leap ? 29 : 28;
            case 4: case 6: case 9: case 11:
                return 30;
            default:
                return 31;
        }
    }

    /** Produces {year, offsetHours, instMonth, instDay, hour, minute, parseMonth, parseDay}. */
    public static class CaseGen extends Generator<int[]> {
        public CaseGen() { super(int[].class); }

        @Override
        public int[] generate(SourceOfRandomness r, GenerationStatus status) {
            int year = r.nextInt(1900, 2100);
            int off = r.nextInt(-12, 14);
            int im, id, hour;
            int mode = r.nextInt(0, 9);
            if (mode <= 2) {
                off = r.nextInt(-12, -1);
                im = 1;
                id = 1;
                hour = r.nextInt(0, -off - 1);
            } else if (mode <= 4) {
                off = r.nextInt(1, 14);
                im = 12;
                id = 31;
                hour = r.nextInt(24 - off, 23);
            } else {
                im = r.nextInt(1, 12);
                id = r.nextInt(1, daysIn(year, im));
                hour = r.nextInt(0, 23);
            }
            int minute = r.nextInt(0, 59);
            int pm = r.nextInt(1, 12);
            int pd = r.nextInt(1, daysIn(year, pm));
            if (r.nextInt(0, 3) == 0 && daysIn(year, 2) == 29) {
                pm = 2;
                pd = 29;
            }
            return new int[] {year, off, im, id, hour, minute, pm, pd};
        }
    }

    @Fuzz
    public void testBugReproduction(@From(CaseGen.class) int[] c) {
        boolean validIf = c != null && c.length == 8
                && c[0] >= 1900 && c[0] <= 2100
                && c[1] >= -12 && c[1] <= 14
                && c[2] >= 1 && c[2] <= 12
                && c[3] >= 1 && c[3] <= daysIn(c[0], c[2])
                && c[4] >= 0 && c[4] <= 23
                && c[5] >= 0 && c[5] <= 59
                && c[6] >= 1 && c[6] <= 12
                && c[7] >= 1 && c[7] <= daysIn(c[0], c[6]);
        boolean buggyIf = c != null && c.length == 8
                && ((c[1] < 0 && c[2] == 1 && c[3] == 1 && c[4] < -c[1])
                || (c[1] > 0 && c[2] == 12 && c[3] == 31 && c[4] >= 24 - c[1]));

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        DateTimeZone zone = DateTimeZone.forOffsetHours(c[1]);
        DateTimeFormatter f = DateTimeFormat.forPattern("M d").withLocale(Locale.UK);
        MutableDateTime result = new MutableDateTime(c[0], c[2], c[3], c[4], c[5], 0, 0, zone);
        String text = c[6] + " " + c[7];
        int ret = -1;
        String exc = "none";
        try {
            ret = f.parseInto(result, text, 0);
        } catch (Exception e) {
            exc = e.getClass().getName();
        }
        final int fret = ret;
        final String fexc = exc;
        Log.logOutIf(true, () -> new Object[]{ fret, fexc, result.toString(), result.getMillis(), result.getYear() });

        MutableDateTime expected = new MutableDateTime(c[0], c[6], c[7], c[4], c[5], 0, 0, zone);
        assertEquals("none", fexc);
        assertEquals(text.length(), fret);
        assertEquals(c[0], result.getYear());
        assertEquals(expected, result);
    }

    @Fuzz
    public void testParseInto_monthDay_feb29_tokyo_endOfYear(@From(CaseGen.class) int[] c) {
        boolean validIf = c != null && c.length == 8
                && c[0] >= 1900 && c[0] <= 2100
                && c[1] >= -12 && c[1] <= 14
                && c[2] >= 1 && c[2] <= 12
                && c[3] >= 1 && c[3] <= daysIn(c[0], c[2])
                && c[4] >= 0 && c[4] <= 23
                && c[5] >= 0 && c[5] <= 59
                && c[6] >= 1 && c[6] <= 12
                && c[7] >= 1 && c[7] <= daysIn(c[0], c[6]);
        boolean buggyIf = c != null && c.length == 8
                && ((c[1] < 0 && c[2] == 1 && c[3] == 1 && c[4] < -c[1])
                || (c[1] > 0 && c[2] == 12 && c[3] == 31 && c[4] >= 24 - c[1]));
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            DateTimeZone zone = DateTimeZone.forOffsetHours(c[1]);
            DateTimeFormatter f = DateTimeFormat.forPattern("M d").withLocale(Locale.UK);
            MutableDateTime result = new MutableDateTime(c[0], c[2], c[3], c[4], c[5], 0, 0, zone);
            String text = c[6] + " " + c[7];
            int ret = -1;
            String exc = "none";
            try {
                ret = f.parseInto(result, text, 0);
            } catch (Exception e) {
                exc = e.getClass().getName();
            }
            final int fret = ret;
            final String fexc = exc;
            Log.logOutIf(preserveIf, () -> new Object[]{ fret, fexc, result.toString(), result.getMillis(), result.getYear() });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
