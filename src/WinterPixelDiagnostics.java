import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Three-pixel trace reproduction only; does not change any sampling rule. */
public final class WinterPixelDiagnostics {
    static final Set<String> POINTS = Set.of("162,226", "189,213", "162,228");
    static final Path PREVIOUS = Path.of("user_runs/qinling_winter_001");

    static Map<String,Double> previous(String method) throws IOException {
        Map<String,Double> values = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(PREVIOUS.resolve(method + "_r1/psd.csv"))) {
            reader.readLine(); String line;
            while ((line = reader.readLine()) != null) {
                String[] p = line.split(",", -1); String key = p[0] + "," + p[1];
                if (POINTS.contains(key)) values.put(key, Double.parseDouble(p[6]));
            }
        }
        if (values.size() != POINTS.size()) throw new IllegalArgumentException("previous pixels missing");
        return values;
    }

    static void intervals(PrintWriter writer, String key, String method,
                          ProjectedBaselineExperiment.Pixel pixel, ProjectedBaselineExperiment.Pixel reference) {
        Map.Entry<Double,Integer> left = null;
        double total = 0; boolean aligned = true;
        for (Map.Entry<Double,Integer> right : pixel.nodes.entrySet()) {
            if (left != null) {
                double a = left.getKey(), b = right.getKey();
                int sa = left.getValue(), sb = right.getValue();
                if (!reference.nodes.containsKey(a) || !reference.nodes.containsKey(b)) {
                    aligned = false;
                    writer.printf("%s,%s,%.12f,%.12f,%d,%d,NaN,NaN,NaN,UNALIGNED_REFERENCE_ENDPOINT%n", key, method, a, b, sa, sb);
                    left = right; continue;
                }
                NavigableMap<Double,Integer> fine = reference.nodes.subMap(a, true, b, true);
                double[] times = new double[fine.size()]; int[] states = new int[times.length]; int i = 0;
                for (Map.Entry<Double,Integer> e : fine.entrySet()) { times[i] = e.getKey(); states[i++] = e.getValue(); }
                double dense = TimeStepBaseline.integrate(times, states);
                double approximate = (b - a) * (sa + sb) / 2.0;
                double delta = approximate - dense; total += delta;
                if (Math.abs(delta) > 1e-8) {
                    String kind = sa == 0 && sb == 0 ? "MISSED_LIGHT"
                            : sa == 1 && sb == 1 ? "MISSED_SHADE" : "TRANSITION_OR_MULTIPLE_CHANGES";
                    writer.printf("%s,%s,%.12f,%.12f,%d,%d,%.12f,%.12f,%.12f,%s%n",
                            key, method, a, b, sa, sb, approximate, dense, delta, kind);
                    System.out.printf("INTERVAL pixel=%s method=%s start=%.3f end=%.3f error_min=%.3f kind=%s%n",
                            key, method, a, b, delta, kind);
                }
            }
            left = right;
        }
        if (aligned && Math.abs(total - (pixel.psd - reference.psd)) > 1e-8)
            throw new AssertionError("interval error accounting mismatch");
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length != 2) throw new IllegalArgumentException("winter-config new-output-directory");
        ProjectedBaselineExperiment.Inputs in = new ProjectedBaselineExperiment.Inputs(Path.of(args[0]));
        if (!in.p.getProperty("date").equals("2023-12-21")) throw new IllegalArgumentException("winter date required");
        in.receivers.removeIf(receiver -> !POINTS.contains(receiver.key()));
        if (in.receivers.size() != POINTS.size()) throw new IllegalArgumentException("diagnostic pixels missing");
        in.traces.clear(); in.traces.addAll(POINTS);
        in.methods = List.of("fixed_1min", "adaptive_30min", "solar_30min", "local_30min", "combined_30min");
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        Path allowed = Path.of("user_runs").toAbsolutePath().normalize();
        if (!output.startsWith(allowed) || output.equals(allowed)) throw new IllegalArgumentException("new user_runs directory required");
        Files.createDirectories(output.getParent()); Files.createDirectory(output);
        Files.copy(in.config, output.resolve("config.properties"));
        Files.writeString(output.resolve("diagnostic_scope.txt"),
                "date=2023-12-21\npixels=162,226;189,213;162,228\nprevious=" + PREVIOUS.toAbsolutePath()
                + "\nsingle pass per method; not a timing benchmark\n");
        System.out.println("DIAGNOSTIC_READY pixels=3 methods=5 single_pass_not_timing_benchmark");
        ProjectedBaselineExperiment.Run reference = null;
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(output.resolve("interval_errors.csv")))) {
            writer.println("row,col,method,start_minute,end_minute,left_state,right_state,method_minutes,fixed1_minutes,error_minutes,kind");
            for (String method : in.methods) {
                Map<String,Double> expected = previous(method);
                ProjectedBaselineExperiment.Run run = ProjectedBaselineExperiment.run(in, method);
                if (run.valid != POINTS.size() || run.stats.unknownResults != 0)
                    throw new AssertionError("diagnostic pixel invalid or UNKNOWN");
                if (reference == null) reference = run;
                for (int i = 0; i < in.receivers.size(); i++) {
                    String key = in.receivers.get(i).key();
                    ProjectedBaselineExperiment.Pixel pixel = run.pixels.get(i);
                    if (!Double.isFinite(pixel.psd) || Math.abs(pixel.psd - expected.get(key)) > 1e-8)
                        throw new AssertionError("previous PSD not reproduced: " + method + " " + key);
                    System.out.printf("PIXEL %s method=%s psd=%.3f fixed1=%.3f queries=%d fallback=%s%n",
                            key, method, pixel.psd, reference.pixels.get(i).psd, pixel.samples, pixel.reason);
                    if (!method.equals("fixed_1min")) intervals(writer, key, method, pixel, reference.pixels.get(i));
                }
                ProjectedBaselineExperiment.write(in, run, output.resolve(method));
            }
        }
        System.out.println("WINTER_PIXEL_DIAGNOSTICS_PASS reproduced=15 output=" + output);
    }
}
