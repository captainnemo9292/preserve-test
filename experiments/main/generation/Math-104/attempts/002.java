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
        // series branch (a < 1, or x <= a) with a non-negligible prefactor, where the
        // 1e-8 default epsilon stops the series earlier than the 1e-15 one
        boolean buggyIf = x >= 0.5 && x >= 0.5 * a && x <= a + 5.0 && (a < 1.0 || x <= a);

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        double defaultResult = Double.NaN;
        double preciseResult = Double.NaN;
        String error = "none";
        try {
            defaultResult = Gamma.regularizedGammaP(a, x);
            preciseResult = Gamma.regularizedGammaP(a, x, 1.0e-15, Integer.MAX_VALUE);
        } catch (MathException e) {
            error = e.getClass().getName();
        }

        final double d = defaultResult;
        final double p = preciseResult;
        final String err = error;
        Log.logOutIf(true, () -> new Object[]{ d, p, err });
        assertEquals("none", err);
        // the default must be as accurate as the explicit 1e-15 epsilon
        assertEquals(p, d, 0.0);
    }
}
