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

    /** Independent Stirling approximation of ln(Gamma(z)), z > 0 (test-side only). */
    private static double lgamma(double z) {
        double shift = 0.0;
        double w = z;
        for (int k = 0; k < 12; k++) {
            shift += Math.log(w);
            w += 1.0;
        }
        double s = (w - 0.5) * Math.log(w) - w + 0.5 * Math.log(2.0 * Math.PI)
            + 1.0 / (12.0 * w) - 1.0 / (360.0 * w * w * w);
        return s - shift;
    }

    /**
     * Predicted truncation error of the power series used by regularizedGammaP when it is
     * stopped with the coarse default epsilon 1e-8 (series branch: a < 1 or x <= a).
     */
    private static double predictedTruncationError(double a, double x) {
        double an = 1.0 / a;
        double n = 0.0;
        while (Math.abs(an) > 1.0e-8 && n < 1000000) {
            n += 1.0;
            an = an * (x / (a + n));
        }
        // remaining tail of the series that the coarse epsilon drops
        double tail = 0.0;
        double t = an;
        for (int i = 0; i < 100000 && Math.abs(t) > 1.0e-20; i++) {
            n += 1.0;
            t = t * (x / (a + n));
            tail += t;
        }
        double prefactor = Math.exp(-x + a * Math.log(x) - lgamma(a));
        return prefactor * tail;
    }

    @Fuzz
    public void testBugReproduction(@InRange(minDouble = 0.1, maxDouble = 50.0) double a,
                         @InRange(minDouble = 0.0, maxDouble = 100.0) double x) {
        boolean validIf = a > 0.0 && x >= 0.0;
        // series branch of regularizedGammaP in which the dropped tail (caused by the
        // 1e-8 default epsilon) is large enough to be visible well above double precision
        boolean buggyIf = x > 0.0 && (a < 1.0 || x <= a)
            && predictedTruncationError(a, x) >= 1.0e-11;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        double defaultP = Double.NaN;
        double preciseP = Double.NaN;
        String error = "none";
        try {
            defaultP = Gamma.regularizedGammaP(a, x);
            preciseP = Gamma.regularizedGammaP(a, x, 1.0e-15, Integer.MAX_VALUE);
        } catch (MathException e) {
            error = e.getClass().getName();
        }

        final double dp = defaultP;
        final double pp = preciseP;
        final String err = error;
        Log.logOutIf(true, () -> new Object[]{ dp, pp, err });
        assertEquals("none", err);
        // default accuracy should be near double precision, like the explicit 1e-15 epsilon
        assertEquals(pp, dp, 1.0e-12);
    }
}
