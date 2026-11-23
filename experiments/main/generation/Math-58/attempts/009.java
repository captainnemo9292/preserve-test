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
                // only the far, rising tail of a narrow Gaussian is sampled (as in the bug report)
                n = r.nextInt(27, 30);
                mean = (n - 1) + r.nextDouble(7.15, 8.0);
                sigma = r.nextDouble(2.75, 2.9);
                norm = Math.pow(10.0, r.nextDouble(Math.log10(3.35e-10), Math.log10(5.0e-10)));
            } else {
                n = r.nextInt(5, 41);
                mean = r.nextDouble(0, 60);
                sigma = r.nextDouble(0.5, 10);
                norm = Math.pow(10.0, r.nextDouble(-12, 1));
            }
            return new double[] { norm, mean, sigma, n };
        }
    }

    @Fuzz
    public void testBugReproduction(@From(ParamsGen.class) double[] p) {
        boolean validIf = p != null && p.length == 4
                && p[0] > 0 && p[2] > 0 && p[3] >= 5 && p[3] <= 40 && p[1] >= 0 && p[1] <= 60;
        boolean buggyIf = p != null && p.length == 4
                && (int) p[3] >= 27 && (int) p[3] <= 29
                && p[1] - (p[3] - 1) >= 7.15 && p[1] - (p[3] - 1) <= 8.0
                && p[2] >= 2.75 && p[2] <= 2.9
                && p[0] >= 3.35e-10 && p[0] <= 5.0e-10;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        final int n = (int) p[3];
        final double[] obs = new double[n];
        for (int i = 0; i < n; i++) {
            double d = i - p[1];
            obs[i] = p[0] * Math.exp(-d * d / (2 * p[2] * p[2]));
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
