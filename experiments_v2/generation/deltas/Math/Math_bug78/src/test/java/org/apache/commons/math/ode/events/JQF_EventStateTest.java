package org.apache.commons.math.ode.events;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.apache.commons.math.ode.sampling.StepInterpolator;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

@RunWith(JQF.class)
public class JQF_EventStateTest {

    /** Switching function positive between r1 and r2: increasing at r1, decreasing at r2. */
    private static class TwoRootsHandler implements EventHandler {
        private final double r1;
        private final double r2;
        TwoRootsHandler(double r1, double r2) { this.r1 = r1; this.r2 = r2; }
        public double g(double t, double[] y) { return (t - r1) * (r2 - t); }
        public int eventOccurred(double t, double[] y, boolean increasing) { return CONTINUE; }
        public void resetState(double t, double[] y) { }
    }

    /** Simple forward interpolator over [prev, cur]. */
    private static class SimpleInterpolator implements StepInterpolator {
        private final double prev;
        private final double cur;
        private double time;
        SimpleInterpolator(double prev, double cur) { this.prev = prev; this.cur = cur; this.time = cur; }
        public double getPreviousTime() { return prev; }
        public double getCurrentTime() { return cur; }
        public double getInterpolatedTime() { return time; }
        public void setInterpolatedTime(double t) { time = t; }
        public double[] getInterpolatedState() { return new double[] { time }; }
        public double[] getInterpolatedDerivatives() { return new double[] { 0.0 }; }
        public boolean isForward() { return cur >= prev; }
        public StepInterpolator copy() { return new SimpleInterpolator(prev, cur); }
        public void writeExternal(ObjectOutput out) throws IOException { }
        public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException { }
    }

    @Fuzz
    public void testBugReproduction(@InRange(minDouble = 80, maxDouble = 100) double r1,
                                    @InRange(minDouble = 20, maxDouble = 60) double gap,
                                    @InRange(minDouble = 5, maxDouble = 30) double d0,
                                    @InRange(minDouble = 0.1, maxDouble = 0.9) double f,
                                    @InRange(minDouble = 1, maxDouble = 30) double extra,
                                    @InRange(minDouble = 1.0e-5, maxDouble = 1.0e-3) double convergence,
                                    @InRange(minDouble = 0, maxDouble = 1.0e-3) double eps) {
        boolean validIf = gap >= 20 && f > 0 && f < 1 && convergence > 0 && eps >= 0;
        // the first event was located slightly before its true root (within convergence)
        boolean buggyIf = eps > 0 && eps < convergence;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        final double r2 = r1 + gap;
        final double a = r1 - d0;
        final double b = r1 + gap * f;
        final double tStart2 = r1 - eps;
        final double tEnd2 = r2 + extra;

        EventState state = new EventState(new TwoRootsHandler(r1, r2), 1000.0, convergence, 100);
        boolean firstStepEvent = false;
        boolean secondStepEvent = false;
        double secondEventTime = Double.NaN;
        String error = "none";
        try {
            state.reinitializeBegin(a, new double[] { a });
            firstStepEvent = state.evaluateStep(new SimpleInterpolator(a, b));
            // first event accepted at a time slightly before the real root
            state.stepAccepted(tStart2, new double[] { tStart2 });
            state.reset(tStart2, new double[] { tStart2 });
            secondStepEvent = state.evaluateStep(new SimpleInterpolator(tStart2, tEnd2));
            secondEventTime = state.getEventTime();
        } catch (Throwable t) {
            error = t.getClass().getName();
        }

        final boolean fs = firstStepEvent;
        final boolean ss = secondStepEvent;
        final double st = secondEventTime;
        final String err = error;
        Log.logOutIf(true, () -> new Object[]{ fs, ss, st, err });

        final double delta = 2 * convergence + 1.0e-6;
        assertEquals("none", err);
        assertTrue("first event should be detected", fs);
        assertTrue("second event should be detected", ss);
        assertEquals(r2, st, delta);
    }

    @Fuzz
    public void closeEvents(@InRange(minDouble = 80, maxDouble = 100) double r1,
                                    @InRange(minDouble = 20, maxDouble = 60) double gap,
                                    @InRange(minDouble = 5, maxDouble = 30) double d0,
                                    @InRange(minDouble = 0.1, maxDouble = 0.9) double f,
                                    @InRange(minDouble = 1, maxDouble = 30) double extra,
                                    @InRange(minDouble = 1.0e-5, maxDouble = 1.0e-3) double convergence,
                                    @InRange(minDouble = 0, maxDouble = 1.0e-3) double eps) {
        boolean validIf = gap >= 20 && f > 0 && f < 1 && convergence > 0 && eps >= 0;
        boolean buggyIf = eps > 0 && eps < convergence;
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            final double r2 = r1 + gap;
            final double a = r1 - d0;
            final double b = r1 + gap * f;
            final double tStart2 = r1 - eps;
            final double tEnd2 = r2 + extra;

            EventState state = new EventState(new TwoRootsHandler(r1, r2), 1000.0, convergence, 100);
            boolean firstStepEvent = false;
            boolean secondStepEvent = false;
            double secondEventTime = Double.NaN;
            String error = "none";
            try {
                state.reinitializeBegin(a, new double[] { a });
                firstStepEvent = state.evaluateStep(new SimpleInterpolator(a, b));
                // first event accepted at a time slightly before the real root
                state.stepAccepted(tStart2, new double[] { tStart2 });
                state.reset(tStart2, new double[] { tStart2 });
                secondStepEvent = state.evaluateStep(new SimpleInterpolator(tStart2, tEnd2));
                secondEventTime = state.getEventTime();
            } catch (Throwable t) {
                error = t.getClass().getName();
            }

            final boolean fs = firstStepEvent;
            final boolean ss = secondStepEvent;
            final double st = secondEventTime;
            final String err = error;
            final long stBits = Double.doubleToLongBits(st);
            final double dev = st - r2;
            final long devBits = Double.doubleToLongBits(dev);
            final boolean withinDelta = Math.abs(dev) <= 2 * convergence + 1.0e-6;
            Log.logOutIf(preserveIf, () -> new Object[]{ fs, ss, st, stBits, dev, devBits, withinDelta, err, eps > 0, eps < convergence });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
