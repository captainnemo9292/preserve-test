package org.apache.commons.math.special;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.apache.commons.math.MathException;

@RunWith(JQF.class)
public class JQF_GammaTest {

    private static final double COARSE = 10e-9;   // current Gamma default
    private static final double FINE = 10e-15;    // accuracy the report asks for (as in Beta)

    /** Number of terms the power series of regularizedGammaP sums for the given epsilon. */
    private static int seriesCount(double a, double x, double eps) {
        double n = 0.0;
        double an = 1.0 / a;
        while (Math.abs(an) > eps && n < 10000000) {
            n = n + 1.0;
            an = an * (x / (a + n));
        }
        return (int) n;
    }

    /** Number of convergents the continued fraction of regularizedGammaQ evaluates. */
    private static int cfCount(double a, double x, double eps) {
        double p0 = 1.0;
        double p1 = (1.0 - a + x);
        double q0 = 0.0;
        double q1 = 1.0;
        double c = p1 / q1;
        int n = 0;
        double rel = Double.MAX_VALUE;
        while (n < 100000 && rel > eps) {
            ++n;
            double an = (2.0 * n + 1.0) - a + x;
            double bn = n * (a - n);
            double p2 = an * p1 + bn * p0;
            double q2 = an * q1 + bn * q0;
            double r = p2 / q2;
            rel = Math.abs(r / c - 1.0);
            c = r;
            p0 = p1;
            p1 = p2;
            q0 = q1;
            q1 = q2;
        }
        return n;
    }

    /** Number of iterations regularizedGammaP performs with the given epsilon. */
    private static int iterations(double a, double x, double eps) {
        if (a >= 1.0 && x > a) {
            return cfCount(a, x, eps);
        }
        return seriesCount(a, x, eps);
    }

    @Fuzz
    public void testBugReproduction(@InRange(minDouble = 0.1, maxDouble = 50.0) double a,
                         @InRange(minDouble = 0.0, maxDouble = 100.0) double x) {
        boolean validIf = a > 0.0 && x >= 0.0;
        // inputs for which stopping at the coarse default epsilon ends the iteration earlier
        // than stopping at the fine epsilon (series branch and continued fraction branch)
        boolean buggyIf = x > 0.0 && iterations(a, x, COARSE) != iterations(a, x, FINE);

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        double defaultP = Double.NaN;
        double fineP = Double.NaN;
        String error = "none";
        try {
            defaultP = Gamma.regularizedGammaP(a, x);
            fineP = Gamma.regularizedGammaP(a, x, FINE, Integer.MAX_VALUE);
        } catch (MathException e) {
            error = e.getClass().getName();
        }

        final double dp = defaultP;
        final double fp = fineP;
        final String err = error;
        Log.logOutIf(true, () -> new Object[]{ dp, fp, err });
        assertEquals("none", err);
        // default call must be as accurate as the explicit high-accuracy call
        assertEquals(fp, dp, 0.0);
    }
}
