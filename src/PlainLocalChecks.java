import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic and saved-state checks only. No DEM loading, ray queries or timing benchmark. */
public final class PlainLocalChecks {
    private static int checks;
    private static void require(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }
    private static boolean same(double a, double b) {
        return Double.isNaN(a) ? Double.isNaN(b) : Math.abs(a - b) < 1e-8;
    }
    private static void synthetic(String label, double day, AdaptiveTimeV1.Oracle oracle) {
        AtomicInteger calls = new AtomicInteger();
        AdaptiveTimeV2Local.Result plain = PlainLocalRefinement.adaptiveWithDarkGuard(day, 30,
                t -> { calls.incrementAndGet(); return oracle.at(t); });
        AdaptiveTimeV1.Result reference = AdaptiveTimeV1.fixed(day, 1, oracle);
        require(same(plain.psd, reference.psd), label + " expected integral");
        require(calls.get() == plain.evaluated.size(), label + " cache reuse");
        require(plain.quarterProbes == 0, label + " no quarter probes");
        require(plain.integrationNodes.keySet().stream().allMatch(t -> t == day || t == Math.floor(t)),
                label + " minute alignment");
        System.out.printf("CASE %s psd=%.3f fixed1=%.3f queries=%d reason=%s%n",
                label, plain.psd, reference.psd, calls.get(), plain.fallbackReason);
    }
    private static Map<String, NavigableMap<Double, Integer>> traces(Path path) throws IOException {
        Map<String, NavigableMap<Double, Integer>> result = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            require("row,col,minutes_since_sunrise,state,used_for_integration".equals(reader.readLine()), "trace header");
            String line;
            while ((line = reader.readLine()) != null) {
                String[] p = line.split(",");
                result.computeIfAbsent(p[0] + "," + p[1], k -> new TreeMap<>())
                        .put(Double.parseDouble(p[2]), Integer.parseInt(p[3]));
            }
        }
        return result;
    }
    private static int saved(Path root, Map<String, String[]> expected, String season,
                             String referenceRun, String localRun, String localMethod) throws IOException {
        Map<String, NavigableMap<Double, Integer>> reference = traces(root.resolve("user_runs")
                .resolve(referenceRun).resolve("fixed_1min_r1/samples.csv"));
        Map<String, NavigableMap<Double, Integer>> old = traces(root.resolve("user_runs")
                .resolve(localRun).resolve(localMethod + "/samples.csv"));
        for (Map.Entry<String, NavigableMap<Double, Integer>> entry : reference.entrySet()) {
            String key = entry.getKey();
            NavigableMap<Double, Integer> states = entry.getValue();
            AdaptiveTimeV1.Oracle oracle = t -> {
                Integer state = states.get(t);
                if (state == null) throw new AssertionError("saved minute state missing: " + t);
                return state;
            };
            AdaptiveTimeV2Local.Result plain = PlainLocalRefinement.adaptiveWithDarkGuard(states.lastKey(), 30, oracle);
            AdaptiveTimeV2Local.Result local = AdaptiveTimeV2Local.adaptiveWithDarkGuard(states.lastKey(), 30, oracle);
            require(local.evaluated.equals(old.get(key)), "existing local trace unchanged " + key);
            require(plain.quarterProbes == 0, "saved no quarter probes " + key);
            for (Map.Entry<Double, Integer> node : plain.evaluated.entrySet())
                require(node.getValue().equals(states.get(node.getKey())), "saved lookup " + key);
            for (String rule : List.of("plain_local", "current")) {
                String[] row = expected.get(season + "," + key + "," + rule);
                require(row != null, "offline expectation exists " + key);
                AdaptiveTimeV2Local.Result result = rule.equals("plain_local") ? plain : local;
                require(same(result.psd, Double.parseDouble(row[5])), "offline PSD " + key + rule);
                require(result.evaluated.size() == Integer.parseInt(row[8]), "offline query count " + key + rule);
            }
            System.out.printf("SAVED %s pixel=%s plain_psd=%.3f local_psd=%.3f plain_queries=%d local_queries=%d%n",
                    season, key, plain.psd, local.psd, plain.evaluated.size(), local.evaluated.size());
        }
        return reference.size();
    }
    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        synthetic("all_light", 120, t -> 1);
        synthetic("all_dark", 120, t -> 0);
        synthetic("single_window", 120, t -> t >= 13 && t < 97 ? 1 : 0);
        synthetic("two_windows", 120, t -> (t >= 10 && t < 20) || (t >= 70 && t < 80) ? 1 : 0);
        synthetic("hidden_light_guard", 120, t -> t >= 3 && t < 12 ? 1 : 0);
        synthetic("fractional_sunset", 116.5, t -> 1);
        synthetic("unknown_coarse", 120, t -> t == 30 ? -1 : 1);
        synthetic("unknown_refinement", 120, t -> t == 8 ? -1 : t >= 10 ? 1 : 0);
        AdaptiveTimeV1.Oracle hidden = t -> t >= 3 && t < 5 ? 0 : 1;
        AdaptiveTimeV2Local.Result limited = PlainLocalRefinement.adaptiveWithDarkGuard(120, 30, hidden);
        require(limited.psd == 120 && AdaptiveTimeV1.fixed(120, 1, hidden).psd == 118,
                "explicit hidden shade limitation retained");
        System.out.println("KNOWN_LIMITATION plain_PSD=120 fixed1_PSD=118");
        require(ProjectedBaselineExperiment.SUPPORTED.contains("plainlocal_30min"), "driver mode registered");
        Map<String, String[]> expected = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(root.resolve("user_runs/saved_visibility_replay_003/replay.csv"))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] p = line.split(",");
                expected.put(p[0] + "," + p[1] + "," + p[2] + "," + p[3], p);
            }
        }
        int pixels = saved(root, expected, "winter", "qinling_512_reference_002",
                "qinling_512_guardlocal_30min_20261029_120231_207", "guardlocal_30min_r1");
        pixels += saved(root, expected, "spring", "qinling_spring_001", "qinling_spring_001", "guardlocal_30min_r1");
        require(pixels == 7, "all seven saved traces checked");
        System.out.println("PLAIN_LOCAL_CHECKS_PASS checks=" + checks + " saved_trace_pixels=" + pixels + " no_terrain_queries");
    }
}
