package org.apache.commons.math3.distribution;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.apache.commons.math3.distribution.HypergeometricDistribution;

@RunWith(JQF.class)
public class JQF_HypergeometricDistributionTest {

    @Fuzz
    public void testBugReproduction(@InRange(minInt = 1, maxInt = 2147483647) int populationSize,
                                    @InRange(minInt = 0, maxInt = 2147483647) int numberOfSuccesses,
                                    @InRange(minInt = 0, maxInt = 2147483647) int sampleSize) {
        boolean validIf = numberOfSuccesses <= populationSize && sampleSize <= populationSize;
        boolean buggyIf = (long) sampleSize * (long) numberOfSuccesses > (long) Integer.MAX_VALUE;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        HypergeometricDistribution dist =
                new HypergeometricDistribution(populationSize, numberOfSuccesses, sampleSize);
        double mean = dist.getNumericalMean();
        int lower = dist.getSupportLowerBound();
        int upper = dist.getSupportUpperBound();

        Log.logOutIf(true, () -> new Object[]{ mean, lower, upper, populationSize, numberOfSuccesses, sampleSize });
        double expected = (double) sampleSize * ((double) numberOfSuccesses / (double) populationSize);
        assertEquals(expected, mean, 1e-6 * Math.max(1.0, Math.abs(expected)));
        assertTrue("mean must be within [0, sampleSize]: " + mean, mean >= 0 && mean <= sampleSize);
    }
}
