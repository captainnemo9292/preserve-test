package org.apache.commons.math.analysis.solvers;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.apache.commons.math.analysis.UnivariateRealFunction;

@RunWith(JQF.class)
public class JQF_RegulaFalsiSolverTest {

    @Fuzz
    public void testBugReproduction(@InRange(minDouble = -5, maxDouble = 5) double min,
                                    @InRange(minDouble = 5, maxDouble = 20) double max,
                                    @InRange(minDouble = 1, maxDouble = 100) double c) {
        // legal: a proper bracket, f(min) < 0 < f(max) for f(x) = exp(x) - c
        boolean validIf = min < max && Math.exp(min) < c && c < Math.exp(max);
        // bug: after the first step the upper endpoint stays fixed and the other end
        // creeps to the root with linear ratio about c*(max-root)/(exp(max)-c); when that
        // ratio is small (< 0.1) far more than 100 evaluations are needed.
        boolean buggyIf = c * (max - Math.log(c)) < 0.1 * (Math.exp(max) - c);

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        final double cc = c;
        UnivariateRealFunction f = new UnivariateRealFunction() {
            public double value(double x) {
                return Math.exp(x) - cc;
            }
        };

        RegulaFalsiSolver solver = new RegulaFalsiSolver();
        double root = Double.NaN;
        String exc = "none";
        try {
            root = solver.solve(100, f, min, max);
        } catch (RuntimeException e) {
            exc = e.getClass().getName();
        }

        final double r = root;
        final String ex = exc;
        Log.logOutIf(true, () -> new Object[]{ ex, r });
        double expected = Math.log(c);
        assertEquals("no exception expected", "none", ex);
        assertEquals(expected, r, 1e-5);
    }

    @Fuzz
    public void testIssue631(@InRange(minDouble = -5, maxDouble = 5) double min,
                                    @InRange(minDouble = 5, maxDouble = 20) double max,
                                    @InRange(minDouble = 1, maxDouble = 100) double c) {
        boolean validIf = min < max && Math.exp(min) < c && c < Math.exp(max);
        boolean buggyIf = c * (max - Math.log(c)) < 0.1 * (Math.exp(max) - c);
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            final double cc = c;
            UnivariateRealFunction f = new UnivariateRealFunction() {
                public double value(double x) {
                    return Math.exp(x) - cc;
                }
            };

            RegulaFalsiSolver solver = new RegulaFalsiSolver();
            double root = Double.NaN;
            String exc = "none";
            try {
                root = solver.solve(100, f, min, max);
            } catch (RuntimeException e) {
                exc = e.getClass().getName();
            }

            final double r = root;
            final String ex = exc;
            Log.logOutIf(preserveIf, () -> new Object[]{ ex, r });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
