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

    @Fuzz
    public void testBugReproduction(@InRange(minInt = -2, maxInt = 3) int tx,
                                    @InRange(minInt = -2, maxInt = 3) int ty,
                                    @InRange(minInt = -2, maxInt = 3) int tz,
                                    @InRange(minInt = 0, maxInt = 4) int perturbSteps) {
        boolean validIf = perturbSteps >= 0;
        // the exact cube [1,3]x[2,4]x[3,5] from the report (8 vertices, 12 triangles)
        boolean buggyIf = tx == 0 && ty == 0 && tz == 0 && perturbSteps == 0;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        double[] coords = {
            1, 2, 3,  1, 2, 5,  1, 4, 3,  1, 4, 5,
            3, 2, 3,  3, 2, 5,  3, 4, 3,  3, 4, 5
        };
        for (int i = 0; i < 8; i++) {
            coords[3 * i]     += tx;
            coords[3 * i + 1] += ty;
            coords[3 * i + 2] += tz;
        }
        coords[3 * 7 + 2] += 0.1 * perturbSteps;
        int[] indices = {
            0, 1, 2,  2, 1, 3,  4, 6, 5,  5, 6, 7,
            0, 4, 1,  1, 4, 5,  2, 3, 6,  6, 3, 7,
            0, 2, 4,  4, 2, 6,  1, 5, 3,  3, 5, 7
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
                List<SubHyperplane<Euclidean2D>> lines = new ArrayList<SubHyperplane<Euclidean2D>>();
                Vector2D[] proj = new Vector2D[3];
                for (int k = 0; k < 3; k++) {
                    proj[k] = polyPlane.toSubSpace(vertices[k]);
                }
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
        assertEquals(8.0, size, 3.0e-15);
        assertEquals(24.0, boundarySize, 5.0e-15);
    }
}
