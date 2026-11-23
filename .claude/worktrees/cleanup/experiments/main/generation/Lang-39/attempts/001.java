package org.apache.commons.lang3;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

import java.util.Arrays;

@RunWith(JQF.class)
public class JQF_StringUtilsTest {

    private static final String ALPHABET = "abc";

    private static String randomWord(SourceOfRandomness r, int minLen, int maxLen) {
        int len = r.nextInt(minLen, maxLen);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(ALPHABET.charAt(r.nextInt(0, ALPHABET.length() - 1)));
        }
        return sb.toString();
    }

    /** Text: null, empty, or a short word over a small alphabet. */
    public static class TextGen extends Generator<String> {
        public TextGen() { super(String.class); }
        @Override public String generate(SourceOfRandomness r, GenerationStatus status) {
            int k = r.nextInt(0, 9);
            if (k == 0) return null;
            if (k == 1) return "";
            return randomWord(r, 1, 8);
        }
    }

    /** Search pool: non-null strings (possibly empty). */
    public static class SearchGen extends Generator<String[]> {
        public SearchGen() { super(String[].class); }
        @Override public String[] generate(SourceOfRandomness r, GenerationStatus status) {
            String[] arr = new String[4];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = r.nextInt(0, 9) == 0 ? "" : randomWord(r, 1, 2);
            }
            return arr;
        }
    }

    /** Replacement pool: null, empty or short strings. */
    public static class ReplacementGen extends Generator<String[]> {
        public ReplacementGen() { super(String[].class); }
        @Override public String[] generate(SourceOfRandomness r, GenerationStatus status) {
            String[] arr = new String[4];
            for (int i = 0; i < arr.length; i++) {
                int k = r.nextInt(0, 9);
                if (k < 4) arr[i] = null;
                else if (k == 4) arr[i] = "";
                else arr[i] = randomWord(r, 1, 4);
            }
            return arr;
        }
    }

    @Fuzz
    public void testBugReproduction(@From(TextGen.class) String text,
                                    @From(SearchGen.class) String[] searchPool,
                                    @From(ReplacementGen.class) String[] replacementPool,
                                    @InRange(minInt = 0, maxInt = 5) int n) {
        String[] searchList = Arrays.copyOf(searchPool, Math.min(n, searchPool.length));
        String[] replacementList = Arrays.copyOf(replacementPool, Math.min(n, replacementPool.length));

        boolean validIf = searchList.length == replacementList.length;

        boolean hasNullReplacement = false;
        boolean hasActiveMatch = false;
        if (text != null && text.length() > 0) {
            for (int i = 0; i < replacementList.length; i++) {
                if (replacementList[i] == null) {
                    hasNullReplacement = true;
                } else if (searchList[i] != null && searchList[i].length() > 0
                        && text.contains(searchList[i])) {
                    hasActiveMatch = true;
                }
            }
        }
        boolean buggyIf = hasNullReplacement && hasActiveMatch;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        String result = null;
        String exceptionClass = "none";
        try {
            result = StringUtils.replaceEach(text, searchList, replacementList);
        } catch (Throwable t) {
            exceptionClass = t.getClass().getName();
        }

        final String fResult = result;
        final String fException = exceptionClass;
        Log.logOutIf(true, () -> new String[]{
                "result=" + fResult,
                "exception=" + fException,
                "text=" + text,
                "search=" + Arrays.toString(searchList),
                "replacement=" + Arrays.toString(replacementList)
        });
        assertEquals("none", fException);
        assertNotNull(fResult);
    }
}
