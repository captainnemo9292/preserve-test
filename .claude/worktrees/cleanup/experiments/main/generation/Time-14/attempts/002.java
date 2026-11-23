package org.joda.time;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.joda.time.chrono.ISOChronology;

@RunWith(JQF.class)
public class JQF_TestMonthDay_Basics {

    public static class MonthGen extends Generator<Integer> {
        public MonthGen() { super(Integer.class); }
        @Override public Integer generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextInt(10) < 6) {
                return 2;
            }
            return r.nextInt(1, 12);
        }
    }

    public static class DayGen extends Generator<Integer> {
        public DayGen() { super(Integer.class); }
        @Override public Integer generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextInt(10) < 6) {
                return 29;
            }
            return r.nextInt(1, 31);
        }
    }

    @Fuzz
    public void testBugReproduction(@From(MonthGen.class) int month,
                         @From(DayGen.class) int day,
                         @InRange(minInt = 0, maxInt = 2) int op,
                         @InRange(minInt = -100, maxInt = 101) int amount) {
        boolean validIf = month >= 1 && month <= 12 && day >= 1
                && day <= (month == 2 ? 29 : ((month == 4 || month == 6 || month == 9 || month == 11) ? 30 : 31))
                && (op == 0 || op == 1);
        boolean buggyIf = month == 2 && day == 29
                && ((op == 0 && amount >= 1) || (op == 1 && (amount == 1 || amount == -1)));

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        MonthDay md = new MonthDay(month, day, ISOChronology.getInstanceUTC());
        String result = null;
        String exception = "none";
        try {
            MonthDay r = (op == 0) ? md.plusDays(amount) : md.plusMonths(amount);
            result = r.toString();
        } catch (Exception e) {
            exception = e.getClass().getName();
        }

        final String fResult = result;
        final String fException = exception;
        Log.logOutIf(true, () -> new String[]{ md.toString(), String.valueOf(fResult), fException });

        LocalDate base = new LocalDate(2000, 2, 29);
        LocalDate expDate = (op == 0) ? base.plusDays(amount) : base.plusMonths(amount);
        String expected = new MonthDay(expDate.getMonthOfYear(), expDate.getDayOfMonth(),
                ISOChronology.getInstanceUTC()).toString();
        assertEquals("none", fException);
        assertEquals(expected, fResult);
    }
}
