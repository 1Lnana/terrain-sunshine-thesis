import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Explicit single-pass Stage B. Original binary time driver; unchanged Stage A observer. */
public final class Block16UpperBoundRealDiagnostic {
    static final String[] CATEGORIES={"skip","bound_clear","bound_shaded","bound_unknown","safety_clear","safety_shaded","safety_unknown"};
    static final String[] BASE={"rays","visible","shaded","unknown","bound_fail_rays","bound_fail_final_visible","in_grid_segments","outside_events","skipped_samples","interpolations","covered_samples","early_shaded_rays"};
    static final String[] BIN_NAMES={"[0,1)","[1,5)","[5,10)","[10,20)","[20,40)","[40,90)"};
    static final String[] NAMES;
    static {List<String> n=new ArrayList<>(List.of(BASE));for(String c:CATEGORIES){n.add(c+"_segments");n.add(c+"_calls");}NAMES=n.toArray(String[]::new);}
    static void check(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    static boolean bits(double a,double b){return Double.doubleToLongBits(a)==Double.doubleToLongBits(b);}
    static int bin(double a){return a<1?0:a<5?1:a<10?2:a<20?3:a<40?4:5;}
    static final class Metrics {
        final long[] v=new long[NAMES.length];
        void add(Metrics m){for(int i=0;i<v.length;i++)v[i]+=m.v[i];}
        String csv(){StringJoiner j=new StringJoiner(",");for(long n:v)j.add(Long.toString(n));return j.toString();}
    }
    static int seg(int category){return BASE.length+2*category;}
    static int calls(int category){return seg(category)+1;}
    static Metrics observe(Block16UpperBoundDiagnostic.Trace t, VisibilityResult d,
            BlockUpperBoundVisibility.Counters counts,SearchConfig search) {
        Metrics m=new Metrics();m.v[0]=1;
        m.v[d.status==VisibilityStatus.VISIBLE?1:d.status==VisibilityStatus.SHADED?2:3]=1;
        int next=1,position=0;boolean boundSeen=false;
        for(var s:t.segments){
            check(s.start==next && s.end>=s.start && s.end<=search.maxSamples,"segment schedule/index bounds");
            check(s.decision!=null && s.outcome!=null,"unclassified segment");
            boolean inside=s.blockRow>=0; m.v[inside?6:7]++;
            boolean safe=inside && s.complete && Double.isFinite(s.ray);
            boolean skip=safe && s.maximum+s.pad<s.ray;
            check((s.decision==Block16UpperBoundDiagnostic.Decision.SKIP)==skip,"skip predicate");
            check((s.decision==Block16UpperBoundDiagnostic.Decision.SAFETY)==!safe,"safety predicate");
            int category;
            if(skip){
                category=0;check(s.outcome==Block16UpperBoundDiagnostic.Outcome.SKIPPED && s.observations.isEmpty(),"skip observation");
                m.v[8]+=(long)s.end-s.start+1;next=s.end+1;
            }else{
                check(s.outcome!=Block16UpperBoundDiagnostic.Outcome.SKIPPED && !s.observations.isEmpty(),"fallback observation");
                boolean bound=s.decision==Block16UpperBoundDiagnostic.Decision.BOUND_FAIL;
                boundSeen|=bound;category=(bound?1:4)+s.outcome.ordinal()-1;
                int k=s.start;
                for(var ob:s.observations){check(ob.index==k && k<=s.end,"literal loop k order");k++;}
                next=k;
                if(s.outcome==Block16UpperBoundDiagnostic.Outcome.CLEAR){
                    check(k==s.end+1,"CLEAR requires full planned segment");
                    for(var ob:s.observations)check(ob.status==null,"CLEAR has unknown sample");
                }else{
                    check(position==t.segments.size()-1 && k-1==t.stopIndex,"terminal segment suffix");
                    var last=s.observations.get(s.observations.size()-1);
                    if(s.outcome==Block16UpperBoundDiagnostic.Outcome.SHADED)check(last.status==null && d.status==VisibilityStatus.SHADED,"shade cause");
                    else check(last.status==d.status && d.status!=VisibilityStatus.VISIBLE && d.status!=VisibilityStatus.SHADED,"unknown cause");
                }
            }
            m.v[seg(category)]++;m.v[calls(category)]+=s.observations.size();position++;
        }
        m.v[9]=d.spatialSamples;m.v[10]=next-1;
        m.v[4]=boundSeen?1:0;m.v[5]=boundSeen && d.status==VisibilityStatus.VISIBLE?1:0;
        m.v[11]=d.status==VisibilityStatus.SHADED && t.stopIndex<search.maxSamples?1:0;
        long parts=0,actual=0;for(int c=0;c<7;c++){parts+=m.v[seg(c)];actual+=m.v[calls(c)];}
        check(parts==t.segments.size() && parts==m.v[6]+m.v[7],"segment partition");
        check(actual==m.v[9] && actual==counts.spatialSamples && m.v[8]==counts.skippedSamples && m.v[6]==counts.blockTests,"actual/old counters");
        check(m.v[9]+m.v[8]==m.v[10] && m.v[10]==t.stopIndex && bits(t.stopIndex*search.sampleSpacingGridUnits,d.searchedDistanceGridUnits),"literal contiguous prefix conservation");
        check(t.status==d.status,"trace status");return m;
    }
    static BufferedWriter writer(Path p)throws IOException{return Files.newBufferedWriter(p,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);}
    static String[] split(String s){check(s!=null,"short frozen record");return s.split(",",-1);}
    static int column(List<String> h,String name){int n=h.indexOf(name);check(n>=0,"missing frozen column "+name);return n;}
    static void table(Path p,Metrics[] bins,Metrics total)throws IOException{
        try(var w=writer(p)){w.write("bin,"+String.join(",",NAMES)+"\n");
            for(int b=0;b<6;b++)w.write(b+","+bins[b].csv()+"\n");
            w.write("ALL,"+total.csv()+"\n");}
    }
    static void proportions(Path p,Metrics[] bins,Metrics total)throws IOException{
        try(var w=writer(p)){w.write("bin,category,segments,calls,all_actual_calls,unexcluded_segments,calls_fraction_all,segments_fraction_unexcluded\n");
            for(int b=0;b<=6;b++){
                Metrics m=b==6?total:bins[b];long unexcluded=0;for(int c=1;c<7;c++)unexcluded+=m.v[seg(c)];
                for(int c=1;c<7;c++)w.write((b==6?"ALL":b)+","+CATEGORIES[c]+","+m.v[seg(c)]+","+m.v[calls(c)]+","+m.v[9]+","+unexcluded+","+(m.v[9]==0?"NA":Double.toString((double)m.v[calls(c)]/m.v[9]))+","+(unexcluded==0?"NA":Double.toString((double)m.v[seg(c)]/unexcluded))+"\n");
            }
        }
    }
    public static void main(String[] args)throws Exception{
        check(args.length==3 && args[0].equals("--qinling-single-pass"),"explicit --qinling-single-pass CONFIG NEW_OUTPUT required");
        Path out=Path.of(args[2]).toAbsolutePath().normalize(),allowed=Path.of("user_runs").toAbsolutePath().normalize();
        check(out.startsWith(allowed) && !out.equals(allowed),"user_runs child required");Files.createDirectory(out);
        try{
            var in=new ProjectedBaselineExperiment.Inputs(Path.of(args[1]));
            check(in.methods.equals(List.of("binarylocal_30min")) && in.repeats==1 && in.warmups==0,"single old binary pass only");
            check(in.p.getProperty("diagnostic_stage").equals("B_single_pass") && in.grid.rows==356 && in.grid.cols==356 && in.grid.cellSize==90
                    && in.integer("row_start")==114 && in.integer("col_start")==114 && in.integer("rows")==128 && in.integer("cols")==128
                    && in.search.sampleSpacingGridUnits==50 && in.search.maxSamples==200 && in.receivers.size()==16384
                    && in.p.getProperty("date").equals("2023-12-21"),"frozen Qinling contract");
            Files.copy(in.config,out.resolve("effective.properties"));
            var original=new Block16DirectionVisibility(in.grid,16);var diagnostic=new Block16UpperBoundDiagnostic(in.grid,16);
            var trial=new Block16EngineeringBaselineExperiment.Trial();
            Metrics total=new Metrics();Metrics[] bins=new Metrics[6];Arrays.setAll(bins,i->new Metrics());
            Path frozen=in.resolve(in.p.getProperty("verified_run")).resolve("results");
            for(var r:in.receivers)in.traces.add(r.key());
            long start=System.nanoTime();long[] rayId={0};
            try(var fr=new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(frozen.resolve("rays.csv.gz"))),StandardCharsets.UTF_8));
                    var fp=Files.newBufferedReader(frozen.resolve("pixels.csv"));
                    var rw=new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(out.resolve("rays.csv.gz"),StandardOpenOption.CREATE_NEW)),StandardCharsets.UTF_8));
                    var pw=writer(out.resolve("pixels.csv"))){
                List<String> h=List.of(split(fr.readLine())),ph=List.of(split(fp.readLine()));
                int fs=column(h,"b_samples"),fn=column(h,"b_node"),ft=column(h,"b_block_tests"),fk=column(h,"b_skipped");
                int pq=column(ph,"query_count"),pn=column(ph,"integration_node_count");
                rw.write("ray_id,receiver_index,row,col,query_index,time_hex,altitude_hex,bearing_hex,bin,diag_status,b_status,frozen_status,stop_hex,b_stop_hex,frozen_stop_hex,stop_index,b_interpolations,b_block_tests,b_skipped_samples,"+String.join(",",NAMES)+"\n");
                pw.write("receiver_index,row,col,psd_hex,frozen_psd_hex,query_count,integration_node_count,fallback_reason,refinement_queries,guard_queries\n");
                for(int i=0;i<in.receivers.size();i++){
                    final int pixelId=i;var receiver=in.receivers.get(i);String[] p=split(fp.readLine());
                    check(Integer.parseInt(p[0])==i && Integer.parseInt(p[1])==receiver.row && Integer.parseInt(p[2])==receiver.col,"frozen receiver order");
                    int[] qi={0};var expectedTrace=new TreeMap<Double,Integer>();var expectedNodes=new TreeMap<Double,Integer>();
                    ProjectedBaselineExperiment.SpatialQuery query=(contract,row,col,a,b,reference,search,ignored)->{
                        try{
                            String[] f=split(fr.readLine());
                            check(Integer.parseInt(f[0])==pixelId && Integer.parseInt(f[1])==row && Integer.parseInt(f[2])==col && Integer.parseInt(f[3])==qi[0],"frozen query identity/order");
                            check(bits(a,Double.parseDouble(f[5])) && bits(b,Double.parseDouble(f[6])),"frozen solar/query parameters");
                            var bc=new BlockUpperBoundVisibility.Counters();var dc=new BlockUpperBoundVisibility.Counters();var trace=new Block16UpperBoundDiagnostic.Trace();
                            var base=original.visibleAt(contract,row,col,a,b,reference,search,bc);
                            var d=diagnostic.visibleAt(contract,row,col,a,b,reference,search,dc,trace);
                            check(base.status==d.status && bits(base.searchedDistanceGridUnits,d.searchedDistanceGridUnits) && base.spatialSamples==d.spatialSamples,"original B ray mismatch");
                            check(bc.blockTests==dc.blockTests && bc.skippedSamples==dc.skippedSamples && bc.spatialSamples==dc.spatialSamples,"original B counters mismatch");
                            check(d.status.toString().equals(f[8]) && bits(d.searchedDistanceGridUnits,Double.parseDouble(f[11])) && trace.stopIndex==Integer.parseInt(f[14])
                                    && d.spatialSamples==Long.parseLong(f[fs]) && dc.blockTests==Long.parseLong(f[ft]) && dc.skippedSamples==Long.parseLong(f[fk]),"frozen B behavior mismatch");
                            Metrics m=observe(trace,d,dc,search);int bin=bin(a);bins[bin].add(m);total.add(m);
                            double time=Double.parseDouble(f[4]);int state=d.status==VisibilityStatus.VISIBLE?1:d.status==VisibilityStatus.SHADED?0:-1;
                            expectedTrace.put(time,state);if(f[fn].equals("1"))expectedNodes.put(time,state);
                            rw.write(rayId[0]+","+pixelId+","+row+","+col+","+qi[0]+","+f[4]+","+f[5]+","+f[6]+","+bin+","+d.status+","+base.status+","+f[8]+","+Double.toHexString(d.searchedDistanceGridUnits)+","+Double.toHexString(base.searchedDistanceGridUnits)+","+f[11]+","+trace.stopIndex+","+base.spatialSamples+","+bc.blockTests+","+bc.skippedSamples+","+m.csv()+"\n");
                            qi[0]++;rayId[0]++;return d;
                        }catch(IOException e){throw new UncheckedIOException(e);}
                    };
                    var actual=ProjectedBaselineExperiment.calculateWithSpatialQuery(in,receiver,"binarylocal_30min",null,query,null,false);
                    check(actual.trace.equals(expectedTrace) && actual.nodes.equals(expectedNodes),"actual time keys/states/integration maps mismatch");
                    check(actual.samples==Integer.parseInt(p[pq]) && qi[0]==actual.samples && actual.nodes.size()==Integer.parseInt(p[pn]),"query/node count");
                    check(bits(actual.day,Double.parseDouble(p[3])) && bits(actual.psd,Double.parseDouble(p[5])) && actual.reason.equals(p[15])
                            && actual.refinementQueries==Integer.parseInt(p[16]) && actual.guardQueries==Integer.parseInt(p[17]),"pixel PSD/time fallback mismatch");
                    pw.write(i+","+receiver.row+","+receiver.col+","+Double.toHexString(actual.psd)+","+p[5]+","+actual.samples+","+actual.nodes.size()+","+actual.reason+","+actual.refinementQueries+","+actual.guardQueries+"\n");
                    // Keep only scalar Pixel fields after complete trace/node checks.
                    actual.trace=null;actual.nodes=null;trial.pixels.add(actual);
                    if((i+1)%2048==0)System.out.println("DIAGNOSTIC_PROGRESS pixels="+(i+1)+" rays="+rayId[0]);
                }
                check(fr.readLine()==null && fp.readLine()==null,"unconsumed frozen records");
            }
            double diagnosticSeconds=(System.nanoTime()-start)/1e9;
            check(total.v[0]==862525 && total.v[6]==10665319 && total.v[8]==93217471 && total.v[9]==17275977,"historical B totals");
            check(Block16EngineeringBaselineExperiment.digest(in,trial).equals(Block16DirectionIntegrationChecks.EXPECTED),"historical PSD digest");
            table(out.resolve("counts.csv"),bins,total);proportions(out.resolve("proportions.csv"),bins,total);
            Files.writeString(out.resolve("completion.json"),"{\"stage\":\"B\",\"passes\":1,\"warmups\":0,\"performance_comparison\":false,\"ray_mismatches\":0,\"pixel_mismatches\":0,\"conservation_failures\":0,\"frozen_queries_consumed\":"+rayId[0]+",\"diagnostic_wall_seconds\":"+diagnosticSeconds+",\"psd_sha256\":\""+Block16DirectionIntegrationChecks.EXPECTED+"\",\"corridor_analysis\":false}\n",StandardOpenOption.CREATE_NEW);
            System.out.println("STAGE_B_PASS rays="+rayId[0]+" b_mismatches=0 single_pass no_performance no_corridor");
        }catch(Exception|AssertionError e){try(var w=new PrintWriter(writer(out.resolve("failure.txt")))){e.printStackTrace(w);}throw e;}
    }
}
