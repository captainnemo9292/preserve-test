package org.apache.commons.math.stat.regression;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

@RunWith(JQF.class)
public class JQF_SimpleRegressionTest {

    @Fuzz
    public void testBugReproduction(@InRange(minDouble = 0, maxDouble = 200) double x0,
                                    @InRange(minDouble = 0, maxDouble = 1) double dx,
                                    @InRange(minDouble = 0, maxDouble = 10000) double y0,
                                    @InRange(minDouble = 0, maxDouble = 10) double dy) {
        boolean validIf = dx > 0;
        // large, closely spaced x values with an (almost) perfectly linear y
        boolean buggyIf = x0 >= 110.0 && x0 <= 111.0
                && dx >= 8.6e-5 && dx <= 8.7e-5
                && y0 >= 8915.0 && y0 <= 8916.0
                && Math.abs(dy - dx * 48611.11) < 0.01;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        SimpleRegression reg = new SimpleRegression();
        for (int i = 0; i < 3; i++) {
            reg.addData(x0 + i * dx, y0 + i * dy);
        }
        double sse = reg.getSumSquaredErrors();
        long n = reg.getN();
        double slope = reg.getSlope();

        Log.logOutIf(true, () -> new Object[]{ sse, n, slope });
        assertTrue("SSE must not be negative but was " + sse, sse >= 0.0);
    }
}
