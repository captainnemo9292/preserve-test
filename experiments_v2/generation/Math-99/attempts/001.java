package org.apache.commons.math.util;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

@RunWith(JQF.class)
public class JQF_MathUtilsTest {

    public static class IntGen extends Generator<Integer> {
        public IntGen() { super(Integer.class); }
        @Override public Integer generate(SourceOfRandomness r, GenerationStatus status) {
            int c = r.nextInt(0, 9);
            if (c < 4) return Integer.MIN_VALUE;
            if (c < 8) return 0;
            if (c == 8) return r.nextInt(-100, 100);
            return r.nextInt();
        }
    }

    @Fuzz
    public void testBugReproduction(@From(IntGen.class) int p,
                                    @From(IntGen.class) int q) {
        // gcd is defined for all int pairs; only the overflowing result (2^31) is an error
        boolean validIf = true;
        boolean buggyIf = (p == Integer.MIN_VALUE && q == 0) || (p == 0 && q == Integer.MIN_VALUE);

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        String exceptionClass = "none";
        int result = 0;
        try {
            result = MathUtils.gcd(p, q);
        } catch (Throwable t) {
            exceptionClass = t.getClass().getName();
        }

        final String ex = exceptionClass;
        final int res = result;
        Log.logOutIf(true, () -> new Object[]{ ex, res });
        assertEquals("java.lang.ArithmeticException", ex);
    }

    @Fuzz
    public void testGcd(@From(IntGen.class) int p,
                                    @From(IntGen.class) int q) {
        boolean validIf = true;
        boolean buggyIf = (p == Integer.MIN_VALUE && q == 0) || (p == 0 && q == Integer.MIN_VALUE);
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            String exceptionClass = "none";
            int result = 0;
            try {
                result = MathUtils.gcd(p, q);
            } catch (Throwable t) {
                exceptionClass = t.getClass().getName();
            }

            final String ex = exceptionClass;
            final int res = result;
            Log.logOutIf(preserveIf, () -> new Object[]{ ex, res });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
