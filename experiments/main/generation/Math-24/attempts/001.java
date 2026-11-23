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
        final double minSin = 3 * Math.PI / 2;
        final double offset = 1e-8;
        final double delta = 1e-7;
        double lo = minSin - a * delta;
        double hi = 2 * Math.PI;
        double start = minSin + b * delta;

        boolean validIf = lo < hi && lo <= start && start <= hi;
        boolean buggyIf = a >= 6.5 && a <= 7.1 && b >= 9.5 && b <= 10;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        final UnivariateFunction f1 = new Sin();
        final UnivariateFunction f2 = new StepFunction(
                new double[] { minSin, minSin + offset, minSin + 2 * offset },
                new double[] { 0, -1, 0 });
        final UnivariateFunction f = FunctionUtils.add(f1, f2);

        final BrentOptimizer optimizer = new BrentOptimizer(1e-8, 1e-5);
        final UnivariatePointValuePair result =
                optimizer.optimize(200, f, GoalType.MINIMIZE, lo, hi, start);
        final double sol = result.getPoint();
        final double val = result.getValue();

        Log.logOutIf(true, () -> new Double[]{ sol, val, (double) optimizer.getEvaluations() });
        final double initValue = f.value(start);
        assertTrue("Best point not reported: value " + val + " > initial " + initValue,
                   val <= initValue);
    }
}
