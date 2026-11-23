package org.apache.commons.math3.optimization.univariate;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.apache.commons.math3.analysis.FunctionUtils;
import org.apache.commons.math3.analysis.UnivariateFunction;
import org.apache.commons.math3.analysis.function.Sin;
import org.apache.commons.math3.analysis.function.StepFunction;
import org.apache.commons.math3.optimization.GoalType;

@RunWith(JQF.class)
public class JQF_BrentOptimizerTest {

    @Fuzz
    public void testBugReproduction(@InRange(minDouble = 0.1, maxDouble = 10) double a,
                                    @InRange(minDouble = 0, maxDouble = 10) double b) {
        boolean validIf = (3 * Math.PI / 2 - a * 1e-7) < 2 * Math.PI
                && (3 * Math.PI / 2 - a * 1e-7) <= (3 * Math.PI / 2 + b * 1e-7)
                && (3 * Math.PI / 2 + b * 1e-7) <= 2 * Math.PI;
        boolean buggyIf = a >= 6.5 && a <= 7.1 && b >= 9.5 && b <= 10;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        final double minSin = 3 * Math.PI / 2;
        final double offset = 1e-8;
        final double delta = 1e-7;
        final double lo = minSin - a * delta;
        final double hi = 2 * Math.PI;
        final double start = minSin + b * delta;

        final UnivariateFunction f1 = new Sin();
        final UnivariateFunction f2 = new StepFunction(
                new double[] { minSin, minSin + offset, minSin + 2 * offset },
                new double[] { 0, -1, 0 });
        final UnivariateFunction fBase = FunctionUtils.add(f1, f2);

        // records the best value among all evaluations performed by the optimizer
        final double[] bestSeen = new double[] { Double.POSITIVE_INFINITY };
        final double[] lastTwo = new double[] { Double.NaN, Double.NaN };
        final UnivariateFunction f = new UnivariateFunction() {
            public double value(double x) {
                double v = fBase.value(x);
                if (v < bestSeen[0]) {
                    bestSeen[0] = v;
                }
                lastTwo[0] = lastTwo[1];
                lastTwo[1] = v;
                return v;
            }
        };

        final BrentOptimizer optimizer = new BrentOptimizer(1e-8, 1e-5);
        final UnivariatePointValuePair result =
                optimizer.optimize(200, f, GoalType.MINIMIZE, lo, hi, start);
        final double sol = result.getPoint();
        final double val = result.getValue();
        final double best = bestSeen[0];
        final double prevToLast = lastTwo[0];
        final double last = lastTwo[1];

        Log.logOutIf(true, () -> new Double[]{ sol, val, best, prevToLast, last,
                                               (double) optimizer.getEvaluations() });
        assertTrue("Returned value " + val + " is worse than best evaluated value " + best,
                   val <= best);
    }
}
