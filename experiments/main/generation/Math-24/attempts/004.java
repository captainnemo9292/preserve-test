package org.apache.commons.math3.optimization.univariate;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.apache.commons.math3.analysis.UnivariateFunction;
import org.apache.commons.math3.optimization.GoalType;

@RunWith(JQF.class)
public class JQF_BrentOptimizerTest {

    @Fuzz
    public void testBugReproduction(@InRange(minInt = -50, maxInt = 51) int c,
                                    @InRange(minInt = -50, maxInt = 51) int lo,
                                    @InRange(minInt = -50, maxInt = 51) int hi,
                                    @InRange(minInt = -50, maxInt = 51) int start,
                                    @InRange(minDouble = 4.5e-16, maxDouble = 1e-3) double rel,
                                    @InRange(minDouble = 1e-8, maxDouble = 1e-2) double abs,
                                    boolean minimize) {
        boolean validIf = lo < hi && lo <= start && start <= hi
                && rel >= 4.5e-16 && abs > 0;
        // The start value is the exact optimum of the objective and the first
        // convergence test does not stop the algorithm: at least one more point
        // is evaluated, and it is necessarily worse than the starting point.
        boolean buggyIf = start == c
                && Math.abs((double) start - 0.5 * ((double) lo + (double) hi))
                   > 2 * (rel * Math.abs((double) start) + abs) - 0.5 * ((double) hi - (double) lo);

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        final boolean isMin = minimize;
        final int center = c;
        final double[] bestSeen = new double[] { Double.NaN };
        final UnivariateFunction f = new UnivariateFunction() {
            public double value(double x) {
                double v = (x - center) * (x - center);
                if (!isMin) {
                    v = -v;
                }
                if (Double.isNaN(bestSeen[0]) || (isMin ? v < bestSeen[0] : v > bestSeen[0])) {
                    bestSeen[0] = v;
                }
                return v;
            }
        };

        final BrentOptimizer optimizer = new BrentOptimizer(rel, abs);
        final UnivariatePointValuePair result = optimizer.optimize(
                10000, f, minimize ? GoalType.MINIMIZE : GoalType.MAXIMIZE,
                (double) lo, (double) hi, (double) start);
        final double sol = result.getPoint();
        final double val = result.getValue();
        final double best = bestSeen[0];

        Log.logOutIf(true, () -> new Double[]{ sol, val, best, (double) optimizer.getEvaluations() });
        final boolean reportsBest = isMin ? val <= best : val >= best;
        assertTrue("Returned value " + val + " is worse than best evaluated value " + best,
                   reportsBest);
    }
}
