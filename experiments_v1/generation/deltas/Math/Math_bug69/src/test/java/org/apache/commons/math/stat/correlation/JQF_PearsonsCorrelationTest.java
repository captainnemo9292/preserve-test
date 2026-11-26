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

    /** Generates correlation values in (-1, 1) with a mix of weak, moderate and very strong values. */
    public static class CorrGen extends Generator<Double> {
        public CorrGen() { super(Double.class); }
        @Override public Double generate(SourceOfRandomness r, GenerationStatus status) {
            double mag;
            int k = r.nextInt(3);
            if (k == 0) {
                mag = r.nextDouble() * 0.99999999;
            } else if (k == 1) {
                mag = 0.5 + r.nextDouble() * (0.99 - 0.5);
            } else {
                mag = 0.9 + r.nextDouble() * (0.99999999 - 0.9);
            }
            return r.nextBoolean() ? mag : -mag;
        }
    }

    private static double referenceP(int nObs, double rho) {
        try {
            double t = Math.abs(rho * Math.sqrt((nObs - 2) / (1 - rho * rho)));
            return 2 * new TDistributionImpl(nObs - 2).cumulativeProbability(-t);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Fuzz
    public void testBugReproduction(@InRange(minInt = 3, maxInt = 201) int nObs,
                         @From(CorrGen.class) double rho) {
        boolean validIf = nObs >= 3 && Math.abs(rho) < 1.0;
        // The true two-sided p-value is approximately K * (1 - r^2)^((n-2)/2) with K of order 1.
        // The bug zeroes p when it is below ~1e-17; keep it far above double underflow so a fix returns a positive value.
        boolean buggyIf = validIf
                && (nObs - 2) / 2.0 * Math.log10(1 - rho * rho) <= -18.0
                && (nObs - 2) / 2.0 * Math.log10(1 - rho * rho) >= -250.0;

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
        double expected = referenceP(nObs, rho);
        assertEquals("none", fexc);
        assertTrue("p-value must be positive for strong correlation, was " + fp01, fp01 > 0);
        assertEquals(expected, fp01, expected * 1e-3);
        assertEquals(expected, fp10, expected * 1e-3);
    }

    @Fuzz
    public void testPValueNearZero(@InRange(minInt = 3, maxInt = 201) int nObs,
                         @From(CorrGen.class) double rho) {
        boolean validIf = nObs >= 3 && Math.abs(rho) < 1.0;
        boolean buggyIf = validIf
                && (nObs - 2) / 2.0 * Math.log10(1 - rho * rho) <= -18.0
                && (nObs - 2) / 2.0 * Math.log10(1 - rho * rho) >= -250.0;
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
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

            Log.logOutIf(preserveIf, () -> new Object[]{ fexc, fp01, fp10, fp00 });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
