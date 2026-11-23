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
                                    @InRange(minInt = 0, maxInt = 61) int finalGapMs) throws Exception {
        boolean validIf = runMs >= 0 && pauseMs >= 0 && resumedMs >= 0 && finalGapMs >= 0;
        // stop() after suspend(), with a clearly measurable gap between them
        boolean buggyIf = suspendBeforeStop && finalGapMs >= 20;

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
        sw.suspend();
        long timeAtSuspend = sw.getTime();
        Thread.sleep(finalGapMs);
        long timeWhileSuspended = sw.getTime();
        sw.stop();
        long timeAfterStop = sw.getTime();

        final boolean stableWhileSuspended = timeWhileSuspended == timeAtSuspend;
        final boolean unchangedByStop = timeAfterStop == timeAtSuspend;
        Log.logOutIf(true, () -> new Boolean[]{ stableWhileSuspended, unchangedByStop });
        assertTrue(stableWhileSuspended);
        assertEquals(timeAtSuspend, timeAfterStop);
    }
}
