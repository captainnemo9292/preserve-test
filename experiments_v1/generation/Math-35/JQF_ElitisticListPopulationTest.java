package org.apache.commons.math3.genetics;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.math3.exception.OutOfRangeException;

@RunWith(JQF.class)
public class JQF_ElitisticListPopulationTest {

    @Fuzz
    public void testBugReproduction(@InRange(minInt = 1, maxInt = 101) int populationLimit,
                                    @InRange(minDouble = -3.0, maxDouble = 4.0) double elitismRate,
                                    boolean useListConstructor) {
        boolean validIf = populationLimit >= 1;
        boolean buggyIf = elitismRate < 0 || elitismRate > 1;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        String exceptionName = "none";
        double storedRate = Double.NaN;
        try {
            ElitisticListPopulation pop;
            if (useListConstructor) {
                List<Chromosome> chromosomes = new ArrayList<Chromosome>();
                pop = new ElitisticListPopulation(chromosomes, populationLimit, elitismRate);
            } else {
                pop = new ElitisticListPopulation(populationLimit, elitismRate);
            }
            storedRate = pop.getElitismRate();
        } catch (Throwable t) {
            exceptionName = t.getClass().getName();
        }

        final String excLog = exceptionName;
        final double rateLog = storedRate;
        Log.logOutIf(true, () -> new Object[]{ excLog, rateLog });
        String expectedException = OutOfRangeException.class.getName();
        assertEquals(expectedException, exceptionName);
    }

    @Fuzz
    public void testConstructorTooLow(@InRange(minInt = 1, maxInt = 101) int populationLimit,
                                    @InRange(minDouble = -3.0, maxDouble = 4.0) double elitismRate,
                                    boolean useListConstructor) {
        boolean validIf = populationLimit >= 1;
        boolean buggyIf = elitismRate < 0 || elitismRate > 1;
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            String exceptionName = "none";
            double storedRate = Double.NaN;
            try {
                ElitisticListPopulation pop;
                if (useListConstructor) {
                    List<Chromosome> chromosomes = new ArrayList<Chromosome>();
                    pop = new ElitisticListPopulation(chromosomes, populationLimit, elitismRate);
                } else {
                    pop = new ElitisticListPopulation(populationLimit, elitismRate);
                }
                storedRate = pop.getElitismRate();
            } catch (Throwable t) {
                exceptionName = t.getClass().getName();
            }

            final String excLog = exceptionName;
            final double rateLog = storedRate;
            Log.logOutIf(preserveIf, () -> new Object[]{ excLog, rateLog });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
