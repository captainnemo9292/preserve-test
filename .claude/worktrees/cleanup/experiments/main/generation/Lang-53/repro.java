package org.apache.commons.lang.time;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

@RunWith(JQF.class)
public class JQF_DateUtilsTest {

    /** Generates {fieldIndex, year, month, day, hour, minute, second, millis}. */
    public static class DateSpecGen extends Generator<int[]> {
        public DateSpecGen() { super(int[].class); }

        @Override
        public int[] generate(SourceOfRandomness r, GenerationStatus status) {
            int field = r.nextInt(0, 3); // 0 ms, 1 second, 2 minute, 3 hour
            int year = r.nextInt(1971, 2100);
            int month = r.nextInt(0, 11);
            int day = r.nextInt(1, 28);
            int hour = r.nextInt(0, 23);
            int minute = r.nextInt(0, 59);
            int second = r.nextInt(0, 59);
            int ms = r.nextInt(0, 999);
            if (r.nextInt(0, 9) < 4) {
                if (r.nextBoolean()) {
                    field = 2;
                    second = r.nextInt(30, 59);
                    minute = r.nextInt(1, 29);
                } else {
                    field = 1;
                    ms = r.nextInt(500, 999);
                    if (r.nextBoolean()) {
                        minute = r.nextInt(1, 29);
                    } else {
                        second = r.nextInt(1, 29);
                    }
                }
            }
            return new int[]{field, year, month, day, hour, minute, second, ms};
        }
    }

    private static final int[] FIELDS = {
        Calendar.MILLISECOND, Calendar.SECOND, Calendar.MINUTE, Calendar.HOUR_OF_DAY
    };

    @Fuzz
    public void testBugReproduction(@From(DateSpecGen.class) int[] spec) {
        boolean validIf = spec != null && spec.length == 8
                && spec[0] >= 0 && spec[0] <= 3
                && spec[1] >= 1971 && spec[1] <= 2100
                && spec[2] >= 0 && spec[2] <= 11
                && spec[3] >= 1 && spec[3] <= 28
                && spec[4] >= 0 && spec[4] <= 23
                && spec[5] >= 0 && spec[5] <= 59
                && spec[6] >= 0 && spec[6] <= 59
                && spec[7] >= 0 && spec[7] <= 999;
        boolean buggyIf = validIf && (
                (spec[0] == 2 && spec[6] >= 30 && spec[5] >= 1 && spec[5] <= 29)
                || (spec[0] == 1 && spec[7] >= 500
                    && ((spec[6] >= 1 && spec[6] < 30) || (spec[5] >= 1 && spec[5] < 30))));

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        int field = FIELDS[spec[0]];
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        cal.clear();
        cal.set(spec[1], spec[2], spec[3], spec[4], spec[5], spec[6]);
        cal.set(Calendar.MILLISECOND, spec[7]);
        Date date = cal.getTime();
        long input = date.getTime();

        Date rounded = DateUtils.round(date, field);
        long result = rounded.getTime();

        Log.logOutIf(true, () -> new Object[]{ Long.valueOf(result), Long.valueOf(input) });

        long unit = (field == Calendar.SECOND) ? 1000L : 60000L;
        long floor = input - (((input % unit) + unit) % unit);
        long expected = floor + unit;
        assertEquals(expected, result);
    }
}
