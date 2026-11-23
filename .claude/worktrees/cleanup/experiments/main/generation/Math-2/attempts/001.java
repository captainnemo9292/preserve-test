package org.apache.commons.math3.distribution;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.apache.commons.math3.distribution.HypergeometricDistribution;

@RunWith(JQF.class)
public class JQF_HypergeometricDistributionTest {

    /** Generates {populationSize, numberOfSuccesses, sampleSize}. */
    public static class ParamsGen extends Generator<int[]> {
        public ParamsGen() { super(int[].class); }

        @Override
        public int[] generate(SourceOfRandomness r, GenerationStatus status) {
            int mode = r.nextInt(0, 3);
            int popSize;
            if (mode == 0) {
                // large population: product usually overflows int
                popSize = r.nextInt(1000000, Integer.MAX_VALUE - 1);
            } else if (mode == 1) {
                // small population: product never overflows
                popSize = r.nextInt(1, 46340);
            } else {
                // medium population: mixed
                popSize = r.nextInt(1, 200000);
            }
            int succ;
            int sample;
            int sub = r.nextInt(0, 3);
            if (sub == 0) {
                succ = r.nextInt(0, popSize);
                sample = r.nextInt(0, popSize);
            } else if (sub == 1) {
                // many successes, small sample (like the report)
                succ = r.nextInt(popSize / 2, popSize);
                sample = r.nextInt(0, Math.min(popSize, 100));
            } else {
                // boundary-ish values
                succ = r.nextBoolean() ? popSize : r.nextInt(0, popSize);
                sample = r.nextBoolean() ? popSize : r.nextInt(0, popSize);
            }
            return new int[]{popSize, succ, sample};
        }
    }

    @Fuzz
    public void testBugReproduction(@From(ParamsGen.class) int[] p) {
        int populationSize = p[0];
        int numberOfSuccesses = p[1];
        int sampleSize = p[2];

        boolean validIf = populationSize >= 1
                && numberOfSuccesses >= 0 && numberOfSuccesses <= populationSize
                && sampleSize >= 0 && sampleSize <= populationSize;
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
