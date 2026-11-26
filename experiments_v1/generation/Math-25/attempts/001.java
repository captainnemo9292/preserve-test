package org.apache.commons.math3.optimization.fitting;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.apache.commons.math3.optimization.fitting.WeightedObservedPoint;

@RunWith(JQF.class)
public class JQF_HarmonicFitterTest {

    static boolean isValid(double[][] data) {
        if (data == null || data.length != 2 || data[0] == null || data[1] == null) {
            return false;
        }
        double[] xs = data[0];
        double[] ys = data[1];
        if (xs.length < 4 || xs.length != ys.length) {
            return false;
        }
        for (int i = 0; i < xs.length; i++) {
            if (Double.isNaN(xs[i]) || Double.isInfinite(xs[i])
                || Double.isNaN(ys[i]) || Double.isInfinite(ys[i])) {
                return false;
            }
            if (i > 0 && !(xs[i] > xs[i - 1])) {
                return false;
            }
        }
        return true;
    }

    /** Replicates the ill-conditioning test of the guesser (data sorted, strictly increasing x). */
    static boolean fallbackUsed(double[] xs, double[] ys) {
        double sx2 = 0, sy2 = 0, sxy = 0, sxz = 0, syz = 0;
        double currentX = xs[0];
        double currentY = ys[0];
        double f2Integral = 0;
        double fPrime2Integral = 0;
        final double startX = currentX;
        for (int i = 1; i < xs.length; ++i) {
            final double previousX = currentX;
            final double previousY = currentY;
            currentX = xs[i];
            currentY = ys[i];
            final double dx = currentX - previousX;
            final double dy = currentY - previousY;
            final double f2Step = dx * (previousY * previousY + previousY * currentY + currentY * currentY) / 3;
            final double fp2Step = dy * dy / dx;
            final double x = currentX - startX;
            f2Integral += f2Step;
            fPrime2Integral += fp2Step;
            sx2 += x * x;
            sy2 += f2Integral * f2Integral;
            sxy += x * f2Integral;
            sxz += x * fPrime2Integral;
            syz += f2Integral * fPrime2Integral;
        }
        double c1 = sy2 * sxz - sxy * syz;
        double c2 = sxy * sxz - sx2 * syz;
        double c3 = sx2 * sy2 - sxy * sxy;
        return (c1 / c2 < 0) || (c2 / c3 < 0);
    }

    static boolean firstIsStrictExtreme(double[] ys) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int i = 1; i < ys.length; i++) {
            min = Math.min(min, ys[i]);
            max = Math.max(max, ys[i]);
        }
        return ys[0] > max || ys[0] < min;
    }

    static boolean isBuggy(double[][] data) {
        return fallbackUsed(data[0], data[1]) && firstIsStrictExtreme(data[1]);
    }

    static double expectedAmplitude(double[] ys) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double y : ys) {
            min = Math.min(min, y);
            max = Math.max(max, y);
        }
        return 0.5 * (max - min);
    }

    public static class DataGen extends Generator<double[][]> {
        public DataGen() { super(double[][].class); }

        @Override
        public double[][] generate(SourceOfRandomness r, GenerationStatus status) {
            boolean wantBug = r.nextInt(100) < 50;
            double[][] last = null;
            for (int attempt = 0; attempt < 30; attempt++) {
                int n = r.nextInt(4, 13);
                double[] xs = new double[n];
                double[] ys = new double[n];
                double x = r.nextDouble(-10, 10);
                for (int i = 0; i < n; i++) {
                    xs[i] = x;
                    x += r.nextDouble(0.1, 2.0);
                    ys[i] = r.nextDouble(-10, 10);
                }
                if (wantBug) {
                    double off = r.nextDouble(1, 20);
                    ys[0] = r.nextBoolean() ? 10 + off : -10 - off;
                }
                last = new double[][] {xs, ys};
                if (!wantBug || fallbackUsed(xs, ys)) {
                    break;
                }
            }
            return last;
        }
    }

    @Fuzz
    public void testBugReproduction(@From(DataGen.class) double[][] data) {
        boolean validIf = isValid(data);
        boolean buggyIf = validIf && isBuggy(data);

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        WeightedObservedPoint[] obs = new WeightedObservedPoint[data[0].length];
        for (int i = 0; i < obs.length; i++) {
            obs[i] = new WeightedObservedPoint(1.0, data[0][i], data[1][i]);
        }
        double[] guess = null;
        String exc = "none";
        try {
            guess = new HarmonicFitter.ParameterGuesser(obs).guess();
        } catch (Exception e) {
            exc = e.getClass().getName();
        }
        final double[] g = guess;
        final String excF = exc;

        Log.logOutIf(true, () -> new Object[]{ excF,
            g == null ? "null" : String.valueOf(g[0]),
            g == null ? "null" : String.valueOf(g[1]),
            g == null ? "null" : String.valueOf(g[2]) });

        double expectedA = expectedAmplitude(data[1]);
        assertEquals("none", excF);
        assertNotNull(g);
        assertEquals(expectedA, g[0], 1e-9);
    }

    @Fuzz
    public void testMath844(@From(DataGen.class) double[][] data) {
        boolean validIf = isValid(data);
        boolean buggyIf = validIf && isBuggy(data);
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            WeightedObservedPoint[] obs = new WeightedObservedPoint[data[0].length];
            for (int i = 0; i < obs.length; i++) {
                obs[i] = new WeightedObservedPoint(1.0, data[0][i], data[1][i]);
            }
            double[] guess = null;
            String exc = "none";
            try {
                guess = new HarmonicFitter.ParameterGuesser(obs).guess();
            } catch (Exception e) {
                exc = e.getClass().getName();
            }
            final double[] g = guess;
            final String excF = exc;

            Log.logOutIf(preserveIf, () -> new Object[]{ excF,
                g == null ? "null" : String.valueOf(g[0]),
                g == null ? "null" : String.valueOf(g[1]),
                g == null ? "null" : String.valueOf(g[2]) });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
