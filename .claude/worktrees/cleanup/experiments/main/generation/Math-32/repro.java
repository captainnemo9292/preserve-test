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
import org.apache.commons.math3.geometry.partitioning.BSPTree;
import org.apache.commons.math3.geometry.partitioning.BSPTreeVisitor;
import org.apache.commons.math3.geometry.partitioning.BoundaryAttribute;
import org.apache.commons.math3.geometry.partitioning.Region;
import org.apache.commons.math3.geometry.partitioning.SubHyperplane;

@RunWith(JQF.class)
public class JQF_PolyhedronsSetTest {

    private static final float[] V4Z = { -1.0f, -0.999999f, -0.99999f, -0.9999f };
    private static final float[] V5X = {  1.0f,  0.999999f,  0.99999f,  0.9999f };

    @Fuzz
    public void testBugReproduction(@InRange(minInt = 0, maxInt = 4) int pertA,
                                    @InRange(minInt = 0, maxInt = 4) int pertB,
                                    @InRange(minInt = -3, maxInt = 4) int scaleExp) {
        boolean validIf = pertA >= 0 && pertA < 4 && pertB >= 0 && pertB < 4
                          && scaleExp >= -3 && scaleExp <= 3;
        // a cube [-1,1]^3 (scaled by an exact power of two) in which both vertex 4 (z)
        // and vertex 5 (x) are slightly moved off the exact cube positions
        boolean buggyIf = pertA >= 1 && pertB >= 1;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        final double scale = Math.pow(2.0, scaleExp);
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
        int extracted = -1;
        try {
            List<SubHyperplane<Euclidean3D>> boundary = new ArrayList<SubHyperplane<Euclidean3D>>();
            for (int idx = 0; idx < indices.length; idx += 3) {
                Vector3D[] vertices = new Vector3D[3];
                for (int k = 0; k < 3; k++) {
                    int i = indices[idx + k] * 3;
                    vertices[k] = new Vector3D(coords[i] * scale, coords[i + 1] * scale, coords[i + 2] * scale);
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

            // extract the boundary faces from the BSP tree
            final int[] count = new int[1];
            set.getTree(true).visit(new BSPTreeVisitor<Euclidean3D>() {
                public Order visitOrder(BSPTree<Euclidean3D> node) {
                    return Order.MINUS_SUB_PLUS;
                }
                @SuppressWarnings("unchecked")
                public void visitInternalNode(BSPTree<Euclidean3D> node) {
                    BoundaryAttribute<Euclidean3D> attr = (BoundaryAttribute<Euclidean3D>) node.getAttribute();
                    if (attr.getPlusOutside() != null) {
                        count[0]++;
                    }
                    if (attr.getPlusInside() != null) {
                        count[0]++;
                    }
                }
                public void visitLeafNode(BSPTree<Euclidean3D> node) {
                }
            });
            extracted = count[0];

            size = set.getSize();
            boundarySize = set.getBoundarySize();
        } catch (Throwable e) {
            exception = e.getClass().getName();
        }

        final String fException = exception;
        final double fSize = size;
        final double fBoundary = boundarySize;
        final int fFacets = facets;
        final int fExtracted = extracted;
        Log.logOutIf(true, () -> new Object[]{ fException, fSize, fBoundary, fFacets, fExtracted });

        double expectedSize = 8.0 * scale * scale * scale;
        double expectedBoundary = 24.0 * scale * scale;
        assertEquals("none", exception);
        assertEquals(expectedSize, size, 1.0e-3 * scale * scale * scale);
        assertEquals(expectedBoundary, boundarySize, 1.0e-3 * scale * scale);
    }
}
