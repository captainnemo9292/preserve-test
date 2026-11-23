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
                // start of year, negative offset
                off = r.nextInt(-12, -1);
                im = 1;
                id = 1;
                hour = r.nextInt(0, -off - 1);
            } else if (mode <= 4) {
                // end of year, positive offset
                off = r.nextInt(1, 14);
                im = 12;
                id = 31;
                hour = r.nextInt(24 - off, 23);
            } else {
                im = r.nextInt(1, 12);
                id = r.nextInt(1, 31);
                hour = r.nextInt(0, 23);
            }
            int minute = r.nextInt(0, 59);
            int pm = r.nextInt(1, 12);
            int pd = r.nextInt(1, 31);
            if (r.nextInt(0, 3) == 0) {
                pm = 2;
                pd = 29;
            }
            return new int[] {year, off, im, id, hour, minute, pm, pd};
        }
    }

    @Fuzz
    public void testBugReproduction(@From(CaseGen.class) int[] c) {
        int year = c[0];
        int off = c[1];
        int im = c[2];
        int id = c[3];
        int hour = c[4];
        int minute = c[5];
        int pm = c[6];
        int pd = c[7];

        boolean validIf = year >= 1900 && year <= 2100
                && off >= -12 && off <= 14
                && im >= 1 && im <= 12
                && id >= 1 && id <= daysIn(year, im)
                && hour >= 0 && hour <= 23
                && minute >= 0 && minute <= 59
                && pm >= 1 && pm <= 12
                && pd >= 1 && pd <= daysIn(year, pm);
        boolean buggyIf = (off < 0 && im == 1 && id == 1 && hour < -off)
                || (off > 0 && im == 12 && id == 31 && hour >= 24 - off);

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        DateTimeZone zone = DateTimeZone.forOffsetHours(off);
        DateTimeFormatter f = DateTimeFormat.forPattern("M d").withLocale(Locale.UK);
        MutableDateTime result = new MutableDateTime(year, im, id, hour, minute, 0, 0, zone);
        String text = pm + " " + pd;
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

        MutableDateTime expected = new MutableDateTime(year, pm, pd, hour, minute, 0, 0, zone);
        assertEquals("none", fexc);
        assertEquals(text.length(), fret);
        assertEquals(year, result.getYear());
        assertEquals(expected, result);
    }
}
