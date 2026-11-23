package org.apache.commons.math.util;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.apache.commons.math.util.MathUtils;

@RunWith(JQF.class)
public class JQF_MathUtilsTest {

    private static final long[] EXPECTED = new long[] {
        1L, 1L, 2L, 6L, 24L, 120L, 720L, 5040L, 40320L, 362880L, 3628800L,
        39916800L, 479001600L, 6227020800L, 87178291200L, 1307674368000L,
        20922789888000L, 355687428096000L, 6402373705728000L,
        121645100408832000L, 2432902008176640000L };

    @Fuzz
    public void testBugReproduction(@InRange(minInt = -5, maxInt = 30) int n) {
        // valid: n >= 0 and n! fits in a long (n <= 20)
        boolean validIf = n >= 0 && n <= 20;
        // bug: the log-based computation (exp of summed logs, rounded) is off for n = 17, 18, 19
        boolean buggyIf = n >= 17 && n <= 19;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        // The log-based calculation underlying factorial(n) is factorialDouble(n)
        double doubleResult = -1.0;
        String doubleException = "none";
        try {
            doubleResult = MathUtils.factorialDouble(n);
        } catch (Throwable t) {
            doubleException = t.getClass().getName();
        }

        long longResult = -1L;
        String longException = "none";
        try {
            longResult = MathUtils.factorial(n);
        } catch (Throwable t) {
            longException = t.getClass().getName();
        }

        final long fLong = longResult;
        final String fLongEx = longException;
        final double fDouble = doubleResult;
        final String fDoubleEx = doubleException;
        Log.logOutIf(true, () -> new Object[]{ Long.valueOf(fLong), fLongEx,
                Double.valueOf(fDouble), fDoubleEx });
        long expected = EXPECTED[n];
        assertEquals("none", fDoubleEx);
        assertEquals("none", fLongEx);
        assertEquals((double) expected, fDouble, 0.0);
        assertEquals(expected, (long) fDouble);
    }
}
