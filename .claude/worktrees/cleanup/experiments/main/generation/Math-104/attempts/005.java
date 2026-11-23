package org.apache.commons.math.special;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import java.lang.reflect.Field;
import org.apache.commons.math.MathException;

@RunWith(JQF.class)
public class JQF_GammaTest {

    @Fuzz
    public void testBugReproduction(@InRange(minDouble = 0.1, maxDouble = 50.0) double a,
                         @InRange(minDouble = 0.0, maxDouble = 100.0) double x) {
        boolean validIf = a > 0.0 && x >= 0.0;
        // the default epsilon is the same for every call of the two-argument overloads,
        // so every valid input is affected by the coarse 10e-9 default
        boolean buggyIf = a > 0.0 && x >= 0.0;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        double defaultP = Double.NaN;
        double defaultQ = Double.NaN;
        double defaultEpsilon = Double.NaN;
        String error = "none";
        try {
            defaultP = Gamma.regularizedGammaP(a, x);
            defaultQ = Gamma.regularizedGammaQ(a, x);
            Field f = Gamma.class.getDeclaredField("DEFAULT_EPSILON");
            f.setAccessible(true);
            defaultEpsilon = f.getDouble(null);
        } catch (MathException e) {
            error = e.getClass().getName();
        } catch (Exception e) {
            error = e.getClass().getName();
        }

        final double dp = defaultP;
        final double dq = defaultQ;
        final double eps = defaultEpsilon;
        final String err = error;
        Log.logOutIf(true, () -> new Object[]{ dp, dq, eps, err });
        assertEquals("none", err);
        // default must be (close to) the highest double precision accuracy, like erf's 1e-15
        assertTrue("default epsilon too coarse: " + eps, eps <= 1.0e-14);
    }
}
