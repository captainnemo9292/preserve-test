package org.apache.commons.math.analysis.solvers;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.apache.commons.math.FunctionEvaluationException;
import org.apache.commons.math.analysis.UnivariateRealFunction;

@RunWith(JQF.class)
public class JQF_BrentSolverTest {

    private static double eval(boolean neg, double a, double h, double c, double x) {
        double v = a * (x - h) * (x - h) + c;
        return neg ? -v : v;
    }

    @Fuzz
    public void testBugReproduction(@InRange(minDouble = -10, maxDouble = 10) double min,
                                    @InRange(minDouble = -10, maxDouble = 10) double initial,
                                    @InRange(minDouble = -10, maxDouble = 10) double max,
                                    @InRange(minDouble = 0.1, maxDouble = 5) double a,
                                    @InRange(minDouble = -10, maxDouble = 10) double h,
                                    @InRange(minDouble = -5, maxDouble = 5) double c,
                                    boolean neg) {
        boolean validIf = min < initial && initial < max;
        boolean buggyIf = (eval(neg, a, h, c, min) > 1e-9 && eval(neg, a, h, c, initial) > 1e-9 && eval(neg, a, h, c, max) > 1e-9)
                || (eval(neg, a, h, c, min) < -1e-9 && eval(neg, a, h, c, initial) < -1e-9 && eval(neg, a, h, c, max) < -1e-9);

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        final boolean fNeg = neg;
        final double fa = a, fh = h, fc = c;
        UnivariateRealFunction f = new UnivariateRealFunction() {
            public double value(double x) throws FunctionEvaluationException {
                return eval(fNeg, fa, fh, fc, x);
            }
        };

        String outcome;
        double res = Double.NaN;
        try {
            res = new BrentSolver().solve(f, min, max, initial);
            outcome = "returned";
        } catch (IllegalArgumentException e) {
            outcome = "IllegalArgumentException";
        } catch (Exception e) {
            outcome = e.getClass().getName();
        }

        final String outcomeF = outcome;
        final double resF = res;
        Log.logOutIf(true, () -> new String[]{outcomeF, String.valueOf(resF)});
        assertEquals("IllegalArgumentException", outcomeF);
    }
}
