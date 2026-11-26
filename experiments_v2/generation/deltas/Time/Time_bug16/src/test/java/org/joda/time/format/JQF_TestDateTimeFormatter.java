package org.joda.time.format;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.joda.time.DateTimeZone;
import org.joda.time.MutableDateTime;
import org.joda.time.chrono.ISOChronology;

@RunWith(JQF.class)
public class JQF_TestDateTimeFormatter {

    private static boolean isLeap(int y) {
        return (y % 4 == 0 && y % 100 != 0) || y % 400 == 0;
    }

    private static int daysIn(int month, int year) {
        switch (month) {
            case 2: return isLeap(year) ? 29 : 28;
            case 4: case 6: case 9: case 11: return 30;
            default: return 31;
        }
    }

    @Fuzz
    public void testBugReproduction(@InRange(minInt = 1000, maxInt = 3000) int year,
                         @InRange(minInt = 1, maxInt = 13) int month,
                         @InRange(minInt = 1, maxInt = 32) int day) {
        boolean validIf = month >= 1 && month <= 12 && day >= 1 && day <= daysIn(month, year);
        boolean buggyIf = year != 2000;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        DateTimeFormatter f = DateTimeFormat.forPattern("MMdd").withZoneUTC();
        MutableDateTime mdt = new MutableDateTime(year, 1, 1, 0, 0, 0, 0, ISOChronology.getInstanceUTC());
        String text = String.format("%02d%02d", month, day);
        int pos = f.parseInto(mdt, text, 0);

        final int resYear = mdt.getYear();
        final int resMonth = mdt.getMonthOfYear();
        final int resDay = mdt.getDayOfMonth();
        Log.logOutIf(true, () -> new Object[]{ pos, resYear, resMonth, resDay, mdt.getMillis() });
        assertEquals(year, resYear);
        assertEquals(month, resMonth);
        assertEquals(day, resDay);
    }

    @Fuzz
    public void testParseInto_monthOnly_baseStartYear(@InRange(minInt = 1000, maxInt = 3000) int year,
                         @InRange(minInt = 1, maxInt = 13) int month,
                         @InRange(minInt = 1, maxInt = 32) int day) {
        boolean validIf = month >= 1 && month <= 12 && day >= 1 && day <= daysIn(month, year);
        boolean buggyIf = year != 2000;
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            DateTimeFormatter f = DateTimeFormat.forPattern("MMdd").withZoneUTC();
            MutableDateTime mdt = new MutableDateTime(year, 1, 1, 0, 0, 0, 0, ISOChronology.getInstanceUTC());
            String text = String.format("%02d%02d", month, day);
            int pos = f.parseInto(mdt, text, 0);

            final int resYear = mdt.getYear();
            final int resMonth = mdt.getMonthOfYear();
            final int resDay = mdt.getDayOfMonth();
            Log.logOutIf(preserveIf, () -> new Object[]{ pos, resYear, resMonth, resDay, mdt.getMillis(), year, month, day, text, mdt.getDayOfYear(), mdt.getDayOfWeek(), mdt.toString(), resYear == year, resMonth == month, resDay == day });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
