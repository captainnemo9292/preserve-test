package org.apache.commons.lang;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import java.util.Arrays;
import java.util.stream.Collectors;

@RunWith(JQF.class)
public class JQF_StringEscapeUtilsTest {

    public static class StrGen extends Generator<String> {
        public StrGen() { super(String.class); }

        private static final String POOL =
            "abcXYZ019 _-.,;:()[]{}<>!?@#$%^&*+=~|'\"\\\t\n\r\b\f/";

        @Override
        public String generate(SourceOfRandomness r, GenerationStatus status) {
            int k = r.nextInt(20);
            if (k == 0) {
                return null;
            }
            int len = r.nextInt(0, 30);
            StringBuilder sb = new StringBuilder();
            boolean forceSlash = r.nextInt(100) < 50;
            for (int i = 0; i < len; i++) {
                int t = r.nextInt(10);
                if (t < 6) {
                    sb.append(POOL.charAt(r.nextInt(POOL.length())));
                } else if (t < 8) {
                    sb.append('/');
                } else if (t < 9) {
                    sb.append((char) r.nextInt(0, 0x2FF));
                } else {
                    sb.append((char) r.nextInt(0x20, 0x7F));
                }
            }
            if (forceSlash) {
                int pos = len == 0 ? 0 : r.nextInt(0, len + 1);
                sb.insert(pos, '/');
            }
            return sb.toString();
        }
    }

    @Fuzz
    public void testBugReproduction(@From(StrGen.class) String str) {
        boolean validIf = true; // any String, including null, is a legal argument
        boolean buggyIf = str != null && str.indexOf('/') >= 0;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        String actual = StringEscapeUtils.escapeJava(str);

        Log.logOutIf(true, () -> new String[]{ actual });
        // '/' is not escapable in Java: escaping is per-character, so escaping the
        // pieces between slashes and rejoining with a plain '/' is the expected result
        String expected = Arrays.stream(str.split("/", -1))
                .map(p -> StringEscapeUtils.escapeJava(p))
                .collect(Collectors.joining("/"));
        assertEquals(expected, actual);
    }

    @Fuzz
    public void testEscapeJavaWithSlash(@From(StrGen.class) String str) {
        boolean validIf = true;
        boolean buggyIf = str != null && str.indexOf('/') >= 0;
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            String actual = StringEscapeUtils.escapeJava(str);

            Log.logOutIf(preserveIf, () -> new String[]{ actual });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
