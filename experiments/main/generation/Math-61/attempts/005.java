package org.apache.commons.math.distribution;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.apache.commons.math.exception.NotStrictlyPositiveException;

@RunWith(JQF.class)
public class JQF_PoissonDistributionTest {

    @Fuzz
    public void testBugReproduction(@InRange(minDouble = -100, maxDouble = 100) double p,
                                    @InRange(minDouble = 1e-15, maxDouble = 0.5) double epsilon,
                                    @InRange(minInt = 1, maxInt = 1000) int maxIterations) {
        boolean validIf = !Double.isNaN(p) && epsilon > 0 && maxIterations > 0;
        boolean buggyIf = p <= 0;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        String thrown = "none";
        boolean isNotStrictlyPositive = false;
        boolean isIllegalArgument = false;
        String mean = "n/a";
        try {
            PoissonDistributionImpl d = new PoissonDistributionImpl(p, epsilon, maxIterations);
            mean = String.valueOf(d.getMean());
        } catch (Throwable t) {
            thrown = t.getClass().getName();
            isNotStrictlyPositive = t instanceof NotStrictlyPositiveException;
            isIllegalArgument = t instanceof IllegalArgumentException;
        }

        final String fThrown = thrown;
        final String fMean = mean;
        final boolean fNsp = isNotStrictlyPositive;
        final boolean fIae = isIllegalArgument;
        Log.logOutIf(true, () -> new String[]{ fThrown, fMean, String.valueOf(fNsp), String.valueOf(fIae) });
        assertTrue("expected NotStrictlyPositiveException but got " + fThrown, fNsp);
    }
}
