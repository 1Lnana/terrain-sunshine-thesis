import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Full Huabei transfer check. Fixed Qinling rules; old binary benchmark is explicitly historical.
 * Only one candidate is computed: central-1024 warmup, then three whole-ROI timed passes.
 */
public final class HuabeiTimeSearchTransfer {
    private static final double EPS=1e-8;
    private static final String PURE="two_boundary_assumption",CANDIDATE="two_boundary_limited5";
    private record Query(double time,int state,String purpose,boolean fresh) {}
    private record Pixel(double day,double psd,String status,String reason,int requests,int hits,int unique,
                         int anchor,int boundary,int other,int fallback,NavigableMap<Double,Integer> nodes,
                         List<Query> trace,NavigableMap<Double,Integer> evaluated,String branch) {}
    private record Calculation(List<Pixel> pixels,RunStatistics stats,double seconds,String phase,int repeat) {}
    private static final class Budget { long used; }
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
    private static final class Memo {
        final AdaptiveTimeV1.Oracle oracle;
        final NavigableMap<Double,Integer> values=new TreeMap<>();
        final List<Query> trace;
        final int axisLimit;
        int requests,hits,anchor,boundary,fallback;
        Memo(AdaptiveTimeV1.Oracle oracle,boolean capture,double day) {
            this.oracle=oracle; this.trace=capture?new ArrayList<>():null; this.axisLimit=(int)Math.ceil(day)+1;
        }
        int get(double time,String purpose) {
            requests++; Integer state=values.get(time); boolean fresh=state==null;
            if (fresh) {
                boolean isFallback=purpose.equals("fallback");
                require(isFallback || anchor+boundary<24,"direct dual-boundary query bound exceeded");
                require(values.size()<axisLimit,"pixel minute-node budget reached");
                state=oracle.at(time); require(state>=-1 && state<=1,"invalid visibility state");
                values.put(time,state);
                if (isFallback) fallback++; else if (purpose.equals("anchor")) anchor++; else boundary++;
            } else hits++;
            if (trace!=null) trace.add(new Query(time,state,purpose,fresh));
            return state;
        }
    }
    private static Pixel hybrid(double day,AdaptiveTimeV1.Oracle oracle,boolean capture) {
        Memo memo=new Memo(oracle,capture,day);
        double mid=Math.floor(day/2);
        TimeStructureSearch.Result direct=TimeStructureSearch.run(PURE,day,
                t->memo.get(t,t==mid?"anchor":"boundary"));
        double psd=direct.psd; String status=direct.status,reason="NO_DARK_FALLBACK";
        String branch=status.equals("OK")?"TWO_BOUNDARY":"UNKNOWN";
        NavigableMap<Double,Integer> nodes=direct.nodes;
        if (status.equals("NOT_APPLICABLE")) {
            require(memo.values.size()==1 && memo.values.get(mid)==0,"fallback requires observed dark midpoint");
            AdaptiveTimeV2Local.Result old=LimitedDarkBinaryRefinement.adaptive(day,
                    t->memo.get(t,"fallback"));
            require(old.integrationNodes.equals(old.evaluated),"fallback integration contract changed");
            psd=old.psd; status=Double.isFinite(psd)?"OK":"UNKNOWN";
            reason=old.fallbackReason; branch="MIDDAY_DARK_FALLBACK"; nodes=old.integrationNodes;
        }
        int unique=memo.values.size();
        require(memo.requests==memo.hits+unique && unique==memo.anchor+memo.boundary+memo.fallback,"hybrid query accounting");
        return new Pixel(day,psd,status,reason,memo.requests,memo.hits,unique,memo.anchor,memo.boundary,0,
                memo.fallback,nodes,memo.trace,memo.values,branch);
    }
    private static Calculation calculate(ProjectedBaselineExperiment.Inputs in,
                                         List<ProjectedBaselineExperiment.Receiver> receivers,
                                         Budget budget,String phase,int repeat) {
        RunStatistics stats=new RunStatistics();List<Pixel> pixels=new ArrayList<>();
        long cap=24L*receivers.size(),begin=System.nanoTime();
        for (ProjectedBaselineExperiment.Receiver receiver:receivers) {
            double day=TimeStepBaseline.daylight(receiver.lat,in.dec),rise=720-day/2;
            require(day>719 && day<722,"outside fixed Huabei spring daylight scope");
            long before=stats.rayCalls;
            AdaptiveTimeV1.Oracle oracle=t->{
                require(stats.rayCalls<cap && budget.used<1206589L,"real query budget reached; no automatic retry");
                budget.used++;
                double[] angle=TimeStepBaseline.sun(receiver.lat,in.dec,rise+t);
                VisibilityStatus status=ControlledVisibility.visibleAt(in.grid,in.contract,receiver.row,receiver.col,
                        Math.max(1e-7,angle[0]),angle[1]+receiver.north,AngleReference.GRID_NORTH_CLOCKWISE,in.search,stats).status;
                return status==VisibilityStatus.VISIBLE?1:status==VisibilityStatus.SHADED?0:-1;
            };
            Pixel pixel=hybrid(day,oracle,false);
            require(stats.rayCalls-before==pixel.unique(),"ray/query accounting mismatch");
            pixels.add(pixel);
        }
        return new Calculation(pixels,stats,(System.nanoTime()-begin)/1e9,phase,repeat);
    }
    private static void repeatCheck(Calculation first,Calculation next) {
        require(first.pixels().size()==next.pixels().size() && first.stats().rayCalls==next.stats().rayCalls
                && first.stats().spatialSamples==next.stats().spatialSamples,"repeat totals changed");
        for (int i=0;i<first.pixels().size();i++) {
            Pixel a=first.pixels().get(i),b=next.pixels().get(i);
            require((same(a.psd(),b.psd()) || (Double.isNaN(a.psd()) && Double.isNaN(b.psd())))
                    && a.status().equals(b.status()) && a.reason().equals(b.reason()) && a.unique()==b.unique()
                    && a.requests()==b.requests() && a.hits()==b.hits() && sameNodes(a.nodes(),b.nodes())
                    && sameNodes(a.evaluated(),b.evaluated()),"pixel repeat changed at index "+i);
        }
    }
    private static String[] summary(Path historical) throws IOException {
        try (BufferedReader reader=Files.newBufferedReader(historical.resolve("summary.csv"),StandardCharsets.UTF_8)) {
            require(header(reader).startsWith("method,runtime_sec,runtime_min_sec,runtime_max_sec,repeats,rayCalls,"),"historical summary header");
            String line;
            while ((line=reader.readLine())!=null) { String[] p=line.split(",",-1);if (p[0].equals("binarylocal_30min")) return p; }
        }
        throw new IllegalArgumentException("missing historical binary summary");
    }
    private static Map<String,NavigableMap<Double,Integer>> replay(Path root) throws IOException {
        Map<String,NavigableMap<Double,Integer>> data=new HashMap<>();
        try (BufferedReader reader=Files.newBufferedReader(root.resolve("integration_nodes.csv"),StandardCharsets.UTF_8)) {
            require(header(reader).equals("\"case_id\",\"method\",\"time\",\"state\""),"saved replay header");
            String line;
            while ((line=reader.readLine())!=null) {
                String[] p=line.replace("\"","").split(",",-1);
                if (p[0].startsWith("huabei128_spring_") && p[1].equals(PURE))
                    require(data.computeIfAbsent(p[0].substring("huabei128_spring_".length()).replace('_',','),k->new TreeMap<>())
                            .putIfAbsent(Double.parseDouble(p[2]),Integer.parseInt(p[3]))==null,"duplicate replay node");
            }
        }
        require(data.size()==3,"saved replay count");return data;
    }
    private static void save(Path output,ProjectedBaselineExperiment.Inputs in,Calculation run,
                             Map<String,String[]> ref,Map<String,String[]> intervals,Map<String,String[]> baseline,
                             Map<String,NavigableMap<Double,Integer>> replay) throws IOException {
        int checked=0;
        try (PrintWriter pixels=writer(output.resolve("pixels.csv"));PrintWriter nodes=writer(output.resolve("integration_nodes.csv"));
             PrintWriter evaluated=writer(output.resolve("evaluated_nodes.csv"))) {
            csv(pixels,"row","col","daylight_minutes","status","branch","reason","psd_minutes","reference_psd",
                    "signed_error","absolute_error","baseline_psd","baseline_queries","requests","cache_hits","unique",
                    "anchor","boundary","fallback","light_intervals","reference_light_intervals","first_boundary",
                    "reference_first_boundary","last_boundary","reference_last_boundary","first_error","last_error","estimated_zero");
            csv(nodes,"row","col","minute_since_sunrise","state");csv(evaluated,"row","col","minute_since_sunrise","state");
            for (int i=0;i<in.receivers.size();i++) {
                ProjectedBaselineExperiment.Receiver receiver=in.receivers.get(i);Pixel p=run.pixels().get(i);
                String key=receiver.key();String[] r=ref.get(key),s=intervals.get(key),b=baseline.get(key);
                SunlightIntervalStats.Result current=p.status().equals("OK")?structure(p.nodes()):null;
                double error=p.psd()-Double.parseDouble(r[6]);
                csv(pixels,receiver.row,receiver.col,number(p.day()),p.status(),p.branch(),p.reason(),number(p.psd()),r[6],
                        number(error),number(Math.abs(error)),b[6],b[7],p.requests(),p.hits(),p.unique(),p.anchor(),p.boundary(),p.fallback(),
                        current==null?"":current.lightIntervals(),s[3],current==null?"":number(current.firstBoundary()),s[5],
                        current==null?"":number(current.lastBoundary()),s[6],current==null?"":number(current.firstBoundary()-Double.parseDouble(s[5])),
                        current==null?"":number(current.lastBoundary()-Double.parseDouble(s[6])),p.reason().equals("DARK_5MIN_ESTIMATED_ZERO"));
                if (replay.containsKey(key)) { require(sameNodes(p.nodes(),replay.get(key)),"saved Huabei replay mismatch: "+key);checked++; }
                for (Map.Entry<Double,Integer> e:p.nodes().entrySet()) csv(nodes,receiver.row,receiver.col,number(e.getKey()),e.getValue());
                for (Map.Entry<Double,Integer> e:p.evaluated().entrySet()) csv(evaluated,receiver.row,receiver.col,number(e.getKey()),e.getValue());
            }
            require(!pixels.checkError() && !nodes.checkError() && !evaluated.checkError(),"pixel output failed");
        }
        require(checked==3,"replay coverage incomplete");
    }
    private static void summarize(Path output,ProjectedBaselineExperiment.Inputs in,List<Calculation> runs,
                                  Map<String,String[]> ref,String[] historical) throws IOException {
        double[] seconds=runs.stream().mapToDouble(Calculation::seconds).sorted().toArray();
        int ok=0,unknown=0,dual=0,dark=0,limited=0,estimated=0,gt1=0,gt5=0;double sum=0,max=0;
        Calculation first=runs.get(0);
        for (int i=0;i<in.receivers.size();i++) {
            Pixel p=first.pixels().get(i);
            if (p.status().equals("OK")) {ok++;double e=Math.abs(p.psd()-Double.parseDouble(ref.get(in.receivers.get(i).key())[6]));
                sum+=e;max=Math.max(max,e);if(e>1+EPS)gt1++;if(e>5+EPS)gt5++;}else unknown++;
            if (p.branch().equals("TWO_BOUNDARY"))dual++;if(p.branch().equals("MIDDAY_DARK_FALLBACK"))dark++;
            if (p.reason().startsWith("DARK_5MIN_"))limited++;if(p.reason().equals("DARK_5MIN_ESTIMATED_ZERO"))estimated++;
        }
        try (PrintWriter out=writer(output.resolve("summary.csv"))) {
            csv(out,"method","timing_role","repeats","median_sec","mean_sec","min_sec","max_sec","pixels","valid_pixels",
                    "queries_per_pass","spatial_samples_per_pass","MAE_min","MAX_abs_error_min","error_gt1_pixels","error_gt5_pixels",
                    "two_boundary_pixels","dark_fallback_pixels","limited5_pixels","estimated_zero_pixels","unknown_pixels");
            csv(out,"binarylocal_30min","REUSED_HISTORICAL_MEDIAN",historical[4],historical[1],"",historical[2],historical[3],
                    16384,historical[7],historical[5],historical[6],historical[11],historical[12],
                    Math.round(Double.parseDouble(historical[13])*16384),"","","","","",historical[8]);
            csv(out,CANDIDATE,"NEW_3_REPEAT_MEDIAN_CROSS_BATCH_BASELINE",3,number(seconds[1]),number(Arrays.stream(seconds).average().orElseThrow()),
                    number(seconds[0]),number(seconds[2]),16384,ok,first.stats().rayCalls,first.stats().spatialSamples,
                    number(ok==0?Double.NaN:sum/ok),number(max),gt1,gt5,dual,dark,limited,estimated,unknown);
            require(!out.checkError(),"summary output failed");
        }
    }
    private static void manifest(Path root,Path output,Path classes,ProjectedBaselineExperiment.Inputs in,Path history,Path replay) throws Exception {
        Set<Path> files=new TreeSet<>();files.add(in.config);
        for(String key:List.of("dem","receivers","metadata"))files.add(in.resolve(in.p.getProperty(key)));
        Path reference=in.resolve(in.p.getProperty("reference_run"));
        for(Path run:List.of(reference,history))for(String file:List.of("config.properties","receivers.csv","data_metadata.json","summary.csv"))
            if(Files.isRegularFile(run.resolve(file)))files.add(run.resolve(file));
        for(String f:List.of("psd.csv","intervals.csv"))files.add(reference.resolve("fixed_1min_r1/"+f));
        files.add(history.resolve("binarylocal_30min_r1/psd.csv"));files.add(replay.resolve("integration_nodes.csv"));
        files.add(classes.getParent().resolve("build_sources.sha256"));files.add(classes.getParent().resolve("build_classes.sha256"));
        files.add(root.resolve("scripts/compile_huabei_time_search_transfer.ps1"));files.add(root.resolve("scripts/run_huabei_time_search_transfer.ps1"));
        try(var stream=Files.list(root.resolve("src"))){stream.filter(p->p.toString().endsWith(".java")).forEach(files::add);}
        try(var stream=Files.walk(classes)){stream.filter(p->Files.isRegularFile(p) && p.toString().endsWith(".class")).forEach(files::add);}
        try(PrintWriter out=writer(output.resolve("manifest.csv"))){csv(out,"path","sha256");for(Path p:files)csv(out,root.relativize(p),sha(p));require(!out.checkError(),"manifest output failed");}
    }
    public static void main(String[] args) throws Exception {
        require(args.length==3 && args[0].equals("--run"),"usage: --run config new-output");
        Path config=Path.of(args[1]).toAbsolutePath().normalize(),root=config.getParent().getParent();
        Path output=Path.of(args[2]).toAbsolutePath().normalize();
        require(output.getParent().equals(root.resolve("user_runs")) && output.getFileName().toString().startsWith("huabei_time_search_transfer_"),"new isolated output required");
        Path classes=Path.of(HuabeiTimeSearchTransfer.class.getProtectionDomain().getCodeSource().getLocation().toURI());verifyBuild(classes);
        ProjectedBaselineExperiment.Inputs in=new ProjectedBaselineExperiment.Inputs(config);
        for(Map.Entry<String,String> e:Map.ofEntries(Map.entry("experiment_id","huabei_time_search_transfer_v1"),Map.entry("date","2023-03-21"),
                Map.entry("projected_crs","EPSG:32650"),Map.entry("rows","128"),Map.entry("cols","128"),Map.entry("row_start","114"),Map.entry("col_start","114"),
                Map.entry("cellsize_m","90.0"),Map.entry("search_spacing_m","50"),Map.entry("search_samples","200"),Map.entry("methods","binarylocal_30min"),
                Map.entry("warmups","1"),Map.entry("repeats","3")).entrySet())require(e.getValue().equals(in.p.getProperty(e.getKey())),"unsupported scope: "+e.getKey());
        ProjectedBaselineExperiment.loadReference(in);
        Path reference=in.resolve(in.p.getProperty("reference_run")),history=in.resolve(in.p.getProperty("regression_run"));
        Path replayRoot=in.resolve(in.p.getProperty("replay_run"));verifyHistoryInputs(in,history);
        String psdHeader="row,col,latitude,longitude,elevation_m,daylight_minutes,psd_minutes,";
        Map<String,String[]> ref=pixelRows(reference.resolve("fixed_1min_r1/psd.csv"),psdHeader),
                intervals=pixelRows(reference.resolve("fixed_1min_r1/intervals.csv"),"row,col,category,light_intervals,"),
                baseline=pixelRows(history.resolve("binarylocal_30min_r1/psd.csv"),psdHeader);
        require(ref.size()==16384 && intervals.size()==16384 && baseline.size()==16384,"incomplete saved reference/baseline");
        for(ProjectedBaselineExperiment.Receiver r:in.receivers)require(ref.containsKey(r.key()) && intervals.containsKey(r.key()) && baseline.containsKey(r.key())
                && same(Double.parseDouble(ref.get(r.key())[6]),Double.parseDouble(intervals.get(r.key())[8])),"reference pixel/integral mismatch");
        String[] historical=summary(history);require(Long.parseLong(historical[5])==927941 && historical[4].equals("3"),"historical benchmark contract");
        Files.createDirectory(output);Files.copy(config,output.resolve("config.properties"));
        Files.copy(history.resolve("binarylocal_30min_r1/psd.csv"),output.resolve("historical_binary_pixels.csv"));
        Files.copy(history.resolve("summary.csv"),output.resolve("historical_summary.csv"));
        Files.writeString(output.resolve("baseline_source.txt"),history+"\nHistorical binary timings are reused; not same-batch comparison.\n",StandardCharsets.UTF_8);
        List<ProjectedBaselineExperiment.Receiver> warm=new ArrayList<>();
        for(ProjectedBaselineExperiment.Receiver r:in.receivers)if(r.row>=162 && r.row<194 && r.col>=162 && r.col<194)warm.add(r);
        require(warm.size()==1024,"warmup subset size");Budget budget=new Budget();
        List<Calculation> formal=new ArrayList<>();
        try(PrintWriter timing=writer(output.resolve("run_timings.csv"))) {
            csv(timing,"phase","repeat","pixels","compute_sec","real_queries","spatial_samples","cumulative_real_queries");
            Calculation warmed=calculate(in,warm,budget,"WARMUP_EXCLUDED",0);
            csv(timing,warmed.phase(),0,1024,number(warmed.seconds()),warmed.stats().rayCalls,warmed.stats().spatialSamples,budget.used);timing.flush();
            for(int i=1;i<=3;i++) {
                Calculation run=calculate(in,in.receivers,budget,"FORMAL",i);
                csv(timing,run.phase(),i,16384,number(run.seconds()),run.stats().rayCalls,run.stats().spatialSamples,budget.used);timing.flush();
                if(i==1){save(output,in,run,ref,intervals,baseline,replay(replayRoot));formal.add(run);}
                else {repeatCheck(formal.get(0),run);formal.add(run);}
            }
            require(!timing.checkError(),"timing output failed");
        }
        summarize(output,in,formal,ref,historical);manifest(root,output,classes,in,history,replayRoot);
        try(PrintWriter out=writer(output.resolve("verification.txt"))) {
            out.println("HUABEI_TIME_SEARCH_TRANSFER_RECORD_PASS");out.println("pixels=16384 repeats=3 warmup_pixels=1024 repeated_pixels_checked=32768 replay_cases=3");
            out.println("new_real_queries="+budget.used+" cap=1206589 original_binary_new_queries=0");
            out.println("Fixed midpoint/dual/dark-coarse/five-minute rules; no reference-dependent routing or parameter changes.");
            out.println("Historical baseline is reused. Timing ratios are cross-batch; branch counts determine actual validation coverage.");
            require(!out.checkError(),"verification output failed");
        }
        System.out.println("TRANSFER_OUTPUT "+output);System.out.println("HUABEI_TIME_SEARCH_TRANSFER_COMPLETE");
    }
}
