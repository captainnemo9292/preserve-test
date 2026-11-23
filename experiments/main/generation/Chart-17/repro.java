package org.jfree.data.time.junit;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.Year;

@RunWith(JQF.class)
public class JQF_TimeSeriesTests {

    public static class NameGen extends Generator<String> {
        public NameGen() { super(String.class); }
        @Override public String generate(SourceOfRandomness r, GenerationStatus status) {
            int len = r.nextInt(1, 8);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                sb.append((char) ('a' + r.nextInt(0, 26)));
            }
            return sb.toString();
        }
    }

    @Fuzz
    public void testBugReproduction(@From(NameGen.class) String name,
                                    @InRange(minInt = 0, maxInt = 7) int nItems,
                                    @InRange(minInt = 1900, maxInt = 2100) int startYear,
                                    @InRange(minDouble = -1000, maxDouble = 1000) double value) {
        boolean validIf = name != null && nItems >= 0 && startYear >= 1900 && startYear < 2100;
        boolean buggyIf = nItems == 0;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        TimeSeries series = new TimeSeries(name);
        for (int i = 0; i < nItems; i++) {
            series.add(new Year(startYear + i), value + i);
        }

        String exceptionName = "none";
        TimeSeries copy = null;
        try {
            copy = (TimeSeries) series.clone();
        } catch (Throwable t) {
            exceptionName = t.getClass().getName();
        }
        final String exName = exceptionName;
        final TimeSeries fCopy = copy;
        final int origCount = series.getItemCount();

        Log.logOutIf(true, () -> new Object[]{
            exName,
            fCopy == null ? "null" : "copy",
            fCopy == null ? -1 : fCopy.getItemCount(),
            origCount
        });
        assertEquals("none", exName);
        assertNotNull(fCopy);
        assertEquals(0, fCopy.getItemCount());
        assertEquals(0, origCount);
    }
}
