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
    public void testBugReproduction(@InRange(minDouble = 0, maxDouble = 2) double min,
                                    @InRange(minDouble = 5, maxDouble = 15) double max,
                                    @InRange(minDouble = 20, maxDouble = 40) double c) {
        boolean validIf = min < max && Math.exp(min) < c && c < Math.exp(max);
        boolean buggyIf = min <= 1.5 && max >= 8 && c >= 25 && c <= 40;

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
}
