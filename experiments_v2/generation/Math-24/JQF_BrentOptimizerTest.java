package org.apache.commons.math3.optimization.univariate;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
import org.apache.commons.math3.analysis.UnivariateFunction;
import org.apache.commons.math3.optimization.GoalType;
import org.apache.commons.math3.util.FastMath;
import org.apache.commons.math3.util.Precision;

@RunWith(JQF.class)
public class JQF_BrentOptimizerTest {

    private static final double GOLDEN_SECTION = 0.5 * (3 - FastMath.sqrt(5));

    /**
     * Independent re-statement of Brent's iteration (no convergence checker) for the
     * internal minimisation of g(x) = (x - c)^2. Returns true when the last point it
     * evaluates is strictly worse than the best point evaluated so far, i.e. when the
     * "return the last evaluated point" behaviour yields a sub-optimal result.
     */
    static boolean lastEvaluationWorseThanBest(double c, double lo, double hi, double start,
                                               double rel, double abs) {
        double a = lo < hi ? lo : hi;
        double b = lo < hi ? hi : lo;
        double x = start;
        double v = x;
        double w = x;
        double d = 0;
        double e = 0;
        double fx = (x - c) * (x - c);
        double fv = fx;
        double fw = fx;
        double best = fx;
        double last = fx;
        int evals = 1;
        while (evals < 5000) {
            final double m = 0.5 * (a + b);
            final double tol1 = rel * FastMath.abs(x) + abs;
            final double tol2 = 2 * tol1;
            final boolean stop = FastMath.abs(x - m) <= tol2 - 0.5 * (b - a);
            if (stop) {
                return last > best;
            }
            double p = 0;
            double q = 0;
            double r = 0;
            double u = 0;
            if (FastMath.abs(e) > tol1) {
                r = (x - w) * (fx - fv);
                q = (x - v) * (fx - fw);
                p = (x - v) * q - (x - w) * r;
                q = 2 * (q - r);
                if (q > 0) {
                    p = -p;
                } else {
                    q = -q;
                }
                r = e;
                e = d;
                if (p > q * (a - x) && p < q * (b - x) && FastMath.abs(p) < FastMath.abs(0.5 * q * r)) {
                    d = p / q;
                    u = x + d;
                    if (u - a < tol2 || b - u < tol2) {
                        d = (x <= m) ? tol1 : -tol1;
                    }
                } else {
                    e = (x < m) ? b - x : a - x;
                    d = GOLDEN_SECTION * e;
                }
            } else {
                e = (x < m) ? b - x : a - x;
                d = GOLDEN_SECTION * e;
            }
            if (FastMath.abs(d) < tol1) {
                u = (d >= 0) ? x + tol1 : x - tol1;
            } else {
                u = x + d;
            }
            double fu = (u - c) * (u - c);
            evals++;
            last = fu;
            if (fu < best) {
                best = fu;
            }
            if (fu <= fx) {
                if (u < x) {
                    b = x;
                } else {
                    a = x;
                }
                v = w;
                fv = fw;
                w = x;
                fw = fx;
                x = u;
                fx = fu;
            } else {
                if (u < x) {
                    a = u;
                } else {
                    b = u;
                }
                if (fu <= fw || Precision.equals(w, x)) {
                    v = w;
                    fv = fw;
                    w = u;
                    fw = fu;
                } else if (fu <= fv || Precision.equals(v, x) || Precision.equals(v, w)) {
                    v = u;
                    fv = fu;
                }
            }
        }
        return false;
    }

    @Fuzz
    public void testBugReproduction(@InRange(minDouble = -50, maxDouble = 50) double c,
                                    @InRange(minDouble = -50, maxDouble = 50) double lo,
                                    @InRange(minDouble = -50, maxDouble = 50) double hi,
                                    @InRange(minDouble = -50, maxDouble = 50) double start,
                                    @InRange(minDouble = 4.5e-16, maxDouble = 1e-3) double rel,
                                    @InRange(minDouble = 1e-8, maxDouble = 1e-2) double abs,
                                    boolean minimize) {
        boolean validIf = lo < hi && lo <= start && start <= hi && rel >= 4.5e-16 && abs > 0;
        boolean buggyIf = lo < hi && lo <= start && start <= hi && rel >= 4.5e-16 && abs > 0
                && lastEvaluationWorseThanBest(c, lo, hi, start, rel, abs);

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        final boolean isMin = minimize;
        final double center = c;
        final double[] bestSeen = new double[] { Double.NaN };
        final UnivariateFunction f = new UnivariateFunction() {
            public double value(double x) {
                double v = (x - center) * (x - center);
                if (!isMin) {
                    v = -v;
                }
                if (Double.isNaN(bestSeen[0]) || (isMin ? v < bestSeen[0] : v > bestSeen[0])) {
                    bestSeen[0] = v;
                }
                return v;
            }
        };

        final BrentOptimizer optimizer = new BrentOptimizer(rel, abs);
        final UnivariatePointValuePair result = optimizer.optimize(
                10000, f, minimize ? GoalType.MINIMIZE : GoalType.MAXIMIZE, lo, hi, start);
        final double sol = result.getPoint();
        final double val = result.getValue();
        final double best = bestSeen[0];

        Log.logOutIf(true, () -> new Double[]{ sol, val, best, (double) optimizer.getEvaluations() });
        final boolean reportsBest = isMin ? val <= best : val >= best;
        assertTrue("Returned value " + val + " is worse than best evaluated value " + best,
                   reportsBest);
    }

    @Fuzz
    public void testMath855(@InRange(minDouble = -50, maxDouble = 50) double c,
                                    @InRange(minDouble = -50, maxDouble = 50) double lo,
                                    @InRange(minDouble = -50, maxDouble = 50) double hi,
                                    @InRange(minDouble = -50, maxDouble = 50) double start,
                                    @InRange(minDouble = 4.5e-16, maxDouble = 1e-3) double rel,
                                    @InRange(minDouble = 1e-8, maxDouble = 1e-2) double abs,
                                    boolean minimize) {
        boolean validIf = lo < hi && lo <= start && start <= hi && rel >= 4.5e-16 && abs > 0;
        boolean buggyIf = lo < hi && lo <= start && start <= hi && rel >= 4.5e-16 && abs > 0
                && lastEvaluationWorseThanBest(c, lo, hi, start, rel, abs);
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            final boolean isMin = minimize;
            final double center = c;
            final double[] bestSeen = new double[] { Double.NaN };
            final UnivariateFunction f = new UnivariateFunction() {
                public double value(double x) {
                    double v = (x - center) * (x - center);
                    if (!isMin) {
                        v = -v;
                    }
                    if (Double.isNaN(bestSeen[0]) || (isMin ? v < bestSeen[0] : v > bestSeen[0])) {
                        bestSeen[0] = v;
                    }
                    return v;
                }
            };

            final BrentOptimizer optimizer = new BrentOptimizer(rel, abs);
            final UnivariatePointValuePair result = optimizer.optimize(
                    10000, f, minimize ? GoalType.MINIMIZE : GoalType.MAXIMIZE, lo, hi, start);
            final double sol = result.getPoint();
            final double val = result.getValue();
            final double best = bestSeen[0];

            Log.logOutIf(preserveIf, () -> new Double[]{ sol, val, best, (double) optimizer.getEvaluations(),
                    (double) Double.doubleToLongBits(sol), (double) Double.doubleToLongBits(val),
                    (double) Double.doubleToLongBits(best), val - best, isMin ? 1.0 : 0.0,
                    (isMin ? val <= best : val >= best) ? 1.0 : 0.0 });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
