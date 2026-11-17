import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;

/** Final four-way ablation. Streams existing frozen ray evidence during untimed checks only. */
public final class StripT2AblationExperiment {
    private enum Method { BLOCK16, ROW_STRIP16, COL_STRIP16, BOTH_STRIP16 }
    @FunctionalInterface private interface VisibilityQuery {
        VisibilityResult at(ProjectedBaselineExperiment.Receiver receiver, double time, double altitude, double bearing);
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
                && "final_4way_ablation_2warmups_3rounds".equals(in.p.getProperty("perf_stage")),"unexpected final ablation contract");
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
        result.put("scripts/run_strip_ablation.ps1",hash(Path.of("scripts/run_strip_ablation.ps1")));
        return result;
    }
    private static String bindingDigest(Map<String,String> bindings) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json(bindings).getBytes(StandardCharsets.UTF_8)));
    }

    private static Prepared prepare(ProjectedBaselineExperiment.Inputs in,Method m) {
        switch(m) {
            case BLOCK16: {
                var x=new BlockUpperBoundVisibilityNoStats(in.grid,16);
                return new Prepared((r,t,a,b)->x.visibleAt(in.contract,r.row,r.col,a,b,AngleReference.GRID_NORTH_CLOCKWISE,in.search),x.payloadBytes());
            }
            case ROW_STRIP16: {
                var x=new RowStripUpperBoundVisibilityNoStats(in.grid);
                return new Prepared((r,t,a,b)->x.visibleAt(in.contract,r.row,r.col,a,b,AngleReference.GRID_NORTH_CLOCKWISE,in.search),x.payloadBytes());
            }
            case COL_STRIP16: {
                var x=new ColStripUpperBoundVisibilityNoStats(in.grid);
                return new Prepared((r,t,a,b)->x.visibleAt(in.contract,r.row,r.col,a,b,AngleReference.GRID_NORTH_CLOCKWISE,in.search),x.payloadBytes());
            }
            default: {
                var x=new StripUpperBoundVisibilityNoStats(in.grid);
                return new Prepared((r,t,a,b)->x.visibleAt(in.contract,r.row,r.col,a,b,AngleReference.GRID_NORTH_CLOCKWISE,in.search),x.payloadBytes());
            }
        }
    }
    // This exact solar/time/integration path is used by both verification and timing.
    private static AdaptiveTimeV2Local.Result pixel(ProjectedBaselineExperiment.Inputs in,
            ProjectedBaselineExperiment.Receiver r,VisibilityQuery query) {
        double day=TimeStepBaseline.daylight(r.lat,in.dec),rise=720-day/2;
        return BinaryLocalRefinement.adaptiveWithDarkGuard(day,30,t->{
            double[] angle=TimeStepBaseline.sun(r.lat,in.dec,rise+t);
            var result=query.at(r,t,Math.max(1e-7,angle[0]),angle[1]+r.north);
            return result.status==VisibilityStatus.VISIBLE?1:result.status==VisibilityStatus.SHADED?0:-1;
        });
    }
    private static double[] queryAll(ProjectedBaselineExperiment.Inputs in,VisibilityQuery query) {
        double[] psd=new double[in.receivers.size()];
        for(int i=0;i<psd.length;i++)psd[i]=pixel(in,in.receivers.get(i),query).psd;
        return psd;
    }
    private static boolean bits(double a,double b) { return Double.doubleToLongBits(a)==Double.doubleToLongBits(b); }
    private static String digest(ProjectedBaselineExperiment.Inputs in,double[] psd) throws Exception {
        MessageDigest d=MessageDigest.getInstance("SHA-256");ByteBuffer b=ByteBuffer.allocate(16);
        b.putInt(psd.length);d.update(b.array(),0,4);
        for(int i=0;i<psd.length;i++) {
            require(Double.isFinite(psd[i]),"non-finite PSD");var r=in.receivers.get(i);
            b.clear();b.putInt(r.row).putInt(r.col).putLong(Double.doubleToLongBits(psd[i]));d.update(b.array());
        }
        return HexFormat.of().formatHex(d.digest());
    }
    private static final Map<String,Object> context=new LinkedHashMap<>();
    private static String verify(ProjectedBaselineExperiment.Inputs in,Path out) throws Exception {
        Path frozen=in.resolve(in.p.getProperty("verified_run")).resolve("results");
        var methods=new LinkedHashMap<String,Object>();String expectedDigest=null;
        for(Method method:Method.values()) {
            Prepared prepared=prepare(in,method);double[] psd=new double[in.receivers.size()];long[] count=new long[5];
            try(BufferedReader rays=new BufferedReader(new InputStreamReader(new java.util.zip.GZIPInputStream(
                    Files.newInputStream(frozen.resolve("rays.csv.gz"))),StandardCharsets.UTF_8));
                    BufferedReader pixels=Files.newBufferedReader(frozen.resolve("pixels.csv"),StandardCharsets.UTF_8)) {
                var header=Arrays.asList(rays.readLine().split(","));
                final int bs=header.indexOf("b_samples"),cs=header.indexOf("c_samples"),bn=header.indexOf("b_node");
                require(header.get(8).equals("b_status") && header.get(11).equals("b_stop_hex") && bs>=0 && cs>=0 && bn>=0,"ray schema changed");
                var pixelHeader=Arrays.asList(pixels.readLine().split(","));
                final int queryCount=pixelHeader.indexOf("query_count");
                require(pixelHeader.get(5).equals("b_psd_hex") && queryCount>=0,"pixel schema changed");
                for(int ri=0;ri<in.receivers.size();ri++) {
                    final int index=ri;var r=in.receivers.get(ri);String line=pixels.readLine();require(line!=null,"short pixel CSV");
                    String[] fp=line.split(",");
                    require(Integer.parseInt(fp[0])==ri && Integer.parseInt(fp[1])==r.row && Integer.parseInt(fp[2])==r.col,"pixel identity");
                    final int[] qi={0};var expectedNodes=new TreeMap<Double,Integer>();
                    var result=pixel(in,r,(receiver,t,a,b)->{
                        context.clear();context.put("method",method.toString());context.put("receiver_index",index);context.put("query_index",qi[0]);context.put("time_hex",Double.toHexString(t));
                        String raw;
                        try {raw=rays.readLine();}catch(IOException e){throw new UncheckedIOException(e);}
                        require(raw!=null,"short frozen rays");String[] v=raw.split(",");
                        require(Integer.parseInt(v[0])==index && Integer.parseInt(v[1])==r.row && Integer.parseInt(v[2])==r.col
                                && Integer.parseInt(v[3])==qi[0],"ray ordering");
                        require(bits(t,Double.parseDouble(v[4])) && bits(a,Double.parseDouble(v[5])) && bits(b,Double.parseDouble(v[6])),"query time or solar parameter mismatch");
                        var actual=prepared.query.at(receiver,t,a,b);context.put("actual_status",actual.status.toString());
                        context.put("actual_stop",Double.toHexString(actual.searchedDistanceGridUnits));context.put("actual_samples",actual.spatialSamples);
                        context.put("expected_status",v[8]);context.put("expected_stop",v[11]);context.put("frozen_block_samples",v[bs]);context.put("frozen_both_samples",v[cs]);
                        require(actual.status.toString().equals(v[8]),"VisibilityStatus mismatch");
                        require(bits(actual.searchedDistanceGridUnits,Double.parseDouble(v[11])),"first stop distance mismatch");
                        long stop=Long.parseLong(v[14]),base=Long.parseLong(v[bs]),both=Long.parseLong(v[cs]);
                        require(bits(actual.searchedDistanceGridUnits,stop*in.search.sampleSpacingGridUnits),"stop sample index mismatch");
                        require(actual.spatialSamples>=0 && actual.spatialSamples<=stop,"sample count outside original schedule");
                        if(method==Method.BLOCK16)require(actual.spatialSamples==base,"block sample count mismatch");
                        else if(method==Method.BOTH_STRIP16)require(actual.spatialSamples==both,"both sample count mismatch");
                        else require(actual.spatialSamples>=both && actual.spatialSamples<=base,"single-direction sample count outside both/block envelope");
                        int state=actual.status==VisibilityStatus.VISIBLE?1:actual.status==VisibilityStatus.SHADED?0:-1;
                        if(v[bn].equals("1"))expectedNodes.put(t,state);
                        count[0]++;count[1]+=actual.spatialSamples;count[2]+=stop;
                        if(actual.spatialSamples!=base)count[3]++;if(actual.spatialSamples!=both)count[4]++;
                        qi[0]++;return actual;
                    });
                    require(qi[0]==Integer.parseInt(fp[queryCount]),"query count mismatch");
                    require(result.integrationNodes.equals(expectedNodes),"integration nodes mismatch");
                    require(bits(result.psd,Double.parseDouble(fp[5])),"PSD bit mismatch");psd[ri]=result.psd;
                }
                require(rays.readLine()==null && pixels.readLine()==null,"unconsumed frozen rows");
            }
            require(count[0]==862525,"ray coverage incomplete");String hash=digest(in,psd);
            if(expectedDigest==null)expectedDigest=hash;require(expectedDigest.equals(hash),"method digest mismatch");
            var s=new LinkedHashMap<String,Object>();s.put("pixels",psd.length);s.put("rays_compared",count[0]);
            s.put("query_time_solar_status_stop_index_integration_psd_mismatches",0);s.put("sample_count_check_failures",0);
            s.put("actual_interpolations",count[1]);s.put("original_schedule_samples_to_stop",count[2]);
            s.put("rays_with_different_interpolation_count_from_block",count[3]);s.put("rays_with_different_interpolation_count_from_both",count[4]);
            s.put("sample_count_check",method==Method.BLOCK16?"exact frozen block":method==Method.BOTH_STRIP16?"exact frozen both":"per-ray frozen both <= actual <= frozen block; count within original stop index");
            s.put("psd_sha256",hash);s.put("index_payload_bytes",prepared.payload);methods.put(method.toString(),s);
            System.out.println("VERIFY_PASS method="+method+" pixels="+psd.length+" rays="+count[0]+" interpolations="+count[1]);
        }
        writeJson(out.resolve("verification.json"),Map.of("passed",true,"methods",methods,"outside_performance_timing",true,
                "full_ray_records_saved",false,"sample_count_note","Actual interpolation counts intentionally differ. Skips are not independently counted in NoStats. Exact count regression for A/D, envelope checks for B/C; source skip/advance schedule reviewed."));
        return expectedDigest;
    }
    private static Map<String,Object> range(List<Long> ns) {
        var v=ns.stream().sorted().toList();double min=v.get(0)/1e9,max=v.get(v.size()-1)/1e9;
        return Map.of("median_sec",v.get(v.size()/2)/1e9,"min_sec",min,"max_sec",max,"range_sec",max-min);
    }
    private static void benchmark(ProjectedBaselineExperiment.Inputs in,Path out,String expected) throws Exception {
        Method A=Method.BLOCK16,B=Method.ROW_STRIP16,C=Method.COL_STRIP16,D=Method.BOTH_STRIP16;
        Method[][] orders={{A,B,C,D},{D,C,B,A},{A,B,C,D},{D,C,B,A},{B,D,A,C}};
        Map<Method,List<Long>> builds=new EnumMap<>(Method.class),queries=new EnumMap<>(Method.class),computes=new EnumMap<>(Method.class);
        for(Method m:Method.values()){builds.put(m,new ArrayList<>());queries.put(m,new ArrayList<>());computes.put(m,new ArrayList<>());}
        try(BufferedWriter w=Files.newBufferedWriter(out.resolve("runs.csv"),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
            w.write("phase,round,position,method,build_sec,query_sec,compute_sec,psd_sha256,index_payload_bytes\n");
            for(int pass=0;pass<orders.length;pass++)for(int pos=0;pos<4;pos++) {
                Method m=orders[pass][pos];boolean formal=pass>=2;int round=formal?pass-1:pass+1;
                long start=System.nanoTime();Prepared prepared=prepare(in,m);long ready=System.nanoTime();
                double[] result=queryAll(in,prepared.query);long finish=System.nanoTime();
                String hash=digest(in,result);require(expected.equals(hash),"timing PSD digest mismatch: "+m);
                long build=ready-start,query=finish-ready,compute=finish-start;
                w.write((formal?"formal":"warmup")+","+round+","+(pos+1)+","+m+","+build/1e9+","+query/1e9+","+compute/1e9+","+hash+","+prepared.payload+"\n");w.flush();
                if(formal){builds.get(m).add(build);queries.get(m).add(query);computes.get(m).add(compute);}
                System.out.println("TRIAL_DONE phase="+(formal?"formal":"warmup")+" round="+round+" method="+m+" compute_sec="+compute/1e9);
            }
        }
        Map<String,Object> methods=new LinkedHashMap<>();List<String> qualified=new ArrayList<>();
        long medianA=computes.get(A).stream().sorted().toList().get(1);
        try(BufferedWriter w=Files.newBufferedWriter(out.resolve("pairs.csv"),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
            w.write("round,method,block_compute_sec,method_compute_sec,saving_fraction,speed_ratio\n");
            for(Method m:Method.values()) {
                var values=computes.get(m);boolean faster=true;
                for(int i=0;i<3;i++) {
                    long a=computes.get(A).get(i),b=values.get(i);if(b>=a)faster=false;
                    if(m!=A)w.write((i+1)+","+m+","+a/1e9+","+b/1e9+","+(1-b/(double)a)+","+a/(double)b+"\n");
                }
                long median=values.stream().sorted().toList().get(1);double saving=1-median/(double)medianA;
                boolean pass=m!=A && faster && saving>=0.10;if(pass)qualified.add(m.toString());
                methods.put(m.toString(),Map.of("build",range(builds.get(m)),"query",range(queries.get(m)),"compute",range(values),
                        "median_saving_vs_A",saving,"all_three_rounds_faster",faster,"qualifies_for_confirmation",pass));
            }
        }
        writeJson(out.resolve("performance_summary.json"),Map.of("methods",methods,"warmups_per_method",2,"formal_rounds",3,
                "total_timed_runs",20,"all_psd_digests_equal",true,"psd_sha256",expected,"qualified_methods",qualified,
                "stop_entire_strip_route",qualified.isEmpty(),"performance_measured",true,"novelty_established",false));
        System.out.println("ABLATION_COMPLETE timed_runs=20 stop_strip_route="+qualified.isEmpty()+" qualified="+qualified);
    }
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        require(args.length==3 && args[0].equals("--approved-final-ablation"),"explicit final ablation opt-in required");
        Path out=Path.of(args[2]).toAbsolutePath().normalize(),allowed=Path.of("user_runs").toAbsolutePath().normalize();
        require(out.startsWith(allowed)&&!out.equals(allowed),"new user_runs child required");Files.createDirectory(out);
        try {
            var in=new ProjectedBaselineExperiment.Inputs(Path.of(args[1]));checkVerifiedInputs(in);
            Files.copy(in.config,out.resolve("config.properties"));
            var provenance=new LinkedHashMap<String,Object>();provenance.put("fingerprints",bindings(in));
            provenance.put("jvm_arguments",ManagementFactory.getRuntimeMXBean().getInputArguments());provenance.put("java_version",System.getProperty("java.version"));
            provenance.put("config_sha256",hash(in.config));provenance.put("ray_reference_sha256",hash(in.resolve(in.p.getProperty("verified_run")).resolve("results/rays.csv.gz")));
            writeJson(out.resolve("provenance.json"),provenance);
            String expected=verify(in,out);context.clear();benchmark(in,out,expected);
        } catch(Exception | AssertionError e) {
            writeJson(out.resolve("first_mismatch_or_failure.json"),Map.of("error",e.toString(),"context",context,"completed",false));throw e;
        }
    }
}
