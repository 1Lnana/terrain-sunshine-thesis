import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Explicit Stage A only: in-memory artificial grids; no DEM/config loader or benchmark. */
public final class Block16UpperBoundDiagnosticChecks {
    private static long assertions, rays, samples, skipped, blockTests, posthoc, earlyShades;
    private static final long[][][] bins=new long[6][3][4];
    private static final long[] binRays=new long[6];
    private static final Set<VisibilityStatus> statuses=EnumSet.noneOf(VisibilityStatus.class);
    private static final Map<String,long[]> cases=new LinkedHashMap<>();
    private static String active="none";
    private static double[][] activeGrid;
    private static PrintWriter rayOut,segmentOut,sampleOut,conservationOut;
    private static void check(boolean value,String message) {
        assertions++;if(!value)throw new AssertionError(active+": "+message);
    }
    private static boolean bits(double a,double b) {return Double.doubleToLongBits(a)==Double.doubleToLongBits(b);}
    private static String hex(double v) {return Double.toHexString(v);}
    private static double[][] flat(int rows,int cols,double height) {
        double[][] v=new double[rows][cols];for(double[] row:v)Arrays.fill(row,height);return v;
    }
    private static double[][] flat() {return flat(96,96,100);}
    private static int bin(double a) {return a<1?0:a<5?1:a<10?2:a<20?3:a<40?4:5;}
    private static double height(AsciiGrid g,int r,int c) {
        check(g.inside(r,c) && g.valid[r][c],"posthoc support must be valid");return g.elevation[r][c];
    }
    // Read-only posthoc support enumeration mirrors B's exact-row/column decisions.
    // Its value NEVER enters the diagnostic visibility decision or skip predicate.
    private static double supportMax(AsciiGrid g,double row,double col) {
        int nr=(int)Math.rint(row),nc=(int)Math.rint(col);
        boolean er=Math.abs(row-nr)<1e-9,ec=Math.abs(col-nc)<1e-9;
        if(er&&ec)return height(g,nr,nc);
        int r0=(int)Math.floor(row),c0=(int)Math.floor(col);
        if(er)return Math.max(height(g,r0,c0),height(g,r0,c0+1));
        if(ec)return Math.max(height(g,r0,c0),height(g,r0+1,c0));
        return Math.max(Math.max(height(g,r0,c0),height(g,r0+1,c0)),
                Math.max(height(g,r0,c0+1),height(g,r0+1,c0+1)));
    }
    private static void same(VisibilityResult a,VisibilityResult b,String label) {
        check(a.status==b.status,label+" status");
        check(bits(a.searchedDistanceGridUnits,b.searchedDistanceGridUnits),label+" stop distance bits");
        check(a.spatialSamples==b.spatialSamples,label+" interpolation count");
    }
    private static void run(String name,double[][] values,int row,int col,double cell,
            double altitude,double bearing,SearchConfig search,VisibilityStatus expected,int expectedStop) {
        active=name;activeGrid=values;
        var grid=new AsciiGrid(values.length,values[0].length,0,0,cell,-9999,values,ZeroPolicy.PRESERVE_AS_VALID);
        var bc=new BlockUpperBoundVisibility.Counters();var dc=new BlockUpperBoundVisibility.Counters();
        var original=new Block16DirectionVisibility(grid,16);
        var diagnostic=new Block16UpperBoundDiagnostic(grid,16);
        var trace=new Block16UpperBoundDiagnostic.Trace();
        var b=original.visibleAt(TimeStepBaseline.contract(),row,col,altitude,bearing,AngleReference.GRID_NORTH_CLOCKWISE,search,bc);
        var d=diagnostic.visibleAt(TimeStepBaseline.contract(),row,col,altitude,bearing,AngleReference.GRID_NORTH_CLOCKWISE,search,dc,trace);
        same(b,d,"B vs diagnostic");
        check(bc.blockTests==dc.blockTests && bc.skippedSamples==dc.skippedSamples && bc.spatialSamples==dc.spatialSamples,"old counters unchanged");
        var point=ControlledVisibility.visibleAt(grid,TimeStepBaseline.contract(),row,col,altitude,bearing,
                AngleReference.GRID_NORTH_CLOCKWISE,search,null);
        check(point.status==d.status && bits(point.searchedDistanceGridUnits,d.searchedDistanceGridUnits),"point state/stop");
        if(expected!=null) {
            check(d.status==expected,"independent expected state");
            check(bits(d.searchedDistanceGridUnits,expectedStop*search.sampleSpacingGridUnits),"independent expected stop");
        }
        check(trace.status==d.status,"trace terminal state");
        BitSet covered=new BitSet(),actual=new BitSet(),omit=new BitSet();
        long[] counts=new long[7]; // skip, bound-clear/shade/unknown, safety-clear/shade/unknown
        long calls=0,inside=0,oracle=0;int next=1,seq=0,priorBr=-2,priorBc=-2;boolean repeatedBlock=false;
        int altitudeBin=bin(altitude);binRays[altitudeBin]++;
        for(var segment:trace.segments) {
            seq++;check(segment.start==next,"actual schedule continues at prior segment end+1");
            check(segment.start>=1 && segment.end>=segment.start && segment.end<=search.maxSamples,"segment index bounds");
            check(segment.decision!=null && segment.outcome!=null,"classified exactly once");
            if(segment.blockRow>=0) {
                inside++;
                if(priorBr==segment.blockRow && priorBc==segment.blockCol)repeatedBlock=true;
            }
            priorBr=segment.blockRow;priorBc=segment.blockCol;
            bins[altitudeBin][segment.decision.ordinal()][segment.outcome.ordinal()]++;
            boolean rescusable=false;double corridor=Double.NaN;
            if(segment.decision==Block16UpperBoundDiagnostic.Decision.SKIP) {
                counts[0]++;check(segment.outcome==Block16UpperBoundDiagnostic.Outcome.SKIPPED,"skip has no fallback outcome");
                check(segment.observations.isEmpty(),"skip must not interpolate");
                check(segment.complete && Double.isFinite(segment.ray) && segment.maximum+segment.pad<segment.ray,"original skip predicate");
                // Enumerate the scheduled skip interval, independently of the counter's arithmetic.
                for(int k=segment.start;k<=segment.end;k++) {
                    check(!covered.get(k),"skip index disjoint");covered.set(k);omit.set(k);
                }
                next=segment.end+1;
            } else {
                check(segment.outcome!=Block16UpperBoundDiagnostic.Outcome.SKIPPED,"fallback cannot be skipped");
                boolean bound=segment.decision==Block16UpperBoundDiagnostic.Decision.BOUND_FAIL;
                check(bound==(segment.blockRow>=0 && segment.complete && Double.isFinite(segment.ray)),"failure vs safety mutually exclusive");
                if(bound)check(!(segment.maximum+segment.pad<segment.ray),"bound-failure predicate");
                int outcome=segment.outcome.ordinal()-1;check(outcome>=0 && outcome<3,"fallback terminal outcome");
                counts[(bound?1:4)+outcome]++;
                int k=segment.start;
                for(var ob:segment.observations) {
                    check(ob.index==k && ob.index<=segment.end,"literal inner-loop k order");
                    check(!covered.get(ob.index),"interpolation index disjoint");covered.set(ob.index);actual.set(ob.index);
                    calls++;k++;
                    sampleOut.printf(Locale.ROOT,"%s,%d,%d,%s,%s,%s,%s%n",name,seq,ob.index,hex(ob.row),hex(ob.col),hex(ob.value),ob.status==null?"VALID":ob.status);
                }
                check(!segment.observations.isEmpty(),"entered fallback executes at least one iteration");
                next=k;
                if(segment.outcome==Block16UpperBoundDiagnostic.Outcome.CLEAR) {
                    check(k==segment.end+1,"clear means FULL planned segment checked");
                    check(segment.observations.stream().allMatch(ob->ob.status==null),"clear has no UNKNOWN");
                    if(bound) {
                        corridor=Double.NEGATIVE_INFINITY;
                        for(var ob:segment.observations)corridor=Math.max(corridor,supportMax(grid,ob.row,ob.col));
                        check(corridor<=segment.maximum,"corridor support inside original padded block bound");
                        rescusable=corridor+segment.pad<segment.ray;
                        if(rescusable){oracle++;posthoc++;}
                    }
                } else {
                    check(seq==trace.segments.size(),"SHADED/UNKNOWN has no later visited segment");
                    check(k-1==trace.stopIndex,"actual final k equals early-return index");
                    var last=segment.observations.get(segment.observations.size()-1);
                    check(segment.outcome==Block16UpperBoundDiagnostic.Outcome.SHADED ? last.status==null && d.status==VisibilityStatus.SHADED : last.status==d.status,"terminal sample cause");
                }
            }
            String indices=segment.observations.stream().map(ob->Integer.toString(ob.index)).reduce((a,c)->a+";"+c).orElse("");
            segmentOut.printf(Locale.ROOT,"%s,%d,%d,%d,%d,%d,%s,%s,%s,%d,%s,%s,%s,%s,%s,%s,%s%n",
                    name,seq,segment.start,segment.end,segment.blockRow,segment.blockCol,segment.decision,segment.safety,segment.outcome,
                    segment.observations.size(),indices,hex(segment.maximum),hex(segment.ray),hex(segment.pad),segment.complete,hex(corridor),rescusable);
        }
        int stop=trace.stopIndex;
        check(covered.cardinality()==stop && covered.nextClearBit(1)==stop+1,"observed coverage is exactly contiguous indices 1..actual stop");
        check(actual.cardinality()+omit.cardinality()==covered.cardinality(),"actual and skipped disjoint union");
        check(calls==actual.cardinality() && calls==d.spatialSamples && calls==dc.spatialSamples,"actual loop observations equal interpolation counters");
        check(omit.cardinality()==dc.skippedSamples,"enumerated scheduled skips equal old skipped counter");
        check(inside==dc.blockTests,"inside segment events equal blockTests; OUTSIDE not included");
        check(covered.cardinality()==point.spatialSamples,"coverage matches independent point traversal, not fixed cap assumption");
        check(bits(stop*search.sampleSpacingGridUnits,d.searchedDistanceGridUnits),"actual index and stop distance");
        check(Arrays.stream(counts).sum()==trace.segments.size(),"mutually exclusive exhaustive event partition");
        if(name.equals("flat_corridor"))check(counts[0]>0 && calls==0,"flat corridor really skips");
        if(name.equals("off_path_peak") || name.equals("off_path_halo_peak"))check(counts[1]>0 && oracle>0,"off-path peak causes clear fallback with posthoc tighter exclusion");
        if(name.equals("tangent") || name.equals("clear_but_entry_bound_loose") || name.equals("pad_only_clear"))check(counts[1]>0 && oracle==0,"clear fallback is NOT automatically posthoc-excludable");
        if(name.equals("off_path_nodata"))check(counts[4]>0 && oracle==0,"safety fallback clear is not geometric-bound failure");
        if(name.equals("same_block_revisit"))check(repeatedBlock,"one physical block can have multiple segment visits");
        if(d.status==VisibilityStatus.SHADED && stop<search.maxSamples)earlyShades++;
        rays++;samples+=calls;skipped+=omit.cardinality();blockTests+=inside;statuses.add(d.status);cases.put(name,counts);
        StringJoiner rayRow=new StringJoiner(",");
        for(Object value:new Object[]{
                name,row,col,hex(cell),hex(altitude),hex(bearing),search.maxSamples,hex(search.sampleSpacingGridUnits),b.status,d.status,hex(d.searchedDistanceGridUnits),stop,calls,dc.blockTests,dc.skippedSamples,
                counts[0],counts[1],counts[2],counts[3],counts[4],counts[5],counts[6],oracle,altitudeBin,true,
                hex(b.searchedDistanceGridUnits),b.spatialSamples,bc.blockTests,bc.skippedSamples})rayRow.add(value.toString());
        rayOut.println(rayRow);
        conservationOut.printf(Locale.ROOT,"%s,%d,%d,%d,%d,%d,%d,%d,true,true,true,true%n",name,covered.cardinality(),point.spatialSamples,actual.cardinality(),omit.cardinality(),inside,dc.blockTests,trace.segments.size());
        rayOut.flush();segmentOut.flush();sampleOut.flush();conservationOut.flush();
    }
    private static void scene(String name,double[][] v,int r,int c,double a,double b,int cap,double step,VisibilityStatus status,int stop) {
        run(name,v,r,c,1,a,b,new SearchConfig(cap,step),status,stop);
    }
    private static void targeted() {
        scene("flat_corridor",flat(),40,40,5,90,12,1,VisibilityStatus.VISIBLE,12);
        var v=flat();v[35][44]=140;scene("off_path_peak",v,40,40,5,90,12,1,VisibilityStatus.VISIBLE,12);
        v=flat();v[31][44]=140;scene("off_path_halo_peak",v,40,40,5,90,12,1,VisibilityStatus.VISIBLE,12);
        v=flat();v[40][43]=140;scene("ridge_on_path",v,40,40,5,90,12,1,VisibilityStatus.SHADED,3);
        v=flat();v[40][48]=140;scene("interpolation_halo",v,40,46,5,90,8,1.5,VisibilityStatus.SHADED,1);
        v=flat();v[40][48]=140;scene("block_boundary",v,40,40,5,90,12,1,VisibilityStatus.SHADED,8);
        v=flat();v[40][41]=100+Math.tan(Math.toRadians(30));scene("tangent",v,40,40,30,90,12,1,VisibilityStatus.VISIBLE,12);
        scene("below_tangent",v,40,40,30-1e-6,90,12,1,VisibilityStatus.SHADED,1);
        scene("above_tangent",v,40,40,30+1e-6,90,12,1,VisibilityStatus.VISIBLE,12);
        v=flat();v[40][46]=100.3;scene("clear_but_entry_bound_loose",v,40,40,5,90,12,1,VisibilityStatus.VISIBLE,12);
        scene("pad_only_clear",flat(),40,40,1e-7,90,12,1,VisibilityStatus.VISIBLE,12);
        v=flat();v[40][42]=-9999;v[40][44]=200;scene("nodata_before_shade",v,40,40,5,90,12,1,VisibilityStatus.UNKNOWN_RAY_INVALID,2);
        v=flat();v[40][42]=200;v[40][44]=-9999;scene("shade_before_nodata",v,40,40,5,90,12,1,VisibilityStatus.SHADED,2);
        v=flat();v[35][44]=-9999;scene("off_path_nodata",v,40,40,5,90,12,1,VisibilityStatus.VISIBLE,12);
        v=flat();v[35][44]=-9999;v[40][43]=140;scene("unsafe_block_shaded",v,40,40,5,90,12,1,VisibilityStatus.SHADED,3);
        v=flat();v[40][40]=-9999;scene("invalid_receiver",v,40,40,5,90,12,1,VisibilityStatus.UNKNOWN_RECEIVER_INVALID,0);
        scene("out_of_bounds",flat(),2,40,5,0,12,1,VisibilityStatus.UNKNOWN_OUT_OF_BOUNDS,3);
        scene("partial_last_block_clear",flat(67,69,100),30,65,5,90,3,1,VisibilityStatus.VISIBLE,3);
        scene("partial_last_block_unknown",flat(67,69,100),30,65,5,90,4,1,VisibilityStatus.UNKNOWN_OUT_OF_BOUNDS,4);
        scene("same_block_revisit",flat(),40,40,5,0,12,1,VisibilityStatus.VISIBLE,12);
        v=flat();v[40][43]=140;scene("shade_at_final_sample",v,40,40,5,90,3,1,VisibilityStatus.SHADED,3);
        scene("negative_elevation",flat(96,96,-200),40,40,5,90,12,1,VisibilityStatus.VISIBLE,12);
        v=flat();v[23][21]=200;scene("near_integer_row",v,24,20,5,90-1e-8,1,1.25,VisibilityStatus.SHADED,1);
        v=flat();v[25][19]=200;scene("near_integer_col",v,24,20,5,180+1e-8,1,1.25,VisibilityStatus.SHADED,1);
        run("nonfinite_ray_safety",flat(96,96,1e308),40,40,1e308,60,90,new SearchConfig(1,1e308),VisibilityStatus.VISIBLE,1);
        for(double a:new double[]{1e-7,1,5,15,30,60,89})for(double b:new double[]{0,45,90,135,180,270,359.9999999999})
            scene("angle_"+a+"_"+b,flat(),40,40,a,b,12,1,VisibilityStatus.VISIBLE,12);
        Random random=new Random(20261111);
        for(int i=0;i<128;i++) {
            v=flat();for(int k=0;k<24;k++)v[random.nextInt(96)][random.nextInt(96)]=random.nextInt(8)==0?-9999:100+random.nextDouble()*300;
            scene("seeded_"+i,v,16+random.nextInt(64),16+random.nextInt(64),0.01+random.nextDouble()*85,
                    random.nextDouble()*1080-360,1+random.nextInt(64),new double[]{1,1.25,1.5,.5}[i%4],null,0);
        }
    }
    private static PrintWriter writer(Path p) throws IOException {
        return new PrintWriter(Files.newBufferedWriter(p,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW));
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=2 || !args[0].equals("--synthetic-only"))throw new IllegalArgumentException("explicit --synthetic-only NEW-output required; no real DEM or performance mode");
        Path out=Path.of(args[1]).toAbsolutePath().normalize();
        Path allowed=Path.of("user_runs").toAbsolutePath().normalize();
        if(!out.startsWith(allowed) || out.equals(allowed))throw new IllegalArgumentException("user_runs child required");
        Files.createDirectory(out);
        try {
            Path legacy=out.resolve("legacy_synthetic");Files.createDirectory(legacy);
            // Reuse only the existing artificial entry, NEVER verify(), main() or benchmark().
            Block16DirectionIntegrationChecks.synthetic(legacy);
            try(var rw=writer(out.resolve("rays.csv"));var sw=writer(out.resolve("segments.csv"));
                    var ow=writer(out.resolve("samples.csv"));var cw=writer(out.resolve("conservation.csv"))) {
                rayOut=rw;segmentOut=sw;sampleOut=ow;conservationOut=cw;
                rw.println("case,row,col,cell_hex,altitude_hex,bearing_hex,cap,spacing_hex,b_status,diag_status,stop_hex,stop_index,interpolations,block_tests,skipped_samples,skip_segments,bound_clear,bound_shaded,bound_unknown,safety_clear,safety_shaded,safety_unknown,posthoc_excludable_segments,altitude_bin,b_match,b_stop_hex,b_interpolations,b_block_tests,b_skipped_samples");
                sw.println("case,segment,start,end,block_row,block_col,decision,safety_reason,outcome,actual_calls,actual_indices,maximum_hex,entry_ray_hex,pad_hex,complete,corridor_max_hex,posthoc_excludable");
                ow.println("case,segment,k,row_hex,col_hex,height_hex,sample_status");
                cw.println("case,enumerated_covered,point_calls,enumerated_actual,enumerated_skipped,in_grid_segments,block_tests,segments,partition_ok,coverage_ok,counts_ok,b_match");
                targeted();
                check(!rw.checkError() && !sw.checkError() && !ow.checkError() && !cw.checkError(),"record write error");
            }
            check(statuses.size()==5,"all five statuses covered");
            long classified=0;
            try(var w=writer(out.resolve("altitude_counts.csv"))) {
                w.println("bin,decision,outcome,segments,rays_in_bin");
                for(int b=0;b<6;b++)for(var d:Block16UpperBoundDiagnostic.Decision.values())for(var o:Block16UpperBoundDiagnostic.Outcome.values()) {
                    long count=bins[b][d.ordinal()][o.ordinal()];classified+=count;
                    w.println(b+","+d+","+o+","+count+","+binRays[b]);
                }
            }
            check(classified==cases.values().stream().mapToLong(c->Arrays.stream(c).sum()).sum(),"all height bins conserve segment totals");
            long[] totals=new long[7];for(long[] c:cases.values())for(int i=0;i<7;i++)totals[i]+=c[i];
            Files.writeString(out.resolve("summary.json"),String.format(Locale.ROOT,
                    "{\"scope\":\"STAGE_A_ARTIFICIAL_ONLY\",\"diagnostic_rays\":%d,\"legacy_artificial_rays_per_variant\":%d,\"assertions\":%d,\"statuses\":5,\"interpolations\":%d,\"skipped_samples\":%d,\"block_tests\":%d,\"skip_segments\":%d,\"bound_clear\":%d,\"bound_shaded\":%d,\"bound_unknown\":%d,\"safety_clear\":%d,\"safety_shaded\":%d,\"safety_unknown\":%d,\"posthoc_excludable_segments\":%d,\"shade_before_cap_rays\":%d,\"b_mismatches\":0,\"real_dem_queries\":0,\"performance_measured\":false,\"stage_b_started\":false}\n",
                    rays,Block16DirectionIntegrationChecks.syntheticRays,assertions,samples,skipped,blockTests,
                    totals[0],totals[1],totals[2],totals[3],totals[4],totals[5],totals[6],posthoc,earlyShades),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            System.out.println("STAGE_A_PASS diagnostic_rays="+rays+" legacy_artificial_rays_per_variant="+Block16DirectionIntegrationChecks.syntheticRays+" assertions="+assertions+" b_mismatches=0 real_dem_queries=0 no_performance");
        } catch(Exception|AssertionError e) {
            Files.writeString(out.resolve("failure.txt"),"case="+active+"\n"+e,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            if(activeGrid!=null)try(var w=writer(out.resolve("failure_grid.csv"))) {
                for(double[] row:activeGrid){for(int c=0;c<row.length;c++){if(c>0)w.print(',');w.print(hex(row[c]));}w.println();}
            }
            throw e;
        }
    }
}
