package org.apache.commons.math3.optimization.linear;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

import java.util.ArrayList;
import java.util.Collection;

import org.apache.commons.math3.optimization.GoalType;
import org.apache.commons.math3.optimization.RealPointValuePair;
import org.apache.commons.math3.util.Precision;

@RunWith(JQF.class)
public class JQF_SimplexSolverTest {

    @Fuzz
    public void testBugReproduction(@InRange(minInt = 0, maxInt = 4) int objScaleExp) {
        // objective scaled by an exact power of two (same problem, same pivots)
        boolean validIf = objScaleExp >= 0 && objScaleExp <= 3;
        boolean buggyIf = true;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        final double k = (double) (1 << objScaleExp);
        final double eps = 1e-6;

        LinearObjectiveFunction f = new LinearObjectiveFunction(new double[] { 2 * k, 6 * k, 7 * k }, 0);
        Collection<LinearConstraint> constraints = new ArrayList<LinearConstraint>();
        constraints.add(new LinearConstraint(new double[] { 1, 2, 1 }, Relationship.LEQ, 2));
        constraints.add(new LinearConstraint(new double[] { -1, 1, 1 }, Relationship.LEQ, -1));
        constraints.add(new LinearConstraint(new double[] { 2, -3, 1 }, Relationship.LEQ, -1));

        String exc = "none";
        double p0 = Double.NaN, p1 = Double.NaN, p2 = Double.NaN, value = Double.NaN;
        try {
            RealPointValuePair solution = new SimplexSolver().optimize(f, constraints, GoalType.MAXIMIZE, false);
            p0 = solution.getPoint()[0];
            p1 = solution.getPoint()[1];
            p2 = solution.getPoint()[2];
            value = solution.getValue();
        } catch (Throwable t) {
            exc = t.getClass().getName();
        }

        final String excF = exc;
        final double q0 = p0, q1 = p1, q2 = p2, v = value;
        Log.logOutIf(true, () -> new Object[]{ excF, q0, q1, q2, v });

        final double expectedValue = 2.0d * k;
        assertEquals("no exception expected", "none", excF);
        assertTrue("x1 should be > 0, was " + q0, Precision.compareTo(q0, 0.0d, eps) > 0);
        assertTrue("x2 should be > 0, was " + q1, Precision.compareTo(q1, 0.0d, eps) > 0);
        assertTrue("x3 should be < 0, was " + q2, Precision.compareTo(q2, 0.0d, eps) < 0);
        assertEquals(expectedValue, v, eps * k);
    }
}
