package org.apache.commons.math.stat.correlation;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.apache.commons.math.distribution.TDistributionImpl;
import org.apache.commons.math.linear.BlockRealMatrix;
import org.apache.commons.math.linear.RealMatrix;

@RunWith(JQF.class)
public class JQF_PearsonsCorrelationTest {

    /** Generates correlation values in (-1, 1); about half of them are strong (|r| >= 0.99). */
    public static class CorrGen extends Generator<Double> {
        public CorrGen() { super(Double.class); }
        @Override public Double generate(SourceOfRandomness r, GenerationStatus status) {
            double mag;
            if (r.nextBoolean()) {
                mag = 0.99 + r.nextDouble() * (0.99999 - 0.99);
            } else {
                mag = r.nextDouble() * 0.99999;
            }
            return r.nextBoolean() ? mag : -mag;
        }
    }

    @Fuzz
    public void testBugReproduction(@InRange(minInt = 3, maxInt = 201) int nObs,
                         @From(CorrGen.class) double rho) {
        boolean validIf = nObs >= 3 && Math.abs(rho) < 1.0;
        boolean buggyIf = nObs >= 50 && Math.abs(rho) >= 0.99;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        RealMatrix cov = new BlockRealMatrix(new double[][] {{1.0, rho}, {rho, 1.0}});
        PearsonsCorrelation pc = new PearsonsCorrelation(cov, nObs);
        String exc = "none";
        double p01 = Double.NaN;
        double p10 = Double.NaN;
        double p00 = Double.NaN;
        try {
            RealMatrix p = pc.getCorrelationPValues();
            p01 = p.getEntry(0, 1);
            p10 = p.getEntry(1, 0);
            p00 = p.getEntry(0, 0);
        } catch (Exception e) {
            exc = e.getClass().getName();
        }
        final String fexc = exc;
        final double fp01 = p01;
        final double fp10 = p10;
        final double fp00 = p00;

        Log.logOutIf(true, () -> new Object[]{ fexc, fp01, fp10, fp00 });
        double t = Math.abs(rho * Math.sqrt((nObs - 2) / (1 - rho * rho)));
        double expected = 0;
        try {
            expected = 2 * new TDistributionImpl(nObs - 2).cumulativeProbability(-t);
        } catch (Exception e) {
            fail("reference computation failed: " + e);
        }
        assertEquals("none", fexc);
        assertTrue("p-value must be positive for strong correlation, was " + fp01, fp01 > 0);
        assertEquals(expected, fp01, expected * 1e-6);
        assertEquals(expected, fp10, expected * 1e-6);
    }
}
