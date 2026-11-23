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

    // exact integer test: do the infinite lines (p1,p2) and (q1,q2) share a point?
    static boolean linesIntersect(long p1x, long p1y, long p1z, long p2x, long p2y, long p2z,
                                  long q1x, long q1y, long q1z, long q2x, long q2y, long q2z) {
        long d1x = p2x - p1x, d1y = p2y - p1y, d1z = p2z - p1z;
        long d2x = q2x - q1x, d2y = q2y - q1y, d2z = q2z - q1z;
        long cx = d1y * d2z - d1z * d2y;
        long cy = d1z * d2x - d1x * d2z;
        long cz = d1x * d2y - d1y * d2x;
        long wx = q1x - p1x, wy = q1y - p1y, wz = q1z - p1z;
        if (cx == 0 && cy == 0 && cz == 0) {
            long ex = wy * d1z - wz * d1y;
            long ey = wz * d1x - wx * d1z;
            long ez = wx * d1y - wy * d1x;
            return ex == 0 && ey == 0 && ez == 0;
        }
        return wx * cx + wy * cy + wz * cz == 0;
    }

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
        boolean buggyIf = !linesIntersect(p1x, p1y, p1z, p2x, p2y, p2z, q1x, q1y, q1z, q2x, q2y, q2z);

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
