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
        // Tableau rows are ordered a<=1, b<=1, R1(3a-5c), R2(2a-5d), R3(2b-5c), R4(3b-5d), R5, R6,
        // so the a<->b symmetry of the constraints is broken by the row order, which decides
        // the zero-ratio ties of the (>= 0 accepting) ratio test.
        //  c1 >= c2 > 0: a enters (R1 zero ratio), then c (R2 row), then d (a<=1 row), then b;
        //     the ratio test picks the R4 row (3b <= 5d) and the method stops at a=1, b<1.
        //  c2 > c1 > 0: b enters first (R3 row), then c (R4 row), d (b<=1 row), a (R1 row), s5;
        //     the method ends at the optimum a=b=1.
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
