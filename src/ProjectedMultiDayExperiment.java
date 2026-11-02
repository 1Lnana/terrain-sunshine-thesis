import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 三个指定日期的同批比较：地平线每次建表一次，直接方法各天独立查询。 */
public final class ProjectedMultiDayExperiment {
    static final class Setup {
        final Path config;
        final List<ProjectedBaselineExperiment.Inputs> days = new ArrayList<>();
        final List<ProjectedBaselineExperiment.Run> references = new ArrayList<>();
        Setup(Path file) throws IOException {
            config = file.toAbsolutePath().normalize();
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(config)) { p.load(r); }
            Path base = config.getParent().resolve(p.getProperty("base_config")).normalize();
            String[] dates = p.getProperty("dates").split(",");
            String[] refs = p.getProperty("reference_runs").split(",");
            if (dates.length != 3 || refs.length != 3 || new HashSet<>(List.of(dates)).size() != 3)
                throw new IllegalArgumentException("three distinct dates and references required");
            for (int i = 0; i < 3; i++) {
                var in = new ProjectedBaselineExperiment.Inputs(base, Map.of("date", dates[i], "reference_run", refs[i]));
                references.add(ProjectedBaselineExperiment.loadReference(in));
                days.add(in);
            }
            if (!days.get(0).methods.equals(List.of("horizon_1deg", "horizon_2deg", "adaptive_30min",
                    "guardlocal_30min", "guardcombined_30min")))
                throw new IllegalArgumentException("expected five comparison methods");
        }
    }

    static final class Batch {
        String method;
        double preprocessSeconds;
        long tableBuilds, tablePayloadBytes, directions, horizonSpatialSamples;
        final List<ProjectedBaselineExperiment.Run> days = new ArrayList<>();
        double cumulative(int count) {
            double total = preprocessSeconds;
            for (int i = 0; i < count; i++) total += days.get(i).computeSeconds;
            return total;
        }
        long rays() { return days.stream().mapToLong(r -> r.stats.rayCalls).sum(); }
        long spatialSamples() { return days.stream().mapToLong(r -> r.stats.spatialSamples).sum(); }
        long timeQueries() { return days.stream().mapToLong(r -> r.horizonTimeQueries).sum(); }
    }

    static Batch run(Setup setup, String method) {
        Batch batch = new Batch(); batch.method = method;
        var first = setup.days.get(0);
        if (method.startsWith("horizon_")) {
            int step = method.equals("horizon_1deg") ? 1 : 2;
            long start = System.nanoTime();
            List<HorizonBaseline.Table> tables = new ArrayList<>(first.receivers.size());
            for (var receiver : first.receivers) {
                var table = HorizonBaseline.build(first.grid, receiver.row, receiver.col, first.search, step);
                tables.add(table); batch.tableBuilds++;
                batch.tablePayloadBytes += table.payloadBytes();
                batch.directions += table.directions();
                batch.horizonSpatialSamples += table.spatialSamples;
            }
            batch.preprocessSeconds = (System.nanoTime()-start)/1e9;
            // 同一列表、同一表对象供三天使用，没有第二次build。
            for (var in : setup.days) batch.days.add(ProjectedBaselineExperiment.queryHorizon(in, method, tables));
        } else {
            for (var in : setup.days) batch.days.add(ProjectedBaselineExperiment.run(in, method));
        }
        return batch;
    }

    static void consistent(Batch first, Batch next) {
        if (!first.method.equals(next.method) || first.tableBuilds != next.tableBuilds
                || first.tablePayloadBytes != next.tablePayloadBytes || first.directions != next.directions
                || first.horizonSpatialSamples != next.horizonSpatialSamples)
            throw new AssertionError("repeated preprocessing counts differ");
        for (int i = 0; i < 3; i++) ProjectedBaselineExperiment.consistent(first.days.get(i), next.days.get(i));
    }

    static void write(Setup setup, Batch batch, Path output) throws IOException {
        Files.createDirectory(output);
        long start = System.nanoTime();
        for (int i = 0; i < 3; i++) ProjectedBaselineExperiment.write(setup.days.get(i), batch.days.get(i),
                output.resolve("day_"+setup.days.get(i).p.getProperty("date")));
        double writeSeconds = (System.nanoTime()-start)/1e9;
        // 各天runtime只有查询成本；共享建表成本仅在此总runtime计一次。
        String queries = String.format("[%.9f,%.9f,%.9f]", batch.days.get(0).computeSeconds,
                batch.days.get(1).computeSeconds, batch.days.get(2).computeSeconds);
        Files.writeString(output.resolve("runtime.json"), String.format(
                "{\"method\":\"%s\",\"preprocess_sec\":%.9f,\"day_query_sec\":%s,"
                + "\"cumulative_sec\":[%.9f,%.9f,%.9f],\"compute_sec\":%.9f,\"csv_write_sec\":%.9f,"
                + "\"horizon_table_builds\":%d,\"table_payload_bytes\":%d,\"horizon_directions\":%d,"
                + "\"horizon_spatial_samples\":%d,\"horizon_time_queries\":%d,\"rayCalls\":%d,\"spatialSamples\":%d}%n",
                batch.method, batch.preprocessSeconds, queries, batch.cumulative(1), batch.cumulative(2),
                batch.cumulative(3), batch.cumulative(3), writeSeconds, batch.tableBuilds, batch.tablePayloadBytes,
                batch.directions, batch.horizonSpatialSamples, batch.timeQueries(), batch.rays(), batch.spatialSamples()));
    }

    private static double median(double[] values) {
        Arrays.sort(values);
        return (values[(values.length-1)/2] + values[values.length/2])/2;
    }

    static void summarize(Setup setup, Map<String, List<Batch>> results, Path output) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(output.resolve("summary.csv")))) {
            w.println("method,repeats,day1_total_sec,day2_total_sec,day3_total_sec,total_min_sec,total_max_sec,"
                    + "preprocess_sec,horizon_table_builds,table_payload_bytes,horizon_directions,horizon_spatial_samples,horizon_time_queries,rayCalls,spatialSamples");
            for (var entry : results.entrySet()) {
                List<Batch> batches = entry.getValue(); Batch first = batches.get(0);
                double[] totals = batches.stream().mapToDouble(b -> b.cumulative(3)).sorted().toArray();
                w.printf("%s,%d,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%d,%d,%d,%d,%d,%d,%d%n",
                        entry.getKey(), batches.size(), median(batches.stream().mapToDouble(b -> b.cumulative(1)).toArray()),
                        median(batches.stream().mapToDouble(b -> b.cumulative(2)).toArray()),
                        median(totals), totals[0], totals[totals.length-1],
                        median(batches.stream().mapToDouble(b -> b.preprocessSeconds).toArray()),
                        first.tableBuilds, first.tablePayloadBytes, first.directions, first.horizonSpatialSamples,
                        first.timeQueries(), first.rays(), first.spatialSamples());
            }
        }
        for (int i = 0; i < 3; i++) {
            int dayIndex = i;
            Map<String, List<ProjectedBaselineExperiment.Run>> dayResults = new LinkedHashMap<>();
            results.forEach((method, batches) -> dayResults.put(method, batches.stream().map(b -> b.days.get(dayIndex)).toList()));
            Path folder = output.resolve("day_"+setup.days.get(i).p.getProperty("date"));
            Files.createDirectory(folder);
            ProjectedBaselineExperiment.summarize(setup.days.get(i), dayResults, setup.references.get(i), folder);
        }
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length != 2) throw new IllegalArgumentException("three-day-config new-output-directory");
        long loadStart = System.nanoTime();
        Setup setup = new Setup(Path.of(args[0]));
        double loadSeconds = (System.nanoTime()-loadStart)/1e9;
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        Path allowed = Path.of("user_runs").toAbsolutePath().normalize();
        if (!output.startsWith(allowed) || output.equals(allowed)) throw new IllegalArgumentException("new user_runs subdirectory required");
        Files.createDirectory(output);
        Files.copy(setup.config, output.resolve("config.properties"));
        var first = setup.days.get(0);
        Files.copy(first.config, output.resolve("base_config.properties"));
        Files.copy(first.resolve(first.p.getProperty("receivers")), output.resolve("receivers.csv"));
        Files.copy(first.resolve(first.p.getProperty("metadata")), output.resolve("data_metadata.json"));
        Files.writeString(output.resolve("input_load.json"), String.format("{\"input_load_sec\":%.9f}%n", loadSeconds));
        for (var in : setup.days) {
            String date = in.p.getProperty("date");
            try (Writer w = Files.newBufferedWriter(output.resolve("config_"+date+".properties"))) {
                in.p.store(w, "Effective configuration; paths relative to " + in.config.getParent());
            }
            Files.writeString(output.resolve("reference_"+date+".txt"), in.resolve(in.p.getProperty("reference_run"))+"\nfixed_1min_r1/psd.csv\n");
        }
        System.out.printf("MULTIDAY_READY pixels=%d dates=%s methods=%s references_reused=true%n", first.receivers.size(),
                setup.days.stream().map(in -> in.p.getProperty("date")).toList(), first.methods);
        Map<String, List<Batch>> results = new LinkedHashMap<>();
        first.methods.forEach(m -> results.put(m, new ArrayList<>()));
        try (PrintWriter log = new PrintWriter(Files.newBufferedWriter(output.resolve("execution.log")))) {
            for (int i = 0; i < first.warmups; i++) for (String method : first.methods) {
                Batch warmup = run(setup, method);
                String line = "WARMUP_DONE " + method + " table_builds="+warmup.tableBuilds;
                System.out.println(line); log.println(line); log.flush();
            }
            Random random = new Random(20261028);
            for (int repeat = 1; repeat <= first.repeats; repeat++) {
                List<String> order = new ArrayList<>(first.methods); Collections.shuffle(order, random);
                log.println("ORDER repeat="+repeat+" "+order); log.flush();
                for (String method : order) {
                    Batch batch = run(setup, method);
                    if (!results.get(method).isEmpty()) consistent(results.get(method).get(0), batch);
                    write(setup, batch, output.resolve(method+"_r"+repeat));
                    results.get(method).add(batch);
                    String line = String.format("MULTIDAY_DONE %s r%d total_sec=%.6f preprocess_sec=%.6f table_builds=%d rays=%d horizon_queries=%d",
                            method, repeat, batch.cumulative(3), batch.preprocessSeconds, batch.tableBuilds, batch.rays(), batch.timeQueries());
                    System.out.println(line); log.println(line); log.flush();
                }
            }
            summarize(setup, results, output);
            log.println("MULTIDAY_COMPLETE repeats_consistent=true");
        }
        System.out.println("MULTIDAY_COMPLETE summary="+output.resolve("summary.csv"));
    }
}
