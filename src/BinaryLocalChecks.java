import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic examples and saved minute-state replay only; no DEM or ray queries. */
public final class BinaryLocalChecks {
    private static int checks;
    private static final List<String> METHODS = List.of("binarylocal", "plainlocal", "guardlocal");
    private static void require(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }
    private static boolean same(double a, double b) {
        return Double.isNaN(a) ? Double.isNaN(b) : Math.abs(a - b) < 1e-8;
    }
    private static AdaptiveTimeV2Local.Result run(String method, double day, int step,
                                                 AdaptiveTimeV1.Oracle oracle) {
        switch (method) {
            case "binarylocal": return BinaryLocalRefinement.adaptiveWithDarkGuard(day, step, oracle);
            case "plainlocal": return PlainLocalRefinement.adaptiveWithDarkGuard(day, step, oracle);
            case "guardlocal": return AdaptiveTimeV2Local.adaptiveWithDarkGuard(day, step, oracle);
            default: throw new IllegalArgumentException(method);
        }
    }
    private static void record(PrintWriter summary, PrintWriter samples, String kind, String label,
                               double day, AdaptiveTimeV1.Oracle oracle) {
        AdaptiveTimeV1.Result reference = AdaptiveTimeV1.fixed(day, 1, oracle);
        for (Map.Entry<Double, Integer> e : reference.evaluated.entrySet())
            samples.printf(Locale.ROOT, "%s,%s,fixed1,%.12f,%d,1%n", kind, label, e.getKey(), e.getValue());
        for (String method : METHODS) {
            AtomicInteger calls = new AtomicInteger();
            AdaptiveTimeV2Local.Result r = run(method, day, 30,
                    t -> { calls.incrementAndGet(); return oracle.at(t); });
            require(calls.get() == r.evaluated.size(), label + " cache " + method);
            require(r.integrationNodes.firstKey() == 0 && r.integrationNodes.lastKey() == day,
                    label + " endpoints " + method);
            require(r.integrationNodes.equals(r.evaluated), label + " all queries integrated " + method);
            require(r.evaluated.keySet().stream().allMatch(t -> t == day || t == Math.floor(t)),
                    label + " minute alignment " + method);
            summary.printf(Locale.ROOT, "%s,%s,%s,%.12f,%.12f,%.12f,%d,%d,%d,%d,%s%n",
                    kind, label, method, reference.psd, r.psd, r.psd - reference.psd,
                    r.evaluated.size(), r.quarterProbes, r.refinementQueries, r.guardQueries, r.fallbackReason);
            for (Map.Entry<Double, Integer> e : r.evaluated.entrySet())
                samples.printf(Locale.ROOT, "%s,%s,%s,%.12f,%d,%d%n", kind, label, method,
                        e.getKey(), e.getValue(), r.integrationNodes.containsKey(e.getKey()) ? 1 : 0);
            System.out.printf(Locale.ROOT, "%s %s %s psd=%.6f fixed1=%.6f queries=%d reason=%s%n",
                    kind, label, method, r.psd, reference.psd, r.evaluated.size(), r.fallbackReason);
        }
    }
    private static void syntheticChecks() {
        // Every integer switch, both orientations, both supported steps and a fractional sunset.
        for (int step : new int[]{20, 30}) for (double day : new double[]{60, 59.5})
            for (int switchAt = 0; switchAt <= Math.ceil(day); switchAt++) for (int invert = 0; invert < 2; invert++) {
                final int boundary = switchAt, flip = invert;
                AdaptiveTimeV1.Oracle oracle = t -> (t >= boundary ? 1 : 0) ^ flip;
                AdaptiveTimeV2Local.Result r = run("binarylocal", day, step, oracle);
                require(same(r.psd, AdaptiveTimeV1.fixed(day, 1, oracle).psd), "single transition " + boundary);
            }
        require(run("binarylocal", 120, 30, t -> 1).evaluated.size() == 9, "all light schedule");
        AdaptiveTimeV2Local.Result dark = run("binarylocal", 120, 30, t -> 0);
        require(dark.evaluated.size() == 121 && dark.guardQueries == 116
                && dark.fallbackReason.equals("NO_VISIBLE_COARSE"), "dark guard accounting");
        require(run("binarylocal", .5, 30, t -> 1).psd == .5, "subminute day");
        AdaptiveTimeV1.Oracle windows = t -> (t >= 10 && t < 40) || (t >= 70 && t < 100) ? 1 : 0;
        AdaptiveTimeV2Local.Result detected = run("binarylocal", 120, 30, windows);
        require(detected.psd == 60 && detected.fallbackReason.equals("NONE"), "separate detected windows without fallback");
        AdaptiveTimeV1.Oracle darkWindow = t -> t >= 3 && t < 12 ? 1 : 0;
        require(run("binarylocal", 120, 30, darkWindow).psd == 9, "hidden light recovered by dark guard");
        AdaptiveTimeV1.Oracle hidden = t -> t >= 3 && t < 5 ? 0 : 1;
        require(AdaptiveTimeV1.fixed(120, 1, hidden).psd == 118, "hidden shade reference");
        for (String method : METHODS) require(run(method, 120, 30, hidden).psd == 120, "shared missed shade " + method);
        // Artificial counterexample: the binary path skips t=8, while both fills query it.
        AdaptiveTimeV1.Oracle multiple = t -> (t >= 8 && t < 9) || t >= 10 ? 1 : 0;
        require(AdaptiveTimeV1.fixed(30, 1, multiple).psd == 21.5, "multiple reference");
        require(run("binarylocal", 30, 30, multiple).psd == 20.5, "binary known missed window");
        require(run("plainlocal", 30, 30, multiple).psd == 21.5, "plain detects multiple");
        require(run("guardlocal", 30, 30, multiple).psd == 21.5, "guard detects multiple");
        for (int unknown : new int[]{0, 30, 15, 7}) {
            AdaptiveTimeV1.Oracle oracle = t -> t == unknown ? -1 : t >= 10 ? 1 : 0;
            require(Double.isNaN(run("binarylocal", 30, 30, oracle).psd), "queried unknown " + unknown);
        }
        require(Double.isNaN(run("binarylocal", 30, 30, t -> 2).psd), "invalid state normalized");
        for (double day : new double[]{0, -1, 1441, Double.NaN, Double.POSITIVE_INFINITY}) {
            boolean rejected = false;
            try { run("binarylocal", day, 30, t -> 1); } catch (IllegalArgumentException e) { rejected = true; }
            require(rejected, "invalid day");
        }
        boolean rejected = false;
        try { run("binarylocal", 120, 10, t -> 1); } catch (IllegalArgumentException e) { rejected = true; }
        require(rejected, "unsupported coarse step");
        require(ProjectedBaselineExperiment.SUPPORTED.contains("binarylocal_30min"), "driver registration");
    }
    private static Map<String, NavigableMap<Double, Integer>> traces(Path path) throws IOException {
        Map<String, NavigableMap<Double, Integer>> result = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            require("row,col,minutes_since_sunrise,state,used_for_integration".equals(reader.readLine()), "trace header");
            String line;
            while ((line = reader.readLine()) != null) {
                String[] p = line.split(",");
                Integer previous = result.computeIfAbsent(p[0] + "_" + p[1], k -> new TreeMap<>())
                        .put(Double.parseDouble(p[2]), Integer.parseInt(p[3]));
                require(previous == null, "unique saved timestamp");
            }
        }
        return result;
    }
    private static int saved(Path root, String season, String referenceRun, String localRun,
                             PrintWriter summary, PrintWriter samples) throws IOException {
        Map<String, NavigableMap<Double, Integer>> reference = traces(root.resolve("user_runs")
                .resolve(referenceRun).resolve("fixed_1min_r1/samples.csv"));
        Map<String, NavigableMap<Double, Integer>> old = traces(root.resolve("user_runs")
                .resolve(localRun).resolve("guardlocal_30min_r1/samples.csv"));
        for (Map.Entry<String, NavigableMap<Double, Integer>> entry : reference.entrySet()) {
            NavigableMap<Double, Integer> states = entry.getValue();
            double day = states.lastKey();
            require(states.firstKey() == 0 && states.size() == (int)Math.ceil(day) + 1, "complete reference size");
            for (int t = 0; t < day; t++) require(states.containsKey((double)t), "complete minute reference");
            AdaptiveTimeV1.Oracle oracle = t -> {
                Integer state = states.get(t);
                if (state == null) throw new AssertionError("missing saved minute " + t);
                return state;
            };
            require(run("guardlocal", day, 30, oracle).evaluated.equals(old.get(entry.getKey())),
                    "guard trace regression " + entry.getKey());
            record(summary, samples, "SAVED", season + "_" + entry.getKey(), day, oracle);
        }
        return reference.size();
    }
    public static void main(String[] args) throws IOException {
        if (args.length != 2) throw new IllegalArgumentException("usage: BinaryLocalChecks <project-root> <new-output-dir>");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectory(output); // Refuse an existing directory, including a failed previous run.
        syntheticChecks();
        try (PrintWriter summary = new PrintWriter(Files.newBufferedWriter(output.resolve("comparison.csv")));
             PrintWriter samples = new PrintWriter(Files.newBufferedWriter(output.resolve("samples.csv")))) {
            summary.println("kind,case_id,method,fixed1_psd,psd,signed_error,queries,quarter_probes,refinement_queries,guard_queries,fallback_reason");
            samples.println("kind,case_id,method,minutes_since_sunrise,state,used_for_integration");
            record(summary, samples, "SYNTHETIC", "all_light", 120, t -> 1);
            record(summary, samples, "SYNTHETIC", "all_dark", 120, t -> 0);
            record(summary, samples, "SYNTHETIC", "single_window", 120, t -> t >= 13 && t < 97 ? 1 : 0);
            record(summary, samples, "SYNTHETIC", "two_windows", 120,
                    t -> (t >= 10 && t < 40) || (t >= 70 && t < 100) ? 1 : 0);
            record(summary, samples, "SYNTHETIC", "hidden_light_guard", 120, t -> t >= 3 && t < 12 ? 1 : 0);
            record(summary, samples, "SYNTHETIC", "hidden_shade_all_miss", 120, t -> t >= 3 && t < 5 ? 0 : 1);
            record(summary, samples, "SYNTHETIC", "multiple_in_half", 30, t -> (t >= 8 && t < 9) || t >= 10 ? 1 : 0);
            record(summary, samples, "SYNTHETIC", "fractional_sunset", 116.5, t -> t >= 110 ? 1 : 0);
            record(summary, samples, "SYNTHETIC", "unknown_refinement", 30, t -> t == 7 ? -1 : t >= 10 ? 1 : 0);
            int pixels = saved(root, "winter", "qinling_512_reference_002",
                    "qinling_512_guardlocal_30min_20261029_120231_207", summary, samples);
            pixels += saved(root, "spring", "qinling_spring_001", "qinling_spring_001", summary, samples);
            require(pixels == 7, "seven saved traces");
            require(!summary.checkError() && !samples.checkError(), "output writes");
        }
        String pass = "BINARY_LOCAL_CHECKS_PASS checks=" + checks + " saved_trace_pixels=7 no_terrain_queries";
        Files.writeString(output.resolve("checks.txt"), pass + System.lineSeparator());
        System.out.println(pass);
        System.out.println("OUTPUT " + output);
    }
}
