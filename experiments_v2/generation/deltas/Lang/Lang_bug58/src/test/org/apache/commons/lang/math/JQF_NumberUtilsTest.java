package org.apache.commons.lang.math;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

@RunWith(JQF.class)
public class JQF_NumberUtilsTest {

    public static class DigitsGen extends Generator<String> {
        public DigitsGen() { super(String.class); }
        @Override
        public String generate(SourceOfRandomness r, GenerationStatus status) {
            int len;
            if (r.nextInt(100) < 45) {
                len = 1;
            } else {
                len = 1 + r.nextInt(12);
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                sb.append((char) ('0' + r.nextInt(10)));
            }
            return sb.toString();
        }
    }

    @Fuzz
    public void testBugReproduction(@From(DigitsGen.class) String digits,
                                    boolean negative,
                                    boolean upperSuffix) {
        boolean validIf = digits != null && digits.length() >= 1 && digits.length() <= 12 && digits.matches("[0-9]+");
        boolean buggyIf = digits != null && !negative && digits.length() == 1;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        String input = (negative ? "-" : "") + digits + (upperSuffix ? "L" : "l");
        Number result = null;
        String exceptionClass = "none";
        try {
            result = NumberUtils.createNumber(input);
        } catch (Throwable t) {
            exceptionClass = t.getClass().getName();
        }
        final Number fResult = result;
        final String fEx = exceptionClass;

        Log.logOutIf(true, () -> new String[]{
            fEx,
            fResult == null ? "null" : fResult.getClass().getName(),
            fResult == null ? "null" : fResult.toString()
        });
        Long expected = Long.valueOf((negative ? "-" : "") + digits);
        assertEquals("none", fEx);
        assertNotNull(fResult);
        assertEquals(expected, fResult);
    }

    @Fuzz
    public void testLang300(@From(DigitsGen.class) String digits,
                                    boolean negative,
                                    boolean upperSuffix) {
        boolean validIf = digits != null && digits.length() >= 1 && digits.length() <= 12 && digits.matches("[0-9]+");
        boolean buggyIf = digits != null && !negative && digits.length() == 1;
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            String input = (negative ? "-" : "") + digits + (upperSuffix ? "L" : "l");
            Number result = null;
            String exceptionClass = "none";
            try {
                result = NumberUtils.createNumber(input);
            } catch (Throwable t) {
                exceptionClass = t.getClass().getName();
            }
            final Number fResult = result;
            final String fEx = exceptionClass;
            final String fInput = input;
            final int fLen = digits.length();

            Log.logOutIf(preserveIf, () -> new String[]{
                fEx,
                fResult == null ? "null" : fResult.getClass().getName(),
                fResult == null ? "null" : fResult.toString(),
                "input=" + fInput,
                "len=" + fLen,
                "neg=" + negative,
                "hash=" + (fResult == null ? 0 : fResult.hashCode())
            });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
