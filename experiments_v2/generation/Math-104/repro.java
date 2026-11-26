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

    /** Partial sum of the power series of regularizedGammaP for the given epsilon. */
    private static double seriesSum(double a, double x, double eps) {
        double n = 0.0;
        double an = 1.0 / a;
        double sum = an;
        while (Math.abs(an) > eps && n < 10000000) {
            n = n + 1.0;
            an = an * (x / (a + n));
            sum = sum + an;
        }
        return sum;
    }

    /** Value of the continued fraction of regularizedGammaQ for the given epsilon. */
    private static double cfValue(double a, double x, double eps) {
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
        return c;
    }

    /**
     * True if the coarse default epsilon changes the returned P(a, x) by at least a few
     * units in the last place compared to the fine epsilon (so the difference survives
     * rounding, e.g. it is not swallowed when P is extremely close to 1).
     */
    private static boolean epsilonChangesResult(double a, double x) {
        double pref = Math.exp(-x + a * Math.log(x) - lgamma(a));
        double diff;
        double pEst;
        if (a >= 1.0 && x > a) {
            double q8 = pref / cfValue(a, x, COARSE);
            double q14 = pref / cfValue(a, x, FINE);
            diff = Math.abs(q8 - q14);
            pEst = 1.0 - q14;
        } else {
            double p8 = pref * seriesSum(a, x, COARSE);
            double p14 = pref * seriesSum(a, x, FINE);
            diff = Math.abs(p8 - p14);
            pEst = p14;
        }
        return diff >= 3.0 * Math.ulp(Math.max(Math.abs(pEst), Double.MIN_NORMAL));
    }

    @Fuzz
    public void testBugReproduction(@InRange(minDouble = 0.1, maxDouble = 50.0) double a,
                         @InRange(minDouble = 0.0, maxDouble = 100.0) double x) {
        boolean validIf = a > 0.0 && x >= 0.0;
        // inputs where stopping at the coarse default epsilon visibly changes the returned
        // double (series branch and continued fraction branch), not just inside rounding
        boolean buggyIf = x > 0.0 && epsilonChangesResult(a, x);

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

    @Fuzz
    public void testRegularizedGammaPositivePositive(@InRange(minDouble = 0.1, maxDouble = 50.0) double a,
                         @InRange(minDouble = 0.0, maxDouble = 100.0) double x) {
        boolean validIf = a > 0.0 && x >= 0.0;
        boolean buggyIf = x > 0.0 && epsilonChangesResult(a, x);
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
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
            Log.logOutIf(preserveIf, () -> new Object[]{ dp, fp, err,
                Double.doubleToLongBits(dp), Double.doubleToLongBits(fp),
                Double.doubleToLongBits(dp - fp), dp == fp });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
