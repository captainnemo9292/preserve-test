package org.apache.commons.math.stat.descriptive.moment;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

@RunWith(JQF.class)
public class JQF_VarianceTest {

    private static final int N = 6;

    public static class ValuesGen extends Generator<double[]> {
        public ValuesGen() { super(double[].class); }
        @Override public double[] generate(SourceOfRandomness r, GenerationStatus status) {
            double[] a = new double[N];
            for (int i = 0; i < N; i++) {
                a[i] = r.nextInt(5) == 0 ? (double) r.nextInt(-5, 5) : r.nextDouble(-100, 100);
            }
            return a;
        }
    }

    public static class WeightsGen extends Generator<double[]> {
        public WeightsGen() { super(double[].class); }
        @Override public double[] generate(SourceOfRandomness r, GenerationStatus status) {
            double[] a = new double[N];
            for (int i = 0; i < N; i++) {
                int k = r.nextInt(6);
                if (k == 0) {
                    a[i] = 0.0;
                } else if (k == 1) {
                    a[i] = 1.0;
                } else {
                    a[i] = r.nextDouble(0, 5);
                }
            }
            return a;
        }
    }

    static double sumWeights(double[] w, int begin, int length, boolean inside) {
        double s = 0;
        for (int i = 0; i < w.length; i++) {
            boolean in = i >= begin && i < begin + length;
            if (in == inside) {
                s += w[i];
            }
        }
        return s;
    }

    static boolean someValueDiffers(double[] v, double[] w, double mean, int begin, int length) {
        for (int i = begin; i < begin + length; i++) {
            if (w[i] > 0 && Math.abs(v[i] - mean) > 1e-6) {
                return true;
            }
        }
        return false;
    }

    @Fuzz
    public void testBugReproduction(@From(ValuesGen.class) double[] values,
                                    @From(WeightsGen.class) double[] weights,
                                    @InRange(minDouble = -100, maxDouble = 100) double mean,
                                    @InRange(minInt = 0, maxInt = 7) int begin,
                                    @InRange(minInt = 0, maxInt = 8) int length,
                                    boolean biasCorrected) {
        boolean validIf = values != null && weights != null
                && values.length == weights.length
                && begin >= 0 && length >= 0 && begin + length <= values.length;
        boolean buggyIf = validIf && length > 1
                && sumWeights(weights, begin, length, true) > 1.5
                && sumWeights(weights, begin, length, false) > 0.5
                && someValueDiffers(values, weights, mean, begin, length);

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        Variance v = new Variance();
        v.setBiasCorrected(biasCorrected);
        double result = v.evaluate(values, weights, mean, begin, length);

        Log.logOutIf(true, () -> new Object[]{ result, v.isBiasCorrected() });
        double[] subV = new double[length];
        double[] subW = new double[length];
        System.arraycopy(values, begin, subV, 0, length);
        System.arraycopy(weights, begin, subW, 0, length);
        Variance ref = new Variance();
        ref.setBiasCorrected(biasCorrected);
        double expected = ref.evaluate(subV, subW, mean);
        assertEquals(expected, result, 1e-9 * Math.max(1.0, Math.abs(expected)));
    }
}
