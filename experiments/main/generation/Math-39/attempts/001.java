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
                         @InRange(minDouble = -9, maxDouble = 0) double logDt) {
        final double dt = Math.pow(10.0, logDt);
        boolean validIf = dt > 0 && !Double.isNaN(dt) && t0 + dt > t0;
        // the automatically computed first step (>= ~0.01) is much larger than the range
        boolean buggyIf = dt < 1e-3;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        final double tEnd = t0 + dt;
        final double[] maxTime = new double[] { t0 };
        FirstOrderDifferentialEquations ode = new FirstOrderDifferentialEquations() {
            public int getDimension() { return 1; }
            public void computeDerivatives(double t, double[] y, double[] yDot) {
                if (t > maxTime[0]) {
                    maxTime[0] = t;
                }
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
        Log.logOutIf(true, () -> new Object[]{ fFinalTime, y[0], maxTime[0], fException });
        assertEquals("none", fException);
        assertTrue("function evaluated beyond end of range: " + maxTime[0] + " > " + tEnd,
                   maxTime[0] <= tEnd);
        assertEquals(tEnd, fFinalTime, 1e-12);
    }
}
