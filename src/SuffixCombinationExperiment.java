import java.io.*;
import java.lang.management.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** One correctness gate and a fixed 2-warmup/3-round, four-arm controlled experiment. */
public final class SuffixCombinationExperiment {
    static final Path CONFIG=Path.of("configs/qinling_128_block16_bound_diagnostic.properties");
    static final Path TEMPORAL=Path.of("user_runs/qinling_time_search_paired_20261110_125802_39f7a1e4");
    static void check(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    static boolean bits(double a,double b){return Double.doubleToLongBits(a)==Double.doubleToLongBits(b);}
    static boolean close(double a,double b){return Double.isFinite(a)&&Double.isFinite(b)&&Math.abs(a-b)<1e-8;}
    static BufferedWriter writer(Path p)throws IOException{return Files.newBufferedWriter(p,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);}
    static BufferedWriter compressed(Path p)throws IOException{return new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(p,StandardOpenOption.CREATE_NEW)),StandardCharsets.UTF_8));}
    static String[] fields(String line){check(line!=null,"short history");return line.replace("\"","").split(",",-1);}
    static final class NodeReader implements AutoCloseable {
        final BufferedReader r;String[] next;
        NodeReader(Path p)throws IOException{r=Files.newBufferedReader(p);fields(r.readLine());advance();}
        void advance()throws IOException{String line=r.readLine();next=line==null?null:fields(line);}
        NavigableMap<Double,Integer> group(int row,int col)throws IOException{
            var m=new TreeMap<Double,Integer>();
            while(next!=null&&Integer.parseInt(next[0])==row&&Integer.parseInt(next[1])==col){
                check(m.put(Double.parseDouble(next[2]),Integer.parseInt(next[3]))==null,"duplicate frozen node");advance();}
            check(!m.isEmpty(),"missing frozen receiver nodes");return m;
        }
        public void close()throws IOException{r.close();}
    }
    static void equalMaps(NavigableMap<Double,Integer> actual,NavigableMap<Double,Integer> expected,String label){
        check(actual.size()==expected.size(),label+" node count");var a=actual.entrySet().iterator();var b=expected.entrySet().iterator();
        while(a.hasNext()){var x=a.next();var y=b.next();check(close(x.getKey(),y.getKey())&&x.getValue().equals(y.getValue()),label+" key/state");}
    }
    static final class Verification {
        long rays,bSamples,sSamples,bSkipped,sSkipped,blocks,suffix,eligibility,endTests,boundary,planned,actualSuffix;
        long visible,shaded,unknown;long bQueryNanos,sQueryNanos;
        final List<double[]> scalars=new ArrayList<>();final List<String[]> labels=new ArrayList<>();
    }
    static Verification verify(ProjectedBaselineExperiment.Inputs in,boolean dual,Path out)throws Exception{
        String tag=dual?"dual":"old";var result=new Verification();
        var b=new Block16DirectionVisibility(in.grid,16);var bn=new Block16DirectionVisibilityNoStats(in.grid,16);
        var s=new SuffixBlockVisibility(in.grid,16);var sn=new SuffixBlockVisibilityNoStats(in.grid,16);
        Path frozen=Path.of("user_runs/qinling_128_strip_t2_verify_001/results");
        try(var oldRays=new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(frozen.resolve("rays.csv.gz"))),StandardCharsets.UTF_8));
                var hp=Files.newBufferedReader(TEMPORAL.resolve((dual?"two_boundary_limited5":"binarylocal_30min")+"_pixels.csv"));
                var evaluated=new NodeReader(TEMPORAL.resolve((dual?"two_boundary_limited5":"binarylocal_30min")+"_evaluated_nodes.csv"));
                var nodes=new NodeReader(TEMPORAL.resolve((dual?"two_boundary_limited5":"binarylocal_30min")+"_integration_nodes.csv"));
                var rays=compressed(out.resolve("verify_"+tag+"_rays.csv.gz"));
                var maps=compressed(out.resolve("verify_"+tag+"_nodes.csv.gz"));
                var pixels=writer(out.resolve("verify_"+tag+"_pixels.csv"))){
            List<String> oh=List.of(fields(oldRays.readLine()));int fs=oh.indexOf("b_samples"),fb=oh.indexOf("b_block_tests"),fk=oh.indexOf("b_skipped");
            fields(hp.readLine());
            rays.write("receiver_index,query_index,time_hex,altitude_hex,bearing_hex,b_status,s_status,b_stop_hex,s_stop_hex,b_interpolations,s_interpolations,b_skipped,s_skipped,suffix_samples,blocks,eligibility_checks,end_tests,boundary_tests,planned_suffixes,actual_suffix_segments\n");
            maps.write("receiver_index,time_hex,state,integration_node\n");
            pixels.write("receiver_index,row,col,day_hex,psd_hex,queries,status,reason,branch\n");
            for(int i=0;i<in.receivers.size();i++){
                final int id=i;var receiver=in.receivers.get(i);double day=TimeStepBaseline.daylight(receiver.lat,in.dec),rise=720-day/2;
                int[] query={0};long before=result.rays;
                AdaptiveTimeV1.Oracle oracle=t->{
                    double[] angles=TimeStepBaseline.sun(receiver.lat,in.dec,rise+t);double altitude=Math.max(1e-7,angles[0]),bearing=angles[1]+receiver.north;
                    var bc=new BlockUpperBoundVisibility.Counters();var sc=new SuffixBlockVisibility.Counters();
                    long start=System.nanoTime();var br=b.visibleAt(in.contract,receiver.row,receiver.col,altitude,bearing,AngleReference.GRID_NORTH_CLOCKWISE,in.search,bc);result.bQueryNanos+=System.nanoTime()-start;
                    start=System.nanoTime();var sr=s.visibleAt(in.contract,receiver.row,receiver.col,altitude,bearing,AngleReference.GRID_NORTH_CLOCKWISE,in.search,sc);result.sQueryNanos+=System.nanoTime()-start;
                    var bnr=bn.visibleAt(in.contract,receiver.row,receiver.col,altitude,bearing,AngleReference.GRID_NORTH_CLOCKWISE,in.search);
                    var snr=sn.visibleAt(in.contract,receiver.row,receiver.col,altitude,bearing,AngleReference.GRID_NORTH_CLOCKWISE,in.search);
                    for(var v:new VisibilityResult[]{sr,bnr,snr})check(v.status==br.status&&bits(v.searchedDistanceGridUnits,br.searchedDistanceGridUnits),"SPATIAL state/stop mismatch");
                    check(bnr.spatialSamples==br.spatialSamples&&snr.spatialSamples==sr.spatialSamples,"SPATIAL stats/nostats sample mismatch");
                    check(sc.blockTests==bc.blockTests&&bc.spatialSamples-sc.spatialSamples==sc.suffixSamples
                            &&bc.skippedSamples+sc.suffixSamples==sc.skippedSamples&&sc.spatialSamples+sc.skippedSamples==bc.spatialSamples+bc.skippedSamples,"SPATIAL conservation mismatch");
                    try{
                        if(!dual){String[] f=fields(oldRays.readLine());check(Integer.parseInt(f[0])==id&&Integer.parseInt(f[3])==query[0]
                                    &&bits(t,Double.parseDouble(f[4]))&&bits(altitude,Double.parseDouble(f[5]))&&bits(bearing,Double.parseDouble(f[6]))
                                    &&br.status.toString().equals(f[8])&&bits(br.searchedDistanceGridUnits,Double.parseDouble(f[11]))
                                    &&br.spatialSamples==Long.parseLong(f[fs])&&bc.blockTests==Long.parseLong(f[fb])&&bc.skippedSamples==Long.parseLong(f[fk]),"old frozen query contract");}
                        rays.write(id+","+query[0]+","+Double.toHexString(t)+","+Double.toHexString(altitude)+","+Double.toHexString(bearing)+","+br.status+","+sr.status+","+Double.toHexString(br.searchedDistanceGridUnits)+","+Double.toHexString(sr.searchedDistanceGridUnits)+","+br.spatialSamples+","+sr.spatialSamples+","+bc.skippedSamples+","+sc.skippedSamples+","+sc.suffixSamples+","+bc.blockTests+","+sc.eligibilityChecks+","+sc.endTests+","+sc.boundaryTests+","+sc.plannedSuffixes+","+sc.actualSuffixSegments+"\n");
                    }catch(IOException e){throw new UncheckedIOException(e);}
                    result.rays++;result.bSamples+=br.spatialSamples;result.sSamples+=sr.spatialSamples;result.bSkipped+=bc.skippedSamples;result.sSkipped+=sc.skippedSamples;
                    result.blocks+=bc.blockTests;result.suffix+=sc.suffixSamples;result.eligibility+=sc.eligibilityChecks;result.endTests+=sc.endTests;result.boundary+=sc.boundaryTests;result.planned+=sc.plannedSuffixes;result.actualSuffix+=sc.actualSuffixSegments;
                    if(br.status==VisibilityStatus.VISIBLE)result.visible++;else if(br.status==VisibilityStatus.SHADED)result.shaded++;else result.unknown++;
                    query[0]++;return br.status==VisibilityStatus.VISIBLE?1:br.status==VisibilityStatus.SHADED?0:-1;
                };
                var p=dual?SuffixTemporalAdapter.hybrid(day,oracle,true):SuffixTemporalAdapter.baseline(day,oracle);
                check(result.rays-before==p.unique(),"temporal fresh-query count");
                // Frozen temporal implementation and accepted accuracy are a gate, not ground truth.
                String[] history=fields(hp.readLine());check(Integer.parseInt(history[0])==receiver.row&&Integer.parseInt(history[1])==receiver.col,"history pixel identity");
                check(close(p.psd(),Double.parseDouble(history[6]))&&p.unique()==Integer.parseInt(history[9])&&p.status().equals(history[3])
                        &&p.reason().equals(history[5])&&p.branch().equals(history[4]),"TEMPORAL frozen scalar mismatch");
                equalMaps(p.evaluated(),evaluated.group(receiver.row,receiver.col),"TEMPORAL evaluated");
                equalMaps(p.nodes(),nodes.group(receiver.row,receiver.col),"TEMPORAL integration");
                if(dual)check(p.requests()==p.hits()+p.unique()&&p.unique()==p.anchor()+p.boundary()+p.fallback(),"dual memo accounting");
                for(var e:p.evaluated().entrySet())maps.write(i+","+Double.toHexString(e.getKey())+","+e.getValue()+","+(p.nodes().containsKey(e.getKey())?1:0)+"\n");
                pixels.write(i+","+receiver.row+","+receiver.col+","+Double.toHexString(day)+","+Double.toHexString(p.psd())+","+p.unique()+","+p.status()+","+p.reason()+","+p.branch()+"\n");
                result.scalars.add(new double[]{p.psd(),p.unique()});result.labels.add(new String[]{p.status(),p.reason(),p.branch()});
                if((i+1)%4096==0)System.out.println("VERIFY_PROGRESS method="+tag+" pixels="+(i+1));
            }
            check(hp.readLine()==null&&evaluated.next==null&&nodes.next==null,"unconsumed temporal history");
            if(!dual)check(oldRays.readLine()==null,"unconsumed frozen old rays");
        }
        check(result.rays==(dual?310488:862525)&&result.bSamples-result.sSamples==result.suffix,"verification totals");
        System.out.println("VERIFY_PASS method="+tag+" rays="+result.rays+" suffix_samples="+result.suffix+" state_stop_mismatches=0");return result;
    }
    @FunctionalInterface interface Spatial{VisibilityResult at(int row,int col,double altitude,double bearing);}
    static final class Trial {
        long rays,samples,visible,shaded,unknown,queryNanos,payload;
        double load,build,compute,write,full;
        double[] psd;int[] queries;String[] reasons,branches,statuses;String digest;
        long heapUsed,heapCommitted,nonheapUsed,poolPeakSum;
    }
    static String digest(Trial t)throws Exception{
        MessageDigest md=MessageDigest.getInstance("SHA-256");ByteBuffer b=ByteBuffer.allocate(12);
        for(int i=0;i<t.psd.length;i++){b.clear();b.putDouble(t.psd[i]).putInt(t.queries[i]);md.update(b.array());
            for(String s:new String[]{t.statuses[i],t.reasons[i],t.branches[i]}){md.update(s.getBytes(StandardCharsets.UTF_8));md.update((byte)0);}}
        return HexFormat.of().formatHex(md.digest());
    }
    static Trial trial(boolean dual,boolean suffix,Verification expected,Path file)throws Exception{
        for(var p:ManagementFactory.getMemoryPoolMXBeans())if(p.isValid())p.resetPeakUsage();
        long begin=System.nanoTime();var in=new ProjectedBaselineExperiment.Inputs(CONFIG);long loaded=System.nanoTime();
        Spatial spatial;long payload;
        if(suffix){var index=new SuffixBlockVisibilityNoStats(in.grid,16);payload=index.payloadBytes();spatial=(r,c,a,b)->index.visibleAt(in.contract,r,c,a,b,AngleReference.GRID_NORTH_CLOCKWISE,in.search);}
        else{var index=new Block16DirectionVisibilityNoStats(in.grid,16);payload=index.payloadBytes();spatial=(r,c,a,b)->index.visibleAt(in.contract,r,c,a,b,AngleReference.GRID_NORTH_CLOCKWISE,in.search);}
        long built=System.nanoTime();var t=new Trial();int size=in.receivers.size();t.psd=new double[size];t.queries=new int[size];t.reasons=new String[size];t.branches=new String[size];t.statuses=new String[size];t.payload=payload;
        for(int i=0;i<size;i++){
            var r=in.receivers.get(i);double day=TimeStepBaseline.daylight(r.lat,in.dec),rise=720-day/2;long before=t.rays;
            AdaptiveTimeV1.Oracle oracle=time->{
                double[] a=TimeStepBaseline.sun(r.lat,in.dec,rise+time);
                long start=System.nanoTime();var v=spatial.at(r.row,r.col,Math.max(1e-7,a[0]),a[1]+r.north);t.queryNanos+=System.nanoTime()-start;
                t.rays++;t.samples+=v.spatialSamples;if(v.status==VisibilityStatus.VISIBLE)t.visible++;else if(v.status==VisibilityStatus.SHADED)t.shaded++;else t.unknown++;
                return v.status==VisibilityStatus.VISIBLE?1:v.status==VisibilityStatus.SHADED?0:-1;
            };
            var p=dual?SuffixTemporalAdapter.hybrid(day,oracle,false):SuffixTemporalAdapter.baseline(day,oracle);
            check(t.rays-before==p.unique(),"trial query count");t.psd[i]=p.psd();t.queries[i]=p.unique();t.statuses[i]=p.status();t.reasons[i]=p.reason();t.branches[i]=p.branch();
        }
        long computed=System.nanoTime();
        check(t.rays==expected.rays&&t.samples==(suffix?expected.sSamples:expected.bSamples)&&t.visible==expected.visible&&t.shaded==expected.shaded&&t.unknown==expected.unknown,"repeat query/sample/state totals");
        for(int i=0;i<size;i++)check(bits(t.psd[i],expected.scalars.get(i)[0])&&t.queries[i]==(int)expected.scalars.get(i)[1]
                &&t.statuses[i].equals(expected.labels.get(i)[0])&&t.reasons[i].equals(expected.labels.get(i)[1])&&t.branches[i].equals(expected.labels.get(i)[2]),"repeat pixel scalar mismatch");
        t.digest=digest(t);long writing=System.nanoTime();
        try(var w=writer(file)){w.write("receiver_index,psd_hex,queries,status,reason,branch\n");for(int i=0;i<size;i++)w.write(i+","+Double.toHexString(t.psd[i])+","+t.queries[i]+","+t.statuses[i]+","+t.reasons[i]+","+t.branches[i]+"\n");}
        long end=System.nanoTime();t.load=(loaded-begin)/1e9;t.build=(built-loaded)/1e9;t.compute=(computed-built)/1e9;t.write=(end-writing)/1e9;t.full=(end-begin)/1e9;
        var mem=ManagementFactory.getMemoryMXBean();t.heapUsed=mem.getHeapMemoryUsage().getUsed();t.heapCommitted=mem.getHeapMemoryUsage().getCommitted();t.nonheapUsed=mem.getNonHeapMemoryUsage().getUsed();
        for(var p:ManagementFactory.getMemoryPoolMXBeans())if(p.isValid()&&p.getType()==MemoryType.HEAP)t.poolPeakSum+=p.getPeakUsage().getUsed();return t;
    }
    static void stats(Path out,String tag,Verification v)throws IOException{
        try(var w=writer(out.resolve("counts_"+tag+".csv"))){w.write("rays,b_interpolations,s_interpolations,b_skipped,s_skipped,blocks,suffix_samples,eligibility_checks,end_tests,boundary_tests,planned_suffixes,actual_suffix_segments,visible,shaded,unknown,b_stats_query_sec,s_stats_query_sec\n");
            w.write(v.rays+","+v.bSamples+","+v.sSamples+","+v.bSkipped+","+v.sSkipped+","+v.blocks+","+v.suffix+","+v.eligibility+","+v.endTests+","+v.boundary+","+v.planned+","+v.actualSuffix+","+v.visible+","+v.shaded+","+v.unknown+","+v.bQueryNanos/1e9+","+v.sQueryNanos/1e9+"\n");}
    }
    public static void main(String[] args)throws Exception{
        check(args.length==2&&args[0].equals("--controlled-four-arm"),"explicit controlled four-arm gate required");
        Path out=Path.of(args[1]).toAbsolutePath().normalize();check(out.startsWith(Path.of("user_runs").toAbsolutePath().normalize()),"user_runs child");Files.createDirectory(out);
        try{
            var in=new ProjectedBaselineExperiment.Inputs(CONFIG);check(in.receivers.size()==16384&&in.search.maxSamples==200&&in.search.sampleSpacingGridUnits==50,"Qinling frozen contract");
            Verification old=verify(in,false,out);stats(out,"old",old);Verification dual=null;
            try{dual=verify(in,true,out);stats(out,"dual",dual);}catch(IllegalStateException e){
                if(!e.getMessage().startsWith("TEMPORAL"))throw e;
                Files.writeString(out.resolve("dual_gate_failure.txt"),e.toString(),StandardOpenOption.CREATE_NEW);System.out.println("DUAL_GATE_FAILED old spatial comparison retained");}
            try(var w=writer(out.resolve("runs.csv"))){
                w.write("phase,round,position,method,load_sec,build_sec,compute_sec,algorithm_sec,query_sec,write_sec,full_sec,rays,interpolations,visible,shaded,unknown,index_payload_bytes,heap_used_end,heap_committed_end,nonheap_used_end,heap_pool_peak_sum,scalar_sha256\n");
                for(int pass=0;pass<5;pass++){
                    String phase=pass<2?"warmup":"formal";int round=pass<2?pass+1:pass-1;
                    String[] order=pass%2==0?new String[]{"old_b","old_suffix","dual_b","dual_suffix"}:new String[]{"dual_suffix","dual_b","old_suffix","old_b"};
                    int position=0;for(String method:order){boolean isDual=method.startsWith("dual"),suffix=method.endsWith("suffix");if(isDual&&dual==null)continue;
                        position++;String id=phase+"_"+round+"_"+method;System.out.println("RUN_START "+id);
                        Trial t=trial(isDual,suffix,isDual?dual:old,out.resolve(id+"_pixels.csv"));
                        w.write(phase+","+round+","+position+","+method+","+t.load+","+t.build+","+t.compute+","+(t.build+t.compute)+","+t.queryNanos/1e9+","+t.write+","+t.full+","+t.rays+","+t.samples+","+t.visible+","+t.shaded+","+t.unknown+","+t.payload+","+t.heapUsed+","+t.heapCommitted+","+t.nonheapUsed+","+t.poolPeakSum+","+t.digest+"\n");w.flush();
                        System.out.println("RUN_END "+id+" algorithm_sec="+(t.build+t.compute)+" full_sec="+t.full);
                    }
                }
            }
            Files.writeString(out.resolve("completion.json"),"{\"status\":\"CONTROLLED_RUN_COMPLETE\",\"old_correct\":true,\"dual_correct\":"+(dual!=null)+",\"warmups_per_method\":2,\"formal_rounds\":3,\"automatic_extra_rounds\":false,\"candidate_index_payload_same_as_B\":true}\n",StandardOpenOption.CREATE_NEW);
        }catch(Exception|AssertionError e){try(var w=new PrintWriter(writer(out.resolve("failure.txt")))){e.printStackTrace(w);}throw e;}
    }
}
