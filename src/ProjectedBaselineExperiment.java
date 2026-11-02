import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Configurable projected-DEM driver with fixed, V1, local and solar-scheduled methods. */
public final class ProjectedBaselineExperiment {
    static final Set<String> SUPPORTED = Set.of("fixed_1min", "fixed_5min", "fixed_10min",
            "fixed_20min", "fixed_30min", "fixed_60min", "adaptive_20min", "adaptive_30min", "local_30min",
            "solar_30min", "combined_30min", "guardlocal_30min", "guardcombined_30min", "plainlocal_30min", "binarylocal_30min",
            "horizon_1deg", "horizon_2deg", "block8binarylocal_30min", "block16binarylocal_30min",
            "block32binarylocal_30min", "block16fixed_1min", "block4binarylocal_30min", "block64binarylocal_30min");

    static int spatialBlockSize(String method) {
        switch (method) {
            case "block4binarylocal_30min": return 4;
            case "block64binarylocal_30min": return 64;
            case "block8binarylocal_30min": return 8;
            case "block16binarylocal_30min": return 16;
            case "block16fixed_1min": return 16;
            case "block32binarylocal_30min": return 32;
            default: return 0;
        }
    }

    static final class Receiver {
        int row, col;
        double lat, lon, north;
        Receiver(String[] p) {
            row = Integer.parseInt(p[0]); col = Integer.parseInt(p[1]);
            lat = Double.parseDouble(p[2]); lon = Double.parseDouble(p[3]); north = Double.parseDouble(p[4]);
            if (!Double.isFinite(lat) || Math.abs(lat) >= 66 || !Double.isFinite(lon)
                    || Math.abs(lon) > 180 || !Double.isFinite(north))
                throw new IllegalArgumentException("invalid receiver coordinates");
        }
        String key() { return row + "," + col; }
    }

    static final class Inputs {
        Properties p = new Properties();
        AsciiGrid grid;
        List<Receiver> receivers = new ArrayList<>();
        List<String> methods;
        Set<String> traces = new HashSet<>();
        SearchConfig search;
        CoordinateContract contract = TimeStepBaseline.contract();
        double dec;
        int repeats, warmups;
        Path config;

        Inputs(Path file) throws IOException {
            this(file, Map.of());
        }

