import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Independent same-JVM paired timing; historical answers are evaluation only. */
public final class TimeSearchPairedBenchmark {
    private static final double EPS=1e-8;
    private static final String PURE="two_boundary_assumption",CANDIDATE="two_boundary_limited5";
    private record Query(double time,int state,String purpose,boolean fresh) {}
    private record Pixel(double day,double psd,String status,String reason,int requests,int hits,int unique,
                         int anchor,int boundary,int other,int fallback,NavigableMap<Double,Integer> nodes,
                         List<Query> trace,NavigableMap<Double,Integer> evaluated,String branch) {}
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
    private static final String BASELINE="binarylocal_30min";
    private static final long BASE_CAP=927941L,CANDIDATE_CAP=333606L,TOTAL_CAP=5046188L;
    private static final class Budget { long used; }
    private static final class Pass {
        final String method;
        final double[] psd;
        final int[] queries;
        final String[] status,reason,branch;
        final List<Pixel> diagnostics;
        final RunStatistics stats=new RunStatistics();
        double seconds;
        Pass(String method,int count,boolean capture) {
            this.method=method;psd=new double[count];queries=new int[count];
            status=new String[count];reason=new String[count];branch=new String[count];
            diagnostics=capture?new ArrayList<>(count):null;
        }
    }
    private static Pixel baseline(double day,AdaptiveTimeV1.Oracle oracle) {
        AdaptiveTimeV2Local.Result result=BinaryLocalRefinement.adaptiveWithDarkGuard(day,30,oracle);
        return new Pixel(day,result.psd,Double.isFinite(result.psd)?"OK":"UNKNOWN",result.fallbackReason,
                -1,-1,result.evaluated.size(),0,0,0,0,result.integrationNodes,null,result.evaluated,"COARSE_BINARY");
    }
    private static Pass calculate(ProjectedBaselineExperiment.Inputs in,String method,Budget budget,boolean capture) {
        // Only this common loop is timed. No reference, old answer or output writer enters it.
        Pass pass=new Pass(method,in.receivers.size(),capture);
        long cap=method.equals(BASELINE)?BASE_CAP:CANDIDATE_CAP;
        long begin=System.nanoTime();
        for(int i=0;i<in.receivers.size();i++) {
            ProjectedBaselineExperiment.Receiver receiver=in.receivers.get(i);
            double day=TimeStepBaseline.daylight(receiver.lat,in.dec),rise=720-day/2;
            require(day>719 && day<722,"outside fixed Huabei spring scope");
            long before=pass.stats.rayCalls;
            AdaptiveTimeV1.Oracle oracle=t->{
                require(pass.stats.rayCalls<cap && budget.used<TOTAL_CAP,"query cap reached; no automatic retry");
                budget.used++;
                double[] angle=TimeStepBaseline.sun(receiver.lat,in.dec,rise+t);
                VisibilityStatus status=ControlledVisibility.visibleAt(in.grid,in.contract,receiver.row,receiver.col,
                        Math.max(1e-7,angle[0]),angle[1]+receiver.north,AngleReference.GRID_NORTH_CLOCKWISE,in.search,pass.stats).status;
                return status==VisibilityStatus.VISIBLE?1:status==VisibilityStatus.SHADED?0:-1;
            };
            Pixel pixel=method.equals(BASELINE)?baseline(day,oracle):hybrid(day,oracle,false);
            require(pass.stats.rayCalls-before==pixel.unique(),"ray/query accounting mismatch");
            pass.psd[i]=pixel.psd();pass.queries[i]=pixel.unique();pass.status[i]=pixel.status();
            pass.reason[i]=pixel.reason();pass.branch[i]=pixel.branch();
            if(capture)pass.diagnostics.add(pixel);
        }
        pass.seconds=(System.nanoTime()-begin)/1e9;
        require(pass.stats.rayCalls==cap,"same-input query regression changed");
        require(pass.stats.rayCalls==pass.stats.visibleResults+pass.stats.shadedResults+pass.stats.unknownResults,"state totals mismatch");
        return pass;
    }
    private static void repeatCheck(Pass warm,Pass next) {
        require(warm.method.equals(next.method) && warm.stats.rayCalls==next.stats.rayCalls
                && warm.stats.spatialSamples==next.stats.spatialSamples
                && warm.stats.visibleResults==next.stats.visibleResults && warm.stats.shadedResults==next.stats.shadedResults
                && warm.stats.unknownResults==next.stats.unknownResults,"repeat totals changed");
        for(int i=0;i<warm.psd.length;i++)require(
                (same(warm.psd[i],next.psd[i]) || (Double.isNaN(warm.psd[i]) && Double.isNaN(next.psd[i])))
                && warm.queries[i]==next.queries[i] && warm.status[i].equals(next.status[i])
                && warm.reason[i].equals(next.reason[i]) && warm.branch[i].equals(next.branch[i]),"repeat changed at index "+i);
    }
    private static Map<String,String[]> candidateRows(Path path) throws IOException {
        // This saved file contains only numeric and fixed enum fields, with no embedded CSV commas.
        Map<String,String[]> result=new LinkedHashMap<>();
        try(BufferedReader reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)) {
            require(header(reader).startsWith("\"row\",\"col\",\"daylight_minutes\",\"status\",\"branch\""),"candidate history header");
            String line;
            while((line=reader.readLine())!=null) {
                String[] fields=line.replace("\"","").split(",",-1);
                require(fields.length==27 && result.putIfAbsent(fields[0]+","+fields[1],fields)==null,"candidate history duplicate/width");
            }
        }
        require(result.size()==16384,"candidate history incomplete");return result;
    }
    private record Accuracy(int valid,int unknown,int gt1,int gt5,int dual,int dark,int limited,int estimated,
                            double mae,double max,double firstMax,double lastMax,int boundaryMismatch,int windowMismatch) {}
    private static boolean equalOrMissing(double a,double b) {
        return same(a,b) || (Double.isNaN(a) && Double.isNaN(b));
    }
    private static double numeric(String value) { return value.isEmpty()?Double.NaN:Double.parseDouble(value); }
    private static Accuracy saveWarm(Path output,ProjectedBaselineExperiment.Inputs in,Pass pass,
                                      Map<String,String[]> ref,Map<String,String[]> intervals,
                                      Map<String,String[]> previous) throws IOException {
        int valid=0,unknown=0,gt1=0,gt5=0,dual=0,dark=0,limited=0,estimated=0,boundaryMismatch=0,windowMismatch=0;
        double sum=0,max=0,firstMax=0,lastMax=0;
        try(PrintWriter pixels=writer(output.resolve(pass.method+"_pixels.csv"));
            PrintWriter nodes=writer(output.resolve(pass.method+"_integration_nodes.csv"));
            PrintWriter evaluated=writer(output.resolve(pass.method+"_evaluated_nodes.csv"))) {
            csv(pixels,"row","col","daylight_minutes","status","branch","reason","psd_minutes","reference_psd",
                    "absolute_error","unique","first_boundary","reference_first_boundary","last_boundary","reference_last_boundary",
                    "light_intervals","reference_light_intervals","estimated_zero");
            csv(nodes,"row","col","minute_since_sunrise","state");csv(evaluated,"row","col","minute_since_sunrise","state");
            for(int i=0;i<in.receivers.size();i++) {
                var receiver=in.receivers.get(i);Pixel p=pass.diagnostics.get(i);
                String[] r=ref.get(receiver.key()),s=intervals.get(receiver.key()),old=previous.get(receiver.key());
                require(r!=null && s!=null && old!=null,"missing saved pixel");
                require(same(p.day(),Double.parseDouble(r[5])) && same(Double.parseDouble(r[6]),Double.parseDouble(s[8])),"saved day/integral mismatch");
                boolean original=pass.method.equals(BASELINE);
                require(equalOrMissing(p.psd(),numeric(old[6])) && p.unique()==Integer.parseInt(old[original?7:14]),"historical pixel regression: "+receiver.key());
                if(original)require(p.reason().equals(old[8]),"baseline fallback changed");
                else require(p.status().equals(old[3]) && p.branch().equals(old[4]) && p.reason().equals(old[5]),"candidate branch changed");
                SunlightIntervalStats.Result structure=structure(p.nodes());
                require(equalOrMissing(structure.psdMinutes(),p.psd()),"node integral differs from result");
                require(p.nodes().entrySet().stream().allMatch(e->Objects.equals(p.evaluated().get(e.getKey()),e.getValue())),"integration node not evaluated");
                double first=structure.firstBoundary(),last=structure.lastBoundary(),rf=numeric(s[5]),rl=numeric(s[6]);
                if(!original)require(equalOrMissing(first,numeric(old[20])) && equalOrMissing(last,numeric(old[22]))
                        && structure.lightIntervals()==Integer.parseInt(old[18]),"candidate boundary/window regression");
                double error=Math.abs(p.psd()-Double.parseDouble(r[6]));
                if(Double.isFinite(p.psd())) {valid++;sum+=error;max=Math.max(max,error);if(error>1+EPS)gt1++;if(error>5+EPS)gt5++;}
                else unknown++;
                if(!equalOrMissing(first,rf) || !equalOrMissing(last,rl))boundaryMismatch++;
                if(structure.lightIntervals()!=Integer.parseInt(s[3]))windowMismatch++;
                if(Double.isFinite(first) && Double.isFinite(rf))firstMax=Math.max(firstMax,Math.abs(first-rf));
                if(Double.isFinite(last) && Double.isFinite(rl))lastMax=Math.max(lastMax,Math.abs(last-rl));
                if(p.branch().equals("TWO_BOUNDARY"))dual++;if(p.branch().equals("MIDDAY_DARK_FALLBACK"))dark++;
                if(p.reason().startsWith("DARK_5MIN_"))limited++;if(p.reason().equals("DARK_5MIN_ESTIMATED_ZERO"))estimated++;
                csv(pixels,receiver.row,receiver.col,number(p.day()),p.status(),p.branch(),p.reason(),number(p.psd()),r[6],number(error),p.unique(),
                        number(first),s[5],number(last),s[6],structure.lightIntervals(),s[3],p.reason().equals("DARK_5MIN_ESTIMATED_ZERO"));
                for(var e:p.nodes().entrySet())csv(nodes,receiver.row,receiver.col,number(e.getKey()),e.getValue());
                for(var e:p.evaluated().entrySet())csv(evaluated,receiver.row,receiver.col,number(e.getKey()),e.getValue());
            }
            require(!pixels.checkError() && !nodes.checkError() && !evaluated.checkError(),"warm diagnostic output failed");
        }
        return new Accuracy(valid,unknown,gt1,gt5,dual,dark,limited,estimated,valid==0?Double.NaN:sum/valid,max,firstMax,lastMax,boundaryMismatch,windowMismatch);
    }
    private static void timing(PrintWriter out,Pass pass,String phase,int pair,int order,long cumulative) {
        csv(out,phase,pair,order,pass.method,pass.psd.length,number(pass.seconds),pass.stats.rayCalls,pass.stats.spatialSamples,
                pass.stats.visibleResults,pass.stats.shadedResults,pass.stats.unknownResults,pass.stats.elapsedNanos,cumulative);
        out.flush();require(!out.checkError(),"timing output failed");
    }
    private static void saveFormal(Path output,ProjectedBaselineExperiment.Inputs in,Pass pass,int pair) throws IOException {
        try(PrintWriter out=writer(output.resolve(pass.method+"_formal_"+pair+".csv"))) {
            csv(out,"row","col","psd_minutes","unique","status","reason","branch");
            for(int i=0;i<in.receivers.size();i++) {
                var r=in.receivers.get(i);csv(out,r.row,r.col,number(pass.psd[i]),pass.queries[i],pass.status[i],pass.reason[i],pass.branch[i]);
            }
            require(!out.checkError(),"formal pixel output failed");
        }
    }
    private static void summaries(Path output,Map<String,List<Pass>> formal,Map<String,Accuracy> accuracy) throws IOException {
        try(PrintWriter out=writer(output.resolve("summary.csv"));PrintWriter pairs=writer(output.resolve("paired_timings.csv"))) {
            csv(out,"method","timing_role","repeats","median_sec","mean_sec","min_sec","max_sec","queries_per_pass","spatial_samples_per_pass",
                    "valid_pixels","unknown_pixels","MAE_min","MAX_abs_error_min","error_gt1_pixels","error_gt5_pixels","first_boundary_MAX_min","last_boundary_MAX_min",
                    "boundary_mismatch_pixels","window_mismatch_pixels","two_boundary_pixels","dark_fallback_pixels","limited5_pixels","estimated_zero_pixels");
            for(String method:List.of(BASELINE,CANDIDATE)) {
                List<Pass> passes=formal.get(method);double[] seconds=passes.stream().mapToDouble(p->p.seconds).sorted().toArray();
                Pass p=passes.get(0);Accuracy a=accuracy.get(method);
                csv(out,method,"NEW_SAME_JVM_PAIRED",3,number(seconds[1]),number(Arrays.stream(seconds).average().orElseThrow()),number(seconds[0]),number(seconds[2]),
                        p.stats.rayCalls,p.stats.spatialSamples,a.valid(),a.unknown(),number(a.mae()),number(a.max()),a.gt1(),a.gt5(),number(a.firstMax()),number(a.lastMax()),
                        a.boundaryMismatch(),a.windowMismatch(),a.dual(),a.dark(),a.limited(),a.estimated());
            }
            csv(pairs,"pair","order","baseline_sec","candidate_sec","saved_sec","saved_fraction","speedup","baseline_queries","candidate_queries");
            for(int i=0;i<3;i++) {
                Pass a=formal.get(BASELINE).get(i),b=formal.get(CANDIDATE).get(i);
                csv(pairs,i+1,i==1?"candidate_then_baseline":"baseline_then_candidate",number(a.seconds),number(b.seconds),number(a.seconds-b.seconds),
                        number(1-b.seconds/a.seconds),number(a.seconds/b.seconds),a.stats.rayCalls,b.stats.rayCalls);
            }
            require(!out.checkError() && !pairs.checkError(),"summary output failed");
        }
    }
    private static void manifest(Path root,Path output,Path classes,ProjectedBaselineExperiment.Inputs in,Path history,Path previous) throws Exception {
        Set<Path> files=new TreeSet<>();files.add(in.config);
        for(String key:List.of("dem","receivers","metadata"))files.add(in.resolve(in.p.getProperty(key)));
        Path reference=in.resolve(in.p.getProperty("reference_run"));
        for(Path run:List.of(reference,history))for(String f:List.of("config.properties","receivers.csv","data_metadata.json"))files.add(run.resolve(f));
        for(String f:List.of("psd.csv","intervals.csv"))files.add(reference.resolve("fixed_1min_r1/"+f));
        files.add(history.resolve("binarylocal_30min_r1/psd.csv"));files.add(previous.resolve("pixels.csv"));
        for(String f:List.of("build_sources.sha256","build_classes.sha256"))files.add(classes.getParent().resolve(f));
        for(String f:List.of("compile_time_search_paired.ps1","run_time_search_paired.ps1"))files.add(root.resolve("scripts/"+f));
        files.add(root.resolve("user_runs/time_search_paired_preparation_20261110_001/protocol.json"));
        try(var stream=Files.list(root.resolve("src"))){stream.filter(p->p.toString().endsWith(".java")).forEach(files::add);}
        try(var stream=Files.walk(classes)){stream.filter(p->Files.isRegularFile(p) && p.toString().endsWith(".class")).forEach(files::add);}
        try(PrintWriter out=writer(output.resolve("manifest.csv"))) {
            csv(out,"path","sha256");for(Path p:files)csv(out,root.relativize(p),sha(p));require(!out.checkError(),"manifest output failed");
        }
    }
    public static void main(String[] args) throws Exception {
        require(args.length==4 && args[0].equals("--run"),"usage: --run config new-output approved-query-cap");
        long approved=Long.parseLong(args[3]);
        require(approved>=TOTAL_CAP,"paired protocol requires explicit approval for 5046188 new queries; previous cap is 1206589; no query executed");
        Path config=Path.of(args[1]).toAbsolutePath().normalize(),root=config.getParent().getParent();
        Path output=Path.of(args[2]).toAbsolutePath().normalize();
        require(output.getParent().equals(root.resolve("user_runs")) && output.getFileName().toString().startsWith("time_search_paired_"),"new isolated output required");
        Path classes=Path.of(TimeSearchPairedBenchmark.class.getProtectionDomain().getCodeSource().getLocation().toURI());verifyBuild(classes);
        ProjectedBaselineExperiment.Inputs in=new ProjectedBaselineExperiment.Inputs(config);
        for(var e:Map.ofEntries(Map.entry("experiment_id","huabei_time_search_paired_v1"),Map.entry("date","2023-03-21"),
                Map.entry("projected_crs","EPSG:32650"),Map.entry("rows","128"),Map.entry("cols","128"),Map.entry("row_start","114"),Map.entry("col_start","114"),
                Map.entry("cellsize_m","90.0"),Map.entry("search_spacing_m","50"),Map.entry("search_samples","200"),Map.entry("methods",BASELINE),
                Map.entry("warmups","1"),Map.entry("repeats","3"),Map.entry("elevation_unit_assumption","m_from_project_SRTM_record")).entrySet())
            require(e.getValue().equals(in.p.getProperty(e.getKey())),"unsupported scope: "+e.getKey());
        require(in.receivers.size()==16384,"full original ROI required");
        ProjectedBaselineExperiment.loadReference(in);
        Path reference=in.resolve(in.p.getProperty("reference_run")),history=in.resolve(in.p.getProperty("regression_run"));
        Path previous=in.resolve(in.p.getProperty("candidate_regression_run"));verifyHistoryInputs(in,history);
        verifyHistoryInputs(in,reference);
        require(Files.mismatch(previous.resolve("config.properties"),root.resolve("configs/huabei_time_search_transfer.properties"))==-1,"candidate history config mismatch");
        String psdHeader="row,col,latitude,longitude,elevation_m,daylight_minutes,psd_minutes,";
        Map<String,String[]> ref=pixelRows(reference.resolve("fixed_1min_r1/psd.csv"),psdHeader),
                intervals=pixelRows(reference.resolve("fixed_1min_r1/intervals.csv"),"row,col,category,light_intervals,"),
                historical=pixelRows(history.resolve("binarylocal_30min_r1/psd.csv"),psdHeader),prior=candidateRows(previous.resolve("pixels.csv"));
        require(ref.size()==16384 && intervals.size()==16384 && historical.size()==16384,"incomplete saved reference/history");
        Files.createDirectory(output);Files.copy(config,output.resolve("config.properties"));
        Files.copy(root.resolve("user_runs/time_search_paired_preparation_20261110_001/protocol.json"),output.resolve("protocol.json"));
        Files.writeString(output.resolve("environment.txt"),"java="+System.getProperty("java.runtime.version")+"\nvm="+System.getProperty("java.vm.name")
                +"\nos="+System.getProperty("os.name")+" "+System.getProperty("os.version")+"\nmax_heap="+Runtime.getRuntime().maxMemory()
                +"\napproved_query_cap="+approved+"\neffective_query_cap="+TOTAL_CAP+"\n",StandardCharsets.UTF_8);
        Budget budget=new Budget();Map<String,Pass> warm=new HashMap<>();Map<String,Accuracy> accuracy=new HashMap<>();
        Map<String,List<Pass>> formal=new LinkedHashMap<>();formal.put(BASELINE,new ArrayList<>());formal.put(CANDIDATE,new ArrayList<>());
        try(PrintWriter timings=writer(output.resolve("run_timings.csv"))) {
            csv(timings,"phase","pair","order","method","pixels","compute_sec","real_queries","spatial_samples","visible","shaded","unknown","ray_elapsed_nanos","cumulative_real_queries");
            int order=0;
            for(String method:List.of(CANDIDATE,BASELINE)) {
                Pass pass=calculate(in,method,budget,true);timing(timings,pass,"WARMUP_DIAGNOSTIC_EXCLUDED",0,++order,budget.used);
                accuracy.put(method,saveWarm(output,in,pass,ref,intervals,method.equals(BASELINE)?historical:prior));
                pass.diagnostics.clear();warm.put(method,pass);
            }
            // Release diagnostic node maps before all formal passes; no forced GC or detailed per-ray log.
            ref.clear();intervals.clear();historical.clear();prior.clear();
            for(int pair=1;pair<=3;pair++) {
                List<String> methods=pair==2?List.of(CANDIDATE,BASELINE):List.of(BASELINE,CANDIDATE);order=0;
                for(String method:methods) {
                    Pass pass=calculate(in,method,budget,false);timing(timings,pass,"FORMAL",pair,++order,budget.used);
                    repeatCheck(warm.get(method),pass);formal.get(method).add(pass);
                }
            }
        }
        require(budget.used==TOTAL_CAP,"complete protocol query total changed");
        for(String method:List.of(BASELINE,CANDIDATE))for(int i=0;i<3;i++)saveFormal(output,in,formal.get(method).get(i),i+1);
        summaries(output,formal,accuracy);manifest(root,output,classes,in,history,previous);
        Files.writeString(output.resolve("verification.txt"),"TIME_SEARCH_PAIRED_RECORD_PASS\nnew_queries="+budget.used+"\n"
                +"Both methods newly computed: 1 full warmup and 3 formal passes each. Same JVM, receiver loop, spatial oracle, statistics and timer scope.\n"
                +"Warmups: full integration/evaluated nodes and boundary/window evaluation. Formal: scalar outputs saved for every pixel and repeat.\n"
                +"Historical results used only after computation for regression. Fixed1 is a conditional reference. Huabei does not validate untriggered dark/limited5 branches.\n",StandardCharsets.UTF_8);
        System.out.println("PAIRED_OUTPUT "+output);System.out.println("TIME_SEARCH_PAIRED_COMPLETE");
    }
}
