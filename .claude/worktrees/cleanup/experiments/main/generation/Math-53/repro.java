package org.apache.commons.math.complex;

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

        @Override
        public Double generate(SourceOfRandomness r, GenerationStatus status) {
            int k = r.nextInt(10);
            switch (k) {
                case 0:
                case 1:
                    return Double.NaN;
                case 2:
                    return Double.POSITIVE_INFINITY;
                case 3:
                    return Double.NEGATIVE_INFINITY;
                case 4:
                    return 0.0;
                default:
                    return r.nextDouble(-100.0, 100.0);
            }
        }
    }

    @Fuzz
    public void testBugReproduction(@From(PartGen.class) double r1,
                                    @From(PartGen.class) double i1,
                                    @From(PartGen.class) double r2,
                                    @From(PartGen.class) double i2) {
        boolean validIf = true;
        boolean buggyIf = (Double.isNaN(r1) || Double.isNaN(i1) || Double.isNaN(r2) || Double.isNaN(i2))
                && !(Double.isNaN(r1 + r2) && Double.isNaN(i1 + i2));

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        Complex a = new Complex(r1, i1);
        Complex b = new Complex(r2, i2);
        Complex sum = a.add(b);
        Complex diff = a.subtract(b);

        Log.logOutIf(true, () -> new String[]{
                Double.toString(sum.getReal()),
                Double.toString(sum.getImaginary()),
                Boolean.toString(sum.isNaN()),
                Double.toString(diff.getReal()),
                Double.toString(diff.getImaginary()),
                Boolean.toString(diff.isNaN())
        });

        assertTrue("subtract should return NaN", diff.isNaN());
        assertTrue("add should be NaN when an operand has NaN", sum.isNaN());
        assertTrue(Double.isNaN(sum.getReal()));
        assertTrue(Double.isNaN(sum.getImaginary()));
    }
}
