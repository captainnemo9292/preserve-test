package org.joda.time.field;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

@RunWith(JQF.class)
public class JQF_TestFieldUtils {

    public static class LongGen extends Generator<Long> {
        public LongGen() { super(Long.class); }
        @Override public Long generate(SourceOfRandomness r, GenerationStatus status) {
            int c = r.nextInt(10);
            if (c < 4) return Long.MIN_VALUE;
            if (c == 4) return Long.MAX_VALUE;
            if (c == 5) return Long.MIN_VALUE + r.nextInt(3);
            if (c == 6) return (long) r.nextInt(-100, 100);
            return r.nextLong(Long.MIN_VALUE, Long.MAX_VALUE);
        }
    }

    public static class IntGen extends Generator<Integer> {
        public IntGen() { super(Integer.class); }
        @Override public Integer generate(SourceOfRandomness r, GenerationStatus status) {
            int c = r.nextInt(10);
            if (c < 4) return -1;
            if (c == 4) return Integer.MIN_VALUE;
            if (c == 5) return Integer.MAX_VALUE;
            if (c == 6) return r.nextInt(-3, 4);
            return r.nextInt(Integer.MIN_VALUE, Integer.MAX_VALUE);
        }
    }

    @Fuzz
    public void testBugReproduction(@From(LongGen.class) long val1,
                                    @From(IntGen.class) int scalar) {
        boolean validIf = true;
        boolean buggyIf = val1 == Long.MIN_VALUE && scalar == -1;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        long result = 0;
        String exc = "none";
        try {
            result = FieldUtils.safeMultiply(val1, scalar);
        } catch (Throwable t) {
            exc = t.getClass().getName();
        }
        final long fr = result;
        final String fe = exc;

        Log.logOutIf(true, () -> new Object[]{ fr, fe });
        String expected = "java.lang.ArithmeticException";
        assertEquals(expected, fe);
    }

    @Fuzz
    public void testSafeMultiplyLongInt(@From(LongGen.class) long val1,
                                    @From(IntGen.class) int scalar) {
        boolean validIf = true;
        boolean buggyIf = val1 == Long.MIN_VALUE && scalar == -1;
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            long result = 0;
            String exc = "none";
            try {
                result = FieldUtils.safeMultiply(val1, scalar);
            } catch (Throwable t) {
                exc = t.getClass().getName();
            }
            final long fr = result;
            final String fe = exc;
            final long fv = val1;
            final int fs = scalar;

            Log.logOutIf(preserveIf, () -> new Object[]{ fr, fe, fv, fs, Long.hashCode(fr), fe.length() });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
