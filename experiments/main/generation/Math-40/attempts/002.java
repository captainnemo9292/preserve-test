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

    public static class Params {
        public double scale;
        public double min;
        public double max;
        public double start;
    }

    public static class ParamsGen extends Generator<Params> {
        public ParamsGen() { super(Params.class); }

        @Override
        public Params generate(SourceOfRandomness r, GenerationStatus status) {
            Params p = new Params();
            if (r.nextBoolean()) {
                // region around the known failing configuration
                p.scale = r.nextDouble(9.0e8, 1.1e9);
                p.min = r.nextDouble(-0.99999995, -0.9999998);
                p.max = r.nextDouble(25.0, 35.0);
                p.start = r.nextDouble(10.0, 20.0);
            } else {
                p.scale = r.nextDouble(1.0e6, 1.0e10);
                p.min = r.nextDouble(-0.99999999, -0.6);
                p.max = r.nextDouble(0.0, 100.0);
                p.start = r.nextDouble(-0.9, 50.0);
            }
            return p;
        }
    }

    @Fuzz
    public void testBugReproduction(@From(ParamsGen.class) Params p) {
        // f(x) = (2x+1) / (scale * (x+1)), root at -0.5, sharp turn near x = -1
        double scale = p.scale, min = p.min, max = p.max, start = p.start;
        boolean validIf = scale > 0 && min > -1.0 && min < -0.5 && max > -0.5
                && start > min && start < max;
        boolean buggyIf = scale >= 9.0e8 && scale <= 1.1e9
                && min >= -0.99999995 && min <= -0.9999998
                && max >= 25.0 && max <= 35.0
                && start >= 10.0 && start <= 20.0;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        final double s = scale;
        UnivariateFunction f = new UnivariateFunction() {
            public double value(double x) {
                return (2 * x + 1) / (s * (x + 1));
            }
        };
        BracketingNthOrderBrentSolver solver =
                new BracketingNthOrderBrentSolver(1.0e-12, 1.0e-10, 1.0e-22, 5);
        double result = Double.NaN;
        String exception = "none";
        try {
            result = solver.solve(100, f, min, max, start, AllowedSolution.RIGHT_SIDE);
        } catch (RuntimeException e) {
            exception = e.getClass().getName();
        }
        final double fResult = result;
        final String fException = exception;
        final double fValue = Double.isNaN(result) ? Double.NaN : f.value(result);

        Log.logOutIf(true, () -> new Object[]{ fResult, fException, fValue });

        assertEquals("no exception expected", "none", fException);
        assertEquals(-0.5, fResult, 1.0e-6);
        assertTrue(fValue >= 0);
    }
}
