package org.jfree.data.xy.junit;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.jfree.data.xy.XYSeries;

@RunWith(JQF.class)
public class JQF_XYSeriesTests {

    @Fuzz
    public void testBugReproduction(boolean autoSort,
                                    boolean allowDuplicates,
                                    @InRange(minInt = -5, maxInt = 6) int x1,
                                    @InRange(minInt = -5, maxInt = 6) int x2,
                                    @InRange(minInt = -5, maxInt = 6) int x3,
                                    @InRange(minDouble = 0, maxDouble = 1) double y1,
                                    @InRange(minDouble = 0, maxDouble = 1) double y2,
                                    @InRange(minDouble = 0, maxDouble = 1) double y3) {
        boolean validIf = true;
        boolean buggyIf = autoSort && allowDuplicates && (x1 == x2 || x1 == x3 || x2 == x3);

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        XYSeries series = new XYSeries("S", autoSort, allowDuplicates);
        String error = "none";
        try {
            series.addOrUpdate((double) x1, y1);
            series.addOrUpdate((double) x2, y2);
            series.addOrUpdate((double) x3, y3);
        } catch (Throwable t) {
            error = t.getClass().getName();
        }
        final String err = error;
        final int count = series.getItemCount();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append("(").append(series.getX(i)).append(",").append(series.getY(i)).append(")");
        }
        final String items = sb.toString();

        Log.logOutIf(true, () -> new Object[]{ err, count, items });
        assertEquals("none", err);
        assertEquals(3, count);
    }

    @Fuzz
    public void testBug1955483(boolean autoSort,
                                    boolean allowDuplicates,
                                    @InRange(minInt = -5, maxInt = 6) int x1,
                                    @InRange(minInt = -5, maxInt = 6) int x2,
                                    @InRange(minInt = -5, maxInt = 6) int x3,
                                    @InRange(minDouble = 0, maxDouble = 1) double y1,
                                    @InRange(minDouble = 0, maxDouble = 1) double y2,
                                    @InRange(minDouble = 0, maxDouble = 1) double y3) {
        boolean validIf = true;
        boolean buggyIf = autoSort && allowDuplicates && (x1 == x2 || x1 == x3 || x2 == x3);
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            XYSeries series = new XYSeries("S", autoSort, allowDuplicates);
            String error = "none";
            try {
                series.addOrUpdate((double) x1, y1);
                series.addOrUpdate((double) x2, y2);
                series.addOrUpdate((double) x3, y3);
            } catch (Throwable t) {
                error = t.getClass().getName();
            }
            final String err = error;
            final int count = series.getItemCount();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < count; i++) {
                sb.append("(").append(series.getX(i)).append(",").append(series.getY(i)).append(")");
            }
            final String items = sb.toString();

            Log.logOutIf(preserveIf, () -> new Object[]{ err, count, items });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
