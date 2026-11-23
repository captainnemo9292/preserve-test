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
        // series branch (x <= a, a >= 1) where the 1e-8 default epsilon truncates visibly
        boolean buggyIf = a >= 2.0 && x >= 0.5 * a && x <= a;

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
        assertEquals(p, d, 1.0e-12);
    }
}
