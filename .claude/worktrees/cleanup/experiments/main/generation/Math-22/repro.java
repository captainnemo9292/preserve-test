package org.apache.commons.math3.distribution;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.apache.commons.math3.distribution.FDistribution;

@RunWith(JQF.class)
public class JQF_FDistributionTest {

    @Fuzz
    public void testBugReproduction(@InRange(minDouble = 0.01, maxDouble = 100.0) double d1,
                                    @InRange(minDouble = 0.01, maxDouble = 100.0) double d2) {
        boolean validIf = d1 > 0 && d2 > 0;
        // The lower bound (0) is always reported as inclusive, but density(0) is not a finite number.
        boolean buggyIf = d1 > 0 && d2 > 0;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        FDistribution dist = new FDistribution(d1, d2);
        double lower = dist.getSupportLowerBound();
        double upper = dist.getSupportUpperBound();
        boolean lowerInc = dist.isSupportLowerBoundInclusive();
        boolean upperInc = dist.isSupportUpperBoundInclusive();
        double densLower = dist.density(lower);
        double densUpper = dist.density(upper);

        Log.logOutIf(true, () -> new Object[]{ lower, upper, lowerInc, upperInc, densLower, densUpper });

        boolean lowerFinite = !Double.isNaN(densLower) && !Double.isInfinite(densLower);
        boolean upperFinite = !Double.isNaN(densUpper) && !Double.isInfinite(densUpper);
        assertEquals("lower bound inclusive must match finiteness of density at lower bound", lowerFinite, lowerInc);
        assertEquals("upper bound inclusive must match finiteness of density at upper bound", upperFinite, upperInc);
    }
}
