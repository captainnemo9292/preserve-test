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
    public void testBugReproduction(@InRange(minInt = -5, maxInt = 31) int c1,
                                    @InRange(minInt = -5, maxInt = 31) int c2) {
        // Any integer objective coefficients for a and b are legal (the LP is bounded: a,b <= 1).
        boolean validIf = c1 >= -5 && c1 <= 30 && c2 >= -5 && c2 <= 30;
        // Exact pivot trace (rows: a<=1, b<=1, R1..R4, R5, R6; Dantzig column rule; the ratio
        // test accepts zero entries and keeps the first strictly smaller ratio):
        //  c1 >= c2 > 0: a enters (row R1, ratio 0), c enters (row R2, ratio 0), d enters
        //     (only row a<=1 qualifies), then b enters and the R4 row (3b <= 5d) limits it:
        //     the method stops at a=1, b<1.
        //  c2 > c1 > 0: b enters (row R3, ratio 0), c (row R4, ratio 0), d (row b<=1), then a
        //     (row R1, ratio 2/3) and s5; the method ends at the optimum a=b=1.
        //  c2 == 0: a=1, b=0 is reached, which is an alternative optimum (value c1).
        boolean buggyIf = c2 > 0 && c1 >= c2;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        LinearObjectiveFunction f = new LinearObjectiveFunction(new double[]{c1, c2, 0, 0}, 0);
        Collection<LinearConstraint> cons = new ArrayList<LinearConstraint>();
        cons.add(new LinearConstraint(new double[]{1, 0, 0, 0}, Relationship.LEQ, 1));
        cons.add(new LinearConstraint(new double[]{0, 1, 0, 0}, Relationship.LEQ, 1));
        cons.add(new LinearConstraint(new double[]{3, 0, -5, 0}, Relationship.LEQ, 0));
        cons.add(new LinearConstraint(new double[]{2, 0, 0, -5}, Relationship.LEQ, 0));
        cons.add(new LinearConstraint(new double[]{0, 2, -5, 0}, Relationship.LEQ, 0));
        cons.add(new LinearConstraint(new double[]{0, 3, 0, -5}, Relationship.LEQ, 0));
        cons.add(new LinearConstraint(new double[]{3, 2, 0, 0}, Relationship.LEQ, 5));
        cons.add(new LinearConstraint(new double[]{2, 3, 0, 0}, Relationship.LEQ, 5));

        String exc = "none";
        double a = Double.NaN;
        double b = Double.NaN;
        double value = Double.NaN;
        try {
            RealPointValuePair result = new SimplexSolver().optimize(f, cons, GoalType.MAXIMIZE, true);
            a = result.getPoint()[0];
            b = result.getPoint()[1];
            value = result.getValue();
        } catch (Exception e) {
            exc = e.getClass().getName();
        }

        final String fexc = exc;
        final double fa = a;
        final double fb = b;
        final double fvalue = value;
        Log.logOutIf(true, () -> new Object[]{fexc, fa, fb, fvalue});

        // a = b = 1 is feasible (3+2<=5, 2+3<=5) and the unique optimum for positive coefficients
        double expectedValue = c1 + c2;
        assertEquals("none", exc);
        assertEquals(expectedValue, value, 1e-6);
        assertEquals(1.0, a, 1e-6);
        assertEquals(1.0, b, 1e-6);
    }
}
