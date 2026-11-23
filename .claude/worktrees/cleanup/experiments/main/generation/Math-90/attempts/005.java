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
                case 3: return "p" + r.nextInt(0, 4);
                case 4: return "s" + r.nextInt(0, 50);
                default: return Long.valueOf(r.nextInt(-100, 100));
            }
        }
    }

    private static String cls(Throwable t) {
        return t == null ? "none" : t.getClass().getName();
    }

    @Fuzz
    public void testBugReproduction(@From(ValueGen.class) Object v,
                         @InRange(minInt = 1, maxInt = 5) int prefixCount) {
        boolean validIf = v != null;
        // Lookups with a non-Comparable object are not rejected: they silently return
        // 0 / 0.0 as if the object were simply absent.
        boolean buggyIf = !(v instanceof Comparable);

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        final Frequency f = new Frequency();
        for (int i = 0; i < prefixCount; i++) {
            f.addValue("p" + i);
        }
        long count = -1;
        String countEx = "none";
        try {
            count = f.getCount(v);
        } catch (RuntimeException e) {
            countEx = cls(e);
        }
        double pct = -1;
        String pctEx = "none";
        try {
            pct = f.getPct(v);
        } catch (RuntimeException e) {
            pctEx = cls(e);
        }
        long cum = -1;
        String cumEx = "none";
        try {
            cum = f.getCumFreq(v);
        } catch (RuntimeException e) {
            cumEx = cls(e);
        }
        double cumPct = -1;
        String cumPctEx = "none";
        try {
            cumPct = f.getCumPct(v);
        } catch (RuntimeException e) {
            cumPctEx = cls(e);
        }
        final String[] out = new String[]{
            String.valueOf(count), countEx,
            String.valueOf(pct), pctEx,
            String.valueOf(cum), cumEx,
            String.valueOf(cumPct), cumPctEx,
            String.valueOf(f.getSumFreq())
        };
        final String countExF = countEx;

        Log.logOutIf(true, () -> out);
        assertNotEquals("getCount of a non-Comparable object must be rejected, not silently return 0",
                "none", countExF);
    }
}
