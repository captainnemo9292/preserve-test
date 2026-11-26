package org.apache.commons.lang;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

@RunWith(JQF.class)
public class JQF_BooleanUtilsTest {

    public static class StrGen extends Generator<String> {
        private static final String ALPHA = "truefalsynoTRUEFALSYNO xz01";

        public StrGen() { super(String.class); }

        private String rand(SourceOfRandomness r, int len) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                sb.append(ALPHA.charAt(r.nextInt(ALPHA.length())));
            }
            return sb.toString();
        }

        private char pick(SourceOfRandomness r, char lower) {
            return r.nextBoolean() ? lower : Character.toUpperCase(lower);
        }

        @Override
        public String generate(SourceOfRandomness r, GenerationStatus status) {
            int k = r.nextInt(10);
            if (k < 4) {
                // bug-triggering: 3 chars t/T, r/R, u/U
                return "" + pick(r, 't') + pick(r, 'r') + pick(r, 'u');
            }
            if (k == 4) {
                return null;
            }
            if (k == 5) {
                String[] words = {"true", "TRUE", "True", "yes", "YES", "Yes", "on", "ON", "off", "no", "false", "tru", ""};
                return words[r.nextInt(words.length)];
            }
            if (k == 6) {
                // near misses of length 3 starting with t/T
                return "" + pick(r, 't') + rand(r, 2);
            }
            return rand(r, r.nextInt(7));
        }
    }

    @Fuzz
    public void testBugReproduction(@From(StrGen.class) String s) {
        boolean validIf = true;
        boolean buggyIf = s != null && s.length() == 3
                && (s.charAt(0) == 't' || s.charAt(0) == 'T')
                && (s.charAt(1) == 'r' || s.charAt(1) == 'R')
                && (s.charAt(2) == 'u' || s.charAt(2) == 'U');

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        boolean result = false;
        String exc = "none";
        try {
            result = BooleanUtils.toBoolean(s);
        } catch (Throwable t) {
            exc = t.getClass().getName();
        }

        final boolean fResult = result;
        final String fExc = exc;
        Log.logOutIf(true, () -> new Object[]{ String.valueOf(fResult), fExc });
        boolean expected = false;
        assertEquals("none", fExc);
        assertEquals(expected, fResult);
    }

    @Fuzz
    public void test_toBoolean_String(@From(StrGen.class) String s) {
        boolean validIf = true;
        boolean buggyIf = s != null && s.length() == 3
                && (s.charAt(0) == 't' || s.charAt(0) == 'T')
                && (s.charAt(1) == 'r' || s.charAt(1) == 'R')
                && (s.charAt(2) == 'u' || s.charAt(2) == 'U');
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            boolean result = false;
            String exc = "none";
            try {
                result = BooleanUtils.toBoolean(s);
            } catch (Throwable t) {
                exc = t.getClass().getName();
            }

            final boolean fResult = result;
            final String fExc = exc;
            final String fIn = s == null ? "<null>" : s;
            final int fLen = s == null ? -1 : s.length();
            Log.logOutIf(preserveIf, () -> new Object[]{ String.valueOf(fResult), fExc, fIn, String.valueOf(fLen) });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString(), __e.getClass().getName() });
        }
    }
}
