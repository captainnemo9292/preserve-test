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

        boolean buggyIf = false;
        if (validIf && length > 1) {
            double inSum = 0, outSum = 0;
            boolean differs = false;
            for (int i = 0; i < weights.length; i++) {
                if (i >= begin && i < begin + length) {
                    inSum += weights[i];
                    if (weights[i] > 0 && Math.abs(values[i] - mean) > 1e-6) {
                        differs = true;
                    }
                } else {
                    outSum += weights[i];
                }
            }
            buggyIf = inSum > 1.5 && outSum > 0.5 && differs;
        }

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
