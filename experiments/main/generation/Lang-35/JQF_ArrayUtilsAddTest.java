package org.apache.commons.lang3;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

@RunWith(JQF.class)
public class JQF_ArrayUtilsAddTest {

    public static class StringArrayGen extends Generator<String[]> {
        public StringArrayGen() { super(String[].class); }
        @Override public String[] generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextInt(10) < 6) {
                return null;
            }
            int n = r.nextInt(0, 5);
            String[] a = new String[n];
            for (int i = 0; i < n; i++) {
                a[i] = r.nextBoolean() ? null : "s" + r.nextInt(0, 100);
            }
            return a;
        }
    }

    public static class StringGen extends Generator<String> {
        public StringGen() { super(String.class); }
        @Override public String generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextInt(10) < 6) {
                return null;
            }
            return "e" + r.nextInt(0, 100);
        }
    }

    @Fuzz
    public void testBugReproduction(@From(StringArrayGen.class) String[] array,
                                    @From(StringGen.class) String element) {
        boolean validIf = true; // null or non-null array / element are all legal arguments
        boolean buggyIf = array == null && element == null;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        String exceptionName = "none";
        String resultClass = "null";
        int resultLength = -1;
        String resultContent = "null";
        try {
            Object result = ArrayUtils.add(array, element);
            if (result != null) {
                resultClass = result.getClass().getName();
                Object[] arr = (Object[]) result;
                resultLength = arr.length;
                resultContent = java.util.Arrays.toString(arr);
            }
        } catch (Throwable t) {
            exceptionName = t.getClass().getName();
        }

        final String fEx = exceptionName;
        final String fCls = resultClass;
        final int fLen = resultLength;
        final String fContent = resultContent;
        Log.logOutIf(true, () -> new Object[]{ fEx, fCls, fLen, fContent });
        String expectedException = "java.lang.IllegalArgumentException";
        assertEquals(expectedException, fEx);
    }

    @Fuzz
    public void testLANG571(@From(StringArrayGen.class) String[] array,
                                    @From(StringGen.class) String element) {
        boolean validIf = true;
        boolean buggyIf = array == null && element == null;
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            String exceptionName = "none";
            String resultClass = "null";
            int resultLength = -1;
            String resultContent = "null";
            try {
                Object result = ArrayUtils.add(array, element);
                if (result != null) {
                    resultClass = result.getClass().getName();
                    Object[] arr = (Object[]) result;
                    resultLength = arr.length;
                    resultContent = java.util.Arrays.toString(arr);
                }
            } catch (Throwable t) {
                exceptionName = t.getClass().getName();
            }

            final String fEx = exceptionName;
            final String fCls = resultClass;
            final int fLen = resultLength;
            final String fContent = resultContent;
            Log.logOutIf(preserveIf, () -> new Object[]{ fEx, fCls, fLen, fContent });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
