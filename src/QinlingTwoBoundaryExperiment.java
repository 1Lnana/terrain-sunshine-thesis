import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Independent Qinling winter comparison; approximation errors are measured outcomes. Frozen cores and the previous offline search are unchanged.
 * Real visibility calls occur only when the user explicitly runs this entry.
 * Single-pass elapsed times include search bookkeeping and are diagnostic, not a performance trial.
 */
public final class QinlingTwoBoundaryExperiment {
    private static final double EPS = 1e-8;
    private static final String BASE = "binarylocal30", CANDIDATE = "two_boundary_assumption";
    private record Query(double time, int state, String purpose, boolean fresh) {}
    private record Pixel(double day, double psd, String status, String reason,
                         int requests, int hits, int unique, int anchor, int boundary, int other,
                         int fallback, NavigableMap<Double,Integer> nodes, List<Query> trace) {}
    private record Calculation(String method, List<Pixel> pixels, RunStatistics stats, double seconds, long[] pixelNanos) {}
    private static final class Review {
        int failures, compared, baselineHistory, historicalTraces, savedReplay;
        final List<String> firstFailures = new ArrayList<>();
        void check(boolean ok, String message) {
            if (!ok) { failures++; if (firstFailures.size() < 20) firstFailures.add(message); }
        }
    }
    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }
    private static boolean same(double a, double b) {
        return Double.isFinite(a) && Double.isFinite(b) && Math.abs(a-b) < EPS;
    }
    private static String number(double n) {
        return Double.isFinite(n) ? String.format(Locale.ROOT, "%.12f", n) : "";
    }
    private static String quoted(Object value) {
        return "\"" + String.valueOf(value).replace("\"", "\"\"") + "\"";
    }
    private static void csv(PrintWriter writer, Object... fields) {
        for (int i=0; i<fields.length; i++) { if (i>0) writer.print(','); writer.print(quoted(fields[i])); }
        writer.println();
    }
    private static PrintWriter writer(Path file) throws IOException {
        return new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8));
    }
    private static String header(BufferedReader reader) throws IOException {
        String value = reader.readLine();
        return value != null && value.startsWith("\uFEFF") ? value.substring(1) : value;
    }
    private static Map<String,String[]> pixelRows(Path path, String expected) throws IOException {
        Map<String,String[]> result = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String first = header(reader);
            require(first != null && first.startsWith(expected), "unexpected header: " + path);
            String line;
            while ((line=reader.readLine()) != null) {
                String[] p = line.split(",", -1);
                require(p.length >= 8 && result.putIfAbsent(p[0]+","+p[1], p) == null,
                        "malformed/duplicate pixel: " + path);
            }
        }
        return result;
    }
    private static Map<String,NavigableMap<Double,Integer>> traces(Path path) throws IOException {
        Map<String,NavigableMap<Double,Integer>> result = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            require("row,col,minutes_since_sunrise,state,used_for_integration".equals(header(reader)),
                    "unexpected trace header: " + path);
            String line;
            while ((line=reader.readLine()) != null) {
                String[] p=line.split(",", -1);
                require(p.length == 5 && p[4].equals("1"), "nonintegrated/malformed saved trace");
                int state = p[3].equals("U") ? -1 : Integer.parseInt(p[3]);
                require(result.computeIfAbsent(p[0]+","+p[1], k->new TreeMap<>())
                        .putIfAbsent(Double.parseDouble(p[2]), state) == null, "duplicate saved trace node");
            }
        }
        return result;
    }
    private static boolean sameNodes(NavigableMap<Double,Integer> a, NavigableMap<Double,Integer> b) {
        if (a.size() != b.size()) return false;
        Iterator<Map.Entry<Double,Integer>> left=a.entrySet().iterator(), right=b.entrySet().iterator();
        while (left.hasNext()) {
            Map.Entry<Double,Integer> x=left.next(), y=right.next();
            if (!same(x.getKey(), y.getKey()) || !x.getValue().equals(y.getValue())) return false;
        }
        return true;
    }
    private static SunlightIntervalStats.Result structure(NavigableMap<Double,Integer> nodes) {
        double[] t=new double[nodes.size()]; int[] s=new int[t.length]; int i=0;
        for (Map.Entry<Double,Integer> e:nodes.entrySet()) { t[i]=e.getKey(); s[i++]=e.getValue(); }
        return SunlightIntervalStats.analyze(t,s);
    }
    private static String sha(Path path) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try (InputStream stream=Files.newInputStream(path)) {
            byte[] buffer=new byte[65536]; int n;
            while ((n=stream.read(buffer)) != -1) digest.update(buffer,0,n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void verifyBuild(Path classes) throws Exception {
        List<String> lines=Files.readAllLines(classes.getParent().resolve("build_sources.sha256"), StandardCharsets.UTF_8);
        require(!lines.isEmpty(), "missing build-source fingerprints");
        for (String line:lines) {
            String[] fields=line.split("\t",2);
            require(fields.length == 2 && fields[0].equals(sha(Path.of(fields[1]))),
                    "source changed after compilation; compile again");
        }
        Set<Path> expected=new TreeSet<>();
        for (String line:Files.readAllLines(classes.getParent().resolve("build_classes.sha256"),StandardCharsets.UTF_8)) {
            String[] fields=line.split("\t",2);
            require(fields.length==2,"malformed compiled-class fingerprint");
            Path path=Path.of(fields[1]).toAbsolutePath().normalize();
            require(path.startsWith(classes) && path.toString().endsWith(".class") && expected.add(path)
                    && fields[0].equals(sha(path)),"compiled class changed or invalid; compile again");
        }
        Set<Path> actual=new TreeSet<>();
        try (var stream=Files.walk(classes)) {
            stream.filter(p->Files.isRegularFile(p) && p.toString().endsWith(".class"))
                    .map(p->p.toAbsolutePath().normalize()).forEach(actual::add);
        }
        require(!expected.isEmpty() && expected.equals(actual),"compiled class set changed; compile again");
    }
    private static void verifyHistoryInputs(ProjectedBaselineExperiment.Inputs in, Path historical) throws IOException {
        Properties previous=new Properties();
        try (Reader reader=Files.newBufferedReader(historical.resolve("config.properties"), StandardCharsets.UTF_8)) {
            previous.load(reader);
        }
        for (String key:List.of("projected_crs","cellsize_m","row_start","col_start","rows","cols",
                "date","search_spacing_m","search_samples","elevation_unit_assumption"))
            require(Objects.equals(previous.getProperty(key), in.p.getProperty(key)), "historical input differs: "+key);
        require(Files.mismatch(historical.resolve("receivers.csv"), in.resolve(in.p.getProperty("receivers"))) == -1,
                "historical receiver snapshot differs");
        require(Files.mismatch(historical.resolve("data_metadata.json"), in.resolve(in.p.getProperty("metadata"))) == -1,
                "historical metadata snapshot differs");
    }
    private static Calculation calculate(ProjectedBaselineExperiment.Inputs in, String method) {
        RunStatistics stats=new RunStatistics(); List<Pixel> pixels=new ArrayList<>();
        long[] pixelNanos=new long[in.receivers.size()];
        long queryLimit=method.equals(BASE)?862525L:344064L;
        long begin=System.nanoTime();
        for (ProjectedBaselineExperiment.Receiver receiver:in.receivers) {
            long pixelBegin=System.nanoTime();
            double day=TimeStepBaseline.daylight(receiver.lat,in.dec), rise=720-day/2;
            require(day>586 && day<587,"daylight outside the fixed Qinling winter scope");
            long before=stats.rayCalls;
            boolean capture=in.traces.contains(receiver.key());
            List<Query> actual=capture ? new ArrayList<>() : null;
            // Exactly the frozen driver's solar/UTM-north/clamping/space contract; no reference access.
            AdaptiveTimeV1.Oracle oracle=t->{
                require(stats.rayCalls<queryLimit,"real query limit reached; do not extend this batch");
                // With 586 < day < 587, [0,293] needs at most 9 probes, but the
                // fractional interval [293,day] can need 10, including minute 586.
                // Keep the independent whole-region budget unchanged.
                require(method.equals(BASE) || stats.rayCalls-before<22,
                        "candidate per-pixel query bound exceeded at "+receiver.key()+" time="+t+" day="+day);
                double[] angle=TimeStepBaseline.sun(receiver.lat,in.dec,rise+t);
                VisibilityStatus visible=ControlledVisibility.visibleAt(in.grid,in.contract,receiver.row,receiver.col,
                        Math.max(1e-7,angle[0]),angle[1]+receiver.north,AngleReference.GRID_NORTH_CLOCKWISE,
                        in.search,stats).status;
                int state=visible==VisibilityStatus.VISIBLE ? 1 : visible==VisibilityStatus.SHADED ? 0 : -1;
                if (capture) actual.add(new Query(t,state,"other",true));
                return state;
            };
            Pixel pixel;
            if (method.equals(BASE)) {
                AdaptiveTimeV2Local.Result value=BinaryLocalRefinement.adaptiveWithDarkGuard(day,30,oracle);
                require(value.integrationNodes.equals(value.evaluated), "baseline query/integration mismatch");
                int unique=value.evaluated.size();
                pixel=new Pixel(day,value.psd,Double.isFinite(value.psd)?"OK":"UNKNOWN",value.fallbackReason,
                        unique,0,unique,0,0,unique,0,value.integrationNodes,actual);
            } else {
                TimeStructureSearch.Result value=TimeStructureSearch.run(CANDIDATE,day,oracle);
                List<Query> trace=null;
                if (capture) {
                    trace=new ArrayList<>();
                    for (TimeStructureSearch.Query q:value.queries) trace.add(new Query(q.time,q.state,q.purpose,q.isNew));
                }
                pixel=new Pixel(day,value.psd,value.status,"NO_AUTOMATIC_FALLBACK",value.requests,value.hits,
                        value.unique(),value.anchor,value.boundary,value.other,value.fallback,value.nodes,trace);
            }
            require(stats.rayCalls-before == pixel.unique(), "real ray/query count mismatch");
            require(pixel.requests() == pixel.hits()+pixel.unique() && pixel.unique() ==
                    pixel.anchor()+pixel.boundary()+pixel.other()+pixel.fallback(), "query accounting mismatch");
            pixelNanos[pixels.size()]=System.nanoTime()-pixelBegin;
            pixels.add(pixel);
        }
        return new Calculation(method,pixels,stats,(System.nanoTime()-begin)/1e9,pixelNanos);
    }
    private static void save(Path output, ProjectedBaselineExperiment.Inputs in,
                             Calculation calculation, Map<String,String[]> reference,
                             Map<String,String[]> intervals, Map<String,String[]> old,
                             Map<String,NavigableMap<Double,Integer>> oldTraces,
                             Map<String,NavigableMap<Double,Integer>> savedTraces, Review review) throws IOException {
        Path dir=output.resolve(calculation.method()); Files.createDirectory(dir);
        try (PrintWriter ps=writer(dir.resolve("pixels.csv"));
             PrintWriter trace=writer(dir.resolve("queries.csv"));
             PrintWriter nodes=writer(dir.resolve("integration_nodes.csv"))) {
            csv(ps,"row","col","daylight_minutes","status","reason","psd_minutes","reference_psd",
                    "signed_error","absolute_error","light_intervals","reference_light_intervals",
                    "first_boundary","reference_first_boundary","last_boundary","reference_last_boundary",
                    "first_error","last_error","requests","cache_hits","unique","anchor","boundary",
                    "other","fallback","matches_minute_reference","compute_nanos","reference_category");
            csv(trace,"row","col","request_index","minute_since_sunrise","state","purpose","is_new");
            csv(nodes,"row","col","minute_since_sunrise","state");
            for (int i=0;i<in.receivers.size();i++) {
                ProjectedBaselineExperiment.Receiver receiver=in.receivers.get(i);
                Pixel pixel=calculation.pixels().get(i); String key=receiver.key();
                String[] ref=reference.get(key), interval=intervals.get(key);
                SunlightIntervalStats.Result current=pixel.status().equals("OK") ? structure(pixel.nodes()) : null;
                double target=Double.parseDouble(ref[6]);
                boolean ok=pixel.status().equals("OK") && Double.isFinite(pixel.psd()) && current != null;
                boolean qualified=ok && same(pixel.psd(),target) && current.lightIntervals()==Integer.parseInt(interval[3])
                        && equalBoundary(current.firstBoundary(),Double.parseDouble(interval[5]))
                        && equalBoundary(current.lastBoundary(),Double.parseDouble(interval[6]));
                double signed=ok?pixel.psd()-target:Double.NaN;
                double firstError=ok?current.firstBoundary()-Double.parseDouble(interval[5]):Double.NaN;
                double lastError=ok?current.lastBoundary()-Double.parseDouble(interval[6]):Double.NaN;
                csv(ps,receiver.row,receiver.col,number(pixel.day()),pixel.status(),pixel.reason(),number(pixel.psd()),
                        number(target),number(signed),number(Math.abs(signed)),current==null?"":current.lightIntervals(),
                        interval[3],current==null?"":number(current.firstBoundary()),interval[5],
                        current==null?"":number(current.lastBoundary()),interval[6],number(firstError),number(lastError),
                        pixel.requests(),pixel.hits(),pixel.unique(),pixel.anchor(),pixel.boundary(),pixel.other(),
                        pixel.fallback(),qualified,calculation.pixelNanos()[i],interval[2]);
                if (calculation.method().equals(BASE)) {
                    String[] prior=old.get(key);
                    review.check(prior!=null && same(pixel.psd(),Double.parseDouble(prior[6]))
                            && pixel.unique()==Integer.parseInt(prior[7]), "baseline historical PSD/query mismatch: "+key);
                    review.baselineHistory++;
                    if (oldTraces.containsKey(key)) {
                        review.check(sameNodes(pixel.nodes(),oldTraces.get(key)),"baseline historical nodes mismatch: "+key);
                        review.historicalTraces++;
                    }
                } else if (savedTraces.containsKey(key)) {
                    review.check(sameNodes(pixel.nodes(),savedTraces.get(key)),"candidate saved-minute replay mismatch: "+key);
                    review.savedReplay++;
                }
                if (pixel.trace()!=null) {
                    int index=0;
                    for (Query q:pixel.trace()) csv(trace,receiver.row,receiver.col,++index,number(q.time()),
                            q.state(),q.purpose(),q.fresh());
                }
                for (Map.Entry<Double,Integer> e:pixel.nodes().entrySet())
                    csv(nodes,receiver.row,receiver.col,number(e.getKey()),e.getValue());
                review.compared++;
            }
            require(!ps.checkError() && !trace.checkError() && !nodes.checkError(), "output write failed");
        }
    }
    private static boolean equalBoundary(double a,double b) {
        return same(a,b) || (Double.isNaN(a) && Double.isNaN(b));
    }
    private static boolean completed(Pixel p) {
        return p.status().equals("OK") && Double.isFinite(p.psd());
    }
    private static String referenceGroup(String[] interval, Pixel p) {
        int n=Integer.parseInt(interval[3]);
        if (n==0) return "ALL_DARK";
        if (n==1 && same(Double.parseDouble(interval[8]),p.day())) return "ALL_LIGHT";
        return n==1?"SINGLE_WITH_TRANSITIONS":n==2?"TWO_WINDOWS":"THREE_OR_MORE_WINDOWS";
    }
    private static double percentile(List<Double> sorted,double fraction) {
        return sorted.isEmpty()?Double.NaN:sorted.get(Math.max(0,(int)Math.ceil(fraction*sorted.size())-1));
    }
    private static void summarize(Path output, ProjectedBaselineExperiment.Inputs in,
                                  Calculation baseline, Calculation candidate,
                                  Map<String,String[]> reference, Map<String,String[]> intervals) throws IOException {
        try (PrintWriter out=writer(output.resolve("summary.csv"))) {
            csv(out,"method","scope","attempted_pixels","ok_pixels","not_applicable_pixels","unknown_pixels",
                    "completion_fraction","real_queries","compute_sec","timing_role","error_evaluated_pixels",
                    "MAE_min","MAX_abs_error_min","P95_abs_error_min","P99_abs_error_min",
                    "error_gt1_pixels","error_gt5_pixels","window_count_mismatch_pixels",
                    "first_boundary_error_pixels","first_boundary_MAE_min","first_boundary_MAX_min",
                    "last_boundary_error_pixels","last_boundary_MAE_min","last_boundary_MAX_min");
            for (String scope:List.of("ALL_ATTEMPTED","COMMON_OK","ALL_LIGHT","SINGLE_WITH_TRANSITIONS",
                    "TWO_WINDOWS","THREE_OR_MORE_WINDOWS","ALL_DARK")) {
                for (Calculation calculation:List.of(baseline,candidate)) {
                    int count=0,ok=0,na=0,unknown=0,gt1=0,gt5=0,windowMismatch=0,firstN=0,lastN=0;
                    long queries=0,nanos=0;
                    double total=0,firstTotal=0,lastTotal=0,firstMax=0,lastMax=0;
                    List<Double> errors=new ArrayList<>();
                    for (int i=0;i<in.receivers.size();i++) {
                        String key=in.receivers.get(i).key(); Pixel p=calculation.pixels().get(i);
                        boolean common=completed(baseline.pixels().get(i)) && completed(candidate.pixels().get(i));
                        String[] interval=intervals.get(key);
                        if (!scope.equals("ALL_ATTEMPTED") && !(scope.equals("COMMON_OK")?common:
                                scope.equals(referenceGroup(interval,p)))) continue;
                        count++; queries+=p.unique(); nanos+=calculation.pixelNanos()[i];
                        if (p.status().equals("NOT_APPLICABLE")) na++;
                        else if (p.status().equals("UNKNOWN")) unknown++;
                        if (!completed(p)) continue;
                        ok++;
                        double error=Math.abs(p.psd()-Double.parseDouble(reference.get(key)[6]));
                        errors.add(error); total+=error; if (error>1+EPS) gt1++; if (error>5+EPS) gt5++;
                        SunlightIntervalStats.Result current=structure(p.nodes());
                        if (current.lightIntervals()!=Integer.parseInt(interval[3])) windowMismatch++;
                        double first=Math.abs(current.firstBoundary()-Double.parseDouble(interval[5]));
                        double last=Math.abs(current.lastBoundary()-Double.parseDouble(interval[6]));
                        if (Double.isFinite(first)) { firstN++;firstTotal+=first;firstMax=Math.max(firstMax,first); }
                        if (Double.isFinite(last)) { lastN++;lastTotal+=last;lastMax=Math.max(lastMax,last); }
                    }
                    Collections.sort(errors);
                    csv(out,calculation.method(),scope,count,ok,na,unknown,number(count==0?Double.NaN:ok/(double)count),
                            queries,number(scope.equals("ALL_ATTEMPTED")?calculation.seconds():nanos/1e9),
                            scope.equals("ALL_ATTEMPTED")?"SINGLE_PASS_WALL_DIAGNOSTIC":"SAME_PASS_PIXEL_TIMER_SUM_DIAGNOSTIC",
                            errors.size(),number(errors.isEmpty()?Double.NaN:total/errors.size()),
                            number(percentile(errors,1)),number(percentile(errors,.95)),number(percentile(errors,.99)),
                            gt1,gt5,windowMismatch,firstN,number(firstN==0?Double.NaN:firstTotal/firstN),
                            number(firstN==0?Double.NaN:firstMax),lastN,number(lastN==0?Double.NaN:lastTotal/lastN),
                            number(lastN==0?Double.NaN:lastMax));
                }
            }
            require(!out.checkError(),"summary write failed");
        }
        try (PrintWriter out=writer(output.resolve("comparison.csv"))) {
            csv(out,"row","col","reference_group","baseline_status","candidate_status","common_ok",
                    "baseline_psd","candidate_psd","reference_psd","baseline_queries","candidate_queries",
                    "query_difference_baseline_minus_candidate","baseline_compute_nanos","candidate_compute_nanos");
            for (int i=0;i<in.receivers.size();i++) {
                ProjectedBaselineExperiment.Receiver r=in.receivers.get(i);
                Pixel a=baseline.pixels().get(i),b=candidate.pixels().get(i);
                csv(out,r.row,r.col,referenceGroup(intervals.get(r.key()),a),a.status(),b.status(),
                        completed(a)&&completed(b),number(a.psd()),number(b.psd()),reference.get(r.key())[6],
                        a.unique(),b.unique(),a.unique()-b.unique(),baseline.pixelNanos()[i],candidate.pixelNanos()[i]);
            }
            require(!out.checkError(),"comparison write failed");
        }
    }
    private static Map<String,NavigableMap<Double,Integer>> savedCandidate(Path run) throws IOException {
        Map<String,NavigableMap<Double,Integer>> result=new HashMap<>();
        try (BufferedReader reader=Files.newBufferedReader(run.resolve("integration_nodes.csv"),StandardCharsets.UTF_8)) {
            require("\"case_id\",\"method\",\"time\",\"state\"".equals(header(reader)), "offline node header mismatch");
            String line;
            while ((line=reader.readLine())!=null) {
                String[] p=line.replace("\"", "").split(",",-1);
                if (p[0].startsWith("qinling128_winter_") && p[1].equals(CANDIDATE)) {
                    String key=p[0].substring("qinling128_winter_".length()).replace('_',',');
                    require(result.computeIfAbsent(key,k->new TreeMap<>()).putIfAbsent(Double.parseDouble(p[2]),
                            Integer.parseInt(p[3]))==null,"duplicate offline node");
                }
            }
        }
        require(result.size()==3,"expected three saved Qinling winter replay cases"); return result;
    }
    private static void manifest(Path root, Path classes, Path output, ProjectedBaselineExperiment.Inputs in,
                                 Path historical, Path replay) throws Exception {
        Set<Path> paths=new TreeSet<>(); paths.add(in.config);
        for (String key:List.of("dem","receivers","metadata")) paths.add(in.resolve(in.p.getProperty(key)));
        Path reference=in.resolve(in.p.getProperty("reference_run"));
        for (Path run:List.of(reference,historical))
            for (String file:List.of("config.properties","receivers.csv","data_metadata.json","fixed_1min_r1/psd.csv")) {
                Path p=run.resolve(file); if (Files.isRegularFile(p)) paths.add(p);
            }
        paths.add(reference.resolve("fixed_1min_r1/intervals.csv"));
        paths.add(historical.resolve("binarylocal_30min_r1/psd.csv"));
        paths.add(historical.resolve("binarylocal_30min_r1/samples.csv"));
        paths.add(replay.resolve("integration_nodes.csv")); paths.add(replay.resolve("manifest.csv"));
        paths.add(classes.getParent().resolve("build_sources.sha256"));
        paths.add(classes.getParent().resolve("build_classes.sha256"));
        paths.add(root.resolve("scripts/compile_qinling_two_boundary.ps1"));
        paths.add(root.resolve("scripts/run_qinling_two_boundary_compare.ps1"));
        try (var stream=Files.list(root.resolve("src"))) { stream.filter(p->p.toString().endsWith(".java")).forEach(paths::add); }
        try (var stream=Files.walk(classes)) { stream.filter(p->Files.isRegularFile(p) && p.toString().endsWith(".class")).forEach(paths::add); }
        try (PrintWriter out=writer(output.resolve("manifest.csv"))) {
            csv(out,"path","sha256"); for (Path p:paths) csv(out,root.relativize(p),sha(p));
            require(!out.checkError(),"manifest write failed");
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length!=3 || !args[0].equals("--compare"))
            throw new IllegalArgumentException("usage: QinlingTwoBoundaryExperiment --compare config new-output-directory");
        Path config=Path.of(args[1]).toAbsolutePath().normalize();
        Path root=config.getParent().getParent();
        Path output=Path.of(args[2]).toAbsolutePath().normalize();
        require(output.getParent().equals(root.resolve("user_runs")) && output.getFileName().toString().startsWith("qinling_two_boundary_compare_"),
                "use a new user_runs/qinling_two_boundary_compare_* directory");
        Properties chosen=new Properties();
        try (Reader reader=Files.newBufferedReader(config,StandardCharsets.UTF_8)) { chosen.load(reader); }
        for (Map.Entry<String,String> e:Map.ofEntries(
                Map.entry("experiment_id","qinling_winter_two_boundary_comparison_v1"),Map.entry("stage","compare"),
                Map.entry("date","2023-12-21"),Map.entry("rows","128"),Map.entry("cols","128"),
                Map.entry("row_start","114"),Map.entry("col_start","114"),Map.entry("projected_crs","EPSG:32649"),
                Map.entry("cellsize_m","90.0"),Map.entry("search_spacing_m","50"),Map.entry("search_samples","200"),
                Map.entry("methods","binarylocal_30min"),Map.entry("warmups","0"),Map.entry("repeats","1")).entrySet())
            require(e.getValue().equals(chosen.getProperty(e.getKey())),"unsupported scope or protocol: "+e.getKey());
        Path classes=Path.of(QinlingTwoBoundaryExperiment.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        require(Files.isDirectory(classes),"isolated classes directory required"); verifyBuild(classes);
        long loading=System.nanoTime();
        ProjectedBaselineExperiment.Inputs in=new ProjectedBaselineExperiment.Inputs(config);
        // Strict reference loader verifies date/space/receivers/metadata/DEM before any real query.
        ProjectedBaselineExperiment.loadReference(in);
        Path reference=in.resolve(in.p.getProperty("reference_run"));
        Path historical=in.resolve(in.p.getProperty("regression_run"));
        Path replay=in.resolve(in.p.getProperty("replay_run"));
        verifyHistoryInputs(in,historical);
        String psdHeader="row,col,latitude,longitude,elevation_m,daylight_minutes,psd_minutes,";
        Map<String,String[]> ref=pixelRows(reference.resolve("fixed_1min_r1/psd.csv"),psdHeader);
        Map<String,String[]> interval=pixelRows(reference.resolve("fixed_1min_r1/intervals.csv"),"row,col,category,light_intervals,");
        Map<String,String[]> previous=pixelRows(historical.resolve("binarylocal_30min_r1/psd.csv"),psdHeader);
        Map<String,NavigableMap<Double,Integer>> historicalTrace=traces(historical.resolve("binarylocal_30min_r1/samples.csv"));
        Map<String,NavigableMap<Double,Integer>> replayTrace=savedCandidate(replay);
        require(ref.size()==16384 && interval.size()==16384 && previous.size()==16384,"incomplete reference/history");
        for (ProjectedBaselineExperiment.Receiver receiver:in.receivers) {
            String key=receiver.key(); String[] p=ref.get(key),s=interval.get(key);
            require(p!=null && s!=null && previous.containsKey(key),"pixel identity mismatch");
            int count=Integer.parseInt(s[3]);
            require(count>=0 && Set.of("DARK","SINGLE","MULTI").contains(s[2])
                    && s[2].equals(count==0?"DARK":count==1?"SINGLE":"MULTI")
                    && same(Double.parseDouble(s[8]),Double.parseDouble(p[6])),
                    "invalid reference structure or integral: "+key);
        }
        double loadSeconds=(System.nanoTime()-loading)/1e9;
        Files.createDirectory(output);
        Files.copy(config,output.resolve("config.properties"));
        Review review=new Review();
        // The evaluation tables above are never passed to either search.
        Calculation baseline=calculate(in,BASE); save(output,in,baseline,ref,interval,previous,historicalTrace,replayTrace,review);
        if (review.failures!=0) {
            List<String> messages=new ArrayList<>();
            messages.add("QINLING_BASELINE_REGRESSION_FAILED; candidate was not run");
            messages.addAll(review.firstFailures);
            Files.write(output.resolve("verification.txt"),messages,StandardCharsets.UTF_8);
            throw new IllegalStateException("baseline regression failed; retain output before proceeding");
        }
        Calculation candidate=calculate(in,CANDIDATE); save(output,in,candidate,ref,interval,previous,historicalTrace,replayTrace,review);
        review.check(review.baselineHistory==16384 && review.historicalTraces==6 && review.savedReplay==3,
                "historical/replay regression coverage incomplete");
        summarize(output,in,baseline,candidate,ref,interval);
        manifest(root,classes,output,in,historical,replay);
        try (PrintWriter out=writer(output.resolve("single_pass_diagnostic.csv"))) {
            csv(out,"method","pixels","real_ray_calls","spatial_samples","unknown_queries","compute_sec",
                    "timing_role","query_bookkeeping","warmups","formal_timing_repeats");
            for (Calculation r:List.of(baseline,candidate)) csv(out,r.method(),r.pixels().size(),r.stats().rayCalls,
                    r.stats().spatialSamples,r.stats().unknownResults,number(r.seconds()),"EXPLORATORY_SINGLE_PASS_ONLY",
                    r.method().equals(BASE)?"frozen core plus selected trace logging":"verified prototype includes query objects",
                    0,0);
            require(!out.checkError(),"diagnostic write failed");
        }
        try (PrintWriter out=writer(output.resolve("verification.txt"))) {
            out.println(review.failures==0 ? "QINLING_TWO_BOUNDARY_RECORD_CHECK_PASS" : "QINLING_TWO_BOUNDARY_RECORD_CHECK_NEEDS_REVIEW");
            out.println("compared_method_pixels="+review.compared+" baseline_historical_pixels="+review.baselineHistory
                    +" baseline_saved_traces="+review.historicalTraces+" candidate_replay_traces="+review.savedReplay);
            out.println("failures="+review.failures+" input_and_reference_load_sec="+number(loadSeconds));
            out.println("Single-pass elapsed times are diagnostic. No warmups or formal performance repeats were run.");
            out.println("Baseline calls are observable at Oracle boundary; internal cache hits/purposes are not exposed.");
            out.println("Reference labels are evaluation-only; no classifier or fallback scheduler is implemented.");
            out.println("PSD/boundary/window errors and NOT_APPLICABLE are outcomes, not implementation failures. Passing record checks is not method acceptance.");
            for (String fail:review.firstFailures) out.println("FAIL "+fail);
            require(!out.checkError(),"verification summary write failed");
        }
        System.out.println("COMPARISON_OUTPUT "+output);
        if (review.failures!=0) throw new IllegalStateException("verification needs review; retain all output, failures="+review.failures);
        System.out.println("QINLING_TWO_BOUNDARY_RECORD_CHECK_PASS (formal performance not started)");
    }
}
