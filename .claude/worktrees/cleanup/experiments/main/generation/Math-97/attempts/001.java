package org.apache.commons.math.analysis;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.apache.commons.math.analysis.BrentSolver;
import org.apache.commons.math.analysis.UnivariateRealFunction;

@RunWith(JQF.class)
public class JQF_BrentSolverTest {

    private static double rootOf(int min, int max, int mode, double frac) {
        if (mode == 0) return (double) min;
        if (mode == 1) return (double) max;
        return min + frac * (max - min);
    }

    @Fuzz
    public void testBugReproduction(@InRange(minInt = -100, maxInt = 100) int min,
                         @InRange(minInt = -100, maxInt = 100) int max,
                         @InRange(minDouble = -10, maxDouble = 10) double slope,
                         @InRange(minInt = 0, maxInt = 4) int mode,
                         @InRange(minDouble = -1, maxDouble = 2) double frac) {
        final double root = rootOf(min, max, mode, frac);
        final double s = slope;
        boolean validIf = min < max && s != 0.0;
        boolean buggyIf = validIf && ((s * ((double) min - root)) == 0.0 || (s * ((double) max - root)) == 0.0);

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        UnivariateRealFunction fn = new UnivariateRealFunction() {
            public double value(double x) {
                return s * (x - root);
            }
        };
        BrentSolver solver = new BrentSolver(fn);
        double result = Double.NaN;
        String exc = "none";
        try {
            result = solver.solve((double) min, (double) max);
        } catch (Throwable t) {
            exc = t.getClass().getName();
        }

        final double fResult = result;
        final String fExc = exc;
        Log.logOutIf(true, () -> new String[]{ fExc, String.valueOf(fResult) });
        double expectedRoot = ((s * ((double) min - root)) == 0.0) ? (double) min : (double) max;
        assertEquals("none", fExc);
        assertEquals(expectedRoot, fResult, 1e-6);
    }
}
