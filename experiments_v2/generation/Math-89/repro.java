package org.apache.commons.math.stat;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import java.util.ArrayList;

@RunWith(JQF.class)
public class JQF_FrequencyTest {

    @Fuzz
    public void testBugReproduction(@InRange(minInt = 0, maxInt = 2) int kind,
                         @InRange(minInt = -100, maxInt = 100) int value,
                         @InRange(minInt = 0, maxInt = 5) int nPrior,
                         @InRange(minInt = -100, maxInt = 100) int priorStart) {
        // kind 0: Integer (Comparable); kind 1: plain Object; kind 2: non-Comparable StringBuilder
        boolean validIf = true;
        boolean buggyIf = kind != 0;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        Frequency f = new Frequency();
        for (int i = 0; i < nPrior; i++) {
            f.addValue(priorStart + i);
        }
        final Object obj;
        if (kind == 0) {
            obj = Integer.valueOf(value);
        } else if (kind == 1) {
            obj = new Object();
        } else {
            obj = new StringBuilder("v" + value);
        }

        String exName = "none";
        try {
            f.addValue(obj);
        } catch (Throwable t) {
            exName = t.getClass().getName();
        }
        final String exFinal = exName;
        final long sum = f.getSumFreq();
        final long cnt = f.getCount(obj);
        final long priorCount = f.getCount(priorStart);

        Log.logOutIf(true, () -> new Object[]{ exFinal, sum, cnt, priorCount });
        String expected = "java.lang.IllegalArgumentException";
        assertEquals(expected, exFinal);
        assertEquals(nPrior, sum);
    }

    @Fuzz
    public void testAddNonComparable(@InRange(minInt = 0, maxInt = 2) int kind,
                         @InRange(minInt = -100, maxInt = 100) int value,
                         @InRange(minInt = 0, maxInt = 5) int nPrior,
                         @InRange(minInt = -100, maxInt = 100) int priorStart) {
        boolean validIf = true;
        boolean buggyIf = kind != 0;
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            Frequency f = new Frequency();
            for (int i = 0; i < nPrior; i++) {
                f.addValue(priorStart + i);
            }
            final Object obj;
            if (kind == 0) {
                obj = Integer.valueOf(value);
            } else if (kind == 1) {
                obj = new Object();
            } else {
                obj = new StringBuilder("v" + value);
            }

            String exName = "none";
            try {
                f.addValue(obj);
            } catch (Throwable t) {
                exName = t.getClass().getName();
            }
            final String exFinal = exName;
            final long sum = f.getSumFreq();
            final long cnt = f.getCount(obj);
            final long priorCount = f.getCount(priorStart);

            Log.logOutIf(preserveIf, () -> new Object[]{ exFinal, sum, cnt, priorCount, kind, value, nPrior, priorStart, obj.getClass().getName(), exFinal.length(), sum - nPrior, cnt + priorCount });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.getClass().getName(), __e.toString() });
        }
    }
}
