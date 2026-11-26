package org.apache.commons.lang3.math;

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

    public static class NumStrGen extends Generator<String> {
        public NumStrGen() { super(String.class); }

        private static void digits(StringBuilder sb, SourceOfRandomness r, int max) {
            int n = r.nextInt(0, max);
            for (int i = 0; i < n; i++) {
                sb.append((char) ('0' + r.nextInt(0, 9)));
            }
        }

        @Override
        public String generate(SourceOfRandomness r, GenerationStatus status) {
            StringBuilder sb = new StringBuilder();
            int mode = r.nextInt(0, 9);
            if (mode <= 4) {
                // bug-shaped: [-]digits . digits [lL]
                if (r.nextBoolean()) sb.append('-');
                digits(sb, r, 6);
                sb.append('.');
                digits(sb, r, 6);
                sb.append(r.nextBoolean() ? 'L' : 'l');
            } else if (mode == 5) {
                // integer long
                if (r.nextBoolean()) sb.append('-');
                digits(sb, r, 8);
                sb.append(r.nextBoolean() ? 'L' : 'l');
            } else if (mode == 6) {
                // exponent forms
                if (r.nextBoolean()) sb.append('-');
                digits(sb, r, 4);
                if (r.nextBoolean()) { sb.append('.'); digits(sb, r, 4); }
                sb.append(r.nextBoolean() ? 'e' : 'E');
                if (r.nextBoolean()) sb.append(r.nextBoolean() ? '+' : '-');
                digits(sb, r, 4);
                if (r.nextBoolean()) sb.append("lLdDfF".charAt(r.nextInt(0, 5)));
            } else if (mode == 7) {
                // decimals with other suffixes
                if (r.nextBoolean()) sb.append('-');
                digits(sb, r, 5);
                if (r.nextBoolean()) { sb.append('.'); digits(sb, r, 5); }
                if (r.nextBoolean()) sb.append("dDfF".charAt(r.nextInt(0, 4)));
            } else {
                // arbitrary characters
                String alphabet = "0123456789.-+eExXlLdDfFaAbc ";
                int n = r.nextInt(0, 8);
                for (int i = 0; i < n; i++) {
                    sb.append(alphabet.charAt(r.nextInt(0, alphabet.length() - 1)));
                }
            }
            return sb.toString();
        }
    }

    @Fuzz
    public void testBugReproduction(@From(NumStrGen.class) String s) {
        boolean validIf = s != null;
        boolean buggyIf = validIf
                && s.matches("-?[0-9]*\\.[0-9]*[lL]")
                && s.matches(".*[0-9].*");

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        boolean result = NumberUtils.isNumber(s);

        Log.logOutIf(true, () -> new Object[]{ s, Boolean.valueOf(result) });
        boolean expected = false;
        assertEquals("isNumber(\"" + s + "\") must be false: a long cannot have a decimal point",
                expected, result);
    }

    @Fuzz
    public void testIsNumber(@From(NumStrGen.class) String s) {
        boolean validIf = s != null;
        boolean buggyIf = validIf
                && s.matches("-?[0-9]*\\.[0-9]*[lL]")
                && s.matches(".*[0-9].*");
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            boolean result = NumberUtils.isNumber(s);

            Log.logOutIf(preserveIf, () -> new Object[]{ s, Boolean.valueOf(result), Integer.valueOf(s.length()), Integer.valueOf(s.hashCode()), Boolean.valueOf(s.endsWith("l") || s.endsWith("L")), Boolean.valueOf(s.indexOf('.') >= 0) });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString(), __e.getClass().getName(), String.valueOf(s) });
        }
    }
}
