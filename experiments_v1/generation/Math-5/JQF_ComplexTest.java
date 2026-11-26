package org.apache.commons.math3.complex;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

@RunWith(JQF.class)
public class JQF_ComplexTest {

    public static class PartGen extends Generator<Double> {
        public PartGen() { super(Double.class); }
        @Override public Double generate(SourceOfRandomness r, GenerationStatus status) {
            int k = r.nextInt(10);
            if (k < 5) {
                return r.nextBoolean() ? 0.0 : -0.0;
            } else if (k < 7) {
                return (double) r.nextInt(-5, 5);
            } else {
                return r.nextDouble(-100.0, 100.0);
            }
        }
    }

    @Fuzz
    public void testBugReproduction(@From(PartGen.class) Double re,
                                    @From(PartGen.class) Double im) {
        boolean validIf = !re.isNaN() && !im.isNaN() && !re.isInfinite() && !im.isInfinite();
        boolean buggyIf = re == 0.0 && im == 0.0;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        Complex z = new Complex(re, im);
        Complex result = z.reciprocal();

        Log.logOutIf(true, () -> new Object[]{ result.toString(), result.isNaN(), result.isInfinite(),
                result.getReal(), result.getImaginary() });
        Complex expected = Complex.INF;
        assertFalse(result.isNaN());
        assertTrue(result.isInfinite());
        assertEquals(expected, result);
    }

    @Fuzz
    public void testReciprocalZero(@From(PartGen.class) Double re,
                                    @From(PartGen.class) Double im) {
        boolean validIf = !re.isNaN() && !im.isNaN() && !re.isInfinite() && !im.isInfinite();
        boolean buggyIf = re == 0.0 && im == 0.0;
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            Complex z = new Complex(re, im);
            Complex result = z.reciprocal();

            Log.logOutIf(preserveIf, () -> new Object[]{ result.toString(), result.isNaN(), result.isInfinite(),
                    result.getReal(), result.getImaginary() });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
