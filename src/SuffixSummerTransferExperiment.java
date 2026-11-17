import java.io.*;
import java.lang.management.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Summer transfer only; algorithms frozen, winter measurement body reused verbatim. */
public final class SuffixSummerTransferExperiment {
    static final Path CONFIG=Path.of("configs/qinling_spatial_transfer_summer.properties");
    static void check(boolean ok,String m){if(!ok)throw new IllegalStateException(m);}
    static boolean bits(double a,double b){return Double.doubleToLongBits(a)==Double.doubleToLongBits(b);}
    static BufferedWriter writer(Path p)throws IOException{return Files.newBufferedWriter(p,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);}
    static BufferedWriter compressed(Path p)throws IOException{return new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(p,StandardOpenOption.CREATE_NEW)),StandardCharsets.UTF_8));}
    static final class Verification {
        long rays,bSamples,sSamples,bSkipped,sSkipped,blocks,suffix,eligibility,endTests,boundary,planned,actualSuffix;
        long visible,shaded,unknown;long bQueryNanos,sQueryNanos;
        final List<double[]> scalars=new ArrayList<>();final List<String[]> labels=new ArrayList<>();
    }
    record Stamp(double time,double altitude,double bearing,VisibilityStatus status,double stop,long samples){}
    static int bin(double a){return a<1?0:a<5?1:a<10?2:a<20?3:a<40?4:5;}
    static Verification verifySummer(ProjectedBaselineExperiment.Inputs in,Path out)throws Exception{
        var reference=ProjectedBaselineExperiment.loadReference(in);var result=new Verification();
        var b=new Block16DirectionVisibility(in.grid,16);var bn=new Block16DirectionVisibilityNoStats(in.grid,16);
        var s=new SuffixBlockVisibility(in.grid,16);var sn=new SuffixBlockVisibilityNoStats(in.grid,16);
        long[][] bins=new long[6][13];
        try(var rays=compressed(out.resolve("paired_rays.csv.gz"));var maps=compressed(out.resolve("paired_nodes.csv.gz"));
                var requests=compressed(out.resolve("paired_requests.csv.gz"));var pixels=writer(out.resolve("paired_pixels.csv"))){
            rays.write("receiver_index,query_index,time_hex,altitude_hex,bearing_hex,b_status,s_status,point_status,b_stop_hex,s_stop_hex,point_stop_hex,b_interpolations,s_interpolations,point_interpolations,b_skipped,s_skipped,suffix_samples,blocks,eligibility_checks,end_tests,boundary_tests,planned_suffixes,actual_suffix_segments\n");
            maps.write("receiver_index,time_hex,b_state,s_state,integration_node\n");requests.write("receiver_index,request_index,time_hex,state,purpose,fresh,pair_match\n");
            pixels.write("receiver_index,row,col,latitude,longitude,day_hex,b_psd_hex,s_psd_hex,reference_psd_hex,queries,status,reason,branch,requests,hits,anchor,boundary,fallback,absolute_error_hex,error_bin\n");
            for(int i=0;i<in.receivers.size();i++){
                final int id=i;var r=in.receivers.get(i);double day=TimeStepBaseline.daylight(r.lat,in.dec),rise=720-day/2;
                var stamps=new ArrayList<Stamp>();
                AdaptiveTimeV1.Oracle oracle=t->{
                    double[] angles=TimeStepBaseline.sun(r.lat,in.dec,rise+t);double altitude=Math.max(1e-7,angles[0]),bearing=angles[1]+r.north;
                    var bc=new BlockUpperBoundVisibility.Counters();var sc=new SuffixBlockVisibility.Counters();
                    long start=System.nanoTime();var br=b.visibleAt(in.contract,r.row,r.col,altitude,bearing,AngleReference.GRID_NORTH_CLOCKWISE,in.search,bc);result.bQueryNanos+=System.nanoTime()-start;
                    start=System.nanoTime();var sr=s.visibleAt(in.contract,r.row,r.col,altitude,bearing,AngleReference.GRID_NORTH_CLOCKWISE,in.search,sc);result.sQueryNanos+=System.nanoTime()-start;
                    var bnr=bn.visibleAt(in.contract,r.row,r.col,altitude,bearing,AngleReference.GRID_NORTH_CLOCKWISE,in.search);
                    var snr=sn.visibleAt(in.contract,r.row,r.col,altitude,bearing,AngleReference.GRID_NORTH_CLOCKWISE,in.search);
                    var pr=ControlledVisibility.visibleAt(in.grid,in.contract,r.row,r.col,altitude,bearing,AngleReference.GRID_NORTH_CLOCKWISE,in.search,null);
                    for(var v:new VisibilityResult[]{sr,bnr,snr,pr})check(v.status==br.status&&bits(v.searchedDistanceGridUnits,br.searchedDistanceGridUnits),"SAFETY state/first stop mismatch; stop experiment");
                    check(bnr.spatialSamples==br.spatialSamples&&snr.spatialSamples==sr.spatialSamples,"SAFETY Stats/NoStats mismatch");
                    check(bc.spatialSamples-sc.spatialSamples==sc.suffixSamples&&bc.skippedSamples+sc.suffixSamples==sc.skippedSamples
                            &&bc.blockTests==sc.blockTests&&sc.spatialSamples+sc.skippedSamples==pr.spatialSamples,"SAFETY sample conservation");
                    try{rays.write(id+","+stamps.size()+","+Double.toHexString(t)+","+Double.toHexString(altitude)+","+Double.toHexString(bearing)+","+br.status+","+sr.status+","+pr.status+","+Double.toHexString(br.searchedDistanceGridUnits)+","+Double.toHexString(sr.searchedDistanceGridUnits)+","+Double.toHexString(pr.searchedDistanceGridUnits)+","+br.spatialSamples+","+sr.spatialSamples+","+pr.spatialSamples+","+bc.skippedSamples+","+sc.skippedSamples+","+sc.suffixSamples+","+bc.blockTests+","+sc.eligibilityChecks+","+sc.endTests+","+sc.boundaryTests+","+sc.plannedSuffixes+","+sc.actualSuffixSegments+"\n");}catch(IOException e){throw new UncheckedIOException(e);}
                    stamps.add(new Stamp(t,altitude,bearing,br.status,br.searchedDistanceGridUnits,sr.spatialSamples));
                    result.rays++;result.bSamples+=br.spatialSamples;result.sSamples+=sr.spatialSamples;result.bSkipped+=bc.skippedSamples;result.sSkipped+=sc.skippedSamples;
                    result.blocks+=bc.blockTests;result.suffix+=sc.suffixSamples;result.eligibility+=sc.eligibilityChecks;result.endTests+=sc.endTests;result.boundary+=sc.boundaryTests;result.planned+=sc.plannedSuffixes;result.actualSuffix+=sc.actualSuffixSegments;
                    int state=br.status==VisibilityStatus.VISIBLE?1:br.status==VisibilityStatus.SHADED?0:-1;
                    if(state==1)result.visible++;else if(state==0)result.shaded++;else result.unknown++;
                    long[] v=bins[bin(altitude)];v[0]++;v[1]+=br.spatialSamples;v[2]+=sr.spatialSamples;v[3]+=sc.suffixSamples;v[4]+=bc.blockTests;
                    v[5]+=sc.eligibilityChecks;v[6]+=sc.endTests;v[7]+=sc.boundaryTests;v[8]+=sc.plannedSuffixes;v[9]+=sc.actualSuffixSegments;v[state==1?10:state==0?11:12]++;
                    return state;
                };
                var p=SuffixTemporalAdapter.hybrid(day,oracle,true);check(p.unique()==stamps.size(),"fresh count");
                int[] position={0};
                AdaptiveTimeV1.Oracle independentCandidate=t->{
                    check(position[0]<stamps.size(),"SAFETY candidate time query extra");Stamp expected=stamps.get(position[0]++);
                    double[] angles=TimeStepBaseline.sun(r.lat,in.dec,rise+t);double altitude=Math.max(1e-7,angles[0]),bearing=angles[1]+r.north;
                    check(bits(t,expected.time())&&bits(altitude,expected.altitude())&&bits(bearing,expected.bearing()),"SAFETY different actual solar query sequence");
                    var v=sn.visibleAt(in.contract,r.row,r.col,altitude,bearing,AngleReference.GRID_NORTH_CLOCKWISE,in.search);
                    check(v.status==expected.status()&&bits(v.searchedDistanceGridUnits,expected.stop())&&v.spatialSamples==expected.samples(),"SAFETY independent candidate ray");
                    return v.status==VisibilityStatus.VISIBLE?1:v.status==VisibilityStatus.SHADED?0:-1;
                };
                var other=SuffixTemporalAdapter.hybrid(day,independentCandidate,true);
                check(position[0]==stamps.size()&&p.equals(other),"SAFETY actual time requests/cache/integration/pixel differ");
                for(var e:p.evaluated().entrySet())maps.write(i+","+Double.toHexString(e.getKey())+","+e.getValue()+","+other.evaluated().get(e.getKey())+","+(p.nodes().containsKey(e.getKey())?1:0)+"\n");
                for(int q=0;q<p.trace().size();q++){var e=p.trace().get(q);requests.write(i+","+q+","+Double.toHexString(e.time())+","+e.state()+","+e.purpose()+","+e.fresh()+",true\n");}
                double ref=reference.pixels.get(i).psd,err=Math.abs(p.psd()-ref);
                String errorBin=!Double.isFinite(err)?"UNKNOWN":err<=1e-8?"NUMERIC_ZERO":err<=1?"LE1":err<=5?"GT1_LE5":err<=10?"GT5_LE10":err<=30?"GT10_LE30":"GT30";
                pixels.write(i+","+r.row+","+r.col+","+r.lat+","+r.lon+","+Double.toHexString(day)+","+Double.toHexString(p.psd())+","+Double.toHexString(other.psd())+","+Double.toHexString(ref)+","+p.unique()+","+p.status()+","+p.reason()+","+p.branch()+","+p.requests()+","+p.hits()+","+p.anchor()+","+p.boundary()+","+p.fallback()+","+Double.toHexString(err)+","+errorBin+"\n");
                result.scalars.add(new double[]{p.psd(),p.unique()});result.labels.add(new String[]{p.status(),p.reason(),p.branch()});
                if((i+1)%4096==0)System.out.println("SUMMER_VERIFY_PROGRESS pixels="+(i+1)+" rays="+result.rays);
            }
        }
        try(var w=writer(out.resolve("altitude_counts.csv"))){w.write("bin,rays,b_interpolations,s_interpolations,suffix_samples,blocks,eligibility_checks,end_tests,boundary_tests,planned_suffixes,actual_suffix_segments,visible,shaded,unknown\n");
            for(int b0=0;b0<6;b0++){w.write(Integer.toString(b0));for(long v:bins[b0])w.write(","+v);w.write("\n");}}
        check(result.rays>0&&result.bSamples-result.sSamples==result.suffix,"summer totals");
        Files.writeString(out.resolve("correctness_gate.json"),"{\"state_stop_mismatches\":0,\"actual_query_sequence_mismatches\":0,\"time_pixel_mismatches\":0,\"rays\":"+result.rays+",\"minute_reference_reused\":true}\n",StandardOpenOption.CREATE_NEW);
        System.out.println("SUMMER_CORRECTNESS_PASS rays="+result.rays+" samples_B="+result.bSamples+" samples_suffix="+result.sSamples);return result;
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
        check(args.length==2&&args[0].equals("--summer-frozen-pair"),"explicit frozen summer pair required");
        Path out=Path.of(args[1]).toAbsolutePath().normalize();check(out.startsWith(Path.of("user_runs").toAbsolutePath().normalize()),"user_runs child");Files.createDirectory(out);
        try{
            var in=new ProjectedBaselineExperiment.Inputs(CONFIG);check(in.receivers.size()==16384&&in.grid.rows==356&&in.grid.cols==356&&in.grid.cellSize==90
                    &&in.search.maxSamples==200&&in.search.sampleSpacingGridUnits==50&&in.p.getProperty("date").equals("2023-06-21"),"summer frozen contract");
            Verification gate=verifySummer(in,out);stats(out,"summer",gate);
            try(var w=writer(out.resolve("runs.csv"))){
                w.write("phase,round,position,method,load_sec,build_sec,compute_sec,algorithm_sec,query_sec,write_sec,full_sec,rays,interpolations,visible,shaded,unknown,index_payload_bytes,heap_used_end,heap_committed_end,nonheap_used_end,heap_pool_peak_sum,scalar_sha256\n");
                for(int pass=0;pass<13;pass++){
                    String phase=pass<3?"warmup":"formal";int round=pass<3?pass+1:pass-2;
                    // Balance formal pair order independently of the odd warmup count.
                    String[] order=round%2==1?new String[]{"dual_b","dual_suffix"}:new String[]{"dual_suffix","dual_b"};
                    for(int position=0;position<2;position++){
                        String method=order[position],id=phase+"_"+round+"_"+method;System.out.println("RUN_START "+id);
                        Trial t=trial(true,method.endsWith("suffix"),gate,out.resolve(id+"_pixels.csv"));
                        w.write(phase+","+round+","+(position+1)+","+method+","+t.load+","+t.build+","+t.compute+","+(t.build+t.compute)+","+t.queryNanos/1e9+","+t.write+","+t.full+","+t.rays+","+t.samples+","+t.visible+","+t.shaded+","+t.unknown+","+t.payload+","+t.heapUsed+","+t.heapCommitted+","+t.nonheapUsed+","+t.poolPeakSum+","+t.digest+"\n");w.flush();
                        System.out.println("RUN_END "+id+" algorithm_sec="+(t.build+t.compute)+" full_sec="+t.full);
                    }
                }
            }
            Files.writeString(out.resolve("completion.json"),"{\"status\":\"SUMMER_FROZEN_PAIR_COMPLETE\",\"warmups_per_method\":3,\"formal_pairs\":10,\"algorithm_changed\":false,\"extra_rounds\":0}\n",StandardOpenOption.CREATE_NEW);
        }catch(Exception|AssertionError e){try(var w=new PrintWriter(writer(out.resolve("failure.txt")))){e.printStackTrace(w);}throw e;}
    }
}
