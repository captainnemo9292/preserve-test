package org.jfree.data.time.junit;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.jfree.data.time.Day;
import org.jfree.data.time.RegularTimePeriod;
import org.jfree.data.time.TimeSeries;

@RunWith(JQF.class)
public class JQF_TimeSeriesTests {

    /** Generates a sorted array of distinct day offsets in [0, 40). */
    public static class DaysGen extends Generator<int[]> {
        public DaysGen() { super(int[].class); }

        @Override
        public int[] generate(SourceOfRandomness r, GenerationStatus status) {
            int n = r.nextInt(0, 7);
            boolean[] used = new boolean[40];
            int count = 0;
            for (int i = 0; i < n; i++) {
                int v = r.nextInt(0, 40);
                if (!used[v]) {
                    used[v] = true;
                    count++;
                }
            }
            int[] out = new int[count];
            int k = 0;
            for (int i = 0; i < 40; i++) {
                if (used[i]) {
                    out[k++] = i;
                }
            }
            return out;
        }
    }

    private static Day dayAt(int offset) {
        Day d = new Day(1, 1, 2005);
        for (int i = 0; i < offset; i++) {
            d = (Day) d.next();
        }
        return d;
    }

    @Fuzz
    public void testBugReproduction(@From(DaysGen.class) int[] days,
                                    @InRange(minInt = 0, maxInt = 40) int s,
                                    @InRange(minInt = 0, maxInt = 40) int e) {
        boolean validIf = s <= e;

        boolean anyBefore = false;
        boolean anyAfter = false;
        boolean anyInside = false;
        for (int d : days) {
            if (d < s) anyBefore = true;
            if (d > e) anyAfter = true;
            if (d >= s && d <= e) anyInside = true;
        }
        boolean buggyIf = anyBefore && anyAfter && !anyInside;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        TimeSeries series = new TimeSeries("foo", Day.class);
        for (int d : days) {
            series.add(dayAt(d), 1.0);
        }
        String exceptionName = "none";
        TimeSeries copy = null;
        try {
            copy = series.createCopy(dayAt(s), dayAt(e));
        } catch (Exception ex) {
            exceptionName = ex.getClass().getName();
        }
        final String exName = exceptionName;
        final int copySize = copy == null ? -1 : copy.getItemCount();
        final int origSize = series.getItemCount();
        Log.logOutIf(true, () -> new Object[]{ exName, copySize, origSize });
        assertEquals("none", exName);
        assertEquals(0, copySize);
    }
}
