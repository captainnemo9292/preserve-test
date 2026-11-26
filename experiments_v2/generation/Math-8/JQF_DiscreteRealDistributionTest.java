package org.apache.commons.math3.distribution;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.math3.random.Well19937c;
import org.apache.commons.math3.util.MathArrays;
import org.apache.commons.math3.util.Pair;

@RunWith(JQF.class)
public class JQF_DiscreteRealDistributionTest {

    private static Object make(int type, int i) {
        switch (type) {
            case 0: return Integer.valueOf(i);
            case 1: return "s" + i;
            default: return Double.valueOf(i + 0.5);
        }
    }

    /**
     * Replays the random draws that sample(int) performs (same seeded generator,
     * same cumulative-sum selection) and reports whether any drawn element has a
     * class that is not the class of the first singleton.
     */
    private static boolean samplesOtherClass(int n, int firstType, int otherType,
                                             int pFirstW, int pOtherW,
                                             int sampleSize, int seed) {
        if (n < 2 || firstType == otherType) {
            return false;
        }
        if (pFirstW + (n - 1) * pOtherW <= 0 || sampleSize <= 0) {
            return false;
        }
        double[] w = new double[n];
        w[0] = pFirstW;
        for (int i = 1; i < n; i++) {
            w[i] = pOtherW;
        }
        double[] probs = MathArrays.normalizeArray(w, 1.0);
        Well19937c rng = new Well19937c(seed);
        for (int k = 0; k < sampleSize; k++) {
            double r = rng.nextDouble();
            double sum = 0;
            int idx = n - 1;
            for (int i = 0; i < n; i++) {
                sum += probs[i];
                if (r < sum) {
                    idx = i;
                    break;
                }
            }
            if (idx != 0) {
                return true;
            }
        }
        return false;
    }

    @Fuzz
    public void testBugReproduction(@InRange(minInt = 1, maxInt = 7) int n,
                         @InRange(minInt = 0, maxInt = 3) int firstType,
                         @InRange(minInt = 0, maxInt = 3) int otherType,
                         @InRange(minInt = 0, maxInt = 4) int pFirstW,
                         @InRange(minInt = 0, maxInt = 4) int pOtherW,
                         @InRange(minInt = 1, maxInt = 11) int sampleSize,
                         @InRange(minInt = 0, maxInt = 1000) int seed) {
        boolean validIf = pFirstW + (n - 1) * pOtherW > 0;
        boolean buggyIf = samplesOtherClass(n, firstType, otherType, pFirstW, pOtherW, sampleSize, seed);

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        List<Pair<Object, Double>> list = new ArrayList<Pair<Object, Double>>();
        list.add(new Pair<Object, Double>(make(firstType, 0), Double.valueOf(pFirstW)));
        for (int i = 1; i < n; i++) {
            list.add(new Pair<Object, Double>(make(otherType, i), Double.valueOf(pOtherW)));
        }

        String exception = "none";
        int length = -1;
        String contents = "";
        try {
            DiscreteDistribution<Object> d =
                new DiscreteDistribution<Object>(new Well19937c(seed), list);
            Object[] out = d.sample(sampleSize);
            length = out.length;
            StringBuilder sb = new StringBuilder();
            for (Object o : out) {
                sb.append(o).append(';');
            }
            contents = sb.toString();
        } catch (Throwable t) {
            exception = t.getClass().getName();
        }

        final String exceptionF = exception;
        final int lengthF = length;
        final String contentsF = contents;
        Log.logOutIf(true, () -> new Object[]{ exceptionF, lengthF, contentsF });
        assertEquals("none", exceptionF);
        assertEquals(sampleSize, lengthF);
    }

    @Fuzz
    public void testIssue942(@InRange(minInt = 1, maxInt = 7) int n,
                         @InRange(minInt = 0, maxInt = 3) int firstType,
                         @InRange(minInt = 0, maxInt = 3) int otherType,
                         @InRange(minInt = 0, maxInt = 4) int pFirstW,
                         @InRange(minInt = 0, maxInt = 4) int pOtherW,
                         @InRange(minInt = 1, maxInt = 11) int sampleSize,
                         @InRange(minInt = 0, maxInt = 1000) int seed) {
        boolean validIf = pFirstW + (n - 1) * pOtherW > 0;
        boolean buggyIf = samplesOtherClass(n, firstType, otherType, pFirstW, pOtherW, sampleSize, seed);
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            List<Pair<Object, Double>> list = new ArrayList<Pair<Object, Double>>();
            list.add(new Pair<Object, Double>(make(firstType, 0), Double.valueOf(pFirstW)));
            for (int i = 1; i < n; i++) {
                list.add(new Pair<Object, Double>(make(otherType, i), Double.valueOf(pOtherW)));
            }

            String exception = "none";
            int length = -1;
            String contents = "";
            try {
                DiscreteDistribution<Object> d =
                    new DiscreteDistribution<Object>(new Well19937c(seed), list);
                Object[] out = d.sample(sampleSize);
                length = out.length;
                StringBuilder sb = new StringBuilder();
                for (Object o : out) {
                    sb.append(o).append(';');
                }
                contents = sb.toString();
            } catch (Throwable t) {
                exception = t.getClass().getName();
            }

            final String exceptionF = exception;
            final int lengthF = length;
            final String contentsF = contents;
            final int contentsLen = contents.length();
            final int contentsHash = contents.hashCode();
            final int listSize = list.size();
            Log.logOutIf(preserveIf, () -> new Object[]{ exceptionF, lengthF, contentsF, contentsLen, contentsHash, listSize, sampleSize });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString(), __e.getClass().getName() });
        }
    }
}
