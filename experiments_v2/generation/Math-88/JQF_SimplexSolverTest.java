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
    public void testBugReproduction(@InRange(minInt = 0, maxInt = 31) int l1,
                                    @InRange(minInt = 0, maxInt = 31) int l4,
                                    @InRange(minInt = 0, maxInt = 31) int l5) {
        // feasible: group A needs l1 + l5 <= 30, group B needs l4 <= 30
        boolean validIf = l1 + l5 <= 30 && l4 <= 30;
        // bug: lower-bound (>=) constraints with several variables sharing rows
        boolean buggyIf = l1 > 0 && l4 > 0 && l5 > 0;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        LinearObjectiveFunction f = new LinearObjectiveFunction(
            new double[] {0.8, 0.2, 0.7, 0.3, 0.6, 0.4}, 0);
        Collection<LinearConstraint> cs = new ArrayList<LinearConstraint>();
        cs.add(new LinearConstraint(new double[] {1, 0, 1, 0, 1, 0}, Relationship.EQ, 30));
        cs.add(new LinearConstraint(new double[] {0, 1, 0, 1, 0, 1}, Relationship.EQ, 30));
        cs.add(new LinearConstraint(new double[] {1, 0, 0, 0, 0, 0}, Relationship.GEQ, l1));
        cs.add(new LinearConstraint(new double[] {0, 0, 0, 1, 0, 0}, Relationship.GEQ, l4));
        cs.add(new LinearConstraint(new double[] {0, 0, 0, 0, 1, 0}, Relationship.GEQ, l5));

        String exc = "none";
        double[] p = new double[0];
        double val = Double.NaN;
        try {
            RealPointValuePair sol = new SimplexSolver().optimize(f, cs, GoalType.MINIMIZE, true);
            p = sol.getPoint();
            val = sol.getValue();
        } catch (Exception e) {
            exc = e.getClass().getName();
        }

        final String fexc = exc;
        final double[] fp = p;
        final double fval = val;
        Log.logOutIf(true, () -> new Object[]{ fexc, java.util.Arrays.toString(fp), fval });

        double expected = 0.8 * l1 + 0.6 * (30 - l1) + 0.3 * l4 + 0.2 * (30 - l4);
        assertEquals("none", fexc);
        assertEquals(6, fp.length);
        assertEquals(expected, fval, 1e-6);
        assertEquals(30.0, fp[0] + fp[2] + fp[4], 1e-6);
        assertEquals(30.0, fp[1] + fp[3] + fp[5], 1e-6);
        assertTrue(fp[0] >= l1 - 1e-6);
        assertTrue(fp[3] >= l4 - 1e-6);
        assertTrue(fp[4] >= l5 - 1e-6);
    }

    @Fuzz
    public void testMath272(@InRange(minInt = 0, maxInt = 31) int l1,
                                    @InRange(minInt = 0, maxInt = 31) int l4,
                                    @InRange(minInt = 0, maxInt = 31) int l5) {
        boolean validIf = l1 + l5 <= 30 && l4 <= 30;
        boolean buggyIf = l1 > 0 && l4 > 0 && l5 > 0;
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            LinearObjectiveFunction f = new LinearObjectiveFunction(
                new double[] {0.8, 0.2, 0.7, 0.3, 0.6, 0.4}, 0);
            Collection<LinearConstraint> cs = new ArrayList<LinearConstraint>();
            cs.add(new LinearConstraint(new double[] {1, 0, 1, 0, 1, 0}, Relationship.EQ, 30));
            cs.add(new LinearConstraint(new double[] {0, 1, 0, 1, 0, 1}, Relationship.EQ, 30));
            cs.add(new LinearConstraint(new double[] {1, 0, 0, 0, 0, 0}, Relationship.GEQ, l1));
            cs.add(new LinearConstraint(new double[] {0, 0, 0, 1, 0, 0}, Relationship.GEQ, l4));
            cs.add(new LinearConstraint(new double[] {0, 0, 0, 0, 1, 0}, Relationship.GEQ, l5));

            String exc = "none";
            double[] p = new double[0];
            double val = Double.NaN;
            try {
                RealPointValuePair sol = new SimplexSolver().optimize(f, cs, GoalType.MINIMIZE, true);
                p = sol.getPoint();
                val = sol.getValue();
            } catch (Exception e) {
                exc = e.getClass().getName();
            }

            final String fexc = exc;
            final double[] fp = p;
            final double fval = val;
            final int fl1 = l1;
            final int fl4 = l4;
            final int fl5 = l5;
            Log.logOutIf(preserveIf, () -> new Object[]{ fexc, java.util.Arrays.toString(fp), fval,
                Double.doubleToLongBits(fval), fp.length, fl1, fl4, fl5 });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
