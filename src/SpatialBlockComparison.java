import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** S2选型、S3消融及S4冻结参数迁移；程序完成与方法贡献分别判断。 */
final class SpatialBlockComparison {
    private static final String ORIGINAL = "binarylocal_30min";

    private static void fingerprint(PrintWriter writer, String key, Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = new byte[65536]; int count;
            while ((count = input.read(bytes)) != -1) digest.update(bytes,0,count);
        }
        writer.println(key + "=" + file.toAbsolutePath().normalize() + " sha256=" + HexFormat.of().formatHex(digest.digest()));
    }

    static void manifest(ProjectedBaselineExperiment.Inputs in, Path output) throws Exception {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(output.resolve("spatial_manifest.txt")))) {
            fingerprint(writer,"config",in.config);
            for (String key : new String[]{"dem","receivers","metadata"})
                fingerprint(writer,key,in.resolve(in.p.getProperty(key)));
            fingerprint(writer,"fixed1_reference",in.resolve(in.p.getProperty("reference_run")).resolve("fixed_1min_r1/psd.csv"));
            if (!in.p.getProperty("spatial_regression_run", "").isBlank())
                fingerprint(writer,"binary_regression",in.resolve(in.p.getProperty("spatial_regression_run")).resolve(ORIGINAL+"_r1/psd.csv"));
            else writer.println("binary_regression=not_available_for_this_scene; original_recomputed_in_same_batch");
            for (String name : new String[]{"ProjectedBaselineExperiment","SpatialBlockComparison","BlockUpperBoundVisibility",
                    "ControlledVisibility","BinaryLocalRefinement","AdaptiveTimeV2Local","AdaptiveTimeV1","TimeStepBaseline","RunStatistics"})
                fingerprint(writer,"source",Path.of("src",name+".java"));
            for (String name : new String[]{"ProjectedBaselineExperiment","SpatialBlockComparison","BlockUpperBoundVisibility"})
                fingerprint(writer,"class",Path.of("user_runs/.classes",name+".class"));
            writer.println("cost=preprocess_plus_query_per_independent_run; input_load_and_output_separate");
            writer.println("protocol=warmups_1_formal_3_seed_20261024; warmup_index_not_reused");
            writer.println("index_payload=primitive_array_estimate_not_peak_memory");
            if (in.p.getProperty("spatial_stage", "").equals("sensitivity"))
                writer.println("sensitivity=widths_4_8_16_32_64_same_batch; selected_method_is_advisory_only; transfer_block_16_not_changed");
            writer.println(in.p.getProperty("spatial_stage", "").equals("transfer")
                    ? "transfer=frozen_block_16_no_parameter_selection; binary_regression_not_assumed"
                    : in.methods.contains("block16fixed_1min")
                    ? "ablation=frozen_block_16_no_parameter_selection; runtime_ratios_not_equal_accuracy_claims"
                    : "gate=median_saving_at_least_0.10_and_each_paired_repeat_faster");
        }
    }

    private static boolean samePixel(ProjectedBaselineExperiment.Pixel a, ProjectedBaselineExperiment.Pixel b) {
        return Double.doubleToLongBits(a.psd) == Double.doubleToLongBits(b.psd)
                && a.day == b.day && a.samples == b.samples && Objects.equals(a.reason,b.reason)
                && a.quarterProbes == b.quarterProbes && a.refinementQueries == b.refinementQueries
                && a.shortIntervals == b.shortIntervals && a.longIntervals == b.longIntervals
                && a.guardQueries == b.guardQueries && Objects.equals(a.intervals,b.intervals) && Objects.equals(a.trace,b.trace)
                && Objects.equals(a.nodes,b.nodes) && Objects.equals(a.schedule,b.schedule);
    }
    private static double median(List<ProjectedBaselineExperiment.Run> runs) {
        double[] times = runs.stream().mapToDouble(r -> r.computeSeconds).sorted().toArray();
        return times[1]; // Inputs限定S2正式重复为3。
    }
    private static final class Candidate {
        String method, baseline; int size; long payload;
        double median, baselineMedian, saving; boolean allFaster, eligible;
    }

    /** 旧fixed1缺guard_queries列；逐行比较全部旧字段，新增该列必须为0，不因表头差异误报。 */
    private static boolean fixedReferenceMatches(Path previous, Path current) throws IOException {
        try (BufferedReader a = Files.newBufferedReader(previous); BufferedReader b = Files.newBufferedReader(current)) {
            String[] oldHeader = a.readLine().split(","), newHeader = b.readLine().split(",");
            List<String> names = Arrays.asList(newHeader);
            int[] columns = new int[oldHeader.length];
            for (int i = 0; i < columns.length; i++) {
                columns[i] = names.indexOf(oldHeader[i]);
                if (columns[i] < 0) return false;
            }
            int guard = names.indexOf("guard_queries");
            String oldLine;
            while ((oldLine = a.readLine()) != null) {
                String newLine = b.readLine();
                if (newLine == null) return false;
                String[] av = oldLine.split(",",-1), bv = newLine.split(",",-1);
                if (av.length != oldHeader.length || bv.length != newHeader.length) return false;
                for (int i = 0; i < columns.length; i++) if (!av[i].equals(bv[columns[i]])) return false;
                if (guard >= 0 && !bv[guard].equals("0")) return false;
            }
            return b.readLine() == null;
        }
    }

    static String write(ProjectedBaselineExperiment.Inputs in,
            Map<String,List<ProjectedBaselineExperiment.Run>> results, Path output) throws IOException {
        List<ProjectedBaselineExperiment.Run> baseline = results.get(ORIGINAL);
        boolean ablation = in.methods.contains("block16fixed_1min");
        boolean transfer = in.p.getProperty("spatial_stage", "").equals("transfer");
        boolean regressionAvailable = !in.p.getProperty("spatial_regression_run", "").isBlank();
        boolean regressionMatches = true;
        if (regressionAvailable) {
            Path regression = in.resolve(in.p.getProperty("spatial_regression_run")).resolve(ORIGINAL+"_r1/psd.csv");
            for (int r = 1; r <= 3; r++)
                regressionMatches &= Files.mismatch(regression,output.resolve(ORIGINAL+"_r"+r+"/psd.csv")) == -1;
        }
        boolean fixedRegression = true;
        if (ablation) for (int r = 1; r <= 3; r++)
            fixedRegression &= fixedReferenceMatches(in.resolve(in.p.getProperty("reference_run")).resolve("fixed_1min_r1/psd.csv"),
                    output.resolve("fixed_1min_r"+r+"/psd.csv"));
        double baseMedian = median(baseline);
        List<Candidate> candidates = new ArrayList<>();
        long totalMismatches = (regressionMatches ? 0 : 1) + (fixedRegression ? 0 : 1);
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(output.resolve(
                transfer ? "spatial_transfer_pairs.csv" : ablation ? "spatial_ablation_pairs.csv" : "spatial_repeat_comparison.csv")))) {
            writer.println("method,block_size,repeat,pixel_mismatches,counters_match,samples_conserved,baseline_total_sec,preprocess_sec,query_sec,total_sec,saving_ratio,spatial_samples,skipped_samples,block_tests,index_payload_bytes");
            for (String method : in.methods) {
                int size = ProjectedBaselineExperiment.spatialBlockSize(method);
                if (size == 0) continue;
                List<ProjectedBaselineExperiment.Run> runs = results.get(method);
                Candidate c = new Candidate(); c.method=method; c.size=size; c.allFaster=true;
                c.baseline = method.equals("block16fixed_1min") ? "fixed_1min" : ORIGINAL;
                List<ProjectedBaselineExperiment.Run> pairedBaseline = results.get(c.baseline);
                c.baselineMedian = median(pairedBaseline);
                for (int r = 0; r < 3; r++) {
                    ProjectedBaselineExperiment.Run a = pairedBaseline.get(r), b = runs.get(r);
                    int bad = Math.abs(a.pixels.size()-b.pixels.size());
                    for (int i = 0; i < Math.min(a.pixels.size(),b.pixels.size()); i++)
                        if (!samePixel(a.pixels.get(i),b.pixels.get(i))) bad++;
                    boolean counters = a.stats.rayCalls == b.stats.rayCalls && a.valid == b.valid && a.fallback == b.fallback
                            && a.stats.visibleResults == b.stats.visibleResults && a.stats.shadedResults == b.stats.shadedResults
                            && a.stats.unknownResults == b.stats.unknownResults;
                    boolean conserved = a.stats.spatialSamples == b.stats.spatialSamples + b.skippedSpatialSamples;
                    totalMismatches += bad + (counters ? 0 : 1) + (conserved ? 0 : 1);
                    c.allFaster &= b.computeSeconds < a.computeSeconds;
                    writer.printf("%s,%d,%d,%d,%s,%s,%.9f,%.9f,%.9f,%.9f,%.12f,%d,%d,%d,%d%n",
                            method,size,r+1,bad,counters,conserved,a.computeSeconds,b.preprocessSeconds,b.querySeconds,
                            b.computeSeconds,1-b.computeSeconds/a.computeSeconds,b.stats.spatialSamples,
                            b.skippedSpatialSamples,b.spatialBlockTests,b.spatialIndexPayloadBytes);
                }
                c.median=median(runs); c.saving=1-c.median/c.baselineMedian;
                c.payload=runs.get(0).spatialIndexPayloadBytes;
                c.eligible=c.saving>=.10 && c.allFaster;
                candidates.add(c);
            }
        }
        boolean correct = totalMismatches == 0;
        if (transfer) {
            Candidate c = candidates.get(0); // Inputs限定只有原/冻结16格这一对。
            boolean gate = correct && c.eligible;
            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(output.resolve("spatial_transfer.json")))) {
                writer.printf("{\n  \"correctness_pass\":%s,\n  \"binary_regression_checked\":%s,\n  \"binary_regression_matches\":%s,\n  \"mismatches\":%d,\n  \"frozen_block_size\":16,\n  \"parameter_selection_performed\":false,\n",
                        correct,regressionAvailable,regressionAvailable ? Boolean.toString(regressionMatches) : "null",totalMismatches);
                writer.printf("  \"baseline_median_total_sec\":%.9f,\n  \"block_median_total_sec\":%.9f,\n  \"saving_ratio\":%.12f,\n  \"all_paired_repeats_faster\":%s,\n  \"performance_gate_pass\":%s\n}%n",
                        c.baselineMedian,c.median,c.saving,c.allFaster,gate);
            }
            if (!correct) throw new AssertionError("transfer mismatch; inspect spatial_transfer_pairs.csv and spatial_transfer.json");
            return "SPATIAL_TRANSFER_COMPLETE correctness=true frozen_block_size=16 performance_gate="+gate;
        }
        if (ablation) {
            double fixed = median(results.get("fixed_1min"));
            double blockFixed = median(results.get("block16fixed_1min"));
            double combined = median(results.get("block16binarylocal_30min"));
            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(output.resolve("spatial_ablation.json")))) {
                writer.printf("{\n  \"correctness_pass\":%s,\n  \"binary_regression_matches\":%s,\n  \"fixed_reference_common_fields_match\":%s,\n  \"mismatches\":%d,\n  \"frozen_block_size\":16,\n  \"parameter_selection_performed\":false,\n",
                        correct,regressionMatches,fixedRegression,totalMismatches);
                writer.printf("  \"median_total_sec\":{\"fixed\":%.9f,\"block_fixed\":%.9f,\"binary\":%.9f,\"block_binary\":%.9f},\n",
                        fixed,blockFixed,baseMedian,combined);
                writer.printf("  \"runtime_ratios\":{\"space_only\":%.9f,\"time_only\":%.9f,\"combined\":%.9f,\"space_given_binary\":%.9f,\"time_given_block\":%.9f}\n}%n",
                        fixed/blockFixed,fixed/baseMedian,fixed/combined,baseMedian/combined,blockFixed/combined);
            }
            if (!correct) throw new AssertionError("S3 mismatch; inspect spatial_ablation_pairs.csv and spatial_ablation.json");
            return "SPATIAL_ABLATION_COMPLETE correctness=true frozen_block_size=16 no_parameter_selection";
        }
        Candidate selected = null;
        double fastest = candidates.stream().filter(c -> c.eligible).mapToDouble(c -> c.median).min().orElse(Double.NaN);
        if (correct) for (Candidate c : candidates) {
            if (!c.eligible || (c.median-fastest)/fastest >= .05) continue;
            if (selected == null || c.payload < selected.payload || (c.payload == selected.payload && c.size > selected.size)) selected=c;
        }
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(output.resolve("spatial_gate.json")))) {
            writer.printf("{\n  \"correctness_pass\":%s,\n  \"baseline_regression_matches\":%s,\n  \"mismatches\":%d,\n  \"baseline_median_total_sec\":%.9f,\n  \"performance_gate_pass\":%s,\n  \"selected_method\":%s,\n  \"candidates\":[\n",
                    correct,regressionMatches,totalMismatches,baseMedian,selected!=null,
                    selected==null ? "null" : "\""+selected.method+"\"");
            for (int i = 0; i < candidates.size(); i++) {
                Candidate c = candidates.get(i);
                writer.printf("    {\"method\":\"%s\",\"median_total_sec\":%.9f,\"saving_ratio\":%.12f,\"all_paired_repeats_faster\":%s,\"eligible\":%s,\"index_payload_bytes\":%d}%s%n",
                        c.method,c.median,c.saving,c.allFaster,c.eligible,c.payload,i+1==candidates.size() ? "" : ",");
            }
            writer.println("  ]\n}");
        }
        if (!correct) throw new AssertionError("spatial S2 mismatch; inspect spatial_repeat_comparison.csv and spatial_gate.json");
        return "SPATIAL_COMPARISON_COMPLETE correctness=true performance_gate="+(selected!=null)
                +" selected="+(selected==null ? "NONE" : selected.method);
    }
}
