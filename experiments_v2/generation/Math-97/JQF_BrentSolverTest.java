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

    @Fuzz
    public void testBugReproduction(@InRange(minInt = -100, maxInt = 100) int min,
                         @InRange(minInt = -100, maxInt = 100) int max,
                         @InRange(minDouble = -10, maxDouble = 10) double slope,
                         @InRange(minInt = 0, maxInt = 4) int mode,
                         @InRange(minDouble = -1, maxDouble = 2) double frac) {
        boolean validIf = min < max && slope != 0.0;
        boolean buggyIf = min < max && slope != 0.0
                && (slope * ((double) min - (mode == 0 ? (double) min : (mode == 1 ? (double) max : min + frac * (max - min)))) == 0.0
                    || slope * ((double) max - (mode == 0 ? (double) min : (mode == 1 ? (double) max : min + frac * (max - min)))) == 0.0);

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        final double root = (mode == 0 ? (double) min : (mode == 1 ? (double) max : min + frac * (max - min)));
        final double s = slope;
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

    @Fuzz
    public void testRootEndpoints(@InRange(minInt = -100, maxInt = 100) int min,
                         @InRange(minInt = -100, maxInt = 100) int max,
                         @InRange(minDouble = -10, maxDouble = 10) double slope,
                         @InRange(minInt = 0, maxInt = 4) int mode,
                         @InRange(minDouble = -1, maxDouble = 2) double frac) {
        boolean validIf = min < max && slope != 0.0;
        boolean buggyIf = min < max && slope != 0.0
                && (slope * ((double) min - (mode == 0 ? (double) min : (mode == 1 ? (double) max : min + frac * (max - min)))) == 0.0
                    || slope * ((double) max - (mode == 0 ? (double) min : (mode == 1 ? (double) max : min + frac * (max - min)))) == 0.0);
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            final double root = (mode == 0 ? (double) min : (mode == 1 ? (double) max : min + frac * (max - min)));
            final double s = slope;
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
            Log.logOutIf(preserveIf, () -> new String[]{ fExc, String.valueOf(fResult),
                    Long.toHexString(Double.doubleToLongBits(fResult)),
                    String.valueOf(Math.abs(fResult - root) < 1e-6),
                    String.valueOf(fResult == (double) min),
                    String.valueOf(fResult == (double) max),
                    String.valueOf(fResult - root) });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString(), __e.getClass().getName() });
        }
    }
}
