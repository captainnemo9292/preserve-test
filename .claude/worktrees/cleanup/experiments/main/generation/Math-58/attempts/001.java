package org.apache.commons.math.optimization.fitting;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.apache.commons.math.optimization.general.LevenbergMarquardtOptimizer;

@RunWith(JQF.class)
public class JQF_GaussianFitterTest {

    /** Generates {norm, mean, sigma, n}: samples of a Gaussian curve at x = 0..n-1. */
    public static class ParamsGen extends Generator<double[]> {
        public ParamsGen() { super(double[].class); }

        @Override
        public double[] generate(SourceOfRandomness r, GenerationStatus status) {
            double norm, mean, sigma;
            int n;
            if (r.nextInt(100) < 45) {
                // region resembling the bug report: only the rising tail of a far-away Gaussian is seen
                n = 27;
                mean = r.nextDouble(32.8, 34.8);
                sigma = r.nextDouble(2.7, 3.3);
                norm = Math.pow(10.0, r.nextDouble(-9.7, -9.2));
            } else {
                n = r.nextInt(5, 40);
                mean = r.nextDouble(0, 50);
                sigma = r.nextDouble(0.5, 10);
                norm = Math.pow(10.0, r.nextDouble(-12, 1));
            }
            return new double[] { norm, mean, sigma, n };
        }
    }

    @Fuzz
    public void testBugReproduction(@From(ParamsGen.class) double[] p) {
        final double norm = p[0];
        final double mean = p[1];
        final double sigma = p[2];
        final int n = (int) p[3];

        boolean validIf = norm > 0 && sigma > 0 && n >= 5 && n <= 40 && mean >= 0 && mean <= 50;
        boolean buggyIf = n == 27 && mean >= 32.8 && mean <= 34.8
                && sigma >= 2.7 && sigma <= 3.3
                && norm >= 2e-10 && norm <= 6.4e-10;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        final double[] obs = new double[n];
        for (int i = 0; i < n; i++) {
            double d = i - mean;
            obs[i] = norm * Math.exp(-d * d / (2 * sigma * sigma));
        }

        String exceptionName = "none";
        double[] result = null;
        try {
            GaussianFitter g = new GaussianFitter(new LevenbergMarquardtOptimizer());
            for (int i = 0; i < n; i++) {
                g.addObservedPoint(i, obs[i]);
            }
            result = g.fit();
        } catch (Exception e) {
            exceptionName = e.getClass().getName();
        }

        final String exName = exceptionName;
        final double[] res = result;
        Log.logOutIf(true, () -> new String[] {
            exName,
            res == null ? "null" : java.util.Arrays.toString(res)
        });
        assertEquals("none", exName);
        assertNotNull(res);
        assertEquals(3, res.length);
        assertTrue(res[2] > 0);
    }
}
