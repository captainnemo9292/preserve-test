package org.apache.commons.math.distribution;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

@RunWith(JQF.class)
public class JQF_FDistributionTest {

    public static class DenomDfGen extends Generator<Double> {
        public DenomDfGen() { super(Double.class); }
        @Override public Double generate(SourceOfRandomness r, GenerationStatus status) {
            if (r.nextInt(100) < 45) {
                return 2.0;
            }
            if (r.nextBoolean()) {
                return (double) (r.nextInt(1, 20));
            }
            return r.nextDouble(0.01, 100.0);
        }
    }

    @Fuzz
    public void testBugReproduction(@InRange(minDouble = 0.01, maxDouble = 100.0) double numDf,
                                    @From(DenomDfGen.class) double denDf,
                                    @InRange(minDouble = 0, maxDouble = 1) double p) {
        boolean validIf = numDf > 0.0 && denDf > 0.0 && p > 0.0 && p < 1.0;
        boolean buggyIf = denDf == 2.0;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        double res = Double.NaN;
        String exc = "none";
        try {
            FDistributionImpl dist = new FDistributionImpl(numDf, denDf);
            res = dist.inverseCumulativeProbability(p);
        } catch (Exception e) {
            exc = e.getClass().getName();
        }
        final double fres = res;
        final String fexc = exc;

        Log.logOutIf(true, () -> new Object[]{ fexc, fres });
        assertEquals("none", fexc);
        assertFalse(Double.isNaN(fres));
        assertFalse(Double.isInfinite(fres));
    }

    @Fuzz
    public void testSmallDegreesOfFreedom(@InRange(minDouble = 0.01, maxDouble = 100.0) double numDf,
                                    @From(DenomDfGen.class) double denDf,
                                    @InRange(minDouble = 0, maxDouble = 1) double p) {
        boolean validIf = numDf > 0.0 && denDf > 0.0 && p > 0.0 && p < 1.0;
        boolean buggyIf = denDf == 2.0;
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            double res = Double.NaN;
            String exc = "none";
            try {
                FDistributionImpl dist = new FDistributionImpl(numDf, denDf);
                res = dist.inverseCumulativeProbability(p);
            } catch (Exception e) {
                exc = e.getClass().getName();
            }
            final double fres = res;
            final String fexc = exc;

            Log.logOutIf(preserveIf, () -> new Object[]{ fexc, fres, Double.doubleToLongBits(fres), Double.isNaN(fres), Double.isInfinite(fres), numDf, denDf, p });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
