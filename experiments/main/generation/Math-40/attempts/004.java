package org.apache.commons.math.analysis.solvers;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.apache.commons.math.analysis.UnivariateFunction;

@RunWith(JQF.class)
public class JQF_BracketingNthOrderBrentSolverTest {

    /** scale of the function: wide valid range, 50% near the failing configuration. */
    public static class ScaleGen extends Generator<Double> {
        public ScaleGen() { super(Double.class); }

        @Override
        public Double generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextBoolean()) {
                return r.nextDouble(9.0e8, 1.1e9);
            }
            return r.nextDouble(1.0e3, 1.0e12);
        }
    }

    /** distance of the lower bound above the pole at -1, i.e. min = -1 + eps. */
    public static class EpsGen extends Generator<Double> {
        public EpsGen() { super(Double.class); }

        @Override
        public Double generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextBoolean()) {
                return r.nextDouble(5.0e-8, 2.0e-7);
            }
            // log-uniform over the whole legal range (0, 0.5)
            double e = r.nextDouble(-10.0, Math.log10(0.4999));
            return Math.pow(10.0, e);
        }
    }

    public static class MaxGen extends Generator<Double> {
        public MaxGen() { super(Double.class); }

        @Override
        public Double generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextBoolean()) {
                return r.nextDouble(20.0, 40.0);
            }
            return r.nextDouble(-0.4, 1000.0);
        }
    }

    public static class StartGen extends Generator<Double> {
        public StartGen() { super(Double.class); }

        @Override
        public Double generate(SourceOfRandom ness r, GenerationStatus status) {
            return null;
        }
    }
}
