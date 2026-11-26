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

    private static int randomLength(SourceOfRandomness r) {
        int k = r.nextInt(0, 9);
        if (k == 0) return 0;
        if (k == 1) return 1;
        if (k <= 6) return 2;
        return 3;
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

    /** Search list: occasionally null, otherwise non-null (possibly empty) strings. */
    public static class SearchGen extends Generator<String[]> {
        public SearchGen() { super(String[].class); }
        @Override public String[] generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextInt(0, 19) == 0) return null;
            String[] arr = new String[randomLength(r)];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = r.nextInt(0, 9) == 0 ? "" : randomWord(r, 1, 2);
            }
            return arr;
        }
    }

    /** Replacement list: occasionally null, otherwise null/empty/short strings. */
    public static class ReplacementGen extends Generator<String[]> {
        public ReplacementGen() { super(String[].class); }
        @Override public String[] generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextInt(0, 19) == 0) return null;
            String[] arr = new String[randomLength(r)];
            for (int i = 0; i < arr.length; i++) {
                int k = r.nextInt(0, 9);
                if (k < 4) arr[i] = null;
                else if (k == 4) arr[i] = "";
                else arr[i] = randomWord(r, 1, 4);
            }
            return arr;
        }
    }

    /** True if the arrays have matching lengths (or either is null). */
    private static boolean lengthsOk(String[] s, String[] rep) {
        return s == null || rep == null || s.length == rep.length;
    }

    /**
     * True if some replacement is null and some other pair with a non-null replacement
     * and a non-empty search string actually matches in the text (so the buggy size
     * estimation loop is reached and dereferences the null replacement).
     */
    private static boolean triggersNpe(String text, String[] s, String[] rep) {
        if (text == null || text.length() == 0 || s == null || rep == null
                || s.length == 0 || s.length != rep.length) {
            return false;
        }
        boolean hasNullReplacement = false;
        boolean hasActiveMatch = false;
        for (int i = 0; i < rep.length; i++) {
            if (rep[i] == null) {
                hasNullReplacement = true;
            } else if (s[i] != null && s[i].length() > 0 && text.contains(s[i])) {
                hasActiveMatch = true;
            }
        }
        return hasNullReplacement && hasActiveMatch;
    }

    @Fuzz
    public void testBugReproduction(@From(TextGen.class) String text,
                                    @From(SearchGen.class) String[] searchList,
                                    @From(ReplacementGen.class) String[] replacementList) {
        boolean validIf = lengthsOk(searchList, replacementList);
        boolean buggyIf = triggersNpe(text, searchList, replacementList);

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

    @Fuzz
    public void testReplace_StringStringArrayStringArray(@From(TextGen.class) String text,
                                    @From(SearchGen.class) String[] searchList,
                                    @From(ReplacementGen.class) String[] replacementList) {
        boolean validIf = lengthsOk(searchList, replacementList);
        boolean buggyIf = triggersNpe(text, searchList, replacementList);
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            String result = null;
            String exceptionClass = "none";
            try {
                result = StringUtils.replaceEach(text, searchList, replacementList);
            } catch (Throwable t) {
                exceptionClass = t.getClass().getName();
            }

            final String fResult = result;
            final String fException = exceptionClass;
            Log.logOutIf(preserveIf, () -> new String[]{
                    "result=" + fResult,
                    "resultLen=" + (fResult == null ? -1 : fResult.length()),
                    "resultHash=" + (fResult == null ? 0 : fResult.hashCode()),
                    "sameAsText=" + (fResult == text),
                    "equalsText=" + (fResult != null && fResult.equals(text)),
                    "exception=" + fException,
                    "text=" + text,
                    "search=" + Arrays.toString(searchList),
                    "replacement=" + Arrays.toString(replacementList)
            });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
