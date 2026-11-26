package org.apache.commons.math.analysis.solvers;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.apache.commons.math.FunctionEvaluationException;
import org.apache.commons.math.analysis.UnivariateRealFunction;

@RunWith(JQF.class)
public class JQF_BisectionSolverTest {

    @Fuzz
    public void testBugReproduction(@InRange(minDouble = -10.0, maxDouble = 10.0) double min,
                                    @InRange(minDouble = -10.0, maxDouble = 10.0) double max,
                                    @InRange(minDouble = -10.0, maxDouble = 10.0) double initial,
                                    @InRange(minDouble = -10.0, maxDouble = 10.0) double root,
                                    boolean functionSetInConstructor) {
        boolean validIf = min < max && max - min > 1e-3
                && initial >= min && initial <= max
                && root >= min && root <= max;
        // The bug only occurs when the solver's member field f was not set via the constructor
        boolean buggyIf = !functionSetInConstructor;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        final double r = root;
        UnivariateRealFunction fn = new UnivariateRealFunction() {
            public double value(double x) throws FunctionEvaluationException {
                return x - r;
            }
        };

        String exceptionName = "none";
        double result = Double.NaN;
        try {
            BisectionSolver solver = functionSetInConstructor
                    ? new BisectionSolver(fn)
                    : new BisectionSolver();
            result = solver.solve(fn, min, max, initial);
        } catch (Throwable t) {
            exceptionName = t.getClass().getName();
        }

        final String exName = exceptionName;
        final double res = result;
        Log.logOutIf(true, () -> new Object[]{ exName, res });
        assertEquals("none", exName);
        assertEquals(root, res, 1e-3);
    }

    @Fuzz
    public void testMath369(@InRange(minDouble = -10.0, maxDouble = 10.0) double min,
                                    @InRange(minDouble = -10.0, maxDouble = 10.0) double max,
                                    @InRange(minDouble = -10.0, maxDouble = 10.0) double initial,
                                    @InRange(minDouble = -10.0, maxDouble = 10.0) double root,
                                    boolean functionSetInConstructor) {
        boolean validIf = min < max && max - min > 1e-3
                && initial >= min && initial <= max
                && root >= min && root <= max;
        boolean buggyIf = !functionSetInConstructor;
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            final double r = root;
            UnivariateRealFunction fn = new UnivariateRealFunction() {
                public double value(double x) throws FunctionEvaluationException {
                    return x - r;
                }
            };

            String exceptionName = "none";
            double result = Double.NaN;
            try {
                BisectionSolver solver = functionSetInConstructor
                        ? new BisectionSolver(fn)
                        : new BisectionSolver();
                result = solver.solve(fn, min, max, initial);
            } catch (Throwable t) {
                exceptionName = t.getClass().getName();
            }

            final String exName = exceptionName;
            final double res = result;
            Log.logOutIf(preserveIf, () -> new Object[]{ exName, res });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
