package org.apache.commons.math.optimization.direct;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

import org.apache.commons.math.analysis.MultivariateRealFunction;
import org.apache.commons.math.optimization.GoalType;
import org.apache.commons.math.optimization.RealPointValuePair;

@RunWith(JQF.class)
public class JQF_MultiDirectionalTest {

    public static class CenterGen extends Generator<double[]> {
        public CenterGen() { super(double[].class); }
        @Override public double[] generate(SourceOfRandomness r, GenerationStatus status) {
            int n = r.nextInt(1, 4);
            double[] c = new double[n];
            for (int i = 0; i < n; i++) {
                c[i] = r.nextInt(-200, 200) / 4.0;
            }
            return c;
        }
    }

    @Fuzz
    public void testBugReproduction(@From(CenterGen.class) double[] center,
                                    @InRange(minInt = -1, maxInt = 2) int offsetSel,
                                    @InRange(minDouble = -5, maxDouble = 5) double step,
                                    boolean maximize) {
        boolean validIf = center.length >= 1 && step != 0.0;
        boolean buggyIf = offsetSel == 0;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        final double[] c = center.clone();
        final double[] start = center.clone();
        start[0] += offsetSel * 0.5;
        final double sign = maximize ? -1.0 : 1.0;

        MultivariateRealFunction f = new MultivariateRealFunction() {
            public double value(double[] x) {
                double s = 0;
                for (int i = 0; i < x.length; i++) {
                    s += (x[i] - c[i]) * (x[i] - c[i]);
                }
                return sign * s;
            }
        };

        double[] steps = new double[center.length];
        java.util.Arrays.fill(steps, step);

        MultiDirectional optimizer = new MultiDirectional();
        optimizer.setStartConfiguration(steps);
        optimizer.setMaxIterations(500);
        optimizer.setMaxEvaluations(100000);

        String exception = "none";
        RealPointValuePair result = null;
        try {
            result = optimizer.optimize(f, maximize ? GoalType.MAXIMIZE : GoalType.MINIMIZE, start);
        } catch (Exception e) {
            exception = e.getClass().getName();
        }

        final String exc = exception;
        final RealPointValuePair res = result;
        Log.logOutIf(true, () -> new Object[]{
            exc,
            res == null ? "null" : java.util.Arrays.toString(res.getPoint()),
            res == null ? "null" : String.valueOf(res.getValue()),
            String.valueOf(optimizer.getIterations()),
            String.valueOf(optimizer.getEvaluations())
        });

        assertEquals("none", exception);
        assertNotNull(result);
        for (int i = 0; i < c.length; i++) {
            assertEquals(c[i], result.getPoint()[i], 1e-6);
        }
        assertEquals(0.0, result.getValue(), 1e-9);
    }
}
