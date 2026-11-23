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

@RunWith(JQF.class)
public class JQF_SparseRealVectorTest {

    public static class ValsGen extends Generator<double[]> {
        public ValsGen() { super(double[].class); }
        @Override public double[] generate(SourceOfRandomness r, GenerationStatus status) {
            double[] a = new double[8];
            for (int i = 0; i < a.length; i++) {
                int c = r.nextInt(10);
                if (c < 4) {
                    a[i] = 0.0;
                } else if (c == 4) {
                    a[i] = r.nextDouble(-1e-8, 1e-8);
                } else {
                    a[i] = r.nextDouble(-10, 10);
                }
            }
            return a;
        }
    }

    private static double eff(double val, double eps) {
        return Math.abs(val) < eps ? 0.0 : val;
    }

    // A stored entry of u whose product becomes (near) zero is removed from the map
    // while iterating; this fails when another stored entry of u follows it.
    private static boolean triggersBug(int dim, double eps, double[] uVals, double[] vVals) {
        int lastStored = -1;
        for (int i = 0; i < dim; i++) {
            if (Math.abs(uVals[i]) >= eps) {
                lastStored = i;
            }
        }
        for (int i = 0; i < lastStored; i++) {
            double ui = eff(uVals[i], eps);
            double vi = eff(vVals[i], eps);
            if (ui != 0.0 && Math.abs(ui * vi) < eps) {
                return true;
            }
        }
        return false;
    }

    @Fuzz
    public void testBugReproduction(@InRange(minInt = 1, maxInt = 9) int dim,
                         @InRange(minDouble = 1e-9, maxDouble = 1e-3) double eps,
                         @From(ValsGen.class) double[] uVals,
                         @From(ValsGen.class) double[] vVals) {
        boolean validIf = dim >= 1 && dim <= 8 && uVals.length >= dim && vVals.length >= dim && eps > 0;
        boolean buggyIf = validIf && triggersBug(dim, eps, uVals, vVals);

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        RealVector u = new OpenMapRealVector(dim, eps);
        RealVector v = new OpenMapRealVector(dim, eps);
        for (int i = 0; i < dim; i++) {
            u.setEntry(i, uVals[i]);
            v.setEntry(i, vVals[i]);
        }
        String exc = "none";
        RealVector res = null;
        try {
            res = u.ebeMultiply(v);
        } catch (RuntimeException e) {
            exc = e.getClass().getName();
        }
        final String excF = exc;
        final String resStr = res == null ? "null" : Arrays.toString(res.toArray());
        final String uStr = Arrays.toString(u.toArray());
        final String vStr = Arrays.toString(v.toArray());

        Log.logOutIf(true, () -> new String[]{excF, resStr, uStr, vStr});
        assertEquals("none", excF);
        assertNotNull(res);
        for (int i = 0; i < dim; i++) {
            double expected = eff(uVals[i], eps) * eff(vVals[i], eps);
            if (Math.abs(expected) < eps) {
                expected = 0.0;
            }
            assertEquals(expected, res.getEntry(i), 1e-12);
        }
    }
}
