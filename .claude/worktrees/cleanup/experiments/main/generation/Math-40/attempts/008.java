package org.apache.commons.math.analysis.solvers;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.apache.commons.math.analysis.UnivariateFunction;

@RunWith(JQF.class)
public class JQF_BracketingNthOrderBrentSolverTest {

    /** Scale of the function: wide legal range, about half of the time near the failing configuration. */
    public static class ScaleGen extends Generator<Double> {
        public ScaleGen() { super(Double.class); }

        @Override
        public Double generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextBoolean()) {
                return r.nextDouble(9.0e8, 1.1e9);
            }
            return r.nextDouble(1.0e3, 1.0e12);
        }
    }

    /** Distance of the lower bound above the pole at -1, i.e. min = -1 + eps. */
    public static class EpsGen extends Generator<Double> {
        public EpsGen() { super(Double.class); }

        @Override
        public Double generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextBoolean()) {
                return r.nextDouble(5.0e-8, 2.0e-7);
            }
            double e = r.nextDouble(-10.0, -0.31);
            return Math.pow(10.0, e);
        }
    }

    public static class MaxGen extends Generator<Double> {
        public MaxGen() { super(Double.class); }

        @Override
        public Double generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextBoolean()) {
                return r.nextDouble(25.0, 35.0);
            }
            return r.nextDouble(-0.4, 1000.0);
        }
    }

    public static class StartGen extends Generator<Double> {
        public StartGen() { super(Double.class); }

        @Override
        public Double generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextBoolean()) {
                return r.nextDouble(10.0, 20.0);
            }
            return r.nextDouble(-0.9, 500.0);
        }
    }

    @Fuzz
    public void testBugReproduction(@From(ScaleGen.class) Double scale,
                                    @From(EpsGen.class) Double eps,
                                    @From(MaxGen.class) Double max,
                                    @From(StartGen.class) Double start,
                                    @InRange(minInt = 0, maxInt = 5) int allowedIdx) {
        // f(x) = (2x+1) / (scale * (x+1)); root at -0.5, pole at -1; lower bound = -1 + eps.
        // Failing configurations: tiny |f| (about 1e-9) on the whole upper side while the
        // lower end, very close to the pole, has a huge |f|; the bracket stays unbalanced.
        boolean validIf = scale > 0 && eps > 0 && eps < 0.5 && max > -0.5
                && start > (-1.0 + eps) && start < max
                && allowedIdx >= 0 && allowedIdx < 5;
        boolean buggyIf = scale >= 9.0e8 && scale <= 1.1e9
                && eps >= 5.0e-8 && eps <= 2.0e-7
                && max >= 25.0 && max <= 35.0
                && start >= 10.0 && start <= 20.0;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        final double s = scale;
        final double min = -1.0 + eps;
        UnivariateFunction f = new UnivariateFunction() {
            public double value(double x) {
                return (2 * x + 1) / (s * (x + 1));
            }
        };
        AllowedSolution[] kinds = new AllowedSolution[] {
            AllowedSolution.ANY_SIDE, AllowedSolution.LEFT_SIDE, AllowedSolution.RIGHT_SIDE,
            AllowedSolution.BELOW_SIDE, AllowedSolution.ABOVE_SIDE
        };
        BracketingNthOrderBrentSolver solver =
                new BracketingNthOrderBrentSolver(1.0e-12, 1.0e-10, 1.0e-22, 5);
        double result = Double.NaN;
        String exception = "none";
        try {
            result = solver.solve(100, f, min, max, start, kinds[allowedIdx]);
        } catch (RuntimeException e) {
            exception = e.getClass().getName();
        }
        final double fResult = result;
        final String fException = exception;
        final double fValue = Double.isNaN(result) ? Double.NaN : f.value(result);
        final int fEvals = solver.getEvaluations();

        Log.logOutIf(true, () -> new Object[]{ fResult, fException, fValue, fEvals });

        assertEquals("no exception expected", "none", fException);
        assertEquals(-0.5, fResult, 1.0e-6);
        assertTrue(fEvals <= 100);
    }
}
