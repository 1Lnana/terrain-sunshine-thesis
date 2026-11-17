import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Untimed four-way checks, and a separate pre-integration adapter cost control. */
public final class Block16DirectionIntegrationChecks {
    @FunctionalInterface public interface CalculationControl {
        ProjectedBaselineExperiment.Pixel calculate(ProjectedBaselineExperiment.Inputs in,
                ProjectedBaselineExperiment.Receiver r,RunStatistics stats,
                BlockUpperBoundVisibility index,BlockUpperBoundVisibility.Counters counts);
    }
    enum Method { A_STATS(true), A_NOSTATS(false), B_STATS(true), B_NOSTATS(false);
        final boolean stats;Method(boolean stats){this.stats=stats;}
    }
    @FunctionalInterface interface RayQuery {
        VisibilityResult at(int r,int c,double a,double b,SearchConfig search,BlockUpperBoundVisibility.Counters counts);
    }
    static final String EXPECTED="bd611e2e8057cf68500de1dfcd6c3b907d1be539b22fc5c5a237f47e14a01fd5";
    static void check(boolean ok,String message) {Block16EngineeringBaselineExperiment.require(ok,message);}
    static boolean bits(double a,double b) {return Block16EngineeringBaselineExperiment.bits(a,b);}
    static RayQuery prepare(AsciiGrid grid,Method m) {
        var p=Block16EngineeringBaselineExperiment.prepare(grid,Block16EngineeringBaselineExperiment.Method.valueOf(m.name()));
        return (r,c,a,b,s,n)->p.query.visibleAt(TimeStepBaseline.contract(),r,c,a,b,AngleReference.GRID_NORTH_CLOCKWISE,s,n);
    }
    static void equal(VisibilityResult a,VisibilityResult b,BlockUpperBoundVisibility.Counters ac,
            BlockUpperBoundVisibility.Counters bc,String label) {
        check(a.status==b.status && bits(a.searchedDistanceGridUnits,b.searchedDistanceGridUnits)
                && a.spatialSamples==b.spatialSamples,label+" result mismatch");
        if(bc!=null)check(ac.blockTests==bc.blockTests && ac.skippedSamples==bc.skippedSamples
                && ac.spatialSamples==bc.spatialSamples,label+" count mismatch");
    }
    static boolean samePixel(ProjectedBaselineExperiment.Pixel a,ProjectedBaselineExperiment.Pixel b) {
        return bits(a.day,b.day) && bits(a.psd,b.psd) && a.samples==b.samples && Objects.equals(a.reason,b.reason)
                && a.quarterProbes==b.quarterProbes && a.refinementQueries==b.refinementQueries
                && a.guardQueries==b.guardQueries && a.shortIntervals==b.shortIntervals && a.longIntervals==b.longIntervals
                && Objects.equals(a.trace,b.trace) && Objects.equals(a.nodes,b.nodes) && Objects.equals(a.schedule,b.schedule)
                && Objects.equals(a.intervals,b.intervals);
    }
    static CalculationControl legacy() throws Exception {
        return (CalculationControl)Class.forName("Block16PreIntegrationControl").getConstructor().newInstance();
    }
    static double[][] flat() { double[][] v=new double[96][96];for(double[] row:v)Arrays.fill(row,100);return v; }
    static long syntheticRays=0;static final Set<VisibilityStatus> statuses=EnumSet.noneOf(VisibilityStatus.class);
    static void scene(BufferedWriter w,String name,double[][] v,int r,int c,double altitude,double bearing,
            SearchConfig search,VisibilityStatus expected,int expectedIndex) throws IOException {
        var g=new AsciiGrid(v.length,v[0].length,0,0,1,-9999,v,ZeroPolicy.PRESERVE_AS_VALID);
        var ac=new BlockUpperBoundVisibility.Counters();var a=prepare(g,Method.A_STATS).at(r,c,altitude,bearing,search,ac);
        if(expected!=null)check(a.status==expected && bits(a.searchedDistanceGridUnits,expectedIndex*search.sampleSpacingGridUnits),name+" expected result");
        var point=ControlledVisibility.visibleAt(g,TimeStepBaseline.contract(),r,c,altitude,bearing,
                AngleReference.GRID_NORTH_CLOCKWISE,search,null);
        check(a.status==point.status && bits(a.searchedDistanceGridUnits,point.searchedDistanceGridUnits),name+" point comparison");
        check(ac.skippedSamples+ac.spatialSamples==point.spatialSamples,name+" conservation");
        for(Method m:Method.values()) {
            var bc=new BlockUpperBoundVisibility.Counters();var b=prepare(g,m).at(r,c,altitude,bearing,search,m.stats?bc:null);
            equal(a,b,ac,m.stats?bc:null,name+" "+m);
        }
        syntheticRays++;statuses.add(a.status);
        w.write(name+","+a.status+","+a.searchedDistanceGridUnits+","+ac.blockTests+","+ac.skippedSamples+","+ac.spatialSamples+"\n");
    }
    static void synthetic(Path out) throws IOException {
        try(var w=Files.newBufferedWriter(out.resolve("synthetic.csv"),StandardOpenOption.CREATE_NEW)) {
            w.write("case,status,stop_distance,block_tests,skipped_samples,interpolations\n");
            var search=new SearchConfig(12,1);
            for(double bearing:new double[]{0,90,180,270,45,135,225,315,-90,450})
                scene(w,"axis_diagonal_"+bearing,flat(),40,40,5,bearing,search,VisibilityStatus.VISIBLE,12);
            double[][] v=flat();v[40][43]=140;
            scene(w,"first_shade",v,40,40,5,90,search,VisibilityStatus.SHADED,3);
            v=flat();v[40][48]=140;
            scene(w,"block_halo",v,40,46,5,90,new SearchConfig(8,1.5),VisibilityStatus.SHADED,1);
            v=flat();v[40][48]=140;
            scene(w,"exact_block_boundary",v,40,40,5,90,search,VisibilityStatus.SHADED,8);
            v=flat();v[40][42]=-9999;v[40][44]=200;
            scene(w,"nodata_before_shade",v,40,40,5,90,search,VisibilityStatus.UNKNOWN_RAY_INVALID,2);
            v=flat();v[40][42]=200;v[40][44]=-9999;
            scene(w,"shade_before_nodata",v,40,40,5,90,search,VisibilityStatus.SHADED,2);
            v=flat();v[40][40]=-9999;
            scene(w,"invalid_receiver",v,40,40,5,90,search,VisibilityStatus.UNKNOWN_RECEIVER_INVALID,0);
            scene(w,"out_of_bounds",flat(),2,40,5,0,search,VisibilityStatus.UNKNOWN_OUT_OF_BOUNDS,3);
            v=flat();v[40][41]=100+Math.tan(Math.toRadians(30));
            scene(w,"tangent",v,40,40,30,90,search,VisibilityStatus.VISIBLE,12);
            scene(w,"below_tangent",v,40,40,30-1e-6,90,search,VisibilityStatus.SHADED,1);
            v=flat();v[23][21]=200;
            scene(w,"near_integer_row",v,24,20,5,90-1e-8,new SearchConfig(1,1.25),VisibilityStatus.SHADED,1);
            v=flat();v[25][19]=200;
            scene(w,"near_integer_col",v,24,20,5,180+1e-8,new SearchConfig(1,1.25),VisibilityStatus.SHADED,1);
            Random random=new Random(20261103);
            for(int i=0;i<800;i++) {
                v=flat();for(int k=0;k<24;k++)v[random.nextInt(96)][random.nextInt(96)]=random.nextInt(8)==0?-9999:100+random.nextDouble()*300;
                scene(w,"seeded_"+i,v,16+random.nextInt(64),16+random.nextInt(64),0.01+random.nextDouble()*85,
                        random.nextDouble()*1080-360,new SearchConfig(1+random.nextInt(80),new double[]{1,1.25,1.5,0.5}[i%4]),null,0);
            }
        }
        check(statuses.size()==5,"missing status coverage");
        System.out.println("SYNTHETIC_PASS rays_per_method="+syntheticRays+" statuses="+statuses);
    }

