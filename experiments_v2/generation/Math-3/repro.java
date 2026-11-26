package org.apache.commons.math3.util;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.apache.commons.math3.util.MathArrays;

@RunWith(JQF.class)
public class JQF_MathArraysTest {

    public static class DoubleArrayGen extends Generator<double[]> {
        public DoubleArrayGen() { super(double[].class); }

        @Override
        public double[] generate(SourceOfRandomness r, GenerationStatus status) {
            int len;
            if (r.nextInt(100) < 55) {
                len = 1;
            } else {
                len = r.nextInt(0, 5);
            }
            double[] arr = new double[len];
            for (int i = 0; i < len; i++) {
                arr[i] = r.nextDouble(-1000.0, 1000.0);
            }
            return arr;
        }
    }

    @Fuzz
    public void testBugReproduction(@From(DoubleArrayGen.class) double[] a,
                                    @From(DoubleArrayGen.class) double[] b) {
        // Equal lengths are legal; different lengths raise DimensionMismatchException by design.
        boolean validIf = a != null && b != null && a.length == b.length;
        boolean buggyIf = a.length == 1 && b.length == 1;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        double result = Double.NaN;
        String exceptionName = "none";
        try {
            result = MathArrays.linearCombination(a, b);
        } catch (Throwable t) {
            exceptionName = t.getClass().getName();
        }

        final double fResult = result;
        final String fException = exceptionName;
        Log.logOutIf(true, () -> new Object[]{ fResult, fException });
        double expected = a[0] * b[0];
        assertEquals("no exception expected", "none", fException);
        assertEquals(expected, fResult, 1e-9);
    }

    @Fuzz
    public void testLinearCombinationWithSingleElementArray(@From(DoubleArrayGen.class) double[] a,
                                    @From(DoubleArrayGen.class) double[] b) {
        boolean validIf = a != null && b != null && a.length == b.length;
        boolean buggyIf = a.length == 1 && b.length == 1;
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            double result = Double.NaN;
            String exceptionName = "none";
            try {
                result = MathArrays.linearCombination(a, b);
            } catch (Throwable t) {
                exceptionName = t.getClass().getName();
            }

            final double fResult = result;
            final String fException = exceptionName;
            final int fLen = a.length;
            final long fBits = Double.doubleToLongBits(fResult);
            double naive = 0.0;
            for (int i = 0; i < a.length; i++) {
                naive += a[i] * b[i];
            }
            final long fNaiveBits = Double.doubleToLongBits(naive);
            final double fDiff = fResult - naive;
            Log.logOutIf(preserveIf, () -> new Object[]{ fResult, fException, fLen, fBits, fNaiveBits, fDiff });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
