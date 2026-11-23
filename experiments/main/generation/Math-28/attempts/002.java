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

import org.apache.commons.math3.optimization.GoalType;
import org.apache.commons.math3.optimization.PointValuePair;

@RunWith(JQF.class)
public class JQF_SimplexSolverTest {

    public static class DataGen extends Generator<double[]> {
        public DataGen() { super(double[].class); }
        @Override public double[] generate(SourceOfRandomness r, GenerationStatus status) {
            double[] d = new double[128];
            boolean frac = r.nextBoolean();
            for (int i = 0; i < d.length; i++) {
                if (frac) {
                    d[i] = 0.01 + r.nextDouble() * 99.99;
                } else {
                    d[i] = r.nextInt(1, 100);
                }
            }
            return d;
        }
    }

    @Fuzz
    public void testBugReproduction(@InRange(minInt = 2, maxInt = 17) int R,
                                    @InRange(minInt = 1, maxInt = 5) int M,
                                    @InRange(minInt = 1, maxInt = 5) int N,
                                    @From(DataGen.class) double[] data) {
        boolean validIf = R >= 2 && R <= 16 && M >= 1 && M <= 4 && N >= 1 && N <= 4
                && data != null && data.length == 128;
        boolean buggyIf = validIf && R >= 10;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        // Solve the DEA problem for every entity; variables: [t, l_0 .. l_{R-1}]
        int failures = 0;
        String firstExc = "none";
        double sumT = 0.0;
        for (int entity = 0; entity < R; entity++) {
            Collection<LinearConstraint> constraints = new ArrayList<LinearConstraint>();
            for (int i = 0; i < M; i++) {
                double[] coef = new double[R + 1];
                for (int j = 0; j < R; j++) {
                    coef[j + 1] = data[i * R + j];
                }
                constraints.add(new LinearConstraint(coef, Relationship.GEQ, data[i * R + entity]));
            }
            for (int i = 0; i < N; i++) {
                double[] coef = new double[R + 1];
                coef[0] = data[64 + i * R + entity];
                for (int j = 0; j < R; j++) {
                    coef[j + 1] = -data[64 + i * R + j];
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
                sumT += sol.getValue();
            } catch (Exception e) {
                failures++;
                if (firstExc.equals("none")) {
                    firstExc = e.getClass().getName();
                }
            }
        }

        final int fFailures = failures;
        final String fExc = firstExc;
        final double fSum = sumT;
        Log.logOutIf(true, () -> new Object[]{ fFailures, fExc, fSum });
        assertEquals("none", firstExc);
        assertEquals(0, failures);
    }
}
