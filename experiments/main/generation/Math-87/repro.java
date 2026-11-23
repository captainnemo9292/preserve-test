package org.apache.commons.math.optimization.linear;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

import java.util.ArrayList;
import java.util.Collection;

import org.apache.commons.math.optimization.GoalType;
import org.apache.commons.math.optimization.RealPointValuePair;

@RunWith(JQF.class)
public class JQF_SimplexSolverTest {

    @Fuzz
    public void testBugReproduction(@InRange(minInt = -3, maxInt = 4) int c,
                                    @InRange(minDouble = -5, maxDouble = 5) double a,
                                    @InRange(minDouble = 0, maxDouble = 10) double b) {
        // maximize c*x subject to a*x <= b, x >= 0
        boolean validIf = true;
        // c < 0: the objective row has no negative entry, so no pivot happens and x stays
        // non-basic; the unique optimum is x = 0. The x column holds a single nonzero entry a
        // (a != 0, a != 1) in the constraint rows, which was wrongly taken to be basic, so the
        // solution reads x = b from the RHS.
        boolean buggyIf = c < 0 && Math.abs(a) > 0.001 && Math.abs(a - 1) > 0.001 && b > 0.001;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        double[] point = null;
        double value = Double.NaN;
        String exc = "none";
        try {
            LinearObjectiveFunction f = new LinearObjectiveFunction(new double[] { c }, 0);
            Collection<LinearConstraint> constraints = new ArrayList<LinearConstraint>();
            constraints.add(new LinearConstraint(new double[] { a }, Relationship.LEQ, b));
            RealPointValuePair sol = new SimplexSolver().optimize(f, constraints, GoalType.MAXIMIZE, true);
            point = sol.getPoint();
            value = sol.getValue();
        } catch (Exception e) {
            exc = e.getClass().getName();
        }

        final double[] fp = point;
        final double fv = value;
        final String fe = exc;
        Log.logOutIf(true, () -> new Object[]{ fe, fp == null ? "null" : String.valueOf(fp[0]), fv });
        assertEquals("none", exc);
        assertEquals(0.0, point[0], 1e-9);
        assertEquals(0.0, value, 1e-9);
    }
}
