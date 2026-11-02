import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 检查真实跨日期复用；仅3个已有像元，不做整区或正式计时。 */
public final class MultiDayReuseChecks {
    private static int checks;
    private static void require(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void near(double a, double b, String message) {
        require(Double.isFinite(a) && Math.abs(a-b) < 1e-8, message);
    }
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length != 2) throw new IllegalArgumentException("three-day-config new-user_runs-output");
        var setup = new ProjectedMultiDayExperiment.Setup(Path.of(args[0]));
        Set<String> selected = Set.of("162,226", "162,228", "189,213");
        for (int day = 0; day < 3; day++) {
            var in = setup.days.get(day);
            List<ProjectedBaselineExperiment.Receiver> receivers = new ArrayList<>();
            var reference = new ProjectedBaselineExperiment.Run(); reference.method = "fixed_1min";
            for (int i = 0; i < in.receivers.size(); i++) {
                if (selected.contains(in.receivers.get(i).key())) {
                    receivers.add(in.receivers.get(i)); reference.pixels.add(setup.references.get(day).pixels.get(i));
                }
            }
            require(receivers.size() == 3, "expected three receivers");
            in.receivers = receivers; setup.references.set(day, reference);
        }
        require(setup.days.get(0).dec != setup.days.get(1).dec && setup.days.get(1).dec != setup.days.get(2).dec,
                "three distinct seasonal solar conditions");
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        Path allowed = Path.of("user_runs").toAbsolutePath().normalize();
        if (!output.startsWith(allowed) || output.equals(allowed)) throw new IllegalArgumentException("new user_runs subdirectory required");
        Files.createDirectory(output);
        Files.copy(setup.config, output.resolve("config.properties"));
        Files.writeString(output.resolve("scope.txt"), "3 selected pixels, 3 specified dates; reuse integration only, not a timing benchmark\n");
        Map<String, List<ProjectedMultiDayExperiment.Batch>> results = new LinkedHashMap<>();
        try (PrintWriter log = new PrintWriter(Files.newBufferedWriter(output.resolve("checks.log")))) {
            String ready = "MULTIDAY_REUSE_READY pixels=3 dates=3 methods=5 no_warmup_not_timing_benchmark";
            System.out.println(ready); log.println(ready); log.flush();
            for (String method : setup.days.get(0).methods) {
                var batch = ProjectedMultiDayExperiment.run(setup, method);
                require(batch.days.size() == 3, "three daily results");
                double sum = batch.preprocessSeconds;
                long independentPrecomputeSamples = 0;
                for (int day = 0; day < 3; day++) {
                    var in = setup.days.get(day);
                    var reused = batch.days.get(day);
                    var separate = ProjectedBaselineExperiment.run(in, method);
                    require(reused.valid == 3 && ProjectedBaselineExperiment.unknownQueries(reused) == 0, "valid selected pixels");
                    require(reused.preprocessSeconds == 0, "shared preprocessing not charged daily");
                    for (int i = 0; i < 3; i++) {
                        near(reused.pixels.get(i).psd, separate.pixels.get(i).psd, "shared vs independent PSD");
                        require(reused.pixels.get(i).samples == separate.pixels.get(i).samples, "daily query counts unchanged");
                        require(reused.pixels.get(i).reason.equals(separate.pixels.get(i).reason), "fallback unchanged");
                    }
                    if (method.startsWith("horizon_")) {
                        require(reused.horizonTimeQueries == separate.horizonTimeQueries && reused.stats.rayCalls == 0, "lookup counts");
                        require(reused.horizonSpatialSamples == 0, "no daily terrain preprocessing");
                        independentPrecomputeSamples += separate.horizonSpatialSamples;
                    } else {
                        require(reused.stats.rayCalls == separate.stats.rayCalls && reused.stats.spatialSamples == separate.stats.spatialSamples,
                                "direct query accounting unchanged");
                    }
                    sum += reused.computeSeconds;
                    near(batch.cumulative(day+1), sum, "cumulative includes preprocessing once");
                    String line = String.format("REUSE method=%s date=%s psd=[%.3f,%.3f,%.3f]",
                            method, in.p.getProperty("date"), reused.pixels.get(0).psd,
                            reused.pixels.get(1).psd, reused.pixels.get(2).psd);
                    System.out.println(line); log.println(line); log.flush();
                }
                if (method.startsWith("horizon_")) {
                    int step = method.equals("horizon_1deg") ? 1 : 2;
                    require(batch.tableBuilds == 3, "one table per receiver for all dates");
                    require(batch.directions == 3L*360/step, "directions counted once");
                    require(batch.horizonSpatialSamples == batch.directions*200, "terrain samples counted once");
                    require(independentPrecomputeSamples == 3*batch.horizonSpatialSamples, "three independent builds cost three scans");
                    require(batch.tablePayloadBytes == batch.directions*Double.BYTES, "shared payload");
                    require(batch.rays() == 0 && batch.timeQueries() > 0, "lookup is not a ray");
                } else {
                    require(batch.tableBuilds == 0 && batch.preprocessSeconds == 0 && batch.timeQueries() == 0, "direct method no horizon cost");
                }
                // 固定已核验的冬季3点记录，防止共享/独立入口一起出现同一种回归。
                List<String> winterRows = Files.readAllLines(Path.of("user_runs/horizon_driver_checks_001", method+"_r1", "psd.csv"));
                for (int i = 0; i < 3; i++) {
                    String[] old = winterRows.get(i+1).split(",");
                    require((old[0]+","+old[1]).equals(setup.days.get(2).receivers.get(i).key()), "winter key unchanged");
                    near(batch.days.get(2).pixels.get(i).psd, Double.parseDouble(old[6]), "matches frozen winter driver PSD");
                    require(batch.days.get(2).pixels.get(i).samples == Integer.parseInt(old[7]), "matches frozen winter query count");
                }
                ProjectedMultiDayExperiment.write(setup, batch, output.resolve(method+"_r1"));
                String runtime = Files.readString(output.resolve(method+"_r1/runtime.json"));
                require(runtime.contains("\"horizon_table_builds\":"+batch.tableBuilds)
                        && runtime.contains("\"cumulative_sec\":["), "batch cost fields written");
                results.put(method, List.of(batch));
            }
            ProjectedMultiDayExperiment.summarize(setup, results, output);
            require(Files.readAllLines(output.resolve("summary.csv")).size() == 6, "five aggregate summary rows");
            for (var in : setup.days) require(Files.exists(output.resolve("day_"+in.p.getProperty("date")+"/summary.csv")), "daily error summary written");
            String passed = "MULTIDAY_REUSE_CHECKS_PASS checks="+checks+" pixels=3 dates=3 not_timing_benchmark";
            System.out.println(passed); log.println(passed);
        }
    }
}
