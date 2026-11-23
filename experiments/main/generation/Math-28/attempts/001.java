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
            for (int i = 0; i < d.length; i++) {
                d[i] = r.nextInt(1, 100);
            }
            return d;
        }
    }

    @Fuzz
    public void testBugReproduction(@InRange(minInt = 2, maxInt = 17) int R,
                                    @InRange(minInt = 1, maxInt = 5) int M,
                                    @InRange(minInt = 1, maxInt = 5) int N,
                                    @InRange(minInt = 0, maxInt = 17) int entity,
                                    @From(DataGen.class) double[] data) {
        boolean validIf = R >= 2 && R <= 17 && M >= 1 && M <= 4 && N >= 1 && N <= 4
                && entity >= 0 && entity < R && data != null && data.length == 128 && R <= 16;
        boolean buggyIf = validIf && R >= 10;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        // variables: [t, l_0 .. l_{R-1}]
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
        double[] obj = new double[R + 1];
        obj[0] = 1.0;
        LinearObjectiveFunction f = new LinearObjectiveFunction(obj, 0.0);

        String exc = "none";
        double t = Double.NaN;
        try {
            PointValuePair sol = new SimplexSolver().optimize(f, constraints, GoalType.MINIMIZE, true);
            t = sol.getValue();
        } catch (Exception e) {
            exc = e.getClass().getName();
        }

        final String fExc = exc;
        final double fT = t;
        Log.logOutIf(true, () -> new Object[]{ fExc, fT });
        assertEquals("none", exc);
        assertTrue(t <= 1.0 + 1e-6);
    }
}
