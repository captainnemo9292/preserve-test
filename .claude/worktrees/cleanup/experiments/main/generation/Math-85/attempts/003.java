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

    // Probabilities p = cdf(k) (k = 2, 3, 4) for a standard normal. The bracketing
    // loop in inverseCumulativeProbability evaluates the CDF at mean + 1 + j
    // (j = 1, 2, ...), so with sd == 1 and an integer mean the function value is
    // exactly 0 at such an endpoint and the strict sign-change check fails.
    private static final double P2 = 0.9772498680518209;
    private static final double P3 = 0.9986501019683698;
    private static final double P4 = 0.9999683287581673;

    public static class ProbGen extends Generator<Double> {
        public ProbGen() { super(Double.class); }

        @Override
        public Double generate(SourceOfRandomness r, GenerationStatus status) {
            int choice = r.nextInt(10);
            if (choice < 4) {
                double[] special = new double[] { P2, P3, P4 };
                return special[r.nextInt(special.length)];
            } else if (choice == 4) {
                return 0.977249868051820; // not an exact bracket endpoint value
            } else {
                return r.nextDouble();
            }
        }
    }

    public static class SdGen extends Generator<Double> {
        public SdGen() { super(Double.class); }

        @Override
        public Double generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextBoolean()) {
                return 1.0;
            }
            return 0.1 + r.nextDouble() * 4.9;
        }
    }

    @Fuzz
    public void testBugReproduction(@InRange(minInt = -10, maxInt = 11) int mean,
                                    @From(SdGen.class) Double sd,
                                    @From(ProbGen.class) Double p) {
        boolean validIf = sd > 0.0 && p >= 0.0 && p <= 1.0;
        boolean buggyIf = sd == 1.0
                && (p == P2 || p == P3 || p == P4);

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        double result = Double.NaN;
        String exceptionClass = "none";
        try {
            NormalDistributionImpl normal = new NormalDistributionImpl((double) mean, sd);
            result = normal.inverseCumulativeProbability(p);
        } catch (Exception e) {
            exceptionClass = e.getClass().getName();
        }

        final double fResult = result;
        final String fEx = exceptionClass;
        Log.logOutIf(true, () -> new Object[]{ fResult, fEx });

        double expected = mean + (p == P2 ? 2.0 : (p == P3 ? 3.0 : 4.0));
        assertEquals("none", fEx);
        assertEquals(expected, fResult, 1e-3);
    }
}
