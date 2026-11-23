package org.apache.commons.math3.geometry.euclidean.threed;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

@RunWith(JQF.class)
public class JQF_SubLineTest {

    @Fuzz
    public void testBugReproduction(
            @InRange(minInt = -10, maxInt = 11) int p1x,
            @InRange(minInt = -10, maxInt = 11) int p1y,
            @InRange(minInt = -10, maxInt = 11) int p1z,
            @InRange(minInt = -10, maxInt = 11) int p2x,
            @InRange(minInt = -10, maxInt = 11) int p2y,
            @InRange(minInt = -10, maxInt = 11) int p2z,
            @InRange(minInt = -10, maxInt = 11) int q1x,
            @InRange(minInt = -10, maxInt = 11) int q1y,
            @InRange(minInt = -10, maxInt = 11) int q1z,
            @InRange(minInt = -10, maxInt = 11) int q2x,
            @InRange(minInt = -10, maxInt = 11) int q2y,
            @InRange(minInt = -10, maxInt = 11) int q2z,
            boolean includeEndPoints) {

        boolean validIf = !(p1x == p2x && p1y == p2y && p1z == p2z)
                && !(q1x == q2x && q1y == q2y && q1z == q2z);

        // direction vectors and cross product (exact integer arithmetic)
        long d1x = p2x - p1x, d1y = p2y - p1y, d1z = p2z - p1z;
        long d2x = q2x - q1x, d2y = q2y - q1y, d2z = q2z - q1z;
        long cx = d1y * d2z - d1z * d2y;
        long cy = d1z * d2x - d1x * d2z;
        long cz = d1x * d2y - d1y * d2x;
        long wx = q1x - p1x, wy = q1y - p1y, wz = q1z - p1z;
        boolean parallel = (cx == 0 && cy == 0 && cz == 0);
        // collinear when w x d1 == 0
        long ex = wy * d1z - wz * d1y;
        long ey = wz * d1x - wx * d1z;
        long ez = wx * d1y - wy * d1x;
        boolean collinear = (ex == 0 && ey == 0 && ez == 0);
        boolean linesIntersect = parallel ? collinear : (wx * cx + wy * cy + wz * cz == 0);
        boolean buggyIf = !linesIntersect;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        String exception = "none";
        Vector3D result = null;
        try {
            SubLine s1 = new SubLine(new Vector3D(p1x, p1y, p1z), new Vector3D(p2x, p2y, p2z));
            SubLine s2 = new SubLine(new Vector3D(q1x, q1y, q1z), new Vector3D(q2x, q2y, q2z));
            result = s1.intersection(s2, includeEndPoints);
        } catch (Throwable t) {
            exception = t.getClass().getName();
        }

        final String exc = exception;
        final Vector3D res = result;
        Log.logOutIf(true, () -> new Object[]{ exc, String.valueOf(res) });
        assertEquals("none", exc);
        assertNull(res);
    }
}
