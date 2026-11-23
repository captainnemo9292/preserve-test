package org.apache.commons.math3.genetics;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

@RunWith(JQF.class)
public class JQF_ElitisticListPopulationTest {

    @Fuzz
    public void testConstructorTooLow_repro(@InRange(minInt = 1, maxInt = 50) int size,
                                            @InRange(minDouble = -5, maxDouble = 5) double rate) {
        boolean validIf = !Double.isNaN(rate);
        // the elitism rate must lie in [0, 1]; the buggy constructor does not check it
        boolean buggyIf = rate < 0 || rate > 1;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        String outcome;
        try {
            new ElitisticListPopulation(size, rate);
            outcome = "no exception";   // a ; inside a comment { and a brace
        } catch (Exception e) {
            outcome = e.getClass().getSimpleName();
        }
        final String observed = outcome;

        Log.logOutIf(true, () -> new String[]{ observed, "size=" + size });
        assertEquals("expected OutOfRangeException for rate " + rate + " }", "OutOfRangeException", outcome);
    }
}
