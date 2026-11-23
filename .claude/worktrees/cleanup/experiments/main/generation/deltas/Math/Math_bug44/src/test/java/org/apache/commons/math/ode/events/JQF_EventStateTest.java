package org.apache.commons.math.ode.events;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

import org.apache.commons.math.ode.FirstOrderDifferentialEquations;
import org.apache.commons.math.ode.nonstiff.ClassicalRungeKuttaIntegrator;

@RunWith(JQF.class)
public class JQF_EventStateTest {

    @Fuzz
    public void testBugReproduction(@InRange(minDouble = 0.1, maxDouble = 0.9) double a,
                                    @InRange(minDouble = 0.1, maxDouble = 0.95) double c,
                                    @InRange(minDouble = -1.0, maxDouble = 2.0) double jump) {
        // events A (t = a, resets state by +jump) and B (y = c) both lie inside the first step [0, 1]
        boolean validIf = (c - a) >= 0.05 && Math.abs(a + jump - c) > 0.01;
        // after the reset, y is already above c: B's sign just after the reset differs from the stale one
        boolean buggyIf = (a + jump - c) > 0.01;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        final int[] countA = new int[1];
        final int[] countB = new int[1];

        EventHandler handlerA = new EventHandler() {
            public double g(double t, double[] y) { return t - a; }
            public Action eventOccurred(double t, double[] y, boolean increasing) {
                countA[0]++;
                return Action.RESET_STATE;
            }
            public void resetState(double t, double[] y) { y[0] += jump; }
        };
        EventHandler handlerB = new EventHandler() {
            public double g(double t, double[] y) { return y[0] - c; }
            public Action eventOccurred(double t, double[] y, boolean increasing) {
                countB[0]++;
                return Action.CONTINUE;
            }
            public void resetState(double t, double[] y) { }
        };

        FirstOrderDifferentialEquations ode = new FirstOrderDifferentialEquations() {
            public int getDimension() { return 1; }
            public void computeDerivatives(double t, double[] y, double[] yDot) { yDot[0] = 1.0; }
        };

        ClassicalRungeKuttaIntegrator integ = new ClassicalRungeKuttaIntegrator(1.0);
        integ.addEventHandler(handlerA, 1.0, 1.0e-6, 100);
        integ.addEventHandler(handlerB, 1.0, 1.0e-6, 100);

        double[] y = new double[] { 0.0 };
        String exception = "none";
        double tEnd = Double.NaN;
        try {
            tEnd = integ.integrate(ode, 0.0, new double[] { 0.0 }, 3.0, y);
        } catch (Exception e) {
            exception = e.getClass().getName();
        }

        final String exc = exception;
        final double tEndF = tEnd;
        final double yF = y[0];
        Log.logOutIf(true, () -> new Object[]{ exc, tEndF, yF, countA[0], countB[0] });
        assertEquals("no exception expected", "none", exc);
        assertEquals(1, countA[0]);
        assertEquals(0, countB[0]);
        assertEquals(3.0, tEndF, 1.0e-9);
        assertEquals(3.0 + jump, yF, 1.0e-6);
    }

    @Fuzz
    public void testIssue695(@InRange(minDouble = 0.1, maxDouble = 0.9) double a,
                                    @InRange(minDouble = 0.1, maxDouble = 0.95) double c,
                                    @InRange(minDouble = -1.0, maxDouble = 2.0) double jump) {
        boolean validIf = (c - a) >= 0.05 && Math.abs(a + jump - c) > 0.01;
        boolean buggyIf = (a + jump - c) > 0.01;
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            final int[] countA = new int[1];
            final int[] countB = new int[1];

            EventHandler handlerA = new EventHandler() {
                public double g(double t, double[] y) { return t - a; }
                public Action eventOccurred(double t, double[] y, boolean increasing) {
                    countA[0]++;
                    return Action.RESET_STATE;
                }
                public void resetState(double t, double[] y) { y[0] += jump; }
            };
            EventHandler handlerB = new EventHandler() {
                public double g(double t, double[] y) { return y[0] - c; }
                public Action eventOccurred(double t, double[] y, boolean increasing) {
                    countB[0]++;
                    return Action.CONTINUE;
                }
                public void resetState(double t, double[] y) { }
            };

            FirstOrderDifferentialEquations ode = new FirstOrderDifferentialEquations() {
                public int getDimension() { return 1; }
                public void computeDerivatives(double t, double[] y, double[] yDot) { yDot[0] = 1.0; }
            };

            ClassicalRungeKuttaIntegrator integ = new ClassicalRungeKuttaIntegrator(1.0);
            integ.addEventHandler(handlerA, 1.0, 1.0e-6, 100);
            integ.addEventHandler(handlerB, 1.0, 1.0e-6, 100);

            double[] y = new double[] { 0.0 };
            String exception = "none";
            double tEnd = Double.NaN;
            try {
                tEnd = integ.integrate(ode, 0.0, new double[] { 0.0 }, 3.0, y);
            } catch (Exception e) {
                exception = e.getClass().getName();
            }

            final String exc = exception;
            final double tEndF = tEnd;
            final double yF = y[0];
            Log.logOutIf(preserveIf, () -> new Object[]{ exc, tEndF, yF, countA[0], countB[0] });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
