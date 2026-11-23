package org.apache.commons.math3.geometry.euclidean.threed;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.commons.math3.geometry.euclidean.twod.Euclidean2D;
import org.apache.commons.math3.geometry.euclidean.twod.Line;
import org.apache.commons.math3.geometry.euclidean.twod.PolygonsSet;
import org.apache.commons.math3.geometry.euclidean.twod.SubLine;
import org.apache.commons.math3.geometry.euclidean.twod.Vector2D;
import org.apache.commons.math3.geometry.partitioning.BSPTree;
import org.apache.commons.math3.geometry.partitioning.Region;
import org.apache.commons.math3.geometry.partitioning.SubHyperplane;

@RunWith(JQF.class)
public class JQF_PolyhedronsSetTest {

    private static Plane outwardPlane(Vector3D a, Vector3D b, Vector3D c, Vector3D center) {
        Vector3D n = b.subtract(a).crossProduct(c.subtract(a));
        if (n.dotProduct(a.subtract(center)) < 0) {
            return new Plane(a, c, b);
        }
        return new Plane(a, b, c);
    }

    @Fuzz
    public void testBugReproduction(@InRange(minInt = -5, maxInt = 6) int x0,
                                    @InRange(minInt = -5, maxInt = 6) int y0,
                                    @InRange(minInt = -5, maxInt = 6) int z0,
                                    @InRange(minInt = 1, maxInt = 6) int dx,
                                    @InRange(minInt = 1, maxInt = 6) int dy,
                                    @InRange(minInt = 1, maxInt = 6) int dz,
                                    @InRange(minInt = 0, maxInt = 4) int perturbSteps,
                                    boolean addEmptyFacet,
                                    @InRange(minInt = 0, maxInt = 6) int emptyFace,
                                    @InRange(minInt = -3, maxInt = 4) int ex,
                                    @InRange(minInt = -3, maxInt = 4) int ey,
                                    @InRange(minInt = -2, maxInt = 3) int edirY) {
        boolean validIf = dx >= 1 && dy >= 1 && dz >= 1 && emptyFace >= 0 && emptyFace < 6;
        // a boundary facet whose 2D region has a cut but no boundary loop (empty area)
        // makes PolygonsSet.getSize()/computeGeometricalProperties fail
        boolean buggyIf = addEmptyFacet && perturbSteps == 0;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        double[][] pts = new double[8][];
        for (int i = 0; i < 8; i++) {
            double x = x0 + (((i & 1) != 0) ? dx : 0);
            double y = y0 + (((i & 2) != 0) ? dy : 0);
            double z = z0 + (((i & 4) != 0) ? dz : 0);
            pts[i] = new double[] { x, y, z };
        }
        pts[7][2] += 0.1 * perturbSteps;
        Vector3D center = new Vector3D(x0 + dx / 2.0, y0 + dy / 2.0, z0 + dz / 2.0);

        int[][] quads = {
            {0, 2, 6, 4}, {1, 3, 7, 5}, {0, 1, 5, 4},
            {2, 3, 7, 6}, {0, 1, 3, 2}, {4, 5, 7, 6}
        };

        String exception = "none";
        double size = Double.NaN;
        double boundarySize = Double.NaN;
        String location = "none";
        int facets = 0;
        try {
            List<SubHyperplane<Euclidean3D>> boundary = new ArrayList<SubHyperplane<Euclidean3D>>();
            for (int[] q : quads) {
                int[][] tris = { {q[0], q[1], q[2]}, {q[0], q[2], q[3]} };
                for (int[] t : tris) {
                    Vector3D a = new Vector3D(pts[t[0]][0], pts[t[0]][1], pts[t[0]][2]);
                    Vector3D b = new Vector3D(pts[t[1]][0], pts[t[1]][1], pts[t[1]][2]);
                    Vector3D c = new Vector3D(pts[t[2]][0], pts[t[2]][1], pts[t[2]][2]);
                    Plane plane = outwardPlane(a, b, c, center);
                    Vector2D pa = plane.toSubSpace(a);
                    Vector2D pb = plane.toSubSpace(b);
                    Vector2D pc = plane.toSubSpace(c);
                    Collection<SubHyperplane<Euclidean2D>> edges = new ArrayList<SubHyperplane<Euclidean2D>>();
                    edges.add(new SubLine(pa, pb));
                    edges.add(new SubLine(pb, pc));
                    edges.add(new SubLine(pc, pa));
                    Region<Euclidean2D> poly = new PolygonsSet(edges);
                    boundary.add(new SubPlane(plane, poly));
                    facets++;
                }
            }
            if (addEmptyFacet) {
                int[] q = quads[emptyFace];
                Vector3D a = new Vector3D(pts[q[0]][0], pts[q[0]][1], pts[q[0]][2]);
                Vector3D b = new Vector3D(pts[q[1]][0], pts[q[1]][1], pts[q[1]][2]);
                Vector3D c = new Vector3D(pts[q[2]][0], pts[q[2]][1], pts[q[2]][2]);
                Plane plane = outwardPlane(a, b, c, center);
                Line line = new Line(new Vector2D(ex, ey), new Vector2D(ex + 1, ey + edirY));
                BSPTree<Euclidean2D> emptyTree = new BSPTree<Euclidean2D>(
                    line.wholeHyperplane(),
                    new BSPTree<Euclidean2D>(Boolean.FALSE),
                    new BSPTree<Euclidean2D>(Boolean.FALSE),
                    null);
                boundary.add(new SubPlane(plane, new PolygonsSet(emptyTree)));
                facets++;
            }
            PolyhedronsSet set = new PolyhedronsSet(boundary);
            size = set.getSize();
            boundarySize = set.getBoundarySize();
            location = set.checkPoint(center).toString();
        } catch (Throwable e) {
            exception = e.getClass().getName();
        }

        final String fException = exception;
        final double fSize = size;
        final double fBoundary = boundarySize;
        final String fLocation = location;
        final int fFacets = facets;
        Log.logOutIf(true, () -> new Object[]{ fException, fSize, fBoundary, fLocation, fFacets });

        double expectedVolume = (double) dx * dy * dz;
        double expectedArea = 2.0 * ((double) dx * dy + (double) dy * dz + (double) dx * dz);
        assertEquals("none", exception);
        assertEquals(expectedVolume, size, 1.0e-6 * expectedVolume);
        assertEquals(expectedArea, boundarySize, 1.0e-6 * expectedArea);
        assertEquals("INSIDE", location);
    }
}
