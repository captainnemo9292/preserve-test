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
        // bug: the log-based calculation (exp of summed logs, rounded) used for factorial values
        // is off for n = 17, 18, 19
        boolean buggyIf = n >= 17 && n <= 19;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        // factorialDouble(n) is the sum-of-logarithms computation from the report; it must
        // yield the exact integer n! for every n whose factorial fits in a long.
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
        assertEquals((double) expected, fDouble, 0.0);
    }

    @Fuzz
    public void testFactorial(@InRange(minInt = -5, maxInt = 30) int n) {
        boolean validIf = n >= 0 && n <= 20;
        boolean buggyIf = n >= 17 && n <= 19;
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            // factorialDouble(n) is the sum-of-logarithms computation from the report; it must
            // yield the exact integer n! for every n whose factorial fits in a long.
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
            final int fN = n;
            Log.logOutIf(preserveIf, () -> new Object[]{ Integer.valueOf(fN), Long.valueOf(fLong), fLongEx,
                    Double.valueOf(fDouble), fDoubleEx,
                    Long.valueOf(Double.doubleToLongBits(fDouble)),
                    Boolean.valueOf(fDouble == (double) fLong),
                    Long.valueOf(fLong - (long) fDouble) });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
