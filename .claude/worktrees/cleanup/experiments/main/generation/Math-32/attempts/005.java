package org.apache.commons.math3.geometry.euclidean.threed;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.math3.geometry.euclidean.twod.Euclidean2D;
import org.apache.commons.math3.geometry.euclidean.twod.PolygonsSet;
import org.apache.commons.math3.geometry.euclidean.twod.SubLine;
import org.apache.commons.math3.geometry.euclidean.twod.Vector2D;
import org.apache.commons.math3.geometry.partitioning.Region;
import org.apache.commons.math3.geometry.partitioning.SubHyperplane;

@RunWith(JQF.class)
public class JQF_PolyhedronsSetTest {

    private static final float[] V4Z = { -1.0f, -0.999999f, -0.99999f, -0.9999f };
    private static final float[] V5X = {  1.0f,  0.999999f,  0.99999f,  0.9999f };

    @Fuzz
    public void testBugReproduction(@InRange(minInt = 0, maxInt = 4) int pertA,
                                    @InRange(minInt = 0, maxInt = 4) int pertB) {
        boolean validIf = pertA >= 0 && pertA < 4 && pertB >= 0 && pertB < 4;
        // cube [-1,1]^3 where vertex 4 z = -0.999999 and vertex 5 x = 0.999999 (as in the report)
        boolean buggyIf = pertA == 1 && pertB == 1;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        float[] coords = {
             1.0f, -1.0f, -1.0f,
             1.0f, -1.0f,  1.0f,
            -1.0f, -1.0f,  1.0f,
            -1.0f, -1.0f, -1.0f,
             1.0f,  1.0f, V4Z[pertA],
            V5X[pertB], 1.0f,  1.0f,
            -1.0f,  1.0f,  1.0f,
            -1.0f,  1.0f, -1.0f
        };
        int[] indices = {
            0, 1, 2,  2, 3, 0,  4, 7, 6,  6, 5, 4,
            0, 4, 5,  5, 1, 0,  1, 5, 6,  6, 2, 1,
            2, 6, 7,  7, 3, 2,  4, 0, 3,  3, 7, 4
        };

        String exception = "none";
        double size = Double.NaN;
        double boundarySize = Double.NaN;
        int facets = 0;
        try {
            List<SubHyperplane<Euclidean3D>> boundary = new ArrayList<SubHyperplane<Euclidean3D>>();
            for (int idx = 0; idx < indices.length; idx += 3) {
                Vector3D[] vertices = new Vector3D[3];
                for (int k = 0; k < 3; k++) {
                    int i = indices[idx + k] * 3;
                    vertices[k] = new Vector3D(coords[i], coords[i + 1], coords[i + 2]);
                }
                Plane polyPlane = new Plane(vertices[0], vertices[1], vertices[2]);
                Vector2D[] proj = new Vector2D[3];
                for (int k = 0; k < 3; k++) {
                    proj[k] = polyPlane.toSubSpace(vertices[k]);
                }
                List<SubHyperplane<Euclidean2D>> lines = new ArrayList<SubHyperplane<Euclidean2D>>();
                for (int k = 0; k < 3; k++) {
                    lines.add(new SubLine(proj[k], proj[(k + 1) % 3]));
                }
                Region<Euclidean2D> polyRegion = new PolygonsSet(lines);
                boundary.add(new SubPlane(polyPlane, polyRegion));
                facets++;
            }
            PolyhedronsSet set = new PolyhedronsSet(boundary);
            size = set.getSize();
            boundarySize = set.getBoundarySize();
        } catch (Throwable e) {
            exception = e.getClass().getName();
        }

        final String fException = exception;
        final double fSize = size;
        final double fBoundary = boundarySize;
        final int fFacets = facets;
        Log.logOutIf(true, () -> new Object[]{ fException, fSize, fBoundary, fFacets });

        assertEquals("none", exception);
        assertEquals(8.0, size, 3.0e-6);
        assertEquals(24.0, boundarySize, 5.0e-6);
    }
}
