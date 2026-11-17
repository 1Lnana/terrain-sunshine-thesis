import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;

/** Independent performance path. Default wrapper invokes only small --smoke regression.
 * --benchmark needs an explicit flag and a matching smoke receipt; never calls T2 verification.
 */
public final class StripT2PerformanceExperiment {
    private enum Method { BLOCK16, STRIP16 }
    @FunctionalInterface private interface VisibilityQuery {
        VisibilityResult at(ProjectedBaselineExperiment.Receiver receiver, double altitude, double bearing);
    }
    private static final class Prepared {
        final VisibilityQuery query; final long payload;
        Prepared(VisibilityQuery query,long payload) { this.query=query; this.payload=payload; }
    }
    private static void require(boolean value,String message) {
        if(!value)throw new IllegalStateException(message);
    }
    private static String hash(Path file) throws Exception {
        MessageDigest d=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(file)) {
            byte[] b=new byte[65536]; int n; while((n=in.read(b))!=-1)d.update(b,0,n);
        }
        return HexFormat.of().formatHex(d.digest());
    }
    private static String json(Object value) {
        if(value==null)return "null";
        if(value instanceof Boolean || value instanceof Number)return value.toString();
        if(value instanceof Map<?,?> map) {
            StringJoiner j=new StringJoiner(",","{","}");
            for(var e:map.entrySet())j.add(json(e.getKey().toString())+":"+json(e.getValue()));
            return j.toString();
        }
        if(value instanceof Iterable<?> values) {
            StringJoiner j=new StringJoiner(",","[","]");for(Object v:values)j.add(json(v));return j.toString();
        }
        return "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"")
                .replace("\r","\\r").replace("\n","\\n").replace("\t","\\t")+"\"";
    }
    private static void writeJson(Path file,Object data) throws IOException {
        Files.writeString(file,json(data)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
    }
    private static String stringField(String text,String key) {
        Matcher m=Pattern.compile("\""+Pattern.quote(key)+"\"\\s*:\\s*\"([^\"]*)\"").matcher(text);
        require(m.find(),"missing JSON field: "+key);return m.group(1);
    }
    private static void checkVerifiedInputs(ProjectedBaselineExperiment.Inputs in) throws Exception {
        require("2023-12-21".equals(in.p.getProperty("date")) && in.grid.rows==356 && in.grid.cols==356
                && in.grid.cellSize==90 && in.receivers.size()==16384 && in.integer("row_start")==114
                && in.integer("col_start")==114 && in.integer("rows")==128 && in.integer("cols")==128
                && in.search.sampleSpacingGridUnits==50 && in.search.maxSamples==200
                && in.methods.equals(List.of("binarylocal_30min")) && in.warmups==2 && in.repeats==3
                && "initial_2warmups_3pairs".equals(in.p.getProperty("perf_stage")),"unexpected initial performance contract");
        Path verified=in.resolve(in.p.getProperty("verified_run"));
        String report=Files.readString(verified.resolve("results/verification.json"));
        require(Pattern.compile("\"correctness\"\\s*:\\s*true").matcher(report).find()
                && Pattern.compile("\"legacy_calculate_regression\"\\s*:\\s*true").matcher(report).find(),"T2 correctness receipt missing");
        String inputs=Files.readString(verified.resolve("results/inputs.json"));
        for(String key:List.of("dem","receivers","metadata")) {
            Matcher object=Pattern.compile("\""+key+"\"\\s*:\\s*\\{([^}]*)}").matcher(inputs);
            require(object.find(),"T2 input record missing: "+key);
            require(hash(in.resolve(in.p.getProperty(key))).equals(stringField(object.group(1),"sha256")),"T2 input changed: "+key);
        }
        Set<String> verifiedNames=new HashSet<>();boolean header=true;
        for(String line:Files.readAllLines(verified.resolve("compiled_sources.csv"))) {
            if(header){header=false;continue;}if(line.isBlank())continue;
            int comma=line.lastIndexOf(',');
            require(comma>0,"malformed verified source record");
            String path=line.substring(0,comma).replace("\"","");
            String sha=line.substring(comma+1).replace("\"","").toLowerCase(Locale.ROOT);
            String name=Path.of(path).getFileName().toString();verifiedNames.add(name);
            require(sha.matches("[a-f0-9]{64}") && hash(Path.of("src",name)).equals(sha),"verified source changed: "+path);
        }
        require(verifiedNames.containsAll(Set.of("BlockUpperBoundVisibility.java","StripUpperBoundVisibility.java",
                "BinaryLocalRefinement.java","AdaptiveTimeV2Local.java","TimeStepBaseline.java")),"incomplete verified source record");
    }
    private static Map<String,String> bindings(ProjectedBaselineExperiment.Inputs in) throws Exception {
        Map<String,String> result=new TreeMap<>();
        result.put("java_version",System.getProperty("java.version"));
        result.put("jvm_arguments",ManagementFactory.getRuntimeMXBean().getInputArguments().toString());
        result.put("config",hash(in.config));
        for(String key:List.of("dem","receivers","metadata"))result.put(key,hash(in.resolve(in.p.getProperty(key))));
        try(var paths=Files.list(Path.of("src"))) {
            for(Path p:paths.filter(p->p.toString().endsWith(".java")).sorted().toList())result.put(p.toString(),hash(p));
        }
        result.put("scripts/run_strip_perf.ps1",hash(Path.of("scripts/run_strip_perf.ps1")));
        return result;
    }
    private static String bindingDigest(Map<String,String> bindings) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json(bindings).getBytes(StandardCharsets.UTF_8)));
    }
    private static Prepared prepare(ProjectedBaselineExperiment.Inputs in,Method method) {
        // Exactly one current-method index; originals and the other index are never built here.
        if(method==Method.BLOCK16) {
            var index=new BlockUpperBoundVisibilityNoStats(in.grid,16);
            return new Prepared((r,a,b)->index.visibleAt(in.contract,r.row,r.col,a,b,
                    AngleReference.GRID_NORTH_CLOCKWISE,in.search),index.payloadBytes());
        }
        var index=new StripUpperBoundVisibilityNoStats(in.grid);
        return new Prepared((r,a,b)->index.visibleAt(in.contract,r.row,r.col,a,b,
                AngleReference.GRID_NORTH_CLOCKWISE,in.search),index.payloadBytes());
    }
    private static double[] queryAll(ProjectedBaselineExperiment.Inputs in,
            List<ProjectedBaselineExperiment.Receiver> receivers,VisibilityQuery query) {
        double[] psd=new double[receivers.size()];
        for(int i=0;i<receivers.size();i++) {
            var receiver=receivers.get(i);
            double day=TimeStepBaseline.daylight(receiver.lat,in.dec),rise=720-day/2;
            AdaptiveTimeV1.Oracle oracle=t->{
                double[] angles=TimeStepBaseline.sun(receiver.lat,in.dec,rise+t);
                VisibilityStatus s=query.at(receiver,Math.max(1e-7,angles[0]),angles[1]+receiver.north).status;
                return s==VisibilityStatus.VISIBLE?1:s==VisibilityStatus.SHADED?0:-1;
            };
            // Frozen time core retains its own per-pixel cache; nothing retains those maps across pixels.
            psd[i]=BinaryLocalRefinement.adaptiveWithDarkGuard(day,30,oracle).psd;
        }
        return psd;
    }
    private static String resultDigest(List<ProjectedBaselineExperiment.Receiver> receivers,double[] psd) throws Exception {
        require(receivers.size()==psd.length,"PSD count mismatch");
        MessageDigest d=MessageDigest.getInstance("SHA-256");ByteBuffer b=ByteBuffer.allocate(16);
        b.putInt(psd.length);d.update(b.array(),0,4);
        for(int i=0;i<psd.length;i++) {
            require(Double.isFinite(psd[i]),"unexpected non-finite PSD at receiver "+i);
            b.clear();b.putInt(receivers.get(i).row).putInt(receivers.get(i).col).putLong(Double.doubleToLongBits(psd[i]));
            d.update(b.array());
        }
        return HexFormat.of().formatHex(d.digest());
    }
    private static void equalResult(VisibilityResult a,VisibilityResult b,String label) {
        require(a.status==b.status && Double.doubleToLongBits(a.searchedDistanceGridUnits)==Double.doubleToLongBits(b.searchedDistanceGridUnits)
                && a.spatialSamples==b.spatialSamples,"stats-off result mismatch: "+label);
    }
    private static AsciiGrid artificial(double[][] v) {
        return new AsciiGrid(v.length,v[0].length,0,0,1,-9999,v,ZeroPolicy.PRESERVE_AS_VALID);
    }
    private static double[][] flat(double z) {
        double[][] v=new double[96][96];for(double[] row:v)Arrays.fill(row,z);return v;
    }
    private static void fixture(String label,double[][] v,int r,int c,double a,double b,SearchConfig search,
            VisibilityStatus expected,int stop,long[] coverage) {
        var g=artificial(v);var block=new BlockUpperBoundVisibility(g,16);var strip=new StripUpperBoundVisibility(g);
        var leanBlock=new BlockUpperBoundVisibilityNoStats(g,16);var leanStrip=new StripUpperBoundVisibilityNoStats(g);
        var bc=new BlockUpperBoundVisibility.Counters();var sc=new StripUpperBoundVisibility.Counters();
        var oldB=block.visibleAt(TimeStepBaseline.contract(),r,c,a,b,AngleReference.GRID_NORTH_CLOCKWISE,search,bc);
        var oldC=strip.visibleAt(TimeStepBaseline.contract(),r,c,a,b,AngleReference.GRID_NORTH_CLOCKWISE,search,sc);
        equalResult(oldB,leanBlock.visibleAt(TimeStepBaseline.contract(),r,c,a,b,AngleReference.GRID_NORTH_CLOCKWISE,search),label+" block");
        equalResult(oldC,leanStrip.visibleAt(TimeStepBaseline.contract(),r,c,a,b,AngleReference.GRID_NORTH_CLOCKWISE,search),label+" strip");
        require(oldB.status==expected && oldC.status==expected && oldB.searchedDistanceGridUnits==stop*search.sampleSpacingGridUnits
                && oldC.searchedDistanceGridUnits==oldB.searchedDistanceGridUnits,"independent fixture expectation: "+label);
        coverage[0]+=2;if(sc.rescuedSegments>0)coverage[1]++;
    }
    private static void syntheticSmoke(long[] coverage) {
        var search=new SearchConfig(12,1);
        for(double bearing:new double[]{0,90,180,270}) {
            double[][] v=flat(100);v[35][35]=140;
            fixture("off_path_peak",v,40,40,5,bearing,search,VisibilityStatus.VISIBLE,12,coverage);
        }
        double[][] v=flat(100);v[40][43]=140;
        fixture("on_path_peak",v,40,40,5,90,search,VisibilityStatus.SHADED,3,coverage);
        v=flat(100);v[40][48]=140;
        fixture("halo",v,40,46,5,90,new SearchConfig(8,1.5),VisibilityStatus.SHADED,1,coverage);
        v=flat(100);v[40][42]=-9999;v[40][44]=200;
        fixture("unknown_before_shade",v,40,40,5,90,search,VisibilityStatus.UNKNOWN_RAY_INVALID,2,coverage);
        v=flat(100);v[40][42]=200;v[40][44]=-9999;
        fixture("shade_before_unknown",v,40,40,5,90,search,VisibilityStatus.SHADED,2,coverage);
        v=flat(100);v[40][40]=-9999;
        fixture("receiver_invalid",v,40,40,5,90,search,VisibilityStatus.UNKNOWN_RECEIVER_INVALID,0,coverage);
        fixture("boundary",flat(100),2,40,5,0,search,VisibilityStatus.UNKNOWN_OUT_OF_BOUNDS,3,coverage);
        fixture("negative",flat(-200),40,40,5,90,search,VisibilityStatus.VISIBLE,12,coverage);
        v=flat(100);v[40][41]=100+Math.tan(Math.toRadians(30));
        fixture("tangent",v,40,40,30,90,search,VisibilityStatus.VISIBLE,12,coverage);
        fixture("below_tangent",v,40,40,30-1e-6,90,search,VisibilityStatus.SHADED,1,coverage);
        v=flat(0);v[23][21]=100;
        fixture("near_integer_row",v,24,20,5,90-1e-8,new SearchConfig(1,1.25),VisibilityStatus.SHADED,1,coverage);
        v=flat(0);v[25][19]=100;
        fixture("near_integer_col",v,24,20,5,180+1e-8,new SearchConfig(1,1.25),VisibilityStatus.SHADED,1,coverage);
        fixture("extreme_guard",flat(Double.MAX_VALUE/8),40,40,5,90,search,VisibilityStatus.VISIBLE,12,coverage);
        require(coverage[1]>0,"extra strip skip path not exercised");
    }
    private static void smoke(ProjectedBaselineExperiment.Inputs in,Path out,String binding) throws Exception {
        Set<Integer> offsets=new HashSet<>();for(int i=0;i<8;i++)offsets.add(i*127/7);
        var selected=in.receivers.stream().filter(r->(offsets.contains(r.row-114)&&offsets.contains(r.col-114))
                ||in.traces.contains(r.key())).toList();
        require(selected.size()>=64 && selected.size()<=70,"smoke scope changed");
        var oldB=new BlockUpperBoundVisibility(in.grid,16);var oldC=new StripUpperBoundVisibility(in.grid);
        Prepared leanB=prepare(in,Method.BLOCK16),leanC=prepare(in,Method.STRIP16);
        var bc=new BlockUpperBoundVisibility.Counters();var sc=new StripUpperBoundVisibility.Counters();long[] checks=new long[2];
        double[] b=queryAll(in,selected,(r,a,angle)->{
            var expected=oldB.visibleAt(in.contract,r.row,r.col,a,angle,AngleReference.GRID_NORTH_CLOCKWISE,in.search,bc);
            var actual=leanB.query.at(r,a,angle);equalResult(expected,actual,r.key()+" block");checks[0]++;return actual;
        });
        double[] c=queryAll(in,selected,(r,a,angle)->{
            var expected=oldC.visibleAt(in.contract,r.row,r.col,a,angle,AngleReference.GRID_NORTH_CLOCKWISE,in.search,sc);
            var actual=leanC.query.at(r,a,angle);equalResult(expected,actual,r.key()+" strip");checks[1]++;return actual;
        });
        double[] originalB=queryAll(in,selected,(r,a,angle)->oldB.visibleAt(in.contract,r.row,r.col,a,angle,
                AngleReference.GRID_NORTH_CLOCKWISE,in.search,new BlockUpperBoundVisibility.Counters()));
        double[] originalC=queryAll(in,selected,(r,a,angle)->oldC.visibleAt(in.contract,r.row,r.col,a,angle,
                AngleReference.GRID_NORTH_CLOCKWISE,in.search,new StripUpperBoundVisibility.Counters()));
        String digest=resultDigest(selected,b);
        require(digest.equals(resultDigest(selected,c)) && digest.equals(resultDigest(selected,originalB))
                && digest.equals(resultDigest(selected,originalC)),"smoke PSD digest mismatch");
        long[] fixtureCoverage=new long[2];syntheticSmoke(fixtureCoverage);
        try(BufferedWriter w=Files.newBufferedWriter(out.resolve("smoke_pixels.csv"),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
            w.write("row,col,original_block_psd_hex,nostats_block_psd_hex,original_strip_psd_hex,nostats_strip_psd_hex\n");
            for(int i=0;i<selected.size();i++)w.write(selected.get(i).row+","+selected.get(i).col+","+Double.toHexString(originalB[i])+","+
                    Double.toHexString(b[i])+","+Double.toHexString(originalC[i])+","+Double.toHexString(c[i])+"\n");
        }
        Map<String,Object> s=new LinkedHashMap<>();s.put("mode","small_correctness_smoke_only");
        s.put("smoke_pass",true);s.put("binding_sha256",binding);s.put("selected_pixels",selected.size());
        s.put("real_ray_pairs_block",checks[0]);s.put("real_ray_pairs_strip",checks[1]);
        s.put("ray_status_stop_and_sample_mismatches",0);s.put("psd_mismatch_pixels",0);s.put("psd_sha256",digest);
        s.put("synthetic_fixture_pairs",fixtureCoverage[0]);s.put("strip_skip_fixtures_observed",fixtureCoverage[1]);
        s.put("full_roi_nostats_regression",false);s.put("warmups_executed",0);s.put("formal_pairs_executed",0);
        s.put("performance_measured",false);s.put("novelty_established",false);
        writeJson(out.resolve("smoke_summary.json"),s);
        System.out.println("PERF_PREP_SMOKE_PASS pixels="+selected.size()+" block_ray_pairs="+checks[0]+" strip_ray_pairs="+checks[1]);
        System.out.println("STOP_BEFORE_PERFORMANCE warmups=0 formal_pairs=0");
    }
    // One untimed full-ROI regression against the frozen statistical-version CSV.
    private static String fullRoiRegression(ProjectedBaselineExperiment.Inputs in,Path out) throws Exception {
        Path frozen=in.resolve(in.p.getProperty("verified_run")).resolve("results/pixels.csv");
        double[] expected=new double[in.receivers.size()];
        try(BufferedReader reader=Files.newBufferedReader(frozen,StandardCharsets.UTF_8)) {
            String[] header=reader.readLine().split(",");
            require(header[5].equals("b_psd_hex") && header[6].equals("c_psd_hex"),"frozen PSD schema changed");
            for(int i=0;i<expected.length;i++) {
                String line=reader.readLine();require(line!=null,"short frozen PSD CSV");String[] v=line.split(",");
                var r=in.receivers.get(i);
                require(Integer.parseInt(v[0])==i && Integer.parseInt(v[1])==r.row && Integer.parseInt(v[2])==r.col,"frozen receiver order changed");
                expected[i]=Double.parseDouble(v[5]);
                require(Double.doubleToLongBits(expected[i])==Double.doubleToLongBits(Double.parseDouble(v[6])),"frozen methods differ");
            }
            require(reader.readLine()==null,"extra frozen PSD rows");
        }
        String digest=resultDigest(in.receivers,expected);Map<String,Object> methods=new LinkedHashMap<>();
        for(Method method:Method.values()) {
            Prepared prepared=prepare(in,method);double[] actual=queryAll(in,in.receivers,prepared.query);
            for(int i=0;i<actual.length;i++)require(Double.doubleToLongBits(expected[i])==Double.doubleToLongBits(actual[i]),
                    "full ROI PSD mismatch: "+method+" pixel="+i);
            String actualDigest=resultDigest(in.receivers,actual);require(digest.equals(actualDigest),"full ROI digest mismatch");
            methods.put(method.toString(),Map.of("pixels_compared",actual.length,"bit_mismatches",0,"psd_sha256",actualDigest));
            System.out.println("FULL_ROI_PASS method="+method+" pixels="+actual.length+" sha256="+actualDigest);
        }
        writeJson(out.resolve("full_roi_regression.json"),Map.of("passed",true,"methods",methods,
                "frozen_csv_sha256",hash(frozen),"frozen_psd_sha256",digest,"included_in_performance_timing",false));
        return digest;
    }
    private static final class Trial {
        Method method;long buildNs,queryNs,computeNs,payload;double[] psd;
    }
    private static Trial trial(ProjectedBaselineExperiment.Inputs in,Method method) {
        Trial result=new Trial();result.method=method;
        long started=System.nanoTime();
        Prepared prepared=prepare(in,method);
        long ready=System.nanoTime();
        result.psd=queryAll(in,in.receivers,prepared.query);
        long finished=System.nanoTime();
        result.buildNs=ready-started;result.queryNs=finished-ready;result.computeNs=finished-started;result.payload=prepared.payload;
        return result;
    }
    private static long writePsd(ProjectedBaselineExperiment.Inputs in,Trial trial,Path file) throws IOException {
        long started=System.nanoTime();
        try(BufferedWriter w=Files.newBufferedWriter(file,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
            w.write("row,col,psd_hex\n");
            for(int i=0;i<trial.psd.length;i++)w.write(in.receivers.get(i).row+","+in.receivers.get(i).col+","+Double.toHexString(trial.psd[i])+"\n");
        }
        return System.nanoTime()-started;
    }
    private static Map<String,Object> range(List<Long> values) {
        var sorted=values.stream().sorted().toList();long min=sorted.get(0),max=sorted.get(sorted.size()-1);
        double median=(sorted.get((sorted.size()-1)/2)/2.0+sorted.get(sorted.size()/2)/2.0)/1e9;
        return Map.of("median_sec",median,"min_sec",min/1e9,"max_sec",max/1e9,"range_sec",(max-min)/1e9);
    }
    private static void benchmark(ProjectedBaselineExperiment.Inputs in,Path out,String expected) throws Exception {
        Map<Method,List<Long>> builds=new EnumMap<>(Method.class),queries=new EnumMap<>(Method.class),computes=new EnumMap<>(Method.class);
        for(Method m:Method.values()){builds.put(m,new ArrayList<>());queries.put(m,new ArrayList<>());computes.put(m,new ArrayList<>());}
        try(BufferedWriter rows=Files.newBufferedWriter(out.resolve("runs.csv"),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                BufferedWriter pairs=Files.newBufferedWriter(out.resolve("pairs.csv"),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
            rows.write("phase,round,position,method,build_ns,query_ns,compute_ns,csv_write_ns,digest_ns,index_payload_bytes,psd_sha256\n");
            pairs.write("round,order,block_compute_ns,strip_compute_ns,block_minus_strip_ns,saving_fraction\n");
            for(int phase=0;phase<2;phase++)for(int round=1;round<=(phase==0?2:3);round++) {
                Method[] order=round%2==1?new Method[]{Method.BLOCK16,Method.STRIP16}:new Method[]{Method.STRIP16,Method.BLOCK16};
                Map<Method,Long> paired=new EnumMap<>(Method.class);
                for(int position=0;position<order.length;position++) {
                    Method m=order[position];Trial t=trial(in,m);
                    long digestStart=System.nanoTime();String digest=resultDigest(in.receivers,t.psd);long digestNs=System.nanoTime()-digestStart;
                    if(expected==null)expected=digest;
                    if(!expected.equals(digest)) {
                        writePsd(in,t,out.resolve("failed_"+phase+"_"+round+"_"+m+"_psd.csv"));
                        writeJson(out.resolve("digest_mismatch.json"),Map.of("method",m.toString(),"phase",phase,"round",round,
                                "expected",expected,"actual",digest));
                        throw new IllegalStateException("PSD digest mismatch; timing stopped immediately");
                    }
                    long csvNs=0; // Normal runs retain only timing and PSD digest, no redundant pixel CSV.
                    rows.write((phase==0?"warmup":"formal")+","+round+","+(position+1)+","+m+","+t.buildNs+","+t.queryNs+","+
                            t.computeNs+","+csvNs+","+digestNs+","+t.payload+","+digest+"\n");rows.flush();
                    System.out.println("TRIAL_DONE phase="+(phase==0?"warmup":"formal")+" round="+round+" method="+m+" compute_sec="+t.computeNs/1e9);
                    if(phase==1){builds.get(m).add(t.buildNs);queries.get(m).add(t.queryNs);computes.get(m).add(t.computeNs);paired.put(m,t.computeNs);}
                }
                if(phase==1) {
                    long a=paired.get(Method.BLOCK16),b=paired.get(Method.STRIP16);
                    pairs.write(round+","+(round%2==1?"BLOCK16_THEN_STRIP16":"STRIP16_THEN_BLOCK16")+","+a+","+b+","+(a-b)+","+(1-b/(double)a)+"\n");pairs.flush();
                }
            }
        }
        Map<String,Object> methods=new LinkedHashMap<>();
        for(Method m:Method.values())methods.put(m.toString(),Map.of("build",range(builds.get(m)),"query",range(queries.get(m)),"compute",range(computes.get(m))));
        writeJson(out.resolve("performance_summary.json"),Map.of("methods",methods,"warmups_per_method",2,"formal_pairs",3,
                "performance_measured",true,"all_psd_digests_equal",true,"psd_sha256",expected,
                "screening_threshold",0.10,"threshold_is_novelty_criterion",false,"csv_and_digest_excluded_from_compute",true));
        System.out.println("INITIAL_PERFORMANCE_COMPLETE pairs=3 all_psd_digests_equal=true");
    }
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        boolean smoke=args.length==3 && "--smoke".equals(args[0]);
        boolean timing=args.length==5 && "--benchmark".equals(args[0]) && "--allow-initial-timing".equals(args[3]);
        boolean initial=args.length==3 && "--initial-approved".equals(args[0]);
        if(!smoke&&!timing&&!initial)throw new IllegalArgumentException("--smoke config NEW-output OR explicitly approved --benchmark config NEW-output --allow-initial-timing smoke_summary.json");
        Path out=Path.of(args[2]).toAbsolutePath().normalize(),allowed=Path.of("user_runs").toAbsolutePath().normalize();
        require(out.startsWith(allowed)&&!out.equals(allowed),"new user_runs child required");Files.createDirectory(out);
        try {
            var in=new ProjectedBaselineExperiment.Inputs(Path.of(args[1]));checkVerifiedInputs(in);
            Map<String,String> bindings=bindings(in);String binding=bindingDigest(bindings);
            if(timing) {
                String receipt=Files.readString(Path.of(args[4]));
                require(Pattern.compile("\"smoke_pass\"\\s*:\\s*true").matcher(receipt).find()
                        && binding.equals(stringField(receipt,"binding_sha256")),"smoke receipt does not match current sources/config/inputs/JVM");
            }
            Files.copy(in.config,out.resolve("config.properties"));
            writeJson(out.resolve("input_source_manifest.json"),Map.of("mode",smoke?"smoke":initial?"full_roi_then_initial":"benchmark","binding_sha256",binding,
                    "fingerprints",bindings,"classpath",StripT2PerformanceExperiment.class.getProtectionDomain().getCodeSource().getLocation().toString(),
                    "index_memory_definition","primitive array payload only; excludes headers/DEM/counters/results/peak heap"));
            // All input loading, provenance and smoke checks above are outside trial() clocks.
            if(smoke)smoke(in,out,binding);else benchmark(in,out,initial?fullRoiRegression(in,out):null);
        } catch(Exception | AssertionError e) {
            writeJson(out.resolve("failure.json"),Map.of("error",e.toString(),"performance_complete",false));throw e;
        }
    }
}
