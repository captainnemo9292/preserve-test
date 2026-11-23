package org.apache.commons.math.distribution;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

@RunWith(JQF.class)
public class JQF_NormalDistributionTest {

    public static class SdGen extends Generator<Double> {
        public SdGen() { super(Double.class); }

        @Override
        public Double generate(SourceOfRandomness r, GenerationStatus status) {
            int c = r.nextInt(3);
            if (c == 0) {
                return 1.0;
            } else if (c == 1) {
                return 0.5 + r.nextDouble() * 2.5;
            }
            return 0.05 + r.nextDouble() * 5.0;
        }
    }

    /**
     * The bracketing in inverseCumulativeProbability starts at initial = mean +/- sd and
     * moves the endpoint outward by 1.0 per iteration. If p equals the CDF at an endpoint
     * (so f(endpoint) is exactly 0), the strict sign test fails and a MathException is thrown.
     * Selector sel == 0 uses a free random p; otherwise p is the CDF at the j-th endpoint
     * reached by the bracketing (above the mean if below == false, below it otherwise).
     */
    @Fuzz
    public void testBugReproduction(@InRange(minInt = -10, maxInt = 11) int mean,
                                    @From(SdGen.class) Double sd,
                                    @InRange(minInt = 0, maxInt = 9) int j,
                                    boolean below,
                                    @InRange(minInt = 0, maxInt = 5) int sel,
                                    @InRange(minDouble = 0, maxDouble = 1) double pRand) {
        boolean useRand = sel == 0;
        boolean validIf = sd > 0.0 && pRand >= 0.0 && pRand <= 1.0;
        // p is the CDF value at the j-th bracket endpoint (j >= 1); j limited so that p
        // does not saturate to exactly 0 or 1.
        boolean buggyIf = !useRand && j >= 1 && j <= 5 && j <= 5.0 * sd;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        double p = Double.NaN;
        double result = Double.NaN;
        String exceptionClass = "none";
        try {
            NormalDistributionImpl normal = new NormalDistributionImpl((double) mean, sd);
            if (useRand) {
                p = pRand;
            } else {
                double x = below ? (mean - sd) : (mean + sd);
                for (int i = 0; i < j; i++) {
                    x = below ? (x - 1.0) : (x + 1.0);
                }
                p = normal.cumulativeProbability(x);
            }
            result = normal.inverseCumulativeProbability(p);
        } catch (Exception e) {
            exceptionClass = e.getClass().getName();
        }

        final double fP = p;
        final double fResult = result;
        final String fEx = exceptionClass;
        Log.logOutIf(true, () -> new Object[]{ fP, fResult, fEx });

        double expected = below ? (mean - sd - j) : (mean + sd + j);
        assertEquals("none", fEx);
        assertEquals(expected, fResult, 1e-3);
    }
}
