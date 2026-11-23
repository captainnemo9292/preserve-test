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
import java.util.Collection;
import java.util.Random;

import org.apache.commons.math3.optimization.GoalType;
import org.apache.commons.math3.optimization.PointValuePair;

@RunWith(JQF.class)
public class JQF_SimplexSolverTest {

    /** Produces {R, M, N, seed}; about 40% of the time the combination known to trigger the bug. */
    public static class ProblemGen extends Generator<long[]> {
        public ProblemGen() { super(long[].class); }
        @Override public long[] generate(SourceOfRandomness r, GenerationStatus status) {
            int pick = r.nextInt(0, 9);
            if (pick < 4) {
                return new long[]{23, 6, 6, 26449568L};
            }
            long R = r.nextInt(2, 25);
            long M = r.nextInt(1, 6);
            long N = r.nextInt(1, 6);
            long seed = r.nextLong(0L, 100000000L);
            return new long[]{R, M, N, seed};
        }
    }

    @Fuzz
    public void testBugReproduction(@From(ProblemGen.class) long[] cfg) {
        boolean validIf = cfg != null && cfg.length == 4
                && cfg[0] >= 2 && cfg[0] <= 25
                && cfg[1] >= 1 && cfg[1] <= 6
                && cfg[2] >= 1 && cfg[2] <= 6
                && cfg[3] >= 0L && cfg[3] <= 100000000L;
        // Whether the solver wrongly reports "unbounded" depends on the numerical data (seed), not on a
        // size threshold. This (R, M, N, seed) combination yields DEA problems (all LPs feasible and bounded)
        // for which the buggy solver reports at least one LP as unbounded.
        boolean buggyIf = validIf && cfg[0] == 23 && cfg[1] == 6 && cfg[2] == 6 && cfg[3] == 26449568L;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        final int R = (int) cfg[0];
        final int M = (int) cfg[1];
        final int N = (int) cfg[2];
        final long seed = cfg[3];

        Random rnd = new Random(seed);
        int unbounded = 0;
        int solved = 0;
        int otherFailures = 0;
        double sumT = 0.0;
        for (int ds = 0; ds < 40; ds++) {
            double[][] Q = new double[M][R];
            double[][] X = new double[N][R];
            for (int i = 0; i < M; i++) {
                for (int j = 0; j < R; j++) {
                    Q[i][j] = 0.01 + rnd.nextDouble() * 99.99;
                }
            }
            for (int i = 0; i < N; i++) {
                for (int j = 0; j < R; j++) {
                    X[i][j] = 0.01 + rnd.nextDouble() * 99.99;
                }
            }
            // variables: [t, l_0 .. l_{R-1}]; all variables non-negative (restrictToNonNegative = true),
            // and L >= 0 additionally stated as explicit constraints as in the report
            for (int entity = 0; entity < R; entity++) {
                Collection<LinearConstraint> constraints = new ArrayList<LinearConstraint>();
                for (int i = 0; i < M; i++) {
                    double[] coef = new double[R + 1];
                    for (int j = 0; j < R; j++) {
                        coef[j + 1] = Q[i][j];
                    }
                    constraints.add(new LinearConstraint(coef, Relationship.GEQ, Q[i][entity]));
                }
                for (int i = 0; i < N; i++) {
                    double[] coef = new double[R + 1];
                    coef[0] = X[i][entity];
                    for (int j = 0; j < R; j++) {
                        coef[j + 1] = -X[i][j];
                    }
                    constraints.add(new LinearConstraint(coef, Relationship.GEQ, 0.0));
                }
                for (int j = 0; j < R; j++) {
                    double[] coef = new double[R + 1];
                    coef[j + 1] = 1.0;
                    constraints.add(new LinearConstraint(coef, Relationship.GEQ, 0.0));
                }
                double[] obj = new double[R + 1];
                obj[0] = 1.0;
                LinearObjectiveFunction f = new LinearObjectiveFunction(obj, 0.0);
                try {
                    PointValuePair sol = new SimplexSolver().optimize(f, constraints, GoalType.MINIMIZE, true);
                    solved++;
                    sumT += sol.getValue();
                } catch (UnboundedSolutionException e) {
                    unbounded++;
                } catch (Exception e) {
                    otherFailures++;
                }
            }
        }

        final int fUnbounded = unbounded;
        final int fSolved = solved;
        final int fOther = otherFailures;
        final double fSum = sumT;
        Log.logOutIf(true, () -> new Object[]{ fUnbounded, fSolved, fOther, fSum });
        // every LP is feasible (L = e_r, t = 1) and bounded below (t >= 0): never unbounded
        assertEquals("number of LPs reported as unbounded", 0, unbounded);
    }
}
