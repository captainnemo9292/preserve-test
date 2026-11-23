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

    public static class ProbGen extends Generator<Double> {
        private static final double[] SPECIAL = new double[] {
            0.9772498680518209,   // cdf(2)
            0.9986501019683698,   // cdf(3)
            0.9999683287581673    // cdf(4)
        };

        public ProbGen() { super(Double.class); }

        @Override
        public Double generate(SourceOfRandomness r, GenerationStatus status) {
            int choice = r.nextInt(10);
            if (choice < 4) {
                return SPECIAL[r.nextInt(SPECIAL.length)];
            } else if (choice == 4) {
                return 0.977249868051820; // works fine in buggy code
            } else {
                return r.nextDouble();
            }
        }
    }

    @Fuzz
    public void testBugReproduction(@From(ProbGen.class) Double p) {
        boolean validIf = p >= 0.0 && p <= 1.0;
        boolean buggyIf = p == 0.9772498680518209
                || p == 0.9986501019683698
                || p == 0.9999683287581673;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        NormalDistributionImpl normal = new NormalDistributionImpl(0.0, 1.0);
        double result = Double.NaN;
        String exceptionClass = "none";
        try {
            result = normal.inverseCumulativeProbability(p);
        } catch (Exception e) {
            exceptionClass = e.getClass().getName();
        }

        final double fResult = result;
        final String fEx = exceptionClass;
        Log.logOutIf(true, () -> new Object[]{ fResult, fEx });

        double expected = p == 0.9772498680518209 ? 2.0 : (p == 0.9986501019683698 ? 3.0 : 4.0);
        assertEquals("none", fEx);
        assertEquals(expected, fResult, 1e-3);
    }
}
