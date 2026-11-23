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
    public void testBugReproduction(@InRange(minInt = 1, maxInt = 6) int a,
                                    @InRange(minInt = 0, maxInt = 11) int b) {
        boolean validIf = a >= 1 && b >= 0;
        boolean buggyIf = b >= 1;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        LinearObjectiveFunction f = new LinearObjectiveFunction(new double[] {1, 1}, 0);
        Collection<LinearConstraint> constraints = new ArrayList<LinearConstraint>();
        constraints.add(new LinearConstraint(new double[] {a, 0}, Relationship.EQ, b));

        String err = "none";
        double rx = Double.NaN;
        double ry = Double.NaN;
        double rv = Double.NaN;
        try {
            RealPointValuePair sol = new SimplexSolver().optimize(f, constraints, GoalType.MINIMIZE, true);
            rx = sol.getPoint()[0];
            ry = sol.getPoint()[1];
            rv = sol.getValue();
        } catch (Exception e) {
            err = e.getClass().getName();
        }

        final String ferr = err;
        final double fx = rx;
        final double fy = ry;
        final double fv = rv;
        Log.logOutIf(true, () -> new Object[]{ ferr, fx, fy, fv });
        double expectedX = (double) b / a;
        assertEquals("none", ferr);
        assertEquals(expectedX, fx, 1e-6);
        assertEquals(0.0, fy, 1e-6);
        assertTrue(fy >= -1e-9);
    }
}
