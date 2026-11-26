package org.joda.time.tz;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.joda.time.DateTimeZone;

@RunWith(JQF.class)
public class JQF_TestCompiler {

    public static class NameGen extends Generator<String> {
        public NameGen() { super(String.class); }
        @Override public String generate(SourceOfRandomness r, GenerationStatus status) {
            String[] names = new String[] {"", "X", "EST"};
            // bias towards the empty name so that duplicates are frequent
            if (r.nextInt(100) < 60) {
                return names[0];
            }
            return names[r.nextInt(names.length)];
        }
    }

    private static DateTimeZoneBuilder build(String name1, String name2, int stdHours,
                                             int save1, int save2, int month1, int month2,
                                             int toYear, int time1, int time2) {
        return new DateTimeZoneBuilder()
            .addCutover(1601, 'w', 1, 1, 1, false, 7200000)
            .setStandardOffset(stdHours * 3600000)
            .addRecurringSavings(name1, save1, 1601, toYear, 'w', month1, -1, 1, false, time1)
            .addRecurringSavings(name2, save2, 1601, toYear, 'w', month2, -1, 1, false, time2);
    }

    @Fuzz
    public void testBugReproduction(@From(NameGen.class) String name1,
                                    @From(NameGen.class) String name2,
                                    @InRange(minInt = -12, maxInt = 15) int stdHours,
                                    @InRange(minInt = 1, maxInt = 3) int saveHours,
                                    @InRange(minInt = 1, maxInt = 13) int month1,
                                    @InRange(minInt = 1, maxInt = 13) int month2,
                                    boolean forever,
                                    @InRange(minInt = 0, maxInt = 24) int hour1,
                                    @InRange(minInt = 0, maxInt = 24) int hour2) {
        boolean validIf = month1 != month2;
        boolean buggyIf = validIf && forever && name1.equals(name2);

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        final int toYear = forever ? Integer.MAX_VALUE : 2000;
        final int save1 = saveHours * 3600000;
        final int save2 = 0;
        final int time1 = hour1 * 3600000;
        final int time2 = hour2 * 3600000;

        String firstError = "none";
        String firstZone = "null";
        try {
            DateTimeZone z = build(name1, name2, stdHours, save1, save2, month1, month2,
                                   toYear, time1, time2).toDateTimeZone("TestDTZ1", true);
            firstZone = z.getID();
        } catch (Throwable t) {
            firstError = t.getClass().getName();
        }

        final String[] secondError = new String[] {"none"};
        final String[] secondZone = new String[] {"null"};
        final String n1 = name1, n2 = name2;
        final int sh = stdHours, m1 = month1, m2 = month2;
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    DateTimeZone z = build(n1, n2, sh, save1, save2, m1, m2,
                                           toYear, time1, time2).toDateTimeZone("TestDTZ2", true);
                    secondZone[0] = z.getID();
                } catch (Throwable t) {
                    secondError[0] = t.getClass().getName();
                }
            }
        });
        th.start();
        try {
            th.join();
        } catch (InterruptedException e) {
            fail("interrupted");
        }

        final String fe = firstError, fz = firstZone;
        Log.logOutIf(true, () -> new String[]{ fe, fz, secondError[0], secondZone[0] });
        assertEquals("none", fe);
        assertEquals("none", secondError[0]);
        assertEquals("TestDTZ2", secondZone[0]);
    }

    @Fuzz
    public void testDateTimeZoneBuilder(@From(NameGen.class) String name1,
                                    @From(NameGen.class) String name2,
                                    @InRange(minInt = -12, maxInt = 15) int stdHours,
                                    @InRange(minInt = 1, maxInt = 3) int saveHours,
                                    @InRange(minInt = 1, maxInt = 13) int month1,
                                    @InRange(minInt = 1, maxInt = 13) int month2,
                                    boolean forever,
                                    @InRange(minInt = 0, maxInt = 24) int hour1,
                                    @InRange(minInt = 0, maxInt = 24) int hour2) {
        boolean validIf = month1 != month2;
        boolean buggyIf = validIf && forever && name1.equals(name2);
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            final int toYear = forever ? Integer.MAX_VALUE : 2000;
            final int save1 = saveHours * 3600000;
            final int save2 = 0;
            final int time1 = hour1 * 3600000;
            final int time2 = hour2 * 3600000;

            String firstError = "none";
            String firstZone = "null";
            try {
                DateTimeZone z = build(name1, name2, stdHours, save1, save2, month1, month2,
                                       toYear, time1, time2).toDateTimeZone("TestDTZ1", true);
                firstZone = z.getID();
            } catch (Throwable t) {
                firstError = t.getClass().getName();
            }

            final String[] secondError = new String[] {"none"};
            final String[] secondZone = new String[] {"null"};
            final String n1 = name1, n2 = name2;
            final int sh = stdHours, m1 = month1, m2 = month2;
            Thread th = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        DateTimeZone z = build(n1, n2, sh, save1, save2, m1, m2,
                                               toYear, time1, time2).toDateTimeZone("TestDTZ2", true);
                        secondZone[0] = z.getID();
                    } catch (Throwable t) {
                        secondError[0] = t.getClass().getName();
                    }
                }
            });
            th.start();
            try {
                th.join();
            } catch (InterruptedException e) {
                fail("interrupted");
            }

            final String fe = firstError, fz = firstZone;
            final boolean fv = forever;
            final boolean same = name1.equals(name2);
            Log.logOutIf(preserveIf, () -> new String[]{ fe, fz, secondError[0], secondZone[0],
                "forever=" + fv, "sameName=" + same, "n1len=" + n1.length(), "n2len=" + n2.length(),
                "m1=" + m1, "m2=" + m2, "toYear=" + toYear });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
