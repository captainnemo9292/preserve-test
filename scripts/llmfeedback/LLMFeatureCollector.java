package anonymous.llmfeedback;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collects per-iteration features for LLM-driven harness re-synthesis.
 * Not a Zest guidance signal — purely a separate feedback stream that
 * aggregates (args, outputs, exceptions, verdict events) across the run
 * and dumps a single JSON document for the Python prompt builder.
 *
 * Writes to {@code .poracle/llm_features.json} on JVM shutdown.
 * Output directory is picked from the system property
 * {@code llmfeedback.out} or defaults to the CWD.
 */
public class LLMFeatureCollector {

    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("llmfeedback.on", "true"));

    private static long iterations = 0;
    private static long validIterations = 0;
    private static long invalidIterations = 0;
    private static long identicalOutputs = 0;
    private static long differingOutputsNoVerdict = 0;
    private static long verdictR = 0;
    private static long firstValidElapsedNanos = -1;

    private static final Map<Integer, long[]> argRange = new ConcurrentHashMap<>();
    private static final Map<Integer, String> argTypes = new ConcurrentHashMap<>();
    private static final Map<Integer, long[]> argStringLen = new ConcurrentHashMap<>();
    private static final Set<String> uniqueArgTuples = ConcurrentHashMap.newKeySet();
    private static long outputDistanceSum = 0;
    private static long outputDistanceMax = 0;
    private static long outputDistanceMinPositive = Long.MAX_VALUE;
    private static long distBucket0 = 0;
    private static long distBucket1to10 = 0;
    private static long distBucket11to100 = 0;
    private static long distBucket101to1000 = 0;
    private static long distBucket1001plus = 0;

    // Universal signals — populate for any harness regardless of bug type.
    private static long firstValidIteration = -1;
    private static long currentInvalidStreak = 0;
    private static long maxInvalidStreak = 0;
    // Each new patch-side shape adds (iterationCount, shapeIndex) here — used
    // to compute the saturation point at flush. Bounded by SHAPE_CAP.
    private static final java.util.List<Long> shapeAddIterations =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    // ==== Extended signals (v4) ====
    private static long targetHitCount = 0;
    private static long firstTargetHitIter = -1;
    private static int branchCoverageOrg = -1;
    private static int branchCoverageDelta = -1;
    private static long wideningEventCount = 0;
    private static final Map<String, Long> saveQueueWhy = new ConcurrentHashMap<>();
    private static final Map<String, Long> divergentExceptionPairs = new ConcurrentHashMap<>();
    private static final Map<String, Long> outputShapeTokens = new ConcurrentHashMap<>();
    private static final Map<String, Long> argTupleCounter = new ConcurrentHashMap<>();
    private static final Map<String, Long> sampleOrgOutputs = new ConcurrentHashMap<>();
    private static final Map<String, Long> samplePatchOutputs = new ConcurrentHashMap<>();
    private static final Set<String> uniqueOrgShapes = ConcurrentHashMap.newKeySet();
    private static final Set<String> uniquePatchShapes = ConcurrentHashMap.newKeySet();
    private static final int SAMPLE_CAP = 30;
    private static final int SHAPE_CAP = 4096;
    private static final int TUPLE_CAP = 2048;

    private static final long startNanos = System.nanoTime();
    private static long lastFlushNanos = startNanos;

    static {
        if (ENABLED) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { flush(); } catch (Throwable t) { /* best effort */ }
            }, "LLMFeatureCollector-flush"));
        }
    }

    // Static entry points callable from any point in JQF/Poracle. All no-op
    // when the feature is disabled; never throw.
    public static void noteSaveQueueWhy(String why) {
        if (!ENABLED || why == null) return;
        // collapse whitespace/suffixes so counts group sensibly
        String key = why.replaceAll("\\s+", "").replaceAll("\\d+", "N");
        saveQueueWhy.merge(key, 1L, Long::sum);
    }
    public static void noteTargetHit() {
        if (!ENABLED) return;
        targetHitCount++;
        if (firstTargetHitIter < 0) firstTargetHitIter = iterations;
    }
    public static void noteWideningEvent() {
        if (!ENABLED) return;
        wideningEventCount++;
    }
    public static void noteBranchCoverage(int org, int patch) {
        // Called at flush time by whichever guidance has the data. "Delta"
        // here means patch-side coverage minus org-side coverage: a positive
        // delta implies the patch exposes branches the buggy version lacks.
        if (!ENABLED) return;
        branchCoverageOrg = org;
        branchCoverageDelta = patch - org;
    }
    public static void noteExceptionPair(String orgException, String patchException) {
        if (!ENABLED) return;
        if (orgException == null && patchException == null) return;
        if (java.util.Objects.equals(orgException, patchException)) return;
        String key = (orgException == null ? "<none>" : orgException)
                + " -> "
                + (patchException == null ? "<none>" : patchException);
        divergentExceptionPairs.merge(key, 1L, Long::sum);
    }

    public static synchronized void recordIteration(
            Object[] args,
            String orgOutput,
            String patchOutput,
            Throwable orgError,
            boolean diffOutputFound,
            boolean inputIgnored) {
        if (!ENABLED) return;
        iterations++;
        if (inputIgnored) {
            invalidIterations++;
            currentInvalidStreak++;
            if (currentInvalidStreak > maxInvalidStreak) maxInvalidStreak = currentInvalidStreak;
            return;
        }
        currentInvalidStreak = 0;
        validIterations++;
        if (firstValidElapsedNanos < 0) {
            firstValidElapsedNanos = System.nanoTime() - startNanos;
            firstValidIteration = iterations;
        }

        // (buggy, patched) output pair classification
        // null paths removed: Log.addOutput filters null before reaching
        // outputFor* fields, so both_null / one_null never fire in practice.
        // orgException / exceptionCounts also removed: FuzzStatement's `error`
        // only carries uncaught Throwables and preservation harnesses wrap
        // their own try/catch, so that signal never populates in practice.
        if (orgOutput != null && patchOutput != null) {
            if (orgOutput.equals(patchOutput)) identicalOutputs++;
            else if (diffOutputFound) verdictR++;
            else differingOutputsNoVerdict++;
            // Divergent exception-class detection — infer from Log output
            // text, which is what the harness actually recorded.
            String orgEx = extractExceptionClass(orgOutput);
            String patchEx = extractExceptionClass(patchOutput);
            if (orgEx != null || patchEx != null) {
                noteExceptionPair(orgEx, patchEx);
            }
        }

        // Shape diversity (uncapped count of distinct outputs — until SHAPE_CAP
        // reached, then we stop accumulating but keep the count on the set size).
        if (orgOutput != null && uniqueOrgShapes.size() < SHAPE_CAP) {
            uniqueOrgShapes.add(truncate(orgOutput, 200));
        }
        if (patchOutput != null && uniquePatchShapes.size() < SHAPE_CAP) {
            int before = uniquePatchShapes.size();
            if (uniquePatchShapes.add(truncate(patchOutput, 200))) {
                // New patch-side shape — record the iteration count at which
                // it first appeared, for the saturation curve.
                shapeAddIterations.add(iterations);
            }
        }

        // Read the per-iteration output distance Log already computes in
        // FuzzStatement.evaluateOrg (via Log.LogResult.outputDistance()).
        // Distance 0 → identical outputs; small positive → near miss; MAX
        // means one side was null. This complements the strict
        // identical/differing_no_verdict split with a *magnitude*: pattern_B
        // becomes visible even when Poracle's isDiffOutputFound() happens to
        // flag the input (so differingOutputsNoVerdict stays 0 but the
        // distance distribution shows a long tail toward small values).
        try {
            int d = anonymous.log.Log.LogResult.getRecentOutputDistance();
            if (d >= 0 && d < Integer.MAX_VALUE) {
                outputDistanceSum += d;
                if (d > outputDistanceMax) outputDistanceMax = d;
                if (d > 0 && d < outputDistanceMinPositive) outputDistanceMinPositive = d;
                if (d == 0) distBucket0++;
                else if (d <= 10) distBucket1to10++;
                else if (d <= 100) distBucket11to100++;
                else if (d <= 1000) distBucket101to1000++;
                else distBucket1001plus++;
            }
        } catch (Throwable ignore) { /* Log class optional */ }

        // arg-space coverage
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                Object a = args[i];
                // Record the type seen at each position — Math-105 et al.
                // use @From custom generators returning e.g. double[]/Object[],
                // and the numeric-only tracker above was blind to them.
                final String t;
                if (a == null) t = "null";
                else t = a.getClass().getSimpleName();
                argTypes.putIfAbsent(i, t);

                if (a instanceof Number) {
                    long v = ((Number) a).longValue();
                    argRange.compute(i, (k, cur) -> {
                        if (cur == null) return new long[]{v, v, 1L};
                        if (v < cur[0]) cur[0] = v;
                        if (v > cur[1]) cur[1] = v;
                        cur[2]++;
                        return cur;
                    });
                } else if (a instanceof Boolean) {
                    long v = ((Boolean) a) ? 1 : 0;
                    argRange.compute(i, (k, cur) -> {
                        if (cur == null) return new long[]{v, v, 1L};
                        if (v < cur[0]) cur[0] = v;
                        if (v > cur[1]) cur[1] = v;
                        cur[2]++;
                        return cur;
                    });
                } else if (a instanceof CharSequence) {
                    long v = ((CharSequence) a).length();
                    argStringLen.compute(i, (k, cur) -> {
                        if (cur == null) return new long[]{v, v, 1L};
                        if (v < cur[0]) cur[0] = v;
                        if (v > cur[1]) cur[1] = v;
                        cur[2]++;
                        return cur;
                    });
                }
            }
            String tk = tupleKey(args);
            if (uniqueArgTuples.size() < TUPLE_CAP) {
                uniqueArgTuples.add(tk);
            }
            // Top-N most-frequent tuples: track full counts but cap the map.
            if (argTupleCounter.size() < 512 || argTupleCounter.containsKey(tk)) {
                argTupleCounter.merge(tk, 1L, Long::sum);
            }
        }

        // Output-shape tokenization: reduce the sample output to a type
        // template so the LLM reads output *structure* not strings.
        // "none;42;3.14" -> "token;int;double"
        if (patchOutput != null && outputShapeTokens.size() < 64) {
            outputShapeTokens.merge(tokenizeShape(patchOutput), 1L, Long::sum);
        }

        // sample outputs (bounded)
        if (orgOutput != null && sampleOrgOutputs.size() < SAMPLE_CAP) {
            sampleOrgOutputs.merge(truncate(orgOutput, 200), 1L, Long::sum);
        }
        if (patchOutput != null && samplePatchOutputs.size() < SAMPLE_CAP) {
            samplePatchOutputs.merge(truncate(patchOutput, 200), 1L, Long::sum);
        }
    }

    private static String tupleKey(Object[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append("|");
            Object a = args[i];
            if (a == null) sb.append("null");
            else if (a instanceof Number || a instanceof Boolean) sb.append(a);
            else sb.append(a.getClass().getSimpleName()).append('@').append(System.identityHashCode(a) & 0xffff);
        }
        return sb.toString();
    }

    private static final java.util.regex.Pattern EXC_PATTERN =
            java.util.regex.Pattern.compile("\\b([A-Z][A-Za-z0-9]*(?:Exception|Error))\\b");
    private static String extractExceptionClass(String out) {
        if (out == null || out.length() == 0) return null;
        java.util.regex.Matcher m = EXC_PATTERN.matcher(out);
        return m.find() ? m.group(1) : null;
    }

    private static String tokenizeShape(String s) {
        if (s.length() > 400) s = s.substring(0, 400);
        StringBuilder out = new StringBuilder();
        String[] parts = s.split("[;,|\\s]+");
        for (int i = 0; i < Math.min(parts.length, 16); i++) {
            String p = parts[i].trim();
            if (i > 0) out.append(';');
            if (p.isEmpty() || "null".equals(p) || "none".equals(p)) {
                out.append("none");
            } else if (p.matches("-?\\d+")) {
                out.append("int");
            } else if (p.matches("-?\\d*\\.\\d+(E-?\\d+)?") || "NaN".equals(p)) {
                out.append("double");
            } else if (p.matches("(true|false)")) {
                out.append("bool");
            } else if (p.endsWith("Exception") || p.contains("Exception:")) {
                out.append("exception");
            } else {
                out.append("str");
            }
        }
        return out.toString();
    }

    private static String truncate(String s, int n) {
        if (s.length() <= n) return s;
        return s.substring(0, n) + "...";
    }

    public static synchronized void flush() throws IOException {
        if (!ENABLED) return;
        String dir = System.getProperty("llmfeedback.out", ".poracle");
        File out = new File(dir);
        if (!out.exists() && !out.mkdirs()) {
            dir = ".";
            out = new File(dir);
        }
        File f = new File(out, "llm_features.json");

        double elapsedSec = (System.nanoTime() - startNanos) / 1e9;
        String failureMode = classifyFailure();

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        kv(sb, "schema", "llm_features/v4", true);
        // elapsed_seconds removed — throughput_iter_per_sec below already
        // encodes wall-time + iterations in a decision-useful way; raw
        // elapsed is redundant and seldom informs re-synthesis.
        // invalid_iterations removed — derivable as iterations - valid_iterations.
        // Kept the Java-side counter internally for classifier use, but no
        // longer emitted; the JSON consumer can compute it if needed.
        kv(sb, "iterations", iterations);
        kv(sb, "valid_iterations", validIterations);
        kv(sb, "precondition_fail_rate",
                iterations == 0 ? 0.0 : (double) invalidIterations / (double) iterations);
        sb.append("  \"output_pair_stats\": {\n");
        sb.append("    \"identical\": ").append(identicalOutputs).append(",\n");
        sb.append("    \"differing_no_verdict\": ").append(differingOutputsNoVerdict).append(",\n");
        sb.append("    \"verdict_r\": ").append(verdictR).append("\n");
        sb.append("  },\n");

        // Shape-diversity signals: direct measure of how many distinct output
        // strings each side produced. Essential for pattern_A vs pattern_B:
        // tiny patch-side diversity + 0 verdict_r = pattern_A; large diversity
        // + 0 verdict_r = pattern_B (near-miss region).
        int shapeOrg = uniqueOrgShapes.size();
        int shapePatch = uniquePatchShapes.size();
        int shapeDelta;
        {
            Set<String> sym = new java.util.HashSet<>(uniqueOrgShapes);
            java.util.Set<String> inter = new java.util.HashSet<>(uniqueOrgShapes);
            inter.retainAll(uniquePatchShapes);
            sym.addAll(uniquePatchShapes);
            sym.removeAll(inter);
            shapeDelta = sym.size();
        }
        kv(sb, "unique_output_shapes_org", shapeOrg);
        kv(sb, "unique_output_shapes_patch", shapePatch);
        kv(sb, "org_patch_shape_delta", shapeDelta);
        double timeToFirstValidMs = firstValidElapsedNanos < 0
                ? -1.0 : firstValidElapsedNanos / 1e6;
        kv(sb, "time_to_first_valid_ms", timeToFirstValidMs);

        // ==== Universal pipeline signals ====
        // Every harness produces these regardless of bug type. Useful across
        // the whole re-synthesis pipeline, not bound to one failure pattern.

        // 1. Raw throughput: iterations per real-time second.
        //    Low throughput → harness body is heavy (expensive setUp,
        //    object construction, large arrays); LLM should simplify.
        double iterPerSec = elapsedSec > 0 ? iterations / elapsedSec : 0.0;
        kv(sb, "throughput_iter_per_sec", iterPerSec);

        // 2. First valid iteration (index, not time). Combined with
        //    time_to_first_valid_ms this isolates "slow precondition" from
        //    "slow single iteration".
        kv(sb, "first_valid_iteration", firstValidIteration);

        // 3. Worst-case precondition starvation run — "precondition_fail_rate"
        //    averages across the run; maxInvalidStreak surfaces a mid-run
        //    cliff where Zest got stuck exploring invalid territory.
        kv(sb, "max_invalid_streak", maxInvalidStreak);

        // 4. Shape-saturation point: iteration at which 90% of the final
        //    unique patch-side shapes had been seen. As a % of total
        //    iterations → how much of the time budget was discovery vs.
        //    repetition. <30% means saturated early (more runtime won't
        //    help; LLM needs to change the harness). >80% means still
        //    discovering (give it more time or widen).
        long saturation90Iter = -1;
        double saturation90Pct = -1.0;
        synchronized (shapeAddIterations) {
            int n = shapeAddIterations.size();
            if (n > 0) {
                int idx = (int) Math.floor(0.9 * (n - 1));
                saturation90Iter = shapeAddIterations.get(idx);
                if (iterations > 0) {
                    saturation90Pct = 100.0 * saturation90Iter / iterations;
                }
            }
        }
        kv(sb, "saturation_90_iter", saturation90Iter);
        kv(sb, "saturation_90_pct", saturation90Pct);

        sb.append("  \"arg_distribution\": {\n");
        TreeMap<Integer, long[]> sortedArg = new TreeMap<>(argRange);
        int collapsedArgsCount = 0;
        for (Map.Entry<Integer, long[]> e : sortedArg.entrySet()) {
            long[] v = e.getValue();
            long span = v[1] - v[0] + 1;
            if (span == 1) collapsedArgsCount++;
            sb.append("    \"arg").append(e.getKey()).append("\": {")
              .append("\"min_seen\":").append(v[0])
              .append(",\"max_seen\":").append(v[1])
              .append(",\"span\":").append(span)
              .append(",\"samples\":").append(v[2]).append("},\n");
        }
        if (!sortedArg.isEmpty()) sb.setLength(sb.length() - 2);
        sb.append("\n  },\n");

        // Discriminates two pattern_A sub-cases: all-args-collapsed means Zest
        // bias narrowed every parameter to a single value (needs reset or
        // widened @InRange); 0 collapsed with high shape diversity means the
        // harness itself calls an API path that doesn't route through the patch
        // (needs API switch). Sits alongside unique_output_shapes_* to label
        // the sub-pattern.
        kv(sb, "collapsed_args_count", collapsedArgsCount);
        // Diversity ratio: how much the save queue actually accumulates unique
        // input tuples relative to iterations. Low means Zest is cycling on
        // a tiny handful of seeds — bias collapse or heavy deduplication.
        double tupleDiversity = validIterations == 0 ? 0.0
                : (double) uniqueArgTuples.size() / (double) validIterations;
        kv(sb, "arg_tuple_diversity", Math.min(1.0, tupleDiversity));

        sb.append("  \"arg_types\": {");
        boolean firstType = true;
        for (Map.Entry<Integer, String> e : new TreeMap<>(argTypes).entrySet()) {
            if (!firstType) sb.append(", ");
            sb.append("\"arg").append(e.getKey()).append("\": \"")
              .append(escape(e.getValue())).append("\"");
            firstType = false;
        }
        sb.append("},\n");

        if (!argStringLen.isEmpty()) {
            sb.append("  \"arg_string_lengths\": {\n");
            boolean firstSL = true;
            for (Map.Entry<Integer, long[]> e : new TreeMap<>(argStringLen).entrySet()) {
                long[] v = e.getValue();
                if (!firstSL) sb.append(",\n");
                sb.append("    \"arg").append(e.getKey()).append("\": {")
                  .append("\"min_len\":").append(v[0])
                  .append(",\"max_len\":").append(v[1])
                  .append(",\"samples\":").append(v[2]).append("}");
                firstSL = false;
            }
            sb.append("\n  },\n");
        }

        // shape_cap_reached removed — SHAPE_CAP is a known constant (4096),
        // so uniqueOutputShapes >= 4096 is directly comparable by the client.

        // Output-distance distribution: magnitude-aware near-miss view.
        // The LLM reads the histogram: lots of mass in distBucket1to10 while
        // verdict_r=0 means "the fuzzer landed close but never crossed into a
        // true counterexample" — widen @InRange around the observed hot region
        // or add enumerated edges.
        sb.append("  \"output_distance\": {\n");
        sb.append("    \"sum\": ").append(outputDistanceSum).append(",\n");
        sb.append("    \"max\": ").append(outputDistanceMax).append(",\n");
        sb.append("    \"min_positive\": ")
          .append(outputDistanceMinPositive == Long.MAX_VALUE ? -1 : outputDistanceMinPositive)
          .append(",\n");
        // "=0" bucket omitted — redundant with valid_iterations minus the
        // positive-distance totals. Keep only the positive buckets, which
        // are what signal near-miss density.
        sb.append("    \"histogram\": {\n");
        sb.append("      \"1-10\": ").append(distBucket1to10).append(",\n");
        sb.append("      \"11-100\": ").append(distBucket11to100).append(",\n");
        sb.append("      \"101-1000\": ").append(distBucket101to1000).append(",\n");
        sb.append("      \"1001+\": ").append(distBucket1001plus).append("\n");
        sb.append("    }\n");
        sb.append("  },\n");

        // unique_arg_tuples removed — arg_tuple_diversity (above) already
        // encodes the same information normalized by valid_iterations; the
        // raw count is redundant and seldom used directly.

        sb.append("  \"sample_org_outputs\": ");
        appendMap(sb, sampleOrgOutputs);
        sb.append(",\n");

        sb.append("  \"sample_patch_outputs\": ");
        appendMap(sb, samplePatchOutputs);
        sb.append(",\n");

        // ==== Extended signals (v4) ====
        // Did the generator ever call the patched method? 0 = catastrophic
        // miss (the test does not exercise the patch region at all); low =
        // test calls the method rarely; high = fine.
        kv(sb, "target_hit_count", targetHitCount);
        kv(sb, "first_target_hit_iteration", firstTargetHitIter);
        double targetHitRate = validIterations == 0 ? 0.0
                : (double) targetHitCount / (double) validIterations;
        kv(sb, "target_hit_rate", targetHitRate);

        // Branch coverage (via PoracleGuidance flushCoverage callback).
        // Patch_minus_org > 0 means fuzzer found branches only the patch has.
        kv(sb, "branch_coverage_org", branchCoverageOrg);
        kv(sb, "branch_coverage_patch_minus_org", branchCoverageDelta);

        // How many times Poracle's widening heuristic engaged — if 0 under
        // --cond-aware, bias RNG never narrowed, generator produced raw
        // @InRange values throughout.
        kv(sb, "widening_event_count", wideningEventCount);

        // Which save-queue reasons Zest actually used. Key tells the LLM
        // which guidance heuristic is firing: "+dist" (distance), "+outdiv"
        // (output divergence), or missing means no new input was saved.
        sb.append("  \"save_queue_why\": ");
        appendMap(sb, saveQueueWhy);
        sb.append(",\n");

        // (orgException -> patchException) pairs for the SAME input. Direct
        // bug signature — buggy throws NPE, patched throws IAE, etc.
        sb.append("  \"divergent_exception_pairs\": ");
        appendMap(sb, divergentExceptionPairs);
        sb.append(",\n");

        // Output-structure templates (sampled, top-N). "none;int;int" vs
        // "exception:NPE;none" tells the LLM what the test produces
        // without reading raw strings.
        sb.append("  \"output_shape_tokens\": ");
        appendMap(sb, outputShapeTokens);
        sb.append(",\n");

        // Top-5 most frequent arg tuples (count-descending).
        java.util.List<Map.Entry<String, Long>> top = new java.util.ArrayList<>(argTupleCounter.entrySet());
        top.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        sb.append("  \"top_arg_tuples\": {");
        for (int i = 0; i < Math.min(5, top.size()); i++) {
            Map.Entry<String, Long> e = top.get(i);
            if (i > 0) sb.append(", ");
            sb.append('"').append(escape(e.getKey())).append("\": ").append(e.getValue());
        }
        sb.append("},\n");

        kv(sb, "failure_mode", failureMode, true);

        // Boolean signal flags — orthogonal dimensions the LLM can read
        // independently. The single-string failure_mode above forces the
        // classifier to pick one; these flags describe every dimension that
        // is simultaneously true. E.g. Time-11 baseline is simultaneously
        // "queue_starvation=true" AND "singleton_output=true" — the primary
        // enum captures only the first, these flags carry the rest.
        boolean verdictReached = verdictR > 0;
        boolean queueStarvation = iterations > 0
                && (double) invalidIterations / iterations > 0.5;
        boolean singletonOutput = uniquePatchShapes.size() == 1
                && validIterations > 10;
        boolean biasCollapse = validIterations > 10
                && !argRange.isEmpty()
                && ((double) collapsedArgsCount / argRange.size()) >= 0.5;
        boolean wideButDead = uniquePatchShapes.size() > 100
                && uniquePatchShapes.size() == uniqueOrgShapes.size()
                && !verdictReached && !queueStarvation;
        // near_miss now ORs the strict-identity signal with the distance-based
        // one: any iteration landing in the 1-10 or 11-100 bucket while
        // verdict_r stays 0 is a near miss (fuzzer landed close but didn't
        // cross the trigger). The old gate rarely fired because
        // isDiffOutputFound() typically trips as soon as outputs differ, so
        // differingOutputsNoVerdict stayed 0 while the distance was clearly
        // nonzero.
        boolean nearMiss = differingOutputsNoVerdict > 0
                || (verdictR == 0 && (distBucket1to10 > 0 || distBucket11to100 > 0));
        sb.append("  \"signal_flags\": {\n");
        sb.append("    \"verdict_reached\": ").append(verdictReached).append(",\n");
        sb.append("    \"queue_starvation\": ").append(queueStarvation).append(",\n");
        sb.append("    \"singleton_output\": ").append(singletonOutput).append(",\n");
        sb.append("    \"bias_collapse\": ").append(biasCollapse).append(",\n");
        sb.append("    \"wide_but_dead\": ").append(wideButDead).append(",\n");
        sb.append("    \"near_miss\": ").append(nearMiss).append("\n");
        sb.append("  }\n");

        sb.append("}\n");

        try (FileWriter w = new FileWriter(f)) {
            w.write(sb.toString());
        }
        lastFlushNanos = System.nanoTime();
    }

    private static String classifyFailure() {
        if (verdictR > 0) return "verdict_r_reached";
        if (validIterations == 0) return "no_valid_iterations";
        double invalidRate = iterations == 0 ? 0.0 : (double) invalidIterations / (double) iterations;
        if (invalidRate > 0.5) return "pattern_C_queue_starvation";
        double identicalFrac = validIterations == 0 ? 0.0 : (double) identicalOutputs / (double) validIterations;
        if (identicalFrac > 0.9) return "pattern_A_identical_outputs";
        double diffFrac = validIterations == 0 ? 0.0 : (double) differingOutputsNoVerdict / (double) validIterations;
        if (diffFrac > 0.05) return "pattern_B_diverging_no_trigger";
        return "pattern_other";
    }

    private static void kv(StringBuilder sb, String k, Object v, boolean quoted) {
        sb.append("  \"").append(k).append("\": ");
        if (quoted) sb.append('"').append(escape(String.valueOf(v))).append('"');
        else sb.append(v);
        sb.append(",\n");
    }
    private static void kv(StringBuilder sb, String k, long v) { kv(sb, k, v, false); }
    private static void kv(StringBuilder sb, String k, double v) { kv(sb, k, v, false); }

    private static void appendMap(StringBuilder sb, Map<String, Long> m) {
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Long> e : m.entrySet()) {
            if (!first) sb.append(", ");
            sb.append('"').append(escape(e.getKey())).append("\": ").append(e.getValue());
            first = false;
        }
        sb.append("}");
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
