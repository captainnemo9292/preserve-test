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
import java.util.Random;

import org.apache.commons.math3.optimization.GoalType;
import org.apache.commons.math3.optimization.PointValuePair;

@RunWith(JQF.class)
public class JQF_SimplexSolverTest {

    @Fuzz
    public void testBugReproduction(@InRange(minInt = 2, maxInt = 21) int R,
                                    @InRange(minInt = 1, maxInt = 6) int M,
                                    @InRange(minInt = 1, maxInt = 6) int N,
                                    @InRange(minLong = 0L, maxLong = 1000000L) long seed,
                                    @InRange(minInt = 1, maxInt = 41) int datasets) {
        boolean validIf = R >= 2 && R <= 20 && M >= 1 && M <= 5 && N >= 1 && N <= 5
                && datasets >= 1 && datasets <= 40;
        // Bug: with many entities/variables and enough random DEA datasets, some LPs are wrongly reported unbounded.
        boolean buggyIf = validIf && R >= 10 && datasets >= 20;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        Random rnd = new Random(seed);
        int unbounded = 0;
        int solved = 0;
        int otherFailures = 0;
        double sumT = 0.0;
        for (int ds = 0; ds < datasets; ds++) {
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
            // variables: [t, l_0 .. l_{R-1}]; L >= 0 given as explicit constraints
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
                    PointValuePair sol = new SimplexSolver().optimize(f, constraints, GoalType.MINIMIZE, false);
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
