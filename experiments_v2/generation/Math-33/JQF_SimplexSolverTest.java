package org.apache.commons.math3.optimization.linear;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

import org.apache.commons.math3.optimization.GoalType;
import org.apache.commons.math3.optimization.PointValuePair;
import org.apache.commons.math3.util.Precision;

@RunWith(JQF.class)
public class JQF_SimplexSolverTest {

    // layout: obj[0..2], A row-major [3..11], rhs [12..14]
    static final int[] KNOWN = { 2, 6, 7, 1, 2, 1, -1, 1, 1, 2, -3, 1, 2, -1, -1 };

    public static class ProblemGen extends Generator<int[]> {
        public ProblemGen() { super(int[].class); }
        @Override public int[] generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextInt(0, 9) < 4) {
                return KNOWN.clone();
            }
            int[] a = new int[15];
            for (int i = 0; i < 15; i++) {
                a[i] = r.nextInt(-5, 8);
            }
            return a;
        }
    }

    @Fuzz
    public void testBugReproduction(@From(ProblemGen.class) int[] p,
                                    boolean restrictToNonNegative,
                                    boolean maximize) {
        boolean validIf = p != null && p.length == 15;
        boolean buggyIf = validIf && Arrays.equals(p, KNOWN)
                && !restrictToNonNegative && maximize;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        LinearObjectiveFunction f = new LinearObjectiveFunction(
                new double[] { p[0], p[1], p[2] }, 0);
        Collection<LinearConstraint> constraints = new ArrayList<LinearConstraint>();
        for (int i = 0; i < 3; i++) {
            constraints.add(new LinearConstraint(
                    new double[] { p[3 + 3 * i], p[4 + 3 * i], p[5 + 3 * i] },
                    Relationship.LEQ, p[12 + i]));
        }

        String exc = "none";
        double p0 = Double.NaN, p1 = Double.NaN, p2 = Double.NaN, value = Double.NaN;
        try {
            PointValuePair solution = new SimplexSolver().optimize(f, constraints,
                    maximize ? GoalType.MAXIMIZE : GoalType.MINIMIZE, restrictToNonNegative);
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

        final double eps = 1e-6;
        assertEquals("no exception expected", "none", excF);
        assertTrue("x1 should be > 0, was " + q0, Precision.compareTo(q0, 0.0d, eps) > 0);
        assertTrue("x2 should be > 0, was " + q1, Precision.compareTo(q1, 0.0d, eps) > 0);
        assertTrue("x3 should be < 0, was " + q2, Precision.compareTo(q2, 0.0d, eps) < 0);
        assertEquals(2.0d, v, eps);
    }

    @Fuzz
    public void testMath781(@From(ProblemGen.class) int[] p,
                                    boolean restrictToNonNegative,
                                    boolean maximize) {
        boolean validIf = p != null && p.length == 15;
        boolean buggyIf = validIf && Arrays.equals(p, KNOWN)
                && !restrictToNonNegative && maximize;
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            LinearObjectiveFunction f = new LinearObjectiveFunction(
                    new double[] { p[0], p[1], p[2] }, 0);
            Collection<LinearConstraint> constraints = new ArrayList<LinearConstraint>();
            for (int i = 0; i < 3; i++) {
                constraints.add(new LinearConstraint(
                        new double[] { p[3 + 3 * i], p[4 + 3 * i], p[5 + 3 * i] },
                        Relationship.LEQ, p[12 + i]));
            }

            String exc = "none";
            double p0 = Double.NaN, p1 = Double.NaN, p2 = Double.NaN, value = Double.NaN;
            try {
                PointValuePair solution = new SimplexSolver().optimize(f, constraints,
                        maximize ? GoalType.MAXIMIZE : GoalType.MINIMIZE, restrictToNonNegative);
                p0 = solution.getPoint()[0];
                p1 = solution.getPoint()[1];
                p2 = solution.getPoint()[2];
                value = solution.getValue();
            } catch (Throwable t) {
                exc = t.getClass().getName();
            }

            final String excF = exc;
            final double q0 = p0, q1 = p1, q2 = p2, v = value;
            final boolean rnn = restrictToNonNegative, mx = maximize;
            final int ph = Arrays.hashCode(p);
            Log.logOutIf(preserveIf, () -> new Object[]{ excF, q0, q1, q2, v,
                    Double.doubleToLongBits(q0), Double.doubleToLongBits(q1),
                    Double.doubleToLongBits(q2), Double.doubleToLongBits(v),
                    rnn, mx, ph });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
