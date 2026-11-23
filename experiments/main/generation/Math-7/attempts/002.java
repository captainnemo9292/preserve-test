package org.apache.commons.math3.ode.nonstiff;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.apache.commons.math3.ode.FirstOrderDifferentialEquations;
import org.apache.commons.math3.ode.events.EventHandler;

@RunWith(JQF.class)
public class JQF_DormandPrince853IntegratorTest {

    /** y'' = -y as a first order system: y0 = sin t, y1 = cos t (both within [-1, 1]). */
    private static class Oscillator implements FirstOrderDifferentialEquations {
        public int getDimension() { return 2; }
        public void computeDerivatives(double t, double[] y, double[] yDot) {
            yDot[0] = y[1];
            yDot[1] = -y[0];
        }
    }

    /** Handler that triggers an event when sin t crosses a threshold. */
    private static class TriggerHandler implements EventHandler {
        private final double threshold;
        private final EventHandler.Action action;
        final double[] firstEventTime = new double[] { Double.NaN };
        int eventCount = 0;

        TriggerHandler(double threshold, EventHandler.Action action) {
            this.threshold = threshold;
            this.action = action;
        }
        public void init(double t0, double[] y0, double t) { }
        public double g(double t, double[] y) { return y[0] - threshold; }
        public EventHandler.Action eventOccurred(double t, double[] y, boolean increasing) {
            if (eventCount == 0) {
                firstEventTime[0] = t;
            }
            eventCount++;
            // only react on the first event, afterwards just continue
            return eventCount == 1 ? action : EventHandler.Action.CONTINUE;
        }
        public void resetState(double t, double[] y) {
            // keep the state unchanged (still on the oscillator trajectory)
        }
    }

    /** Handler that never triggers an event, but records the calls it receives. */
    private static class SilentHandler implements EventHandler {
        private final double[] triggerTime;
        int violations = 0;
        int calls = 0;
        double maxAbsY = 0;

        SilentHandler(double[] triggerTime) { this.triggerTime = triggerTime; }
        public void init(double t0, double[] y0, double t) { }
        public double g(double t, double[] y) {
            calls++;
            for (double v : y) {
                maxAbsY = Math.max(maxAbsY, Math.abs(v));
            }
            // after the other handler's event the integration restarts at that time:
            // no evaluation may refer to an earlier time (stale event state)
            if (!Double.isNaN(triggerTime[0]) && t < triggerTime[0] - 1.0e-9) {
                violations++;
            }
            return y[0] + 5.0;
        }
        public EventHandler.Action eventOccurred(double t, double[] y, boolean increasing) {
            return EventHandler.Action.CONTINUE;
        }
        public void resetState(double t, double[] y) { }
    }

    @Fuzz
    public void testBugReproduction(@InRange(minDouble = 0.0, maxDouble = 0.95) double threshold,
                                    @InRange(minInt = 0, maxInt = 4) int actionIdx,
                                    @InRange(minDouble = 0.001, maxDouble = 1.0) double silentCheck,
                                    @InRange(minDouble = 0.1, maxDouble = 10.0) double tEnd,
                                    @InRange(minDouble = 0.01, maxDouble = 0.5) double stepSize) {
        boolean validIf = true;

        // fixed step integration of sin t: the first crossing of the threshold is at asin(threshold),
        // inside the step starting at k * stepSize. The silent handler keeps that stale start time
        // after a reset and evaluates g from there, i.e. before the event time, when its
        // sub-step (stale span / number of sub-steps) is shorter than the offset of the event in its step.
        double eventTime = Math.asin(threshold);
        double stepStartOfEvent = Math.floor(eventTime / stepSize) * stepSize;
        double offset = eventTime - stepStartOfEvent;
        double staleSpan = offset + stepSize;
        double subSteps = Math.max(1.0, Math.ceil(staleSpan / silentCheck));
        double subStep = staleSpan / subSteps;
        boolean buggyIf = actionIdx >= 2 && threshold >= 0.01
                && offset > 1.0e-6 && subStep < offset - 1.0e-6
                && tEnd > eventTime + 3 * stepSize;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        EventHandler.Action[] actions = new EventHandler.Action[] {
            EventHandler.Action.CONTINUE, EventHandler.Action.STOP,
            EventHandler.Action.RESET_STATE, EventHandler.Action.RESET_DERIVATIVES
        };
        TriggerHandler trigger = new TriggerHandler(threshold, actions[actionIdx]);
        SilentHandler silent = new SilentHandler(trigger.firstEventTime);

        ClassicalRungeKuttaIntegrator integrator = new ClassicalRungeKuttaIntegrator(stepSize);
        integrator.addEventHandler(trigger, 1.0, 1.0e-9, 100);
        integrator.addEventHandler(silent, silentCheck, 1.0e-9, 100);

        double[] y = new double[] { 0.0, 1.0 };
        String error = "none";
        double finalT = Double.NaN;
        try {
            finalT = integrator.integrate(new Oscillator(), 0.0, y, tEnd, y);
        } catch (RuntimeException e) {
            error = e.getClass().getName();
        }

        final String errorF = error;
        final double finalTF = finalT;
        Log.logOutIf(true, () -> new Object[]{ errorF, finalTF, y[0], y[1],
            trigger.eventCount, trigger.firstEventTime[0], silent.calls, silent.violations, silent.maxAbsY });

        assertEquals("no exception expected", "none", errorF);
        assertEquals("silent handler evaluated at times before the restart time (stale event state)",
                     0, silent.violations);
    }
}
