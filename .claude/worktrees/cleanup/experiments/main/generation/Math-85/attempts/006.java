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
     * inverseCumulativeProbability brackets the root starting at initial = mean +/- sd,
     * stepping the endpoints by 1.0 per iteration, and requires a strict sign change.
     * Whenever p equals the computed CDF at an endpoint visited by the bracketing (so
     * f(endpoint) is exactly 0), the call fails: it throws a MathException (or returns a
     * wrong domain bound).
     * kind == 0: free random probability pRand.
     * kind == 1: p = CDF at the i-th OUTER endpoint (mean+sd+i above, mean-sd-i below).
     * kind == 2: p = CDF at the i-th INNER endpoint (mean+sd-i above, mean-sd+i below),
     *            visited when sd > 1.
     */
    @Fuzz
    public void testBugReproduction(@InRange(minInt = -10, maxInt = 11) int mean,
                                    @From(SdGen.class) Double sd,
                                    @InRange(minInt = 0, maxInt = 11) int i,
                                    boolean below,
                                    @InRange(minInt = 0, maxInt = 3) int kind,
                                    @InRange(minDouble = 0, maxDouble = 1) double pRand) {
        boolean validIf = sd > 0.0 && pRand >= 0.0 && pRand <= 1.0;
        boolean buggyIf = (kind == 1 && i >= 1 && i <= 8 && i <= 5.0 * sd)
                || (kind == 2 && i >= 1 && i < sd - 1e-6);

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        double p = Double.NaN;
        double result = Double.NaN;
        String exceptionClass = "none";
        try {
            NormalDistributionImpl normal = new NormalDistributionImpl((double) mean, sd);
            if (kind == 0) {
                p = pRand;
            } else {
                double x = below ? (mean - sd) : (mean + sd);
                for (int s = 0; s < i; s++) {
                    if (kind == 1) {
                        x = below ? (x - 1.0) : (x + 1.0);
                    } else {
                        x = below ? (x + 1.0) : (x - 1.0);
                    }
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

        double offset = (kind == 1) ? (sd + i) : (sd - i);
        double expected = below ? (mean - offset) : (mean + offset);
        assertEquals("none", fEx);
        assertEquals(expected, fResult, 1e-3);
    }
}
