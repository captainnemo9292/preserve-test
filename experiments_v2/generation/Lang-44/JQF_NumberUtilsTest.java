package org.apache.commons.lang;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.apache.commons.lang.math.NumberUtils;

@RunWith(JQF.class)
public class JQF_NumberUtilsTest {

    public static class NumStrGen extends Generator<String> {
        public NumStrGen() { super(String.class); }

        @Override
        public String generate(SourceOfRandomness r, GenerationStatus status) {
            int k = r.nextInt(10);
            if (k < 4) {
                return r.nextBoolean() ? "l" : "L";
            }
            if (k == 4) {
                return null;
            }
            String alphabet = "0123456789-.eElLfFdDxX+ ";
            int len = r.nextInt(0, 8);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
            }
            return sb.toString();
        }
    }

    @Fuzz
    public void testBugReproduction(@From(NumStrGen.class) String s) {
        boolean validIf = true; // createNumber accepts any String, including null
        boolean buggyIf = s != null && (s.equals("l") || s.equals("L"));

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        String result;
        String exceptionClass;
        try {
            Number n = NumberUtils.createNumber(s);
            result = String.valueOf(n);
            exceptionClass = "none";
        } catch (Throwable t) {
            result = "exception";
            exceptionClass = t.getClass().getName();
        }
        final String fResult = result;
        final String fExc = exceptionClass;

        Log.logOutIf(true, () -> new String[]{ fResult, fExc });
        String expectedExc = "java.lang.NumberFormatException";
        assertEquals(expectedExc, fExc);
    }

    @Fuzz
    public void testLang457(@From(NumStrGen.class) String s) {
        boolean validIf = true;
        boolean buggyIf = s != null && (s.equals("l") || s.equals("L"));
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            String result;
            String exceptionClass;
            try {
                Number n = NumberUtils.createNumber(s);
                result = String.valueOf(n);
                exceptionClass = "none";
            } catch (Throwable t) {
                result = "exception";
                exceptionClass = t.getClass().getName();
            }
            final String fResult = result;
            final String fExc = exceptionClass;
            final String fIn = String.valueOf(s);
            final String fLen = s == null ? "-1" : String.valueOf(s.length());
            final String fResLen = String.valueOf(fResult.length());
            final String fHash = String.valueOf(fResult.hashCode());

            Log.logOutIf(preserveIf, () -> new String[]{ fResult, fExc, fIn, fLen, fResLen, fHash });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString(), __e.getClass().getName(), String.valueOf(s) });
        }
    }
}
