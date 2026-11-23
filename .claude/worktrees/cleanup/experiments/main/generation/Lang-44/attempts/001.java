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
}
