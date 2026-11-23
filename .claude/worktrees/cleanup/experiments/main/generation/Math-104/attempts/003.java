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

    @Fuzz
    public void testBugReproduction(@InRange(minDouble = 0.1, maxDouble = 50.0) double a,
                         @InRange(minDouble = 0.0, maxDouble = 100.0) double x) {
        boolean validIf = a > 0.0 && x >= 0.0;
        // x close to a (neither P nor Q saturated, prefactor non-negligible): the
        // 1e-8 default epsilon stops series / continued fraction visibly early
        boolean buggyIf = a >= 2.0 && x >= 0.5 * a && x <= 1.5 * a;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        double defaultP = Double.NaN;
        double defaultQ = Double.NaN;
        double preciseP = Double.NaN;
        double preciseQ = Double.NaN;
        String error = "none";
        try {
            defaultP = Gamma.regularizedGammaP(a, x);
            defaultQ = Gamma.regularizedGammaQ(a, x);
            preciseP = Gamma.regularizedGammaP(a, x, 1.0e-15, Integer.MAX_VALUE);
            preciseQ = Gamma.regularizedGammaQ(a, x, 1.0e-15, Integer.MAX_VALUE);
        } catch (MathException e) {
            error = e.getClass().getName();
        }

        final double dp = defaultP;
        final double dq = defaultQ;
        final double pp = preciseP;
        final double pq = preciseQ;
        final String err = error;
        Log.logOutIf(true, () -> new Object[]{ dp, dq, pp, pq, err });
        assertEquals("none", err);
        // default accuracy should be close to double precision, like the explicit 1e-15 epsilon
        assertEquals(pp, dp, 1.0e-11);
        assertEquals(pq, dq, 1.0e-11);
    }
}
