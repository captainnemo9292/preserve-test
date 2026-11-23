package org.apache.commons.math.stat;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

@RunWith(JQF.class)
public class JQF_FrequencyTest {

    public static class NonComparable {
        public final int id;
        public NonComparable(int id) { this.id = id; }
        @Override public String toString() { return "NC" + id; }
    }

    public static class ValueGen extends Generator<Object> {
        public ValueGen() { super(Object.class); }
        @Override public Object generate(SourceOfRandomness r, GenerationStatus status) {
            int k = r.nextInt(6);
            switch (k) {
                case 0: return new Object();
                case 1: return new NonComparable(r.nextInt(-100, 100));
                case 2: return new StringBuilder("sb" + r.nextInt(0, 50));
                case 3: return Integer.valueOf(r.nextInt(-100, 100));
                case 4: return "s" + r.nextInt(0, 50);
                default: return Long.valueOf(r.nextInt(-100, 100));
            }
        }
    }

    @Fuzz
    public void testBugReproduction(@From(ValueGen.class) Object v,
                         @InRange(minInt = 0, maxInt = 4) int prefixCount) {
        boolean validIf = v != null;
        boolean buggyIf = !(v instanceof Comparable);

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        final Frequency f = new Frequency();
        for (int i = 0; i < prefixCount; i++) {
            f.addValue("p" + i);
        }
        String exClass = "none";
        String exMsg = "none";
        try {
            f.addValue(v);
        } catch (RuntimeException e) {
            exClass = e.getClass().getName();
            exMsg = String.valueOf(e.getMessage());
        }
        final String exClassF = exClass;
        final String exMsgF = exMsg;
        final long sum = f.getSumFreq();
        final long cnt = f.getCount(v);

        Log.logOutIf(true, () -> new String[]{ exClassF, exMsgF, String.valueOf(sum), String.valueOf(cnt) });
        assertNotEquals("addValue of a non-Comparable object must be rejected", "none", exClassF);
        assertTrue("rejection must state that the object must implement Comparable, but was: " + exMsgF,
                exMsgF.contains("must implement Comparable"));
    }
}
