package org.apache.commons.math.ode.nonstiff;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.apache.commons.math.ode.DerivativeException;
import org.apache.commons.math.ode.FirstOrderDifferentialEquations;
import org.apache.commons.math.ode.IntegratorException;

@RunWith(JQF.class)
public class JQF_ClassicalRungeKuttaIntegratorTest {

    public static class StartGen extends Generator<Double> {
        public StartGen() { super(Double.class); }
        @Override public Double generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextInt(100) < 50) {
                return 1878250320.0000029;
            }
            return r.nextDouble(0.0, 2.0e9);
        }
    }

    public static class EndGen extends Generator<Double> {
        public EndGen() { super(Double.class); }
        @Override public Double generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextInt(100) < 50) {
                return 1878250379.9999986;
            }
            return r.nextDouble(0.0, 2.0e9);
        }
    }

    public static class StepGen extends Generator<Double> {
        public StepGen() { super(Double.class); }
        @Override public Double generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextInt(100) < 60) {
                return 60.0;
            }
            return r.nextDouble(0.001, 100.0);
        }
    }

    @Fuzz
    public void testBugReproduction(@From(StartGen.class) double t0,
                                    @From(EndGen.class) double t,
                                    @From(StepGen.class) double initStep,
                                    @InRange(minDouble = 0, maxDouble = 1) double y0) {
        boolean validIf = t > t0 && initStep > 0 && !Double.isNaN(t0) && !Double.isNaN(t);
        boolean buggyIf = t0 == 1878250320.0000029 && t == 1878250379.9999986 && initStep == 60.0;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        FirstOrderDifferentialEquations ode = new FirstOrderDifferentialEquations() {
            public int getDimension() {
                return 1;
            }
            public void computeDerivatives(double tt, double[] y, double[] yDot)
                throws DerivativeException {
                yDot[0] = y[0] * 1.0e-6;
            }
        };

        double finalT = Double.NaN;
        String err = "none";
        double[] y = { 1.0 };
        try {
            DormandPrince853Integrator integrator =
                new DormandPrince853Integrator(0.0, 100.0, 1.0e-10, 1.0e-10);
            integrator.setInitialStepSize(initStep);
            finalT = integrator.integrate(ode, t0, y, t, y);
        } catch (IntegratorException e) {
            err = e.getClass().getName();
        } catch (DerivativeException e) {
            err = e.getClass().getName();
        }

        final double fT = finalT;
        final String fErr = err;
        final double fy = y[0];
        Log.logOutIf(true, () -> new Object[]{ fT, fErr, fy });
        assertEquals("none", err);
        assertEquals(t, finalT, 1.0e-6);
    }

    @Fuzz
    public void testMissedEndEvent(@From(StartGen.class) double t0,
                                    @From(EndGen.class) double t,
                                    @From(StepGen.class) double initStep,
                                    @InRange(minDouble = 0, maxDouble = 1) double y0) {
        boolean validIf = t > t0 && initStep > 0 && !Double.isNaN(t0) && !Double.isNaN(t);
        boolean buggyIf = t0 == 1878250320.0000029 && t == 1878250379.9999986 && initStep == 60.0;
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            FirstOrderDifferentialEquations ode = new FirstOrderDifferentialEquations() {
                public int getDimension() {
                    return 1;
                }
                public void computeDerivatives(double tt, double[] y, double[] yDot)
                    throws DerivativeException {
                    yDot[0] = y[0] * 1.0e-6;
                }
            };

            double finalT = Double.NaN;
            String err = "none";
            double[] y = { 1.0 };
            try {
                DormandPrince853Integrator integrator =
                    new DormandPrince853Integrator(0.0, 100.0, 1.0e-10, 1.0e-10);
                integrator.setInitialStepSize(initStep);
                finalT = integrator.integrate(ode, t0, y, t, y);
            } catch (IntegratorException e) {
                err = e.getClass().getName();
            } catch (DerivativeException e) {
                err = e.getClass().getName();
            }

            final double fT = finalT;
            final String fErr = err;
            final double fy = y[0];
            final long fTBits = Double.doubleToLongBits(fT);
            final long fyBits = Double.doubleToLongBits(fy);
            final long diffBits = Double.doubleToLongBits(fT - t);
            Log.logOutIf(preserveIf, () -> new Object[]{ fT, fErr, fy, fTBits, fyBits, diffBits, fErr.length() });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
