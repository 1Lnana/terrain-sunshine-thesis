import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 只检查驱动接入的3个已有像元；单次检查，不作整区精度或性能结论。 */
public final class HorizonDriverChecks {
    private static int checks;
    private static void require(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void near(double actual, double expected, String message) {
        require(Double.isFinite(actual) && Math.abs(actual-expected) < 1e-8, message);
    }
    private static void report(PrintWriter log, String message) {
        System.out.println(message); log.println(message); log.flush();
    }
    private static Map<String, String[]> selectedCsv(Path file, Set<String> selected) throws IOException {
        Map<String, String[]> result = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            reader.readLine(); String line;
            while ((line = reader.readLine()) != null) {
                String[] row = line.split(",", -1);
                String key = row[0] + "," + row[1];
                if (selected.contains(key)) result.put(key, row);
            }
        }
        require(result.size() == selected.size(), "historical selected pixels missing");
        return result;
    }
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length != 2) throw new IllegalArgumentException("config new-user_runs-output");
        var in = new ProjectedBaselineExperiment.Inputs(Path.of(args[0]));
        var fullReference = ProjectedBaselineExperiment.loadReference(in);
        Set<String> selected = Set.of("162,226", "162,228", "189,213");
        List<ProjectedBaselineExperiment.Receiver> receivers = new ArrayList<>();
        var reference = new ProjectedBaselineExperiment.Run(); reference.method = "fixed_1min";
        for (int i = 0; i < in.receivers.size(); i++) {
            if (selected.contains(in.receivers.get(i).key())) {
                receivers.add(in.receivers.get(i)); reference.pixels.add(fullReference.pixels.get(i));
            }
        }
        require(receivers.size() == 3, "expected three Qinling receivers");
        require(in.methods.equals(List.of("horizon_1deg", "horizon_2deg", "adaptive_30min",
                "guardlocal_30min", "guardcombined_30min")), "expected winter comparison methods");
        in.receivers = receivers;
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        Path allowed = Path.of("user_runs").toAbsolutePath().normalize();
        if (!output.startsWith(allowed) || output.equals(allowed)) throw new IllegalArgumentException("new user_runs subdirectory required");
        Files.createDirectory(output);
        Files.copy(in.config, output.resolve("config.properties"));
        Files.writeString(output.resolve("scope.txt"), "3 selected winter pixels; driver integration only; not a timing benchmark\n");
        Map<String, double[]> horizonExpected = new HashMap<>();
        List<String> previous = Files.readAllLines(Path.of("user_runs/horizon_checks_001/pixel_summary.csv"));
        for (String line : previous.subList(1, previous.size())) {
            String[] p = line.split(",");
            horizonExpected.put(p[0]+","+p[1]+","+p[2], new double[]{Double.parseDouble(p[4]), Double.parseDouble(p[8])});
        }
        Map<String, List<ProjectedBaselineExperiment.Run>> results = new LinkedHashMap<>();
        try (PrintWriter log = new PrintWriter(Files.newBufferedWriter(output.resolve("checks.log")))) {
            report(log, "HORIZON_DRIVER_READY pixels=3 methods=5 no_warmup_not_timing_benchmark");
            for (String method : in.methods) {
                var run = ProjectedBaselineExperiment.run(in, method);
                require(run.valid == 3 && ProjectedBaselineExperiment.unknownQueries(run) == 0, "all check pixels valid");
                near(run.computeSeconds, run.preprocessSeconds+run.querySeconds, "total includes preprocessing");
                if (method.startsWith("horizon_")) {
                    int step = method.equals("horizon_1deg") ? 1 : 2;
                    long directions = 3L*360/step;
                    require(run.stats.rayCalls == 0 && run.stats.spatialSamples == 0, "lookup is not a direct ray");
                    require(run.horizonDirections == directions, "direction count");
                    require(run.horizonSpatialSamples == directions*in.search.maxSamples, "full scans counted");
                    require(run.tablePayloadBytes == directions*Double.BYTES, "payload bytes");
                    require(run.horizonTimeQueries == 3L*588, "winter minute queries");
                    // 第二次仍冷建表，只检查重复一致，不把3点计时当性能结果。
                    var repeat = ProjectedBaselineExperiment.run(in, method);
                    ProjectedBaselineExperiment.consistent(run, repeat);
                    for (int i = 0; i < 3; i++) {
                        double[] expected = horizonExpected.get(receivers.get(i).key()+","+step);
                        require(expected != null, "historical horizon row present");
                        near(run.pixels.get(i).psd, expected[0], "matches checked horizon PSD");
                        near(run.pixels.get(i).samples, expected[1], "matches horizon time queries");
                    }
                } else {
                    var expected = selectedCsv(Path.of("user_runs/qinling_tradeoff_001", method+"_r1", "psd.csv"), selected);
                    for (int i = 0; i < 3; i++) {
                        String[] p = expected.get(receivers.get(i).key());
                        near(run.pixels.get(i).psd, Double.parseDouble(p[6]), "direct PSD unchanged");
                        require(run.pixels.get(i).samples == Integer.parseInt(p[7]), "direct queries unchanged");
                        require(run.pixels.get(i).reason.equals(p[8]), "direct fallback unchanged");
                    }
                    require(run.preprocessSeconds == 0 && run.horizonTimeQueries == 0, "direct has no horizon preprocessing");
                }
                ProjectedBaselineExperiment.write(in, run, output.resolve(method+"_r1"));
                String runtime = Files.readString(output.resolve(method+"_r1/runtime.json"));
                require(runtime.contains("\"horizon_time_queries\":"+run.horizonTimeQueries)
                        && runtime.contains("\"table_payload_bytes\":"+run.tablePayloadBytes)
                        && runtime.contains("\"preprocess_sec\":"), "cost fields written");
                results.put(method, List.of(run));
                for (int i = 0; i < 3; i++) report(log, String.format(
                        "DRIVER pixel=%s method=%s psd=%.3f time_nodes=%d",
                        receivers.get(i).key(), method, run.pixels.get(i).psd, run.pixels.get(i).samples));
            }
            ProjectedBaselineExperiment.summarize(in, results, reference, output);
            String header = Files.readAllLines(output.resolve("summary.csv")).get(0);
            require(header.endsWith("preprocess_sec,query_sec,table_payload_bytes,horizon_directions,horizon_spatial_samples,horizon_time_queries"), "summary cost columns");
            report(log, "HORIZON_DRIVER_CHECKS_PASS checks="+checks+" pixels=3 not_accuracy_or_speed_acceptance");
        }
    }
}
