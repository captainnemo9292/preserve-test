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

    @Fuzz
    public void testBugReproduction(@InRange(minInt = 1, maxInt = 7) int n,
                         @InRange(minInt = 0, maxInt = 3) int firstType,
                         @InRange(minInt = 0, maxInt = 3) int otherType,
                         boolean firstZero,
                         @InRange(minDouble = 0, maxDouble = 1) double pFirstRaw,
                         @InRange(minDouble = 0, maxDouble = 1) double pOther,
                         @InRange(minInt = 1, maxInt = 11) int sampleSize,
                         @InRange(minInt = 0, maxInt = 1000) int seed) {
        double pFirst = firstZero ? 0.0 : pFirstRaw;
        double total = pFirst + (n - 1) * pOther;
        boolean validIf = total > 0 && sampleSize > 0;
        // The first singleton has probability 0 (never sampled except the fallback),
        // all sampled elements have a class unrelated to the first element's class.
        boolean buggyIf = n >= 2 && firstType != otherType && pFirst == 0.0 && pOther > 0;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        List<Pair<Object, Double>> list = new ArrayList<Pair<Object, Double>>();
        list.add(new Pair<Object, Double>(make(firstType, 0), pFirst));
        for (int i = 1; i < n; i++) {
            list.add(new Pair<Object, Double>(make(otherType, i), pOther));
        }

        String exception = "none";
        int length = -1;
        try {
            DiscreteDistribution<Object> d =
                new DiscreteDistribution<Object>(new Well19937c(seed), list);
            Object[] out = d.sample(sampleSize);
            length = out.length;
        } catch (Throwable t) {
            exception = t.getClass().getName();
        }

        final String exceptionF = exception;
        final int lengthF = length;
        Log.logOutIf(true, () -> new Object[]{ exceptionF, lengthF });
        assertEquals("none", exceptionF);
        assertEquals(sampleSize, lengthF);
    }
}
