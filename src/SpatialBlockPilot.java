import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** 用户运行：人工几何回归＋少量真实方向射线。不是全天PSD或正式性能实验。 */
public final class SpatialBlockPilot {
    private static int checks;
    private static void require(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static AsciiGrid grid(double[][] values) {
        return new AsciiGrid(values.length, values[0].length, 0, 0, 1, -9999,
                values, ZeroPolicy.PRESERVE_AS_VALID);
    }
    private static boolean same(VisibilityResult a, VisibilityResult b) {
        return a.status == b.status && a.searchedDistanceGridUnits == b.searchedDistanceGridUnits;
    }
    private static double[][] flatValues(int rows, int cols, double height) {
        double[][] values = new double[rows][cols];
        for (double[] row : values) Arrays.fill(row, height);
        return values;
    }
    private static void boundaryCase(PrintWriter log, String name, double[][] values, int row, int col,
            SearchConfig search, double altitude, double bearing, VisibilityStatus expected, double stop) {
        AsciiGrid g = grid(values);
        VisibilityResult original = ControlledVisibility.visibleAt(g, TimeStepBaseline.contract(), row, col,
                altitude, bearing, AngleReference.GRID_NORTH_CLOCKWISE, search, null);
        require(original.status == expected && original.searchedDistanceGridUnits == stop,
                name + " independent expected status/stop");
        for (int size : new int[]{8,16,32}) {
            BlockUpperBoundVisibility index = new BlockUpperBoundVisibility(g, size);
            BlockUpperBoundVisibility.Counters count = new BlockUpperBoundVisibility.Counters();
            VisibilityResult filtered = index.visibleAt(TimeStepBaseline.contract(),row,col,altitude,bearing,
                    AngleReference.GRID_NORTH_CLOCKWISE,search,count);
            // 先落盘，断言失败时仍有该配对的完整信息。
            log.printf("BOUNDARY name=%s block=%d expected=%s original=%s filtered=%s original_stop=%.12f filtered_stop=%.12f original_samples=%d filtered_samples=%d skipped=%d blocks=%d%n",
                    name,size,expected,original.status,filtered.status,original.searchedDistanceGridUnits,
                    filtered.searchedDistanceGridUnits,original.spatialSamples,filtered.spatialSamples,
                    count.skippedSamples,count.blockTests);
            log.flush();
            require(same(original,filtered),name + " block=" + size + " status/stop");
            require(original.spatialSamples == filtered.spatialSamples + count.skippedSamples,
                    name + " sample accounting");
            require(count.spatialSamples == filtered.spatialSamples,name + " interpolation accounting");
        }
    }
    private static void boundaries(PrintWriter log) {
        SearchConfig twenty = new SearchConfig(20,1);
        double[][] ridge = flatValues(67,69,100);
        Arrays.fill(ridge[32],120);
        boundaryCase(log,"ridge_on_block_boundary",ridge,48,40,twenty,5,0,VisibilityStatus.SHADED,16);
        // 首样本row=31.5；其插值依赖下一块row=32的山脊节点。
        boundaryCase(log,"ridge_in_interpolation_halo",ridge,33,40,new SearchConfig(10,1.5),5,0,
                VisibilityStatus.SHADED,1.5);

        double[][] tangent = flatValues(67,69,100);
        tangent[44][40] = 100 + 4 * Math.tan(Math.toRadians(30));
        boundaryCase(log,"exact_tangent",tangent,48,40,twenty,30,0,VisibilityStatus.VISIBLE,20);
        boundaryCase(log,"below_tangent",tangent,48,40,twenty,30-1e-6,0,VisibilityStatus.SHADED,4);
        boundaryCase(log,"above_tangent",tangent,48,40,twenty,30+1e-6,0,VisibilityStatus.VISIBLE,20);
        boundaryCase(log,"negative_elevation",flatValues(67,69,-200),48,40,twenty,5,0,
                VisibilityStatus.VISIBLE,20);

        double[][] unknownFirst = flatValues(67,69,100);
        unknownFirst[46][40] = -9999; unknownFirst[44][40] = 200;
        boundaryCase(log,"nodata_before_shade",unknownFirst,48,40,twenty,5,0,
                VisibilityStatus.UNKNOWN_RAY_INVALID,2);
        double[][] shadeFirst = flatValues(67,69,100);
        shadeFirst[46][40] = 200; shadeFirst[44][40] = -9999;
        boundaryCase(log,"shade_before_nodata",shadeFirst,48,40,twenty,5,0,VisibilityStatus.SHADED,2);

        double[][] edge = flatValues(35,37,100);
        boundaryCase(log,"partial_last_block",edge,30,18,new SearchConfig(4,1),5,180,
                VisibilityStatus.VISIBLE,4);
        boundaryCase(log,"first_outside_sample",edge,30,18,new SearchConfig(5,1),5,180,
                VisibilityStatus.UNKNOWN_OUT_OF_BOUNDS,5);
        boundaryCase(log,"near_axis",flatValues(67,69,100),48,40,twenty,5,1e-10,
                VisibilityStatus.VISIBLE,20);
    }
    private static void synthetic(PrintWriter log) {
        Random random = new Random(20261029);
        long skipped = 0;
        EnumSet<VisibilityStatus> statuses = EnumSet.noneOf(VisibilityStatus.class);
        // 平地、斜坡、窄山脊、随机起伏、NoData、部分边缘块。
        for (int scene = 0; scene < 5; scene++) {
            double[][] values = new double[35][37];
            for (int r = 0; r < values.length; r++) for (int c = 0; c < values[0].length; c++)
                values[r][c] = scene == 1 ? 100 + .5 * r - .2 * c
                        : scene == 3 ? 100 + random.nextDouble() * 20 : 100;
            if (scene == 2) for (int c = 3; c < 34; c++) values[11][c] = 115;
            if (scene == 4) { values[12][18] = -9999; values[16][18] = -9999; }
            AsciiGrid g = grid(values);
            for (int size : new int[]{1, 4, 8, 16}) {
                BlockUpperBoundVisibility index = new BlockUpperBoundVisibility(g, size);
                for (int[] receiver : new int[][]{{17,18},{1,1},{33,35},{12,18}})
                    for (double spacing : new double[]{.5,1,1.7})
                        for (double angle : new double[]{1e-7,5,26.56505117707799,45,89})
                            for (double bearing : new double[]{0,1e-10,17.3,45,90,135,180,225,270,315,359.9999999999,-90,450}) {
                                SearchConfig search = new SearchConfig(24, spacing);
                                VisibilityResult original = ControlledVisibility.visibleAt(g, TimeStepBaseline.contract(),
                                        receiver[0], receiver[1], angle, bearing, AngleReference.GRID_NORTH_CLOCKWISE, search, null);
                                BlockUpperBoundVisibility.Counters count = new BlockUpperBoundVisibility.Counters();
                                VisibilityResult filtered = index.visibleAt(TimeStepBaseline.contract(), receiver[0], receiver[1],
                                        angle, bearing, AngleReference.GRID_NORTH_CLOCKWISE, search, count);
                                require(same(original, filtered), "synthetic status/distance scene=" + scene + " size=" + size
                                        + " receiver=" + Arrays.toString(receiver) + " spacing=" + spacing
                                        + " altitude=" + angle + " bearing=" + bearing);
                                require(original.spatialSamples == filtered.spatialSamples + count.skippedSamples,
                                        "synthetic sample accounting");
                                require(count.spatialSamples == filtered.spatialSamples, "interpolation accounting");
                                statuses.add(original.status);
                                skipped += count.skippedSamples;
                            }
            }
        }
        require(skipped > 0, "synthetic must exercise pruning");
        require(statuses.equals(EnumSet.allOf(VisibilityStatus.class)), "all five statuses exercised: " + statuses);
        BlockUpperBoundVisibility flat = new BlockUpperBoundVisibility(grid(new double[5][5]), 2);
        for (double angle : new double[]{0,90,Double.NaN}) {
            boolean rejected = false;
            try { flat.visibleAt(TimeStepBaseline.contract(),2,2,angle,0,AngleReference.GRID_NORTH_CLOCKWISE,
                    new SearchConfig(1,1),new BlockUpperBoundVisibility.Counters()); }
            catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected, "invalid altitude");
        }
        boolean rejected = false;
        try { new BlockUpperBoundVisibility(grid(new double[5][5]), 0); }
        catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "invalid block size");
        boundaries(log);
        log.println("SPATIAL_BLOCK_SYNTHETIC_PASS checks=" + checks + " skipped_samples=" + skipped);
        log.flush();
    }

    private static final class Query {
        int row, col; double altitude, bearing;
        Query(int row, int col, double altitude, double bearing) {
            this.row=row; this.col=col; this.altitude=altitude; this.bearing=bearing;
        }
    }
    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static LinkedHashMap<String, ProjectedBaselineExperiment.Receiver> selectReceivers(
            ProjectedBaselineExperiment.Inputs in) {
        int rows = in.integer("rows"), cols = in.integer("cols");
        if (rows < 4 || cols < 4 || in.traces.size() > 4)
            throw new IllegalArgumentException("pilot requires at least 4x4 ROI and at most four trace points");
        Map<String, ProjectedBaselineExperiment.Receiver> lookup = new HashMap<>();
        for (ProjectedBaselineExperiment.Receiver receiver : in.receivers) lookup.put(receiver.key(),receiver);
        LinkedHashMap<String, ProjectedBaselineExperiment.Receiver> selected = new LinkedHashMap<>();
        // 分区中心落在两格之间时取较大索引；128范围偏移为16、48、80、112。
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) {
            int row = in.integer("row_start") + (int)((2L*r+1)*rows/8);
            int col = in.integer("col_start") + (int)((2L*c+1)*cols/8);
            String key = row + "," + col;
            ProjectedBaselineExperiment.Receiver receiver = lookup.get(key);
            if (receiver == null) throw new IllegalArgumentException("missing partition centre " + key);
            selected.put(key,receiver);
        }
        require(selected.size() == 16,"sixteen unique partition centres");
        for (String key : new TreeSet<>(in.traces)) {
            ProjectedBaselineExperiment.Receiver receiver = lookup.get(key);
            if (receiver == null) throw new IllegalArgumentException("missing trace point " + key);
            selected.put(key,receiver);
        }
        return selected;
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length != 2) throw new IllegalArgumentException("config output_directory");
        Path config = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectory(output); // 已存在即拒绝，保留旧结果。
        try (PrintWriter log = new PrintWriter(Files.newBufferedWriter(output.resolve("checks.log")))) {
            try {
                log.println("SPATIAL_BLOCK_PILOT_STARTED diagnostic_only no_daily_psd no_formal_speedup_claim");
                log.flush();
                // 人工用例失败时也保留本次配置和源代码身份。
                try (PrintWriter manifest = new PrintWriter(Files.newBufferedWriter(output.resolve("manifest.txt")))) {
                    manifest.println("pilot_config=" + config + " sha256=" + sha256(config));
                    for (String name : new String[]{"ControlledVisibility","BlockUpperBoundVisibility","SpatialBlockPilot",
                            "AsciiGrid","SearchConfig","TimeStepBaseline","ProjectedBaselineExperiment"}) {
                        Path file = Path.of("src",name + ".java").toAbsolutePath();
                        manifest.println("source=" + file + " sha256=" + sha256(file));
                    }
                }
                synthetic(log);
                int syntheticChecks = checks;
                Properties p = new Properties();
                try (Reader reader = Files.newBufferedReader(config)) { p.load(reader); }
                Path base = config.getParent().resolve(p.getProperty("base_config")).normalize();
                ProjectedBaselineExperiment.Inputs in = new ProjectedBaselineExperiment.Inputs(base);
                LinkedHashMap<String, ProjectedBaselineExperiment.Receiver> selected = selectReceivers(in);
                List<Query> queries = new ArrayList<>();
                for (ProjectedBaselineExperiment.Receiver receiver : selected.values())
                    for (double altitude : new double[]{1e-7,5,15,30,60,89})
                        for (int bearing = 0; bearing < 360; bearing += 10)
                            queries.add(new Query(receiver.row, receiver.col, altitude, bearing));

                try (PrintWriter manifest = new PrintWriter(Files.newBufferedWriter(output.resolve("manifest.txt"),StandardOpenOption.APPEND))) {
                    manifest.println("base_config=" + base + " sha256=" + sha256(base));
                    for (String key : new String[]{"dem","receivers","metadata"}) {
                        Path file = in.resolve(in.p.getProperty(key));
                        manifest.println(key + "=" + file + " sha256=" + sha256(file));
                    }
                    manifest.println("receiver_selection=4x4_partition_centres_then_sorted_trace_points_deduplicated");
                    manifest.println("receivers=" + selected.keySet());
                    manifest.println("query_count=" + queries.size() + " spacing=" + in.search.sampleSpacingGridUnits
                            + " max_samples=" + in.search.maxSamples);
                    manifest.println("angles=synthetic_direction_grid_not_daily_solar_trajectory");
                    manifest.println("timing=one_diagnostic_pass_baseline_first_no_warmup_no_repeats_not_speed_ranking");
                    manifest.println("index_payload=primitive_array_elements_only_not_peak_memory");
                }

                VisibilityResult[] reference = new VisibilityResult[queries.size()];
                long baselineSamples = 0, start = System.nanoTime();
                for (int i = 0; i < queries.size(); i++) {
                    Query q = queries.get(i);
                    reference[i] = ControlledVisibility.visibleAt(in.grid,in.contract,q.row,q.col,q.altitude,q.bearing,
                            AngleReference.GRID_NORTH_CLOCKWISE,in.search,null);
                    baselineSamples += reference[i].spatialSamples;
                }
                double baselineSeconds = (System.nanoTime() - start) / 1e9;
                int mismatches = 0;
                try (PrintWriter summary = new PrintWriter(Files.newBufferedWriter(output.resolve("summary.csv")));
                     PrintWriter pairs = new PrintWriter(Files.newBufferedWriter(output.resolve("pairs.csv")))) {
                    summary.println("method,block_size,queries,preprocess_sec,query_sec,total_sec,spatial_samples,skipped_samples,block_tests,index_payload_bytes,mismatches");
                    summary.printf("original,0,%d,0,%.9f,%.9f,%d,0,0,0,0%n",queries.size(),baselineSeconds,baselineSeconds,baselineSamples);
                    pairs.println("block_size,row,col,altitude,bearing,original_status,filtered_status,original_stop,filtered_stop,original_samples,filtered_samples,skipped_samples,block_tests,match");
                    for (String raw : p.getProperty("block_sizes").split(",")) {
                        int size = Integer.parseInt(raw.trim());
                        start = System.nanoTime();
                        BlockUpperBoundVisibility index = new BlockUpperBoundVisibility(in.grid,size);
                        double preprocess = (System.nanoTime() - start) / 1e9;
                        BlockUpperBoundVisibility.Counters total = new BlockUpperBoundVisibility.Counters();
                        // 计时只包围查询；逐条CSV及结果核对在计时后完成。
                        VisibilityResult[] filtered = new VisibilityResult[queries.size()];
                        long[] skips = new long[queries.size()], blocks = new long[queries.size()];
                        start = System.nanoTime();
                        for (int i = 0; i < queries.size(); i++) {
                            Query q = queries.get(i);
                            long oldSkips = total.skippedSamples, oldBlocks = total.blockTests;
                            filtered[i] = index.visibleAt(in.contract,q.row,q.col,q.altitude,q.bearing,
                                    AngleReference.GRID_NORTH_CLOCKWISE,in.search,total);
                            skips[i] = total.skippedSamples - oldSkips; blocks[i] = total.blockTests - oldBlocks;
                        }
                        double seconds = (System.nanoTime() - start) / 1e9;
                        int bad = 0;
                        for (int i = 0; i < queries.size(); i++) {
                            Query q = queries.get(i); VisibilityResult a = reference[i], b = filtered[i];
                            boolean match = same(a,b) && a.spatialSamples == b.spatialSamples + skips[i];
                            if (!match) bad++;
                            pairs.printf("%d,%d,%d,%.12f,%.12f,%s,%s,%.9f,%.9f,%d,%d,%d,%d,%s%n",
                                    size,q.row,q.col,q.altitude,q.bearing,a.status,b.status,
                                    a.searchedDistanceGridUnits,b.searchedDistanceGridUnits,a.spatialSamples,b.spatialSamples,
                                    skips[i],blocks[i],match);
                        }
                        summary.printf("block_upper,%d,%d,%.9f,%.9f,%.9f,%d,%d,%d,%d,%d%n",size,queries.size(),
                                preprocess,seconds,preprocess+seconds,total.spatialSamples,total.skippedSamples,
                                total.blockTests,index.payloadBytes(),bad);
                        log.println("REAL_RAYS block_size=" + size + " queries=" + queries.size() + " mismatches=" + bad
                                + " spatial_samples=" + total.spatialSamples + " skipped_samples=" + total.skippedSamples);
                        log.flush(); mismatches += bad;
                    }
                }
                require(mismatches == 0,"real ray mismatches=" + mismatches);
                String done = "SPATIAL_BLOCK_PILOT_PASS synthetic_checks=" + syntheticChecks + " total_checks=" + checks + " real_queries_per_method="
                        + queries.size() + " mismatches=" + mismatches + " diagnostic_only";
                log.println(done); log.flush(); System.out.println(done);
                System.out.println("OUTPUT=" + output);
            } catch (Exception | AssertionError failure) {
                log.println("SPATIAL_BLOCK_PILOT_FAILED " + failure);
                failure.printStackTrace(log); log.flush();
                throw failure;
            }
        }
    }
}
