package org.joda.time;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import java.util.Calendar;
import java.util.GregorianCalendar;
import org.junit.runner.RunWith;
import anonymous.log.Log;

@RunWith(JQF.class)
public class JQF_TestLocalDate_Constructors {

    @Fuzz
    public void testBugReproduction(@InRange(minInt = 1, maxInt = 2001) int yearOfEra,
                                    @InRange(minInt = 1, maxInt = 13) int month,
                                    @InRange(minInt = 1, maxInt = 29) int day,
                                    boolean bc) {
        boolean validIf = yearOfEra >= 1 && month >= 1 && month <= 12 && day >= 1 && day <= 28;
        boolean buggyIf = bc;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        GregorianCalendar cal = new GregorianCalendar(yearOfEra, month - 1, day);
        cal.set(Calendar.ERA, GregorianCalendar.BC);
        String excName = "none";
        LocalDate result = null;
        try {
            result = LocalDate.fromCalendarFields(cal);
        } catch (Exception e) {
            excName = e.getClass().getName();
        }
        final String exc = excName;
        final LocalDate res = result;

        Log.logOutIf(true, () -> new Object[]{
            exc,
            res == null ? "null" : res.toString(),
            res == null ? -999999 : res.getYear(),
            res == null ? -999999 : res.getMonthOfYear(),
            res == null ? -999999 : res.getDayOfMonth()
        });
        int expectedYear = 1 - yearOfEra;
        assertEquals("none", exc);
        assertNotNull(res);
        assertEquals(expectedYear, res.getYear());
        assertEquals(month, res.getMonthOfYear());
        assertEquals(day, res.getDayOfMonth());
    }

    @Fuzz
    public void testFactory_fromCalendarFields_beforeYearZero1(@InRange(minInt = 1, maxInt = 2001) int yearOfEra,
                                    @InRange(minInt = 1, maxInt = 13) int month,
                                    @InRange(minInt = 1, maxInt = 29) int day,
                                    boolean bc) {
        boolean validIf = yearOfEra >= 1 && month >= 1 && month <= 12 && day >= 1 && day <= 28;
        boolean buggyIf = bc;
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            GregorianCalendar cal = new GregorianCalendar(yearOfEra, month - 1, day);
            cal.set(Calendar.ERA, GregorianCalendar.BC);
            String excName = "none";
            LocalDate result = null;
            try {
                result = LocalDate.fromCalendarFields(cal);
            } catch (Exception e) {
                excName = e.getClass().getName();
            }
            final String exc = excName;
            final LocalDate res = result;

            Log.logOutIf(preserveIf, () -> new Object[]{
                exc,
                res == null ? "null" : res.toString(),
                res == null ? -999999 : res.getYear(),
                res == null ? -999999 : res.getMonthOfYear(),
                res == null ? -999999 : res.getDayOfMonth(),
                yearOfEra,
                month,
                day,
                cal.get(Calendar.ERA),
                cal.get(Calendar.YEAR),
                res == null ? -999999 : res.hashCode(),
                res == null ? -999999 : res.getYear() - (1 - yearOfEra),
                res == null ? "null" : res.getChronology().toString(),
                res == null ? -999999 : res.getYearOfEra(),
                res == null ? -999999 : res.getEra()
            });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
