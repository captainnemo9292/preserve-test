package org.apache.commons.math.stat.regression;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

@RunWith(JQF.class)
public class JQF_SimpleRegressionTest {

    /** Produces {x0, dx, y0, dy}: three points (x0 + i*dx, y0 + i*dy), i = 0..2. */
    public static class DataGen extends Generator<double[]> {
        public DataGen() { super(double[].class); }

        @Override
        public double[] generate(SourceOfRandomness r, GenerationStatus status) {
            double[] d = new double[4];
            boolean bugMode = r.nextInt(100) < 50;
            if (bugMode) {
                d[0] = 110.0 + r.nextDouble();
                d[1] = 8.6e-5 + r.nextDouble() * 1.0e-6;
                d[2] = 8915.0 + r.nextDouble();
                d[3] = d[1] * 48611.11;
            } else {
                d[0] = r.nextDouble() * 200.0;
                d[1] = r.nextDouble();
                d[2] = r.nextDouble() * 10000.0;
                d[3] = r.nextDouble() * 10.0;
            }
            return d;
        }
    }

    /**
     * Pure arithmetic predicate (independent of the class under test): whether the
     * cancelling formula sumYY - sumXY^2/sumXX, evaluated with the running-update
     * sums on these three points, comes out negative in floating point.
     */
    static boolean cancellationYieldsNegative(double[] d) {
        double xbar = 0, ybar = 0, sxx = 0, syy = 0, sxy = 0;
        int n = 0;
        for (int i = 0; i < 3; i++) {
            double x = d[0] + i * d[1];
            double y = d[2] + i * d[3];
            if (n == 0) {
                xbar = x;
                ybar = y;
            } else {
                double ddx = x - xbar;
                double ddy = y - ybar;
                sxx += ddx * ddx * (double) n / (double) (n + 1.0);
                syy += ddy * ddy * (double) n / (double) (n + 1.0);
                sxy += ddx * ddy * (double) n / (double) (n + 1.0);
                xbar += ddx / (double) (n + 1.0);
                ybar += ddy / (double) (n + 1.0);
            }
            n++;
        }
        return syy - sxy * sxy / sxx < 0.0;
    }

    @Fuzz
    public void testBugReproduction(@From(DataGen.class) double[] data) {
        boolean validIf = data != null && data.length == 4 && data[1] > 0;
        // large, closely spaced x values with an (almost) perfectly linear y, for which
        // the subtraction in the SSE formula cancels to a negative value
        boolean buggyIf = data != null && data.length == 4 && data[1] > 0
                && data[0] >= 110.0 && data[0] <= 111.0
                && data[1] >= 8.6e-5 && data[1] <= 8.7e-5
                && data[2] >= 8915.0 && data[2] <= 8916.0
                && Math.abs(data[3] - data[1] * 48611.11) < 0.01
                && cancellationYieldsNegative(data);

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        SimpleRegression reg = new SimpleRegression();
        for (int i = 0; i < 3; i++) {
            reg.addData(data[0] + i * data[1], data[2] + i * data[3]);
        }
        double sse = reg.getSumSquaredErrors();
        long n = reg.getN();
        double slope = reg.getSlope();

        Log.logOutIf(true, () -> new Object[]{ sse, n, slope });
        assertTrue("SSE must not be negative but was " + sse, sse >= 0.0);
    }
}
