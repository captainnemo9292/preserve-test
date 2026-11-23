package org.apache.commons.math.ode.nonstiff;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.apache.commons.math.ode.FirstOrderDifferentialEquations;

@RunWith(JQF.class)
public class JQF_DormandPrince853IntegratorTest {

    @Fuzz
    public void testBugReproduction(@InRange(minDouble = -10, maxDouble = 10) double t0,
                         @InRange(minDouble = 1, maxDouble = 10) double y0,
                         @InRange(minDouble = -9, maxDouble = 1) double logDt) {
        // range length is 10^logDt; the automatic first step for y'=1, tolerances 1e-10/1e-10
        // is h1 = (0.01 * (1e-10 + 1e-10 * y0))^(1/8) (about 0.034 .. 0.043)
        boolean validIf = !Double.isNaN(logDt) && t0 + Math.pow(10.0, logDt) > t0;
        boolean buggyIf = Math.pow(10.0, logDt) + 1.0e-12
                          < Math.pow(0.01 * (1.0e-10 + 1.0e-10 * y0), 0.125);

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        final double tEnd = t0 + Math.pow(10.0, logDt);
        // call 0: initial derivative, call 1: step size initialization probe,
        // calls >= 2: Runge-Kutta stages of the first step and later steps
        final int[] calls = new int[] { 0 };
        final double[] maxStageTime = new double[] { t0 };
        FirstOrderDifferentialEquations ode = new FirstOrderDifferentialEquations() {
            public int getDimension() { return 1; }
            public void computeDerivatives(double t, double[] y, double[] yDot) {
                if (calls[0] >= 2 && t > maxStageTime[0]) {
                    maxStageTime[0] = t;
                }
                calls[0]++;
                yDot[0] = 1.0;
            }
        };

        DormandPrince853Integrator integrator =
            new DormandPrince853Integrator(0.0, 100.0, 1.0e-10, 1.0e-10);
        double[] y = new double[] { y0 };
        double finalTime = Double.NaN;
        String exception = "none";
        try {
            finalTime = integrator.integrate(ode, t0, new double[] { y0 }, tEnd, y);
        } catch (Exception e) {
            exception = e.getClass().getName();
        }

        final double fFinalTime = finalTime;
        final String fException = exception;
        Log.logOutIf(true, () -> new Object[]{ fFinalTime, y[0], maxStageTime[0], calls[0], fException });
        assertEquals("none", fException);
        assertTrue("function evaluated beyond end of range: " + maxStageTime[0] + " > " + tEnd,
                   maxStageTime[0] <= tEnd + 1.0e-12);
        assertEquals(tEnd, fFinalTime, 1e-12);
    }
}
