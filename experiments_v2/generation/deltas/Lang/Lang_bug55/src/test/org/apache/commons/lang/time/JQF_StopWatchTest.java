package org.apache.commons.lang.time;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

@RunWith(JQF.class)
public class JQF_StopWatchTest {

    @Fuzz
    public void testBugReproduction(@InRange(minInt = 0, maxInt = 41) int runMs,
                                    boolean doCycle,
                                    @InRange(minInt = 0, maxInt = 41) int pauseMs,
                                    @InRange(minInt = 0, maxInt = 41) int resumedMs,
                                    boolean suspendBeforeStop,
                                    @InRange(minInt = 0, maxInt = 61) int gapMs) throws Exception {
        boolean validIf = runMs >= 0 && pauseMs >= 0 && resumedMs >= 0 && gapMs >= 0;
        // stop() is called while the watch is suspended, after a measurable wait
        boolean buggyIf = suspendBeforeStop && gapMs >= 2;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        StopWatch sw = new StopWatch();
        sw.start();
        Thread.sleep(runMs);
        if (doCycle) {
            sw.suspend();
            Thread.sleep(pauseMs);
            sw.resume();
            Thread.sleep(resumedMs);
        }
        if (suspendBeforeStop) {
            sw.suspend();
        }
        long timeAtSuspend = sw.getTime();
        Thread.sleep(gapMs);
        sw.stop();
        long timeAfterStop = sw.getTime();
        long timeAfterStop2 = sw.getTime();

        final boolean unchangedByStop = timeAfterStop == timeAtSuspend;
        final boolean stopTimeStable = timeAfterStop == timeAfterStop2;
        Log.logOutIf(true, () -> new Boolean[]{ unchangedByStop, stopTimeStable });
        assertEquals("stop() after suspend() must not add the suspended time",
                timeAtSuspend, timeAfterStop);
    }

    @Fuzz
    public void testLang315(@InRange(minInt = 0, maxInt = 41) int runMs,
                                    boolean doCycle,
                                    @InRange(minInt = 0, maxInt = 41) int pauseMs,
                                    @InRange(minInt = 0, maxInt = 41) int resumedMs,
                                    boolean suspendBeforeStop,
                                    @InRange(minInt = 0, maxInt = 61) int gapMs) {
        boolean validIf = runMs >= 0 && pauseMs >= 0 && resumedMs >= 0 && gapMs >= 0;
        boolean buggyIf = suspendBeforeStop && gapMs >= 2;
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            StopWatch sw = new StopWatch();
            sw.start();
            Thread.sleep(runMs);
            if (doCycle) {
                sw.suspend();
                Thread.sleep(pauseMs);
                sw.resume();
                Thread.sleep(resumedMs);
            }
            if (suspendBeforeStop) {
                sw.suspend();
            }
            long timeAtSuspend = sw.getTime();
            Thread.sleep(gapMs);
            sw.stop();
            long timeAfterStop = sw.getTime();
            long timeAfterStop2 = sw.getTime();

            final boolean unchangedByStop = timeAfterStop == timeAtSuspend;
            final boolean stopTimeStable = timeAfterStop == timeAfterStop2;
            final long deltaStop = timeAfterStop - timeAtSuspend;
            final long deltaStable = timeAfterStop2 - timeAfterStop;
            final boolean fSuspend = suspendBeforeStop;
            final boolean fCycle = doCycle;
            final int fGap = gapMs;
            Log.logOutIf(preserveIf, () -> new String[]{
                    "unchanged=" + unchangedByStop,
                    "stable=" + stopTimeStable,
                    "suspend=" + fSuspend,
                    "cycle=" + fCycle,
                    "gapBucket=" + fGap,
                    "deltaStopNeg=" + (deltaStop < 0),
                    "deltaStopZero=" + (deltaStop == 0),
                    "deltaStopCoarse=" + (deltaStop / 5),
                    "deltaStable=" + deltaStable,
                    "stopGeGap=" + (deltaStop >= fGap)
            });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
