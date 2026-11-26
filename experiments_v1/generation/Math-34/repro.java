package org.apache.commons.math3.genetics;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.runner.RunWith;
import anonymous.log.Log;

@RunWith(JQF.class)
public class JQF_ListPopulationTest {

    public static class FixedChromosome extends Chromosome {
        private final double fit;
        public FixedChromosome(double fit) { this.fit = fit; }
        public double fitness() { return fit; }
        @Override public String toString() { return "C(" + fit + ")"; }
    }

    public static class SimplePopulation extends ListPopulation {
        public SimplePopulation(List<Chromosome> chromosomes, int limit) { super(chromosomes, limit); }
        public Population nextGeneration() { return this; }
    }

    public static class FitnessListGen extends Generator<List> {
        public FitnessListGen() { super(List.class); }
        @Override public List generate(SourceOfRandomness r, GenerationStatus status) {
            int n = r.nextInt(0, 10);
            if (n == 0 && r.nextInt(0, 9) < 7) {
                n = r.nextInt(1, 10);
            }
            List<Double> l = new ArrayList<Double>();
            for (int i = 0; i < n; i++) {
                l.add((double) r.nextInt(-50, 50));
            }
            return l;
        }
    }

    @Fuzz
    public void testBugReproduction(@From(FitnessListGen.class) List<Double> fits,
                         @InRange(minInt = 0, maxInt = 6) int slack,
                         @InRange(minInt = 1, maxInt = 12) int nextCalls) {
        boolean validIf = fits != null && slack >= 0 && nextCalls >= 1 && fits.size() + slack >= 1;
        boolean buggyIf = fits != null && fits.size() >= 1 && nextCalls <= fits.size();

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        List<Chromosome> chroms = new ArrayList<Chromosome>();
        for (Double d : fits) { chroms.add(new FixedChromosome(d)); }
        ListPopulation pop = new SimplePopulation(chroms, fits.size() + slack);
        int before = pop.getPopulationSize();

        Iterator<Chromosome> it = pop.iterator();
        for (int i = 0; i < nextCalls; i++) { it.next(); }
        String exc = "none";
        try {
            it.remove();
        } catch (Exception e) {
            exc = e.getClass().getName();
        }
        final String excF = exc;
        int after = pop.getPopulationSize();

        Log.logOutIf(true, () -> new Object[]{ excF, before, after, pop.getChromosomes().toString() });
        assertEquals("java.lang.UnsupportedOperationException", excF);
        assertEquals(before, after);
    }

    @Fuzz
    public void testIterator(@From(FitnessListGen.class) List<Double> fits,
                         @InRange(minInt = 0, maxInt = 6) int slack,
                         @InRange(minInt = 1, maxInt = 12) int nextCalls) {
        boolean validIf = fits != null && slack >= 0 && nextCalls >= 1 && fits.size() + slack >= 1;
        boolean buggyIf = fits != null && fits.size() >= 1 && nextCalls <= fits.size();
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            List<Chromosome> chroms = new ArrayList<Chromosome>();
            for (Double d : fits) { chroms.add(new FixedChromosome(d)); }
            ListPopulation pop = new SimplePopulation(chroms, fits.size() + slack);
            int before = pop.getPopulationSize();

            Iterator<Chromosome> it = pop.iterator();
            for (int i = 0; i < nextCalls; i++) { it.next(); }
            String exc = "none";
            try {
                it.remove();
            } catch (Exception e) {
                exc = e.getClass().getName();
            }
            final String excF = exc;
            int after = pop.getPopulationSize();

            Log.logOutIf(preserveIf, () -> new Object[]{ excF, before, after, pop.getChromosomes().toString() });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
