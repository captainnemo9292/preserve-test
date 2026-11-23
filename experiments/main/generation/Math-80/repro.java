package org.apache.commons.math.linear;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import java.util.Arrays;
import org.apache.commons.math.util.MathUtils;

@RunWith(JQF.class)
public class JQF_EigenDecompositionImplTest {

    static final double[] REF_MAIN = {
        7484.860960227216, 18405.28129035345, 13855.225609560746,
        10016.708722343366, 559.8117399576674, 6750.190788301587,
        71.21428769782159
    };
    static final double[] REF_SEC = {
        -4175.088570476366, 1975.7955858241994, 5193.178422374075,
        1995.286659169179, 75.34535882933804, -234.0808002076056
    };

    static final double[] REF_VALUES = {
        20654.744890306974412, 16828.208208485466457,
        6893.155912634994820, 6757.083016675340332,
        5887.799885688558788, 64.309089923240379,
        57.992628792736340
    };
    static final double[][] REF_VECS = {
        {-0.270356342026904, 0.852811091326997, 0.399639490702077, 0.198794657813990, 0.019739323307666, 0.000106983022327, -0.000001216636321},
        {0.179995273578326, -0.402807848153042, 0.701870993525734, 0.555058211014888, 0.068079148898236, 0.000509139115227, -0.000007112235617},
        {-0.399582721284727, -0.056629954519333, -0.514406488522827, 0.711168164518580, 0.225548081276367, 0.125943999652923, -0.004321507456014},
        {0.058515721572821, 0.010200130057739, 0.063516274916536, -0.090696087449378, -0.017148420432597, 0.991318870265707, -0.034707338554096},
        {0.855205995537564, 0.327134656629775, -0.265382397060548, 0.282690729026706, 0.105736068025572, -0.009138126622039, 0.000367751821196},
        {-0.002913069901144, -0.005177515777101, 0.041906334478672, -0.109315918416258, 0.436192305456741, 0.026307315639535, 0.891797507436344},
        {-0.005738311176435, -0.010207611670378, 0.082662420517928, -0.215733886094368, 0.861606487840411, -0.025478530652759, -0.451080697503958}
    };

    static double valueError(double[] values) {
        if (values == null || values.length != 7) return Double.POSITIVE_INFINITY;
        double m = 0;
        for (int i = 0; i < 7; i++) m = Math.max(m, Math.abs(values[i] - REF_VALUES[i]));
        return m;
    }

    static double vectorError(double[][] vecs) {
        double worst = 0;
        for (int i = 0; i < 7; i++) {
            if (vecs[i] == null || vecs[i].length != 7) return Double.POSITIVE_INFINITY;
            double dot = 0;
            for (int j = 0; j < 7; j++) dot += REF_VECS[i][j] * vecs[i][j];
            double s = 0;
            for (int j = 0; j < 7; j++) {
                double diff = dot < 0 ? REF_VECS[i][j] + vecs[i][j] : REF_VECS[i][j] - vecs[i][j];
                s += diff * diff;
            }
            worst = Math.max(worst, Math.sqrt(s));
        }
        return worst;
    }

    /** Largest residual |T v - lambda v| over all computed eigenpairs of the tridiagonal matrix. */
    static double residual(double[] main, double[] sec, double[] values, double[][] vecs) {
        int n = main.length;
        if (values == null || values.length != n) return Double.POSITIVE_INFINITY;
        double worst = 0;
        for (int k = 0; k < n; k++) {
            double[] v = vecs[k];
            if (v == null || v.length != n) return Double.POSITIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                double t = main[i] * v[i];
                if (i > 0) t += sec[i - 1] * v[i - 1];
                if (i < n - 1) t += sec[i] * v[i + 1];
                worst = Math.max(worst, Math.abs(t - values[k] * v[i]));
            }
        }
        return worst;
    }

    public static class MainGen extends Generator<double[]> {
        public MainGen() { super(double[].class); }
        @Override public double[] generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextInt(10) < 6) return REF_MAIN.clone();
            double[] a = new double[1 + r.nextInt(9)];
            for (int i = 0; i < a.length; i++) a[i] = r.nextDouble(-20000, 20000);
            return a;
        }
    }

    public static class SecGen extends Generator<double[]> {
        public SecGen() { super(double[].class); }
        @Override public double[] generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextInt(10) < 6) return REF_SEC.clone();
            double[] a = new double[r.nextInt(9)];
            for (int i = 0; i < a.length; i++) a[i] = r.nextDouble(-6000, 6000);
            return a;
        }
    }

    @Fuzz
    public void testBugReproduction(@From(MainGen.class) double[] main,
                                    @From(SecGen.class) double[] secondary) {
        boolean validIf = main != null && secondary != null
                && main.length >= 1 && secondary.length == main.length - 1;
        boolean buggyIf = validIf && Arrays.equals(main, REF_MAIN)
                && Arrays.equals(secondary, REF_SEC);

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        String exc = "none";
        double[] values = null;
        double[][] vectors = new double[main.length][];
        try {
            EigenDecomposition d = new EigenDecompositionImpl(main.clone(), secondary.clone(), MathUtils.SAFE_MIN);
            values = d.getRealEigenvalues();
            for (int i = 0; i < main.length; i++) {
                vectors[i] = d.getEigenvector(i).getData();
            }
        } catch (Throwable t) {
            exc = t.getClass().getName();
        }

        final String fexc = exc;
        final double[] fvalues = values;
        final double[][] fvectors = vectors;
        Log.logOutIf(true, () -> new Object[]{ fexc, fvalues == null ? "null" : Arrays.toString(fvalues),
                Arrays.deepToString(fvectors) });

        double valErr = valueError(fvalues);
        double vecErr = vectorError(fvectors);
        double res = residual(main, secondary, fvalues, fvectors);
        assertEquals("none", fexc);
        assertEquals(0.0, valErr, 1.0e-3);
        assertEquals(0.0, vecErr, 1.0e-5);
        assertEquals(0.0, res, 1.0e-2);
    }
}
