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

        @Override
        public String generate(SourceOfRandomness r, GenerationStatus status) {
            int k = r.nextInt(10);
            if (k < 4) {
                // bug-triggering: length 3 starting with t or T
                char first = r.nextBoolean() ? 't' : 'T';
                return first + rand(r, 2);
            }
            if (k == 4) {
                return null;
            }
            if (k == 5) {
                String[] words = {"true", "TRUE", "True", "yes", "YES", "Yes", "on", "ON", "off", "no", "false", "tru", ""};
                return words[r.nextInt(words.length)];
            }
            return rand(r, r.nextInt(7));
        }
    }

    @Fuzz
    public void testBugReproduction(@From(StrGen.class) String s) {
        boolean validIf = true;
        boolean buggyIf = s != null && s.length() == 3
                && (s.charAt(0) == 't' || s.charAt(0) == 'T');

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
}
