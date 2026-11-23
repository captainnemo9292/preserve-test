package org.apache.commons.math.linear;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.apache.commons.math.util.MathUtils;

import java.util.Arrays;

@RunWith(JQF.class)
public class JQF_EigenDecompositionImplTest {

    static final double[] BUG_MAIN = {
        22.330154644539597, 46.65485522478641, 17.393672330044705, 54.46687435351116, 80.17800767709437
    };
    static final double[] BUG_SEC = {
        13.04450406501361, -5.977590941539671, 2.9040909856707517, 7.1570352792841225
    };

    public static class MainGen extends Generator<double[]> {
        public MainGen() { super(double[].class); }
        @Override public double[] generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextInt(100) < 50) {
                return BUG_MAIN.clone();
            }
            int n = r.nextInt(2, 8);
            double[] d = new double[n];
            for (int i = 0; i < n; i++) d[i] = r.nextDouble(0, 100);
            return d;
        }
    }

    public static class SecGen extends Generator<double[]> {
        public SecGen() { super(double[].class); }
        @Override public double[] generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextInt(100) < 50) {
                return BUG_SEC.clone();
            }
            int n = r.nextInt(1, 7);
            double[] d = new double[n];
            for (int i = 0; i < n; i++) d[i] = r.nextDouble(-20, 20);
            return d;
        }
    }

    public static class TolGen extends Generator<Double> {
        public TolGen() { super(Double.class); }
        @Override public Double generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextInt(100) < 70) {
                return MathUtils.SAFE_MIN;
            }
            return r.nextDouble(0, 1e-3);
        }
    }

    @Fuzz
    public void testBugReproduction(@From(MainGen.class) double[] main,
                                    @From(SecGen.class) double[] sec,
                                    @From(TolGen.class) double tol) {
        boolean validIf = main != null && sec != null && main.length >= 1
            && sec.length == main.length - 1 && tol >= 0;
        boolean buggyIf = Arrays.equals(main, BUG_MAIN) && Arrays.equals(sec, BUG_SEC)
            && tol == MathUtils.SAFE_MIN;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        String exc = "none";
        double[] eig = null;
        double[][] vecs = null;
        try {
            EigenDecomposition dec = new EigenDecompositionImpl(main, sec, tol);
            eig = dec.getRealEigenvalues();
            vecs = new double[eig.length][];
            for (int i = 0; i < eig.length; i++) {
                vecs[i] = dec.getEigenvector(i).toArray();
            }
        } catch (Throwable t) {
            exc = t.getClass().getName();
        }

        final String fexc = exc;
        final double[] feig = eig;
        final double[][] fvecs = vecs;
        Log.logOutIf(true, () -> new Object[]{ fexc, Arrays.toString(feig), Arrays.deepToString(fvecs) });

        double[] ref = {
            14.138204224043099, 18.847969733754262, 52.536278520113882, 53.456697699894512, 82.044413207204002
        };
        assertEquals("none", fexc);
        assertNotNull(feig);
        assertEquals(ref.length, feig.length);
        for (int i = 0; i < ref.length; i++) {
            assertEquals(ref[i], feig[i], 1.0e-6);
        }
    }
}