    static String verify(ProjectedBaselineExperiment.Inputs in,Path out) throws Exception {
        check(in.receivers.size()==16384 && in.grid.rows==356 && in.grid.cols==356 && in.grid.cellSize==90
                && in.integer("row_start")==114 && in.integer("col_start")==114
                && in.integer("rows")==128 && in.integer("cols")==128 && in.search.sampleSpacingGridUnits==50
                && in.search.maxSamples==200 && in.p.getProperty("date").equals("2023-12-21"),"Qinling128 frozen regression scope");
        synthetic(out);invalidAngles(in,out);
        CalculationControl old=legacy();var oldIndex=new BlockUpperBoundVisibility(in.grid,16);
        Path frozen=in.resolve(in.p.getProperty("verified_run")).resolve("results");
        Set<String> originalTraces=new HashSet<>(in.traces);
        for(var r:in.receivers)in.traces.add(r.key()); // Complete maps only during untimed regression.
        try(var summary=Files.newBufferedWriter(out.resolve("verification.csv"),StandardOpenOption.CREATE_NEW)) {
            summary.write("method,pixels,rays,block_tests,skipped_samples,interpolations,state_stop_index_query_node_psd_mismatches,legacy_full_pixel_mismatches,psd_sha256\n");
            for(Method method:Method.values()) {
                var prepared=Block16EngineeringBaselineExperiment.prepare(in.grid,Block16EngineeringBaselineExperiment.Method.valueOf(method.name()));
                var counts=new BlockUpperBoundVisibility.Counters();var baseline=new BlockUpperBoundVisibility(in.grid,16);
                var trial=new Block16EngineeringBaselineExperiment.Trial();long[] totals=new long[4];
                try(var rays=new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(frozen.resolve("rays.csv.gz")))));
                        var pixels=Files.newBufferedReader(frozen.resolve("pixels.csv"))) {
                    var h=Arrays.asList(rays.readLine().split(","));int samples=h.indexOf("b_samples"),node=h.indexOf("b_node");
                    check(samples>=0 && node>=0 && h.get(8).equals("b_status") && h.get(11).equals("b_stop_hex"),"frozen schema");
                    var ph=Arrays.asList(pixels.readLine().split(","));int queryCount=ph.indexOf("query_count"),nodeCount=ph.indexOf("integration_node_count");
                    check(ph.get(5).equals("b_psd_hex") && queryCount>=0 && nodeCount>=0,"pixel schema");
                    for(int i=0;i<in.receivers.size();i++) {
                        final int index=i;var receiver=in.receivers.get(i);String[] p=pixels.readLine().split(",");
                        check(Integer.parseInt(p[0])==i && Integer.parseInt(p[1])==receiver.row && Integer.parseInt(p[2])==receiver.col,"receiver identity");
                        int[] qi={0};var expectedTrace=new TreeMap<Double,Integer>();var expectedNodes=new TreeMap<Double,Integer>();
                        ProjectedBaselineExperiment.SpatialQuery observed=(contract,row,col,a,b,reference,search,n)->{
                            String raw;try {raw=rays.readLine();}catch(IOException e){throw new UncheckedIOException(e);}
                            check(raw!=null,"short frozen rays");String[] v=raw.split(",");
                            String label=method+" pixel="+index+" query="+qi[0];
                            check(Integer.parseInt(v[0])==index && Integer.parseInt(v[1])==row && Integer.parseInt(v[2])==col && Integer.parseInt(v[3])==qi[0],label+" order");
                            check(bits(a,Double.parseDouble(v[5])) && bits(b,Double.parseDouble(v[6])),label+" solar/query order");
                            long bt=counts.blockTests,sk=counts.skippedSamples,sp=counts.spatialSamples;
                            var actual=prepared.query.visibleAt(contract,row,col,a,b,reference,search,n);
                            check(actual.status.toString().equals(v[8]) && bits(actual.searchedDistanceGridUnits,Double.parseDouble(v[11]))
                                    && actual.spatialSamples==Long.parseLong(v[samples]),label+" frozen ray");
                            long stop=Long.parseLong(v[14]);check(bits(actual.searchedDistanceGridUnits,stop*search.sampleSpacingGridUnits),label+" stop index");
                            var bc=new BlockUpperBoundVisibility.Counters();var base=baseline.visibleAt(contract,row,col,a,b,reference,search,bc);
                            BlockUpperBoundVisibility.Counters delta=null;
                            if(method.stats) {
                                delta=new BlockUpperBoundVisibility.Counters();delta.blockTests=counts.blockTests-bt;
                                delta.skippedSamples=counts.skippedSamples-sk;delta.spatialSamples=counts.spatialSamples-sp;
                                check(delta.skippedSamples+delta.spatialSamples==stop,label+" conservation");
                                totals[1]+=delta.blockTests;totals[2]+=delta.skippedSamples;
                            }
                            equal(base,actual,bc,delta,label);
                            double time=Double.parseDouble(v[4]);int state=actual.status==VisibilityStatus.VISIBLE?1:actual.status==VisibilityStatus.SHADED?0:-1;
                            expectedTrace.put(time,state);if(v[node].equals("1"))expectedNodes.put(time,state);
                            qi[0]++;totals[0]++;totals[3]+=actual.spatialSamples;return actual;
                        };
                        var actual=ProjectedBaselineExperiment.calculateWithSpatialQuery(in,receiver,"binarylocal_30min",null,observed,method.stats?counts:null,method.stats);
                        check(actual.trace.equals(expectedTrace) && actual.nodes.equals(expectedNodes),"actual time query/integration maps");
                        check(actual.samples==Integer.parseInt(p[queryCount]) && qi[0]==actual.samples && actual.nodes.size()==Integer.parseInt(p[nodeCount]),"query/integration counts");
                        check(bits(actual.psd,Double.parseDouble(p[5])) && actual.reason.equals(p[15])
                                && actual.refinementQueries==Integer.parseInt(p[16]) && actual.guardQueries==Integer.parseInt(p[17]),"PSD and temporal fields");
                        if(method==Method.A_STATS) {
                            var oldCounts=new BlockUpperBoundVisibility.Counters();var original=old.calculate(in,receiver,null,oldIndex,oldCounts);
                            var wrapperCounts=new BlockUpperBoundVisibility.Counters();
                            var wrapper=ProjectedBaselineExperiment.calculate(in,receiver,"binarylocal_30min",null,oldIndex,wrapperCounts);
                            check(samePixel(original,actual) && samePixel(wrapper,actual),"frozen calculate/old signature full Pixel regression");
                            check(oldCounts.blockTests==wrapperCounts.blockTests && oldCounts.skippedSamples==wrapperCounts.skippedSamples
                                    && oldCounts.spatialSamples==wrapperCounts.spatialSamples,"old signature counter regression");
                        }
                        trial.pixels.add(actual);
                    }
                    check(rays.readLine()==null && pixels.readLine()==null,"unconsumed frozen rows");
                }
                check(totals[0]==862525 && totals[3]==17275977,"ray/interpolation total");
                if(method.stats)check(totals[1]==10665319 && totals[2]==93217471,"detailed count totals");
                String hash=Block16EngineeringBaselineExperiment.digest(in,trial);check(EXPECTED.equals(hash),"frozen PSD digest");
                summary.write(method+",16384,"+totals[0]+","+(method.stats?totals[1]:"null")+","+(method.stats?totals[2]:"null")+","+totals[3]+",0,0,"+hash+"\n");summary.flush();
                // Return object interpolation count is available in both modes; not a fabricated detailed counter.
                Block16EngineeringBaselineExperiment.writePixels(in,trial,out.resolve("verify_"+method+"_pixels.csv"));
                System.out.println("VERIFY_PASS method="+method+" pixels=16384 rays="+totals[0]+" mismatches=0");
            }
        } finally {in.traces.clear();in.traces.addAll(originalTraces);}
        return EXPECTED;
    }
    static void invalidAngles(ProjectedBaselineExperiment.Inputs in,Path out) throws IOException {
        int tests=0;
        for(Method m:Method.values()) {
            RayQuery query=prepare(in.grid,m);
            for(double[] a:new double[][]{{0,90},{90,90},{Double.NaN,90},{5,Double.POSITIVE_INFINITY}}) {
                boolean threw=false;
                try {query.at(150,150,a[0],a[1],in.search,m.stats?new BlockUpperBoundVisibility.Counters():null);}
                catch(IllegalArgumentException e){threw=true;}
                check(threw,"invalid angle acceptance "+m);tests++;
            }
        }
        Files.writeString(out.resolve("invalid_angles.txt"),"matched IllegalArgumentException checks="+tests+"\n",StandardOpenOption.CREATE_NEW);
    }
    static Block16EngineeringBaselineExperiment.Trial adapterTrial(ProjectedBaselineExperiment.Inputs in,CalculationControl query) {
        var trial=new Block16EngineeringBaselineExperiment.Trial();
        long start=System.nanoTime();var index=new BlockUpperBoundVisibility(in.grid,16);long ready=System.nanoTime();
        for(var r:in.receivers) {
            var pixel=query.calculate(in,r,trial.rays,index,trial.counts);trial.pixels.add(pixel);trial.calls+=pixel.samples;
        }
        long finish=System.nanoTime();trial.build=ready-start;trial.query=finish-ready;trial.compute=finish-start;return trial;
    }
    static void adapterBenchmark(ProjectedBaselineExperiment.Inputs in,Path out,String expected) throws Exception {
        CalculationControl old=legacy(),current=(data,r,stats,index,counts)->ProjectedBaselineExperiment.calculate(data,r,"binarylocal_30min",stats,index,counts);
        try(var w=Files.newBufferedWriter(out.resolve("adapter_runs.csv"),StandardOpenOption.CREATE_NEW)) {
            w.write("phase,round,position,method,build_sec,query_sec,compute_sec,psd_sha256\n");
            for(int pass=0;pass<15;pass++) {
                boolean formal=pass>=5;int round=formal?pass-4:pass+1;
                String[] order=round%2==1?new String[]{"PRE_INTERFACE_A","INTEGRATED_A"}:new String[]{"INTEGRATED_A","PRE_INTERFACE_A"};
                for(int pos=0;pos<2;pos++) {
                    String method=order[pos];var trial=adapterTrial(in,method.equals("PRE_INTERFACE_A")?old:current);
                    String hash=Block16EngineeringBaselineExperiment.digest(in,trial);check(expected.equals(hash),"adapter digest");
                    check(trial.calls==862525 && trial.counts.blockTests==10665319 && trial.counts.skippedSamples==93217471
                            && trial.counts.spatialSamples==17275977 && trial.rays.rayCalls==trial.calls,"adapter counts");
                    w.write((formal?"formal":"warmup")+","+round+","+(pos+1)+","+method+","+trial.build/1e9+","+trial.query/1e9+","+trial.compute/1e9+","+hash+"\n");w.flush();
                    System.out.println("ADAPTER phase="+(formal?"formal":"warmup")+" round="+round+" method="+method+" compute_sec="+trial.compute/1e9);
                }
            }
        }
    }
}
