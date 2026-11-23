package org.joda.time;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

@RunWith(JQF.class)
public class JQF_TestPartial_Basics {

    // index: 0 clockhourOfDay, 1 hourOfDay, 2 clockhourOfHalfday, 3 hourOfHalfday,
    // 4 minuteOfHour, 5 secondOfMinute, 6 millisOfSecond, 7 monthOfYear, 8 halfdayOfDay, 9 dayOfMonth
    static final int NTYPES = 10;

    static DateTimeFieldType type(int i) {
        switch (i) {
            case 0: return DateTimeFieldType.clockhourOfDay();
            case 1: return DateTimeFieldType.hourOfDay();
            case 2: return DateTimeFieldType.clockhourOfHalfday();
            case 3: return DateTimeFieldType.hourOfHalfday();
            case 4: return DateTimeFieldType.minuteOfHour();
            case 5: return DateTimeFieldType.secondOfMinute();
            case 6: return DateTimeFieldType.millisOfSecond();
            case 7: return DateTimeFieldType.monthOfYear();
            case 8: return DateTimeFieldType.halfdayOfDay();
            default: return DateTimeFieldType.dayOfMonth();
        }
    }

    static int min(int i) {
        switch (i) {
            case 0: case 2: case 7: case 9: return 1;
            default: return 0;
        }
    }

    static int max(int i) {
        switch (i) {
            case 0: return 24;
            case 1: return 23;
            case 2: return 12;
            case 3: return 11;
            case 4: case 5: return 59;
            case 6: return 999;
            case 7: return 12;
            case 8: return 1;
            default: return 28;
        }
    }

    static boolean validValue(int t, int v) {
        return v >= min(t) && v <= max(t);
    }

    // two different field types with same duration and same range (duplicates for Partial)
    static boolean isDup(int a, int b) {
        return a != b && a < 4 && b < 4 && (a / 2) == (b / 2);
    }

    public static class PairGen extends Generator<int[]> {
        public PairGen() { super(int[].class); }

        @Override
        public int[] generate(SourceOfRandomness r, GenerationStatus status) {
            int a, b;
            if (r.nextInt(100) < 45) {
                int base = r.nextBoolean() ? 0 : 2;
                a = base + (r.nextBoolean() ? 1 : 0);
                b = a == base ? base + 1 : base;
            } else {
                a = r.nextInt(NTYPES);
                b = r.nextInt(NTYPES);
            }
            int va = genValue(r, a);
            int vb = genValue(r, b);
            return new int[] { a, b, va, vb };
        }

        private int genValue(SourceOfRandomness r, int t) {
            if (r.nextInt(100) < 85) {
                return r.nextInt(min(t), max(t));
            }
            return r.nextInt(-2, 1001);
        }
    }

    @Fuzz
    public void testBugReproduction(@From(PairGen.class) int[] in) {
        int t1 = in[0];
        int t2 = in[1];
        int v1 = in[2];
        int v2 = in[3];
        boolean validIf = in.length == 4 && t1 >= 0 && t1 < NTYPES && t2 >= 0 && t2 < NTYPES
                && validValue(t1, v1) && validValue(t2, v2);
        boolean buggyIf = isDup(t1, t2);

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        String result = "none";
        String exc = "none";
        try {
            Partial p = new Partial(type(t1), v1).with(type(t2), v2);
            result = p.toString();
        } catch (Exception e) {
            exc = e.getClass().getName();
        }

        final String fResult = result;
        final String fExc = exc;
        Log.logOutIf(true, () -> new String[] { fResult, fExc });
        assertEquals("with() must reject duplicate field types like the constructor does",
                "java.lang.IllegalArgumentException", fExc);
    }
}