        // 多日入口只覆盖日期及参考路径，所有空间读取/校验沿用同一实现。
        Inputs(Path file, Map<String, String> overrides) throws IOException {
            config = file.toAbsolutePath().normalize();
            try (Reader reader = Files.newBufferedReader(config)) { p.load(reader); }
            overrides.forEach(p::setProperty);
            grid = AsciiGridReader.read(resolve(p.getProperty("dem")), ZeroPolicy.PRESERVE_AS_VALID);
            double cell = number("cellsize_m");
            String projectedCrs = p.getProperty("projected_crs", "");
            if (!Set.of("EPSG:32649", "EPSG:32650").contains(projectedCrs) || grid.cellSize != cell)
                throw new IllegalArgumentException("expected prepared UTM49N/50N metre grid");
            // ASCII does not store CRS; check the projected CRS in our preparation metadata.
            String metadata = Files.readString(resolve(p.getProperty("metadata")));
            java.util.regex.Matcher metadataCrs = java.util.regex.Pattern.compile(
                    "\"crs\"\\s*:\\s*\"(EPSG:326[0-9]{2})\"").matcher(metadata);
            if (!metadataCrs.find() || !metadataCrs.group(1).equals(projectedCrs) || metadataCrs.find())
                throw new IllegalArgumentException("projected CRS differs from preparation metadata");
            int r0 = integer("row_start"), c0 = integer("col_start");
            int rows = integer("rows"), cols = integer("cols");
            if (rows < 1 || cols < 1 || !grid.inside(r0, c0) || !grid.inside(r0 + rows - 1, c0 + cols - 1))
                throw new IllegalArgumentException("invalid ROI");
            Set<String> seen = new HashSet<>();
            try (BufferedReader reader = Files.newBufferedReader(resolve(p.getProperty("receivers")))) {
                if (!"row,col,latitude,longitude,true_north_grid_bearing_deg".equals(reader.readLine()))
                    throw new IllegalArgumentException("receiver CSV header mismatch");
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(",", -1);
                    if (parts.length != 5) throw new IllegalArgumentException("invalid receiver row");
                    Receiver receiver = new Receiver(parts);
                    if (receiver.row < r0 || receiver.row >= r0 + rows || receiver.col < c0 || receiver.col >= c0 + cols
                            || !grid.valid[receiver.row][receiver.col] || !seen.add(receiver.key()))
                        throw new IllegalArgumentException("receiver invalid, duplicate, or outside ROI");
                    receivers.add(receiver);
                }
            }
            if (receivers.size() != rows * cols) throw new IllegalArgumentException("receiver count mismatch");
            double spacing = number("search_spacing_m");
            int count = integer("search_samples");
            search = new SearchConfig(count, spacing);
            int margin = (int)Math.ceil(count * spacing / cell) + 1;
            if (r0 < margin || c0 < margin || grid.rows - r0 - rows < margin || grid.cols - c0 - cols < margin)
                throw new IllegalArgumentException("insufficient interpolation/search buffer");
            // Data preparation requires valid elevation over the whole retained search area.
            for (int r = 0; r < grid.rows; r++) for (int c = 0; c < grid.cols; c++)
                if (!grid.valid[r][c]) throw new IllegalArgumentException("prepared DEM has NoData");
            methods = List.of(p.getProperty("methods").split(","));
            if ((!methods.contains("fixed_1min") && p.getProperty("reference_run", "").isBlank()) || new HashSet<>(methods).size() != methods.size()
                    || !SUPPORTED.containsAll(methods)) throw new IllegalArgumentException("unsupported methods or missing fixed1");
            repeats = integer("repeats"); warmups = integer("warmups");
            if (repeats < 1 || warmups < 0) throw new IllegalArgumentException("invalid repeat settings");
            if (methods.stream().anyMatch(m -> spatialBlockSize(m) > 0)
                    && (!methods.contains("binarylocal_30min") || repeats != 3 || warmups != 1
                        || (p.getProperty("spatial_regression_run", "").isBlank()
                            && !p.getProperty("spatial_stage", "").equals("transfer"))))
                throw new IllegalArgumentException("spatial comparison requires binarylocal, one warmup, three repeats and a regression source");
            if (p.getProperty("spatial_stage", "").equals("transfer") && !new HashSet<>(methods).equals(Set.of(
                    "binarylocal_30min", "block16binarylocal_30min")))
                throw new IllegalArgumentException("transfer requires the original and frozen block16 temporal pair");
            if (p.getProperty("spatial_stage", "").equals("sensitivity") && !new HashSet<>(methods).equals(Set.of(
                    "binarylocal_30min", "block4binarylocal_30min", "block8binarylocal_30min",
                    "block16binarylocal_30min", "block32binarylocal_30min", "block64binarylocal_30min")))
                throw new IllegalArgumentException("sensitivity requires the original and all five block widths");
            if (methods.contains("block16fixed_1min") && !new HashSet<>(methods).equals(Set.of(
                    "fixed_1min", "block16fixed_1min", "binarylocal_30min", "block16binarylocal_30min")))
                throw new IllegalArgumentException("spatial ablation requires the four frozen-block methods");
            dec = TimeStepBaseline.declination(LocalDate.parse(p.getProperty("date")));
            for (String key : p.getProperty("trace_pixels", "").split(";")) {
                if (!key.isBlank()) {
                    if (!seen.contains(key)) throw new IllegalArgumentException("trace point outside receivers");
                    traces.add(key);
                }
            }
        }
        Path resolve(String name) { return config.getParent().resolve(name).normalize(); }
        int integer(String key) { return Integer.parseInt(p.getProperty(key)); }
        double number(String key) {
            double value = Double.parseDouble(p.getProperty(key));
            if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException("invalid " + key);
            return value;
        }
    }

    static final class Pixel {
        double day, psd;
        int samples, quarterProbes, refinementQueries, shortIntervals, longIntervals, guardQueries;
        String reason;
        SunlightIntervalStats.Result intervals;
        NavigableMap<Double, Integer> trace, nodes;
        NavigableMap<Double, Integer> schedule;
    }
    static final class Run {
        String method;
        double computeSeconds, writeSeconds;
        double preprocessSeconds, querySeconds;
        long tablePayloadBytes, horizonDirections, horizonSpatialSamples, horizonTimeQueries, horizonUnknownQueries;
        long skippedSpatialSamples, spatialBlockTests, spatialIndexPayloadBytes;
        List<Pixel> pixels = new ArrayList<>();
        RunStatistics stats = new RunStatistics();
        int valid, fallback;
        long quarterProbes, refinementQueries, shortIntervals, longIntervals, guardQueries;
    }

    static Pixel calculate(Inputs in, Receiver receiver, String method, RunStatistics stats) {
        return calculate(in, receiver, method, stats, null, null);
    }

    // 仅替换单时刻空间查询；所有太阳、时间调度、缓存与积分分支共用旧实现。
    static Pixel calculate(Inputs in, Receiver receiver, String method, RunStatistics stats,
            BlockUpperBoundVisibility spatialIndex, BlockUpperBoundVisibility.Counters spatialCounts) {
        int step = Integer.parseInt(method.substring(method.indexOf('_') + 1, method.length() - 3));
        Pixel pixel = new Pixel();
        pixel.day = TimeStepBaseline.daylight(receiver.lat, in.dec);
        double rise = 720 - pixel.day / 2;
        AdaptiveTimeV1.Oracle oracle = t -> {
            double[] angles = TimeStepBaseline.sun(receiver.lat, in.dec, rise + t);
            // UTM is conformal: add the projected true-north bearing to solar true azimuth.
            double gridBearing = angles[1] + receiver.north;
            VisibilityStatus status;
            if (spatialIndex == null) {
                status = ControlledVisibility.visibleAt(in.grid, in.contract, receiver.row, receiver.col,
                        Math.max(1e-7, angles[0]), gridBearing, AngleReference.GRID_NORTH_CLOCKWISE, in.search, stats).status;
            } else {
                // 与原核心同样每射线计时并调用record；算法的块计数成本不隐藏。
                long queryStart = System.nanoTime();
                VisibilityResult result = spatialIndex.visibleAt(in.contract, receiver.row, receiver.col,
                        Math.max(1e-7, angles[0]), gridBearing, AngleReference.GRID_NORTH_CLOCKWISE, in.search, spatialCounts);
                if (stats != null) stats.record(result.status, result.spatialSamples, System.nanoTime() - queryStart);
                status = result.status;
            }
            return status == VisibilityStatus.VISIBLE ? 1 : status == VisibilityStatus.SHADED ? 0 : -1;
        };
        boolean trace = in.traces.contains(receiver.key());
        if (method.startsWith("solar_") || method.startsWith("combined_") || method.startsWith("guardcombined_")) {
            SolarAdaptiveSampling.Result scheduled = method.startsWith("guardcombined_")
                    ? SolarAdaptiveSampling.adaptiveWithDarkGuard(pixel.day, oracle,
                        t -> TimeStepBaseline.sun(receiver.lat, in.dec, rise + t)[0])
                    : SolarAdaptiveSampling.adaptive(pixel.day, oracle,
                        t -> TimeStepBaseline.sun(receiver.lat, in.dec, rise + t)[0], method.startsWith("combined_"));
            AdaptiveTimeV2Local.Result result = scheduled.calculation;
            pixel.psd = result.psd; pixel.samples = result.evaluated.size(); pixel.reason = scheduled.fallbackReason;
            pixel.quarterProbes = result.quarterProbes; pixel.refinementQueries = result.refinementQueries;
            pixel.guardQueries = result.guardQueries;
            for (int selected : scheduled.selectedSteps.values()) {
                if (selected == 10) pixel.shortIntervals++; else pixel.longIntervals++;
            }
            if (trace) { pixel.trace = result.evaluated; pixel.nodes = result.integrationNodes; pixel.schedule = scheduled.selectedSteps; }
        } else if (method.startsWith("local_") || method.startsWith("guardlocal_")
                || method.equals("plainlocal_30min") || method.equals("binarylocal_30min")) {
            AdaptiveTimeV2Local.Result result = method.equals("binarylocal_30min")
                    ? BinaryLocalRefinement.adaptiveWithDarkGuard(pixel.day, step, oracle)
                    : method.equals("plainlocal_30min")
                    ? PlainLocalRefinement.adaptiveWithDarkGuard(pixel.day, step, oracle)
                    : method.startsWith("guardlocal_")
                    ? AdaptiveTimeV2Local.adaptiveWithDarkGuard(pixel.day, step, oracle)
                    : AdaptiveTimeV2Local.adaptive(pixel.day, step, oracle);
            pixel.psd = result.psd; pixel.samples = result.evaluated.size(); pixel.reason = result.fallbackReason;
            pixel.quarterProbes = result.quarterProbes; pixel.refinementQueries = result.refinementQueries;
            pixel.guardQueries = result.guardQueries;
            if (trace) { pixel.trace = result.evaluated; pixel.nodes = result.integrationNodes; }
        } else if (method.startsWith("adaptive_")) {
            AdaptiveTimeV1.Result result = AdaptiveTimeV1.adaptive(pixel.day, step, oracle);
            pixel.psd = result.psd; pixel.samples = result.evaluated.size(); pixel.reason = result.fallbackReason;
            if (trace) { pixel.trace = result.evaluated; pixel.nodes = result.integrationNodes; }
        } else {
            // Preserve the original direct array loop for the fixed baseline.
            int n = (int)Math.ceil(pixel.day / step);
            double[] times = new double[n + 1]; int[] states = new int[n + 1];
            if (trace) pixel.trace = new TreeMap<>();
            for (int i = 0; i <= n; i++) {
                times[i] = Math.min(i * (double)step, pixel.day); states[i] = oracle.at(times[i]);
                if (trace) pixel.trace.put(times[i], states[i]);
            }
            pixel.psd = TimeStepBaseline.integrate(times, states); pixel.samples = n + 1; pixel.reason = "NONE";
            // 只统计逐分钟参考；复用数组，不增加遮挡查询，不改变原积分。
            if (step == 1) pixel.intervals = SunlightIntervalStats.analyze(times, states);
            if (trace) pixel.nodes = pixel.trace;
        }
        if (Double.isFinite(pixel.psd) && (pixel.psd < -1e-8 || pixel.psd > pixel.day + 1e-8))
            throw new AssertionError("PSD outside geometric daylight");
        return pixel;
    }

    static Run run(Inputs in, String method) {
        if (method.startsWith("horizon_")) return runHorizon(in, method);
        Run run = new Run(); run.method = method;
        int blockSize = spatialBlockSize(method);
        String temporalMethod = method.equals("block16fixed_1min") ? "fixed_1min"
                : blockSize == 0 ? method : "binarylocal_30min";
        BlockUpperBoundVisibility.Counters spatialCounts = new BlockUpperBoundVisibility.Counters();
        long start = System.nanoTime(); long samples = 0;
        BlockUpperBoundVisibility spatialIndex = blockSize == 0 ? null : new BlockUpperBoundVisibility(in.grid, blockSize);
        long prepared = System.nanoTime();
        for (Receiver receiver : in.receivers) {
            Pixel pixel = calculate(in, receiver, temporalMethod, run.stats, spatialIndex, spatialCounts);
            run.pixels.add(pixel); samples += pixel.samples;
            run.quarterProbes += pixel.quarterProbes; run.refinementQueries += pixel.refinementQueries;
            run.shortIntervals += pixel.shortIntervals; run.longIntervals += pixel.longIntervals;
            run.guardQueries += pixel.guardQueries;
            if (Double.isFinite(pixel.psd)) run.valid++;
            if (!pixel.reason.equals("NONE")) run.fallback++;
        }
        long finished = System.nanoTime();
        run.computeSeconds = (finished - start) / 1e9;
        run.preprocessSeconds = blockSize == 0 ? 0 : (prepared - start) / 1e9;
        run.querySeconds = blockSize == 0 ? run.computeSeconds : (finished - prepared) / 1e9;
        run.skippedSpatialSamples = spatialCounts.skippedSamples;
        run.spatialBlockTests = spatialCounts.blockTests;
        run.spatialIndexPayloadBytes = spatialIndex == null ? 0 : spatialIndex.payloadBytes();
        if (spatialIndex != null && spatialCounts.spatialSamples != run.stats.spatialSamples)
            throw new AssertionError("spatial interpolation accounting mismatch");
        if (samples != run.stats.rayCalls || samples != run.stats.visibleResults + run.stats.shadedResults + run.stats.unknownResults)
            throw new AssertionError("query accounting mismatch");
        return run;
    }

    /** 每个重复独立建表，包含完整预处理成本；不在正式重复之间复用热表。 */
    static Run runHorizon(Inputs in, String method) {
        int step = method.equals("horizon_1deg") ? 1 : method.equals("horizon_2deg") ? 2 : 0;
        if (step == 0) throw new IllegalArgumentException("unsupported horizon method");
        Run run = new Run(); run.method = method;
        long start = System.nanoTime();
        List<HorizonBaseline.Table> tables = new ArrayList<>(in.receivers.size());
        for (Receiver receiver : in.receivers) {
            HorizonBaseline.Table table = HorizonBaseline.build(in.grid, receiver.row, receiver.col, in.search, step);
            tables.add(table);
            run.tablePayloadBytes += table.payloadBytes();
            run.horizonDirections += table.directions();
            run.horizonSpatialSamples += table.spatialSamples;
        }
        long prepared = System.nanoTime();
        Run result = queryHorizon(in, method, tables);
        long finished = System.nanoTime();
        result.tablePayloadBytes = run.tablePayloadBytes;
        result.horizonDirections = run.horizonDirections;
        result.horizonSpatialSamples = run.horizonSpatialSamples;
        result.preprocessSeconds = (prepared-start)/1e9;
        result.querySeconds = (finished-prepared)/1e9;
        result.computeSeconds = (finished-start)/1e9;
        return result;
    }

    /** 查已有表；多日入口把共享建表成本单独记一次。 */
    static Run queryHorizon(Inputs in, String method, List<HorizonBaseline.Table> tables) {
        if (tables.size() != in.receivers.size()) throw new IllegalArgumentException("table/receiver count mismatch");
        long start = System.nanoTime();
        Run run = new Run(); run.method = method;
        for (int i = 0; i < in.receivers.size(); i++) {
            Receiver receiver = in.receivers.get(i);
            HorizonBaseline.Day day = HorizonBaseline.day(tables.get(i), receiver.lat, in.dec, receiver.north);
            Pixel pixel = new Pixel();
            pixel.day = day.times[day.times.length-1]; pixel.psd = day.psdMinutes;
            pixel.samples = day.timeQueries(); pixel.reason = "NONE";
            run.horizonTimeQueries += day.timeQueries();
            for (int state : day.states) if (state < 0) run.horizonUnknownQueries++;
            if (in.traces.contains(receiver.key())) {
                pixel.trace = new TreeMap<>();
                for (int j = 0; j < day.times.length; j++) pixel.trace.put(day.times[j], day.states[j]);
                pixel.nodes = pixel.trace;
            }
            if (Double.isFinite(pixel.psd)) run.valid++;
            run.pixels.add(pixel);
        }
        run.querySeconds = (System.nanoTime()-start)/1e9;
        run.computeSeconds = run.querySeconds;
        return run;
    }

    static long unknownQueries(Run run) { return run.stats.unknownResults + run.horizonUnknownQueries; }

    static void write(Inputs in, Run run, Path output) throws IOException {
        long start = System.nanoTime();
        Files.createDirectory(output);
        Map<String, Integer> reasons = new TreeMap<>();
        try (PrintWriter ps = new PrintWriter(Files.newBufferedWriter(output.resolve("psd.csv")));
             PrintWriter traces = new PrintWriter(Files.newBufferedWriter(output.resolve("samples.csv")));
             PrintWriter schedule = new PrintWriter(Files.newBufferedWriter(output.resolve("schedule.csv")));
             PrintWriter fallback = new PrintWriter(Files.newBufferedWriter(output.resolve("fallback_summary.csv")))) {
            ps.println("row,col,latitude,longitude,elevation_m,daylight_minutes,psd_minutes,sample_count,fallback_reason,quarter_probes,refinement_queries,short_intervals,long_intervals,guard_queries");
            traces.println("row,col,minutes_since_sunrise,state,used_for_integration");
            schedule.println("row,col,start_minute,end_minute,selected_step_minutes");
            fallback.println("reason,pixels");
            for (int i = 0; i < run.pixels.size(); i++) {
                Receiver receiver = in.receivers.get(i); Pixel pixel = run.pixels.get(i);
                ps.printf("%d,%d,%.12f,%.12f,%.6f,%.12f,%.12f,%d,%s,%d,%d,%d,%d,%d%n", receiver.row, receiver.col,
                        receiver.lat, receiver.lon, in.grid.elevation[receiver.row][receiver.col], pixel.day, pixel.psd, pixel.samples, pixel.reason,
                        pixel.quarterProbes, pixel.refinementQueries, pixel.shortIntervals, pixel.longIntervals, pixel.guardQueries);
                if (pixel.schedule != null) for (Map.Entry<Double,Integer> entry : pixel.schedule.entrySet())
                    schedule.printf("%d,%d,%.12f,%.12f,%d%n", receiver.row, receiver.col, entry.getKey(),
                            Math.min(entry.getKey() + entry.getValue(), pixel.day), entry.getValue());
                if (!pixel.reason.equals("NONE")) reasons.merge(pixel.reason, 1, Integer::sum);
                if (pixel.trace != null) for (Map.Entry<Double, Integer> entry : pixel.trace.entrySet())
                    traces.printf("%d,%d,%.12f,%s,%d%n", receiver.row, receiver.col, entry.getKey(),
                            entry.getValue() < 0 ? "U" : entry.getValue().toString(), pixel.nodes.containsKey(entry.getKey()) ? 1 : 0);
            }
            for (Map.Entry<String, Integer> entry : reasons.entrySet()) fallback.println(entry.getKey() + "," + entry.getValue());
        }
        if (run.method.equals("fixed_1min")) {
            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(output.resolve("intervals.csv")))) {
                writer.println("row,col,category,light_intervals,transitions,first_boundary_min,last_boundary_min,span_minutes,psd_minutes,internal_shade_minutes");
                for (int i = 0; i < run.pixels.size(); i++) {
                    Receiver receiver = in.receivers.get(i);
                    SunlightIntervalStats.Result s = run.pixels.get(i).intervals;
                    writer.printf("%d,%d,%s,%d,%d,%.12f,%.12f,%.12f,%.12f,%.12f%n",
                            receiver.row, receiver.col, s.category(), s.lightIntervals(), s.transitions(),
                            s.firstBoundary(), s.lastBoundary(), s.spanMinutes(), s.psdMinutes(), s.internalShadeMinutes());
                }
            }
        }
        run.writeSeconds = (System.nanoTime() - start) / 1e9;
        Files.writeString(output.resolve("runtime.json"), String.format(
                "{\"method\":\"%s\",\"compute_sec\":%.9f,\"csv_write_sec\":%.9f,\"rayCalls\":%d,"
                        + "\"spatialSamples\":%d,\"unknown_count\":%d,\"valid_pixels\":%d,\"fallback_pixels\":%d,\"quarter_probes\":%d,\"refinement_queries\":%d,\"short_intervals\":%d,\"long_intervals\":%d,\"guard_queries\":%d,"
                        + "\"preprocess_sec\":%.9f,\"query_sec\":%.9f,\"table_payload_bytes\":%d,\"horizon_directions\":%d,\"horizon_spatial_samples\":%d,\"horizon_time_queries\":%d}%n",
                run.method, run.computeSeconds, run.writeSeconds, run.stats.rayCalls, run.stats.spatialSamples,
                unknownQueries(run), run.valid, run.fallback, run.quarterProbes, run.refinementQueries,
                run.shortIntervals, run.longIntervals, run.guardQueries, run.preprocessSeconds, run.querySeconds,
                run.tablePayloadBytes, run.horizonDirections, run.horizonSpatialSamples, run.horizonTimeQueries));
        if (in.methods.stream().anyMatch(m -> spatialBlockSize(m) > 0)) {
            Files.writeString(output.resolve("spatial_runtime.json"), String.format(
                    "{\"block_size\":%d,\"skipped_samples\":%d,\"block_tests\":%d,\"index_payload_bytes\":%d}%n",
                    spatialBlockSize(run.method), run.skippedSpatialSamples, run.spatialBlockTests, run.spatialIndexPayloadBytes));
        }
    }

    static void consistent(Run first, Run next) {
        if (first.skippedSpatialSamples != next.skippedSpatialSamples || first.spatialBlockTests != next.spatialBlockTests
                || first.spatialIndexPayloadBytes != next.spatialIndexPayloadBytes)
            throw new AssertionError("repeat spatial counters differ");
        if (first.stats.rayCalls != next.stats.rayCalls || first.stats.spatialSamples != next.stats.spatialSamples)
            throw new AssertionError("repeat query counts differ");
        if (first.tablePayloadBytes != next.tablePayloadBytes || first.horizonDirections != next.horizonDirections
                || first.horizonSpatialSamples != next.horizonSpatialSamples || first.horizonTimeQueries != next.horizonTimeQueries
                || unknownQueries(first) != unknownQueries(next))
            throw new AssertionError("repeat horizon counts differ");
        for (int i = 0; i < first.pixels.size(); i++) {
            Pixel a = first.pixels.get(i), b = next.pixels.get(i);
            if (!Objects.equals(a.intervals, b.intervals))
                throw new AssertionError("repeat interval statistics differ");
            if (Double.doubleToLongBits(a.psd) != Double.doubleToLongBits(b.psd)
                    || a.samples != b.samples || !a.reason.equals(b.reason)
                    || a.quarterProbes != b.quarterProbes || a.refinementQueries != b.refinementQueries
                    || a.shortIntervals != b.shortIntervals || a.longIntervals != b.longIntervals || a.guardQueries != b.guardQueries)
                throw new AssertionError("repeat pixel outputs differ");
        }
    }

    static Run loadReference(Inputs in) throws IOException {
        Path root = in.resolve(in.p.getProperty("reference_run"));
        Properties previous = new Properties();
        try (Reader reader = Files.newBufferedReader(root.resolve("config.properties"))) { previous.load(reader); }
        for (String key : List.of("projected_crs", "cellsize_m", "row_start", "col_start",
                "rows", "cols", "date", "search_spacing_m", "search_samples", "elevation_unit_assumption")) {
            if (!Objects.equals(previous.getProperty(key), in.p.getProperty(key)))
                throw new IllegalArgumentException("reference configuration differs: " + key);
        }
        if (Files.mismatch(root.resolve("receivers.csv"), in.resolve(in.p.getProperty("receivers"))) != -1
                || Files.mismatch(root.resolve("data_metadata.json"), in.resolve(in.p.getProperty("metadata"))) != -1)
            throw new IllegalArgumentException("reference data snapshots differ");
        String metadata = Files.readString(root.resolve("data_metadata.json"));
        java.util.regex.Matcher hash = java.util.regex.Pattern.compile("\"dem\\.asc\"\\s*:\\s*\"([a-f0-9]{64})\"").matcher(metadata);
        if (!hash.find()) throw new IllegalArgumentException("reference DEM fingerprint missing");
        try {
            String actual = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(in.resolve(in.p.getProperty("dem")))));
            if (!hash.group(1).equals(actual)) throw new IllegalArgumentException("reference DEM fingerprint differs");
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        Run reference = new Run(); reference.method = "fixed_1min";
        try (BufferedReader reader = Files.newBufferedReader(root.resolve("fixed_1min_r1/psd.csv"))) {
            String header = reader.readLine();
            if (header == null || !header.startsWith("row,col,latitude,longitude,elevation_m,daylight_minutes,psd_minutes,"))
                throw new IllegalArgumentException("reference PSD header mismatch");
            for (Receiver receiver : in.receivers) {
                String line = reader.readLine();
                if (line == null) throw new IllegalArgumentException("reference PSD missing pixels");
                String[] p = line.split(",", -1);
                if (p.length < 9 || Integer.parseInt(p[0]) != receiver.row || Integer.parseInt(p[1]) != receiver.col
                        || Math.abs(Double.parseDouble(p[5]) - TimeStepBaseline.daylight(receiver.lat, in.dec)) > 1e-8
                        || Math.abs(Double.parseDouble(p[4]) - in.grid.elevation[receiver.row][receiver.col]) > 1e-6)
                    throw new IllegalArgumentException("reference pixel mismatch");
                Pixel pixel = new Pixel(); pixel.psd = Double.parseDouble(p[6]); reference.pixels.add(pixel);
            }
            if (reader.readLine() != null) throw new IllegalArgumentException("reference PSD extra pixels");
        }
        return reference;
    }

    static void summarize(Inputs in, Map<String, List<Run>> results, Run reference, Path output) throws IOException {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(output.resolve("summary.csv")))) {
            writer.println("method,runtime_sec,runtime_min_sec,runtime_max_sec,repeats,rayCalls,spatialSamples,valid_pixels,"
                    + "unknown_count,fallback_pixels,common_valid_pixels,MAE_min,MAX_abs_error_min,error_gt1_ratio,quarter_probes,refinement_queries,short_intervals,long_intervals,guard_queries,"
                    + "preprocess_sec,query_sec,table_payload_bytes,horizon_directions,horizon_spatial_samples,horizon_time_queries");
            for (String method : in.methods) {
                List<Run> runs = results.get(method); Run first = runs.get(0);
                double[] seconds = runs.stream().mapToDouble(r -> r.computeSeconds).sorted().toArray();
                double median = (seconds[(seconds.length - 1) / 2] + seconds[seconds.length / 2]) / 2;
                double sum = 0, max = 0; int common = 0, over = 0;
                for (int i = 0; i < first.pixels.size(); i++) {
                    double a = first.pixels.get(i).psd, b = reference.pixels.get(i).psd;
                    if (Double.isFinite(a) && Double.isFinite(b)) {
                        double error = Math.abs(a - b); sum += error; max = Math.max(max, error); common++;
                        if (error > 1 + 1e-9) over++;
                    }
                }
                writer.printf("%s,%.9f,%.9f,%.9f,%d,%d,%d,%d,%d,%d,%d,%.12f,%.12f,%.12f,%d,%d,%d,%d,%d,%.9f,%.9f,%d,%d,%d,%d%n",
                        method, median, seconds[0], seconds[seconds.length - 1], runs.size(), first.stats.rayCalls,
                        first.stats.spatialSamples, first.valid, unknownQueries(first), first.fallback, common,
                        common == 0 ? Double.NaN : sum / common, common == 0 ? Double.NaN : max,
                        common == 0 ? Double.NaN : over / (double)common, first.quarterProbes, first.refinementQueries,
                        first.shortIntervals, first.longIntervals, first.guardQueries,
                        median(runs.stream().mapToDouble(r -> r.preprocessSeconds).toArray()),
                        median(runs.stream().mapToDouble(r -> r.querySeconds).toArray()),
                        first.tablePayloadBytes, first.horizonDirections, first.horizonSpatialSamples, first.horizonTimeQueries);
            }
        }
    }

    private static double median(double[] values) {
        Arrays.sort(values);
        return (values[(values.length-1)/2] + values[values.length/2])/2;
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        boolean check = args.length == 2 && args[0].equals("--check-input");
        if (args.length != 2) throw new IllegalArgumentException("--check-input config.properties OR config.properties new-output-directory");
        long loadStart = System.nanoTime();
        Inputs in = new Inputs(Path.of(args[check ? 1 : 0]));
        Run reference = in.p.getProperty("reference_run", "").isBlank() ? null : loadReference(in);
        double loadSeconds = (System.nanoTime() - loadStart) / 1e9;
        System.out.printf("INPUT_READY pixels=%d grid=%dx%d cell_m=%.1f date=%s methods=%s%n", in.receivers.size(),
                in.grid.rows, in.grid.cols, in.grid.cellSize, in.p.getProperty("date"), in.methods);
        if (check) { System.out.println("INPUT_CHECK_PASS no_sunshine_computation"); return; }
        if (reference != null) System.out.println("REFERENCE_REUSED fixed_1min pixels=" + reference.pixels.size());
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        Path allowed = Path.of("user_runs").toAbsolutePath().normalize();
        if (!output.startsWith(allowed) || output.equals(allowed)) throw new IllegalArgumentException("output must be a new user_runs subdirectory");
        Files.createDirectories(output.getParent()); Files.createDirectory(output);
        Files.copy(in.config, output.resolve("config.properties"));
        if (reference != null) Files.writeString(output.resolve("reference_source.txt"),
                in.resolve(in.p.getProperty("reference_run")).toString() + "\nfixed_1min_r1/psd.csv\n");
        Files.copy(in.resolve(in.p.getProperty("receivers")), output.resolve("receivers.csv"));
        Files.copy(in.resolve(in.p.getProperty("metadata")), output.resolve("data_metadata.json"));
        Files.writeString(output.resolve("input_paths.txt"), "config=" + in.config + "\ndem="
                + in.resolve(in.p.getProperty("dem")) + "\nreceivers=" + in.resolve(in.p.getProperty("receivers")) + "\n");
        Files.writeString(output.resolve("input_load.json"), String.format("{\"input_load_sec\":%.9f}%n", loadSeconds));
        boolean spatialComparison = in.methods.stream().anyMatch(m -> spatialBlockSize(m) > 0);
        if (spatialComparison) SpatialBlockComparison.manifest(in, output);
        Map<String, List<Run>> results = new LinkedHashMap<>();
        for (String method : in.methods) results.put(method, new ArrayList<>());
        try (PrintWriter log = new PrintWriter(Files.newBufferedWriter(output.resolve("execution.log")))) {
            for (int i = 0; i < in.warmups; i++) for (String method : in.methods) {
                Run warmup = run(in, method);
                log.println("WARMUP_DONE " + method + " rays=" + warmup.stats.rayCalls); log.flush();
                System.out.println("WARMUP_DONE " + method);
            }
            Random random = new Random(20261024);
            for (int repeat = 1; repeat <= in.repeats; repeat++) {
                List<String> order = new ArrayList<>(in.methods); Collections.shuffle(order, random);
                log.println("ORDER repeat=" + repeat + " " + order); log.flush();
                for (String method : order) {
                    Run result = run(in, method);
                    if (!results.get(method).isEmpty()) consistent(results.get(method).get(0), result);
                    write(in, result, output.resolve(method + "_r" + repeat));
                    results.get(method).add(result);
                    String line = String.format("FORMAL_DONE %s r%d compute_sec=%.6f rays=%d valid=%d unknown=%d preprocess_sec=%.6f query_sec=%.6f horizon_queries=%d",
                            method, repeat, result.computeSeconds, result.stats.rayCalls, result.valid, unknownQueries(result),
                            result.preprocessSeconds, result.querySeconds, result.horizonTimeQueries);
                    System.out.println(line); log.println(line); log.flush();
                }
            }
            summarize(in, results, reference == null ? results.get("fixed_1min").get(0) : reference, output);
            if (spatialComparison) {
                String decision = SpatialBlockComparison.write(in, results, output);
                System.out.println(decision); log.println(decision); log.flush();
            }
            log.println("EXPERIMENT_COMPLETE repeats_consistent=true");
        }
        System.out.println("EXPERIMENT_COMPLETE summary=" + output.resolve("summary.csv"));
    }
}
