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
                // bias towards bug-triggering inputs
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
        int fieldIdx = spec[0];
        int year = spec[1];
        int month = spec[2];
        int day = spec[3];
        int hour = spec[4];
        int minute = spec[5];
        int second = spec[6];
        int ms = spec[7];

        boolean validIf = spec.length == 8
                && fieldIdx >= 0 && fieldIdx <= 3
                && year >= 1971 && year <= 2100
                && month >= 0 && month <= 11
                && day >= 1 && day <= 28
                && hour >= 0 && hour <= 23
                && minute >= 0 && minute <= 59
                && second >= 0 && second <= 59
                && ms >= 0 && ms <= 999;
        boolean buggyIf = validIf && (
                (fieldIdx == 2 && second >= 30 && minute >= 1 && minute <= 29)
                || (fieldIdx == 1 && ms >= 500
                    && ((second >= 1 && second < 30) || (minute >= 1 && minute < 30))));

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        int field = FIELDS[fieldIdx];
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        cal.clear();
        cal.set(year, month, day, hour, minute, second);
        cal.set(Calendar.MILLISECOND, ms);
        Date date = cal.getTime();
        long input = date.getTime();

        Date rounded = DateUtils.round(date, field);
        long result = rounded.getTime();

        Log.logOutIf(true, () -> new Object[]{ Long.valueOf(result), Long.valueOf(input) });

        long unit = (field == Calendar.SECOND) ? 1000L : 60000L;
        long floor = input - (((input % unit) + unit) % unit);
        long expected = floor + unit; // both bug cases round up
        assertEquals(expected, result);
    }
}
