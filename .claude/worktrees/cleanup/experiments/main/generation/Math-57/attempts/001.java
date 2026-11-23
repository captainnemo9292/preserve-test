package org.apache.commons.math.stat.clustering;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;

@RunWith(JQF.class)
public class JQF_KMeansPlusPlusClustererTest {

    /** 1-D point with a double coordinate. */
    public static class P implements Clusterable<P> {
        final double x;
        P(double x) { this.x = x; }
        public double distanceFrom(P p) { return Math.abs(x - p.x); }
        public P centroidOf(Collection<P> c) {
            double s = 0;
            for (P p : c) { s += p.x; }
            return new P(c.isEmpty() ? 0 : s / c.size());
        }
        @Override public String toString() { return Double.toString(x); }
    }

    /** Random with scripted values: first center = index 0, nextDouble = u. */
    public static class ScriptedRandom extends Random {
        private final double u;
        ScriptedRandom(double u) { this.u = u; }
        @Override public int nextInt(int bound) { return 0; }
        @Override public double nextDouble() { return u; }
    }

    public static class XsGen extends Generator<double[]> {
        public XsGen() { super(double[].class); }
        @Override public double[] generate(SourceOfRandomness r, GenerationStatus status) {
            int n = r.nextInt(2, 9);
            double[] xs = new double[n];
            boolean compact = r.nextBoolean();
            double base = r.nextDouble(-5, 5);
            for (int i = 0; i < n; i++) {
                if (compact) {
                    xs[i] = base + r.nextDouble(0, 0.99);
                } else {
                    xs[i] = r.nextDouble(-5, 5);
                }
            }
            return xs;
        }
    }

    /** Index (in the list of remaining points, first point removed) chosen for 2nd center. */
    private static int chosenIndex(double[] xs, double u, boolean buggyInt) {
        int m = xs.length - 1;
        double[] dx2 = new double[m];
        double dsum = 0;
        int isum = 0;
        for (int i = 0; i < m; i++) {
            double d = Math.abs(xs[i + 1] - xs[0]);
            if (buggyInt) {
                isum += d * d;
                dx2[i] = isum;
            } else {
                dsum += d * d;
                dx2[i] = dsum;
            }
        }
        double r = u * (buggyInt ? (double) isum : dsum);
        for (int i = 0; i < m; i++) {
            if (dx2[i] >= r) { return i; }
        }
        return -1;
    }

    @Fuzz
    public void testBugReproduction(@From(XsGen.class) double[] xs,
                         @InRange(minDouble = 0, maxDouble = 1) double u) {
        boolean validIf = xs != null && xs.length >= 2;
        boolean buggyIf = validIf
                && chosenIndex(xs, u, true) != chosenIndex(xs, u, false);

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        List<P> pts = new ArrayList<P>();
        for (double x : xs) { pts.add(new P(x)); }

        KMeansPlusPlusClusterer<P> clusterer = new KMeansPlusPlusClusterer<P>(new ScriptedRandom(u));
        List<Cluster<P>> clusters = clusterer.cluster(pts, 2, 0);

        final String centers = clusters.get(0).getCenter() + "," + clusters.get(1).getCenter();
        final int size0 = clusters.get(0).getPoints().size();
        final int size1 = clusters.get(1).getPoints().size();
        Log.logOutIf(true, () -> new Object[]{ clusters.size(), centers, size0, size1 });

        int expectedIdx = chosenIndex(xs, u, false);
        P expectedCenter = pts.get(expectedIdx + 1);
        assertSame("second initial center should be chosen with probability proportional to true D^2",
                expectedCenter, clusters.get(1).getCenter());
    }
}
