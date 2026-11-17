import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Independent T2 real-DEM correctness entry. Only --verify is implemented.
 * No benchmark, diagnostic algorithm copy, or multi-date execution is available here.
 */
public final class StripT2Experiment {
    private enum Method { POINT, BLOCK16, STRIP16 }
    private static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final Map<String,Object> details;
        Failure(String message, Map<String,Object> details) { super(message); this.details=details; }
    }
    private static void require(boolean ok, String message, Object... details) {
        if (!ok) {
            Map<String,Object> data=new LinkedHashMap<>(); data.put("message",message);
            for (int i=0;i<details.length;i+=2) data.put(details[i].toString(),details[i+1]);
            throw new Failure(message,data);
        }
    }
    private static String hex(double x) { return Double.toHexString(x); }
    private static boolean bits(double a,double b) {
        return Double.doubleToLongBits(a)==Double.doubleToLongBits(b);
    }
    private static int direction(double bearing) {
        double n=bearing%360; if(n<0)n+=360;
        return ((int)(n/22.5))%16;
    }
    private static long[] snapshot(StripUpperBoundVisibility.Counters c) {
        return new long[]{c.blockTests,c.legacyBlockFailures,c.stripAttempts,c.stripValueReads,
                c.rescuedSegments,c.rescuedSamples,c.numericFallbackSegments,c.skippedSamples,c.spatialSamples};
    }
    private static final class Ray {
        double time, altitude, bearing;
        VisibilityResult result;
        long skipped, blocks;
        long[] strip=new long[9];
    }
    private static final class Calculation {
        ProjectedBaselineExperiment.Pixel pixel;
        final List<Ray> rays=new ArrayList<>();
    }
    private static final class Context {
        final BlockUpperBoundVisibility block;
        final StripUpperBoundVisibility strip;
        final BlockUpperBoundVisibility.Counters blockCounts=new BlockUpperBoundVisibility.Counters();
        final BlockUpperBoundVisibility.Counters legacyCounts=new BlockUpperBoundVisibility.Counters();
        final StripUpperBoundVisibility.Counters stripCounts=new StripUpperBoundVisibility.Counters();
        final long[][] directionExtras=new long[16][3]; // interpolation, all skipped, numeric fallbacks
        Context(AsciiGrid grid) {
            block=new BlockUpperBoundVisibility(grid,16);
            strip=new StripUpperBoundVisibility(grid);
        }
    }
    private static int stopIndex(VisibilityResult result, SearchConfig search) {
        double index=result.searchedDistanceGridUnits/search.sampleSpacingGridUnits;
        int j=(int)Math.rint(index);
        require(j>=0 && j<=search.maxSamples
                && bits(j*search.sampleSpacingGridUnits,result.searchedDistanceGridUnits),
                "stop is not an original sample", "distance",hex(result.searchedDistanceGridUnits));
        return j;
    }
    private static Map<String,Object> rayDetails(Ray r, SearchConfig search) {
        Map<String,Object> d=new LinkedHashMap<>();
        d.put("time_hex",hex(r.time)); d.put("altitude_hex",hex(r.altitude));
        d.put("bearing_hex",hex(r.bearing)); d.put("status",r.result.status.toString());
        d.put("stop_hex",hex(r.result.searchedDistanceGridUnits));
        d.put("stop_index",stopIndex(r.result,search));
        d.put("interpolated",r.result.spatialSamples); d.put("skipped",r.skipped);
        return d;
    }
    private static Calculation calculate(ProjectedBaselineExperiment.Inputs in,
            ProjectedBaselineExperiment.Receiver receiver, Method method, Context context,
            Calculation expected) {
        Calculation calculation=new Calculation();
        ProjectedBaselineExperiment.Pixel pixel=new ProjectedBaselineExperiment.Pixel();
        pixel.day=TimeStepBaseline.daylight(receiver.lat,in.dec);
        double rise=720-pixel.day/2;
        AdaptiveTimeV1.Oracle oracle=t->{
            Ray r=new Ray(); r.time=t;
            double[] angles=TimeStepBaseline.sun(receiver.lat,in.dec,rise+t);
            r.altitude=Math.max(1e-7,angles[0]); r.bearing=angles[1]+receiver.north;
            if (method==Method.POINT) {
                r.result=ControlledVisibility.visibleAt(in.grid,in.contract,receiver.row,receiver.col,
                        r.altitude,r.bearing,AngleReference.GRID_NORTH_CLOCKWISE,in.search,null);
            } else if (method==Method.BLOCK16) {
                long skipped=context.blockCounts.skippedSamples, blocks=context.blockCounts.blockTests;
                long interpolated=context.blockCounts.spatialSamples;
                r.result=context.block.visibleAt(in.contract,receiver.row,receiver.col,r.altitude,r.bearing,
                        AngleReference.GRID_NORTH_CLOCKWISE,in.search,context.blockCounts);
                r.skipped=context.blockCounts.skippedSamples-skipped;
                r.blocks=context.blockCounts.blockTests-blocks;
                require(context.blockCounts.spatialSamples-interpolated==r.result.spatialSamples,
                        "block interpolation counter", "receiver",receiver.key(),"time",hex(t));
            } else {
                long[] before=snapshot(context.stripCounts);
                r.result=context.strip.visibleAt(in.contract,receiver.row,receiver.col,r.altitude,r.bearing,
                        AngleReference.GRID_NORTH_CLOCKWISE,in.search,context.stripCounts);
                long[] after=snapshot(context.stripCounts);
                for(int i=0;i<after.length;i++) r.strip[i]=after[i]-before[i];
                r.blocks=r.strip[0]; r.skipped=r.strip[7];
                require(r.strip[8]==r.result.spatialSamples,"strip interpolation counter",
                        "receiver",receiver.key(),"time",hex(t));
                long[] extras=context.directionExtras[direction(r.bearing)];
                extras[0]+=r.result.spatialSamples; extras[1]+=r.skipped; extras[2]+=r.strip[6];
            }
            stopIndex(r.result,in.search);
            if(expected!=null) {
                int i=calculation.rays.size();
                require(i<expected.rays.size(),"extra time query","method",method,"receiver",receiver.key(),"time",hex(t));
                Ray a=expected.rays.get(i);
                require(bits(a.time,r.time) && bits(a.altitude,r.altitude) && bits(a.bearing,r.bearing)
                        && a.result.status==r.result.status
                        && bits(a.result.searchedDistanceGridUnits,r.result.searchedDistanceGridUnits)
                        && a.result.spatialSamples==r.result.spatialSamples+r.skipped,
                        "ray mismatch", "method",method,"receiver",receiver.key(),"query_index",i,
                        "point",rayDetails(a,in.search),"candidate",rayDetails(r,in.search));
            }
            calculation.rays.add(r);
            return r.result.status==VisibilityStatus.VISIBLE ? 1 : r.result.status==VisibilityStatus.SHADED ? 0 : -1;
        };
        AdaptiveTimeV2Local.Result result=BinaryLocalRefinement.adaptiveWithDarkGuard(pixel.day,30,oracle);
        pixel.psd=result.psd; pixel.samples=result.evaluated.size(); pixel.reason=result.fallbackReason;
        pixel.quarterProbes=result.quarterProbes; pixel.refinementQueries=result.refinementQueries;
        pixel.guardQueries=result.guardQueries; pixel.trace=result.evaluated; pixel.nodes=result.integrationNodes;
        require(pixel.samples==calculation.rays.size(),"time query accounting","receiver",receiver.key());
        if(expected!=null) require(calculation.rays.size()==expected.rays.size(),"missing time query",
                "method",method,"receiver",receiver.key());
        calculation.pixel=pixel;
        return calculation;
    }
    private static void samePixel(ProjectedBaselineExperiment.Pixel a, ProjectedBaselineExperiment.Pixel b,
            String label, String receiver) {
        require(bits(a.day,b.day) && bits(a.psd,b.psd) && a.samples==b.samples
                && Objects.equals(a.reason,b.reason) && a.quarterProbes==b.quarterProbes
                && a.refinementQueries==b.refinementQueries && a.guardQueries==b.guardQueries
                && a.shortIntervals==b.shortIntervals && a.longIntervals==b.longIntervals
                && Objects.equals(a.trace,b.trace) && Objects.equals(a.nodes,b.nodes),
                "pixel regression mismatch","label",label,"receiver",receiver,
                "expected_psd",hex(a.psd),"actual_psd",hex(b.psd));
    }
    private static void csv(BufferedWriter out,Object... fields) throws IOException {
        for(int i=0;i<fields.length;i++) {
            if(i>0)out.write(',');
            String s=String.valueOf(fields[i]);
            if(s.indexOf(',')>=0 || s.indexOf('"')>=0 || s.indexOf('\n')>=0)
                out.write('"'+s.replace("\"","\"\"")+'"');
            else out.write(s);
        }
        out.newLine();
    }
    private static BufferedWriter gzip(Path file) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(
                Files.newOutputStream(file,StandardOpenOption.CREATE_NEW)),StandardCharsets.UTF_8));
    }
    private static String json(Object value) {
        if(value==null)return "null";
        if(value instanceof Number || value instanceof Boolean)return value.toString();
        if(value instanceof Map<?,?> map) {
            StringJoiner j=new StringJoiner(",","{","}");
            for(var e:map.entrySet())j.add(json(e.getKey().toString())+":"+json(e.getValue()));
            return j.toString();
        }
        return "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"")
                .replace("\r","\\r").replace("\n","\\n").replace("\t","\\t")+"\"";
    }
    private static void writeJson(Path path,Object object) throws IOException {
        Files.writeString(path,json(object)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
    }
    private static String hash(Path p) throws Exception {
        MessageDigest d=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(p)) {
            byte[] buf=new byte[65536]; int n; while((n=in.read(buf))!=-1)d.update(buf,0,n);
        }
        return HexFormat.of().formatHex(d.digest());
    }
    private static void verify(ProjectedBaselineExperiment.Inputs in,Path output) throws Exception {
        require(in.p.getProperty("date").equals("2023-12-21") && in.grid.rows==356 && in.grid.cols==356
                && in.grid.cellSize==90 && in.receivers.size()==16384 && in.integer("row_start")==114
                && in.integer("col_start")==114 && in.integer("rows")==128 && in.integer("cols")==128
                && in.search.sampleSpacingGridUnits==50 && in.search.maxSamples==200
                && in.methods.equals(List.of("binarylocal_30min"))
                && "verify_only".equals(in.p.getProperty("t2_mode")),"unexpected T2 verification contract");
        ProjectedBaselineExperiment.loadReference(in); // Validate existing reference provenance; never recompute it.
        Files.copy(in.config,output.resolve("config.properties"));
        Map<String,Object> inputs=new LinkedHashMap<>();
        for(String key:List.of("dem","receivers","metadata")) {
            Path p=in.resolve(in.p.getProperty(key));
            inputs.put(key,Map.of("path",p.toAbsolutePath().toString(),"sha256",hash(p)));
        }
        inputs.put("config",Map.of("path",in.config.toString(),"sha256",hash(in.config)));
        inputs.put("loaded_classes",StripT2Experiment.class.getProtectionDomain().getCodeSource().getLocation().toString());
        inputs.put("scope","real_dem_correctness_only_no_performance");
        writeJson(output.resolve("inputs.json"),inputs);
        Context context=new Context(in.grid);
        long rays=0,pointSamples=0,unknown=0,fallbackPixels=0,refinements=0,guards=0;
        try(BufferedWriter rayOut=gzip(output.resolve("rays.csv.gz"));
                BufferedWriter pixels=Files.newBufferedWriter(output.resolve("pixels.csv"),StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW)) {
            rayOut.write("receiver_index,row,col,query_index,time_hex,altitude_hex,bearing_hex,a_status,b_status,c_status,a_stop_hex,b_stop_hex,c_stop_hex,a_stop_index,b_stop_index,c_stop_index,a_samples,b_samples,c_samples,b_skipped,c_skipped,a_node,b_node,c_node,legacy_point_state,legacy_block_state,legacy_point_node,legacy_block_node,b_block_tests,c_block_tests,c_legacy_failures,c_attempts,c_reads,c_rescued_segments,c_rescued_samples,c_numeric_fallbacks\n");
            pixels.write("receiver_index,row,col,day_hex,a_psd_hex,b_psd_hex,c_psd_hex,legacy_point_psd_hex,legacy_block_psd_hex,query_count,integration_node_count,legacy_point_query_count,legacy_block_query_count,legacy_point_node_count,legacy_block_node_count,fallback_reason,refinement_queries,guard_queries\n");
            for(int ri=0;ri<in.receivers.size();ri++) {
                var receiver=in.receivers.get(ri);
                Calculation a=calculate(in,receiver,Method.POINT,context,null);
                Calculation b=calculate(in,receiver,Method.BLOCK16,context,a);
                Calculation c=calculate(in,receiver,Method.STRIP16,context,a);
                samePixel(a.pixel,b.pixel,"new point vs block",receiver.key());
                samePixel(a.pixel,c.pixel,"new point vs strip",receiver.key());
                boolean hadTrace=in.traces.contains(receiver.key());
                in.traces.add(receiver.key()); // Verification-only: expose all legacy evaluated/integration nodes.
                ProjectedBaselineExperiment.Pixel legacyPoint,legacyBlock;
                try {
                    legacyPoint=ProjectedBaselineExperiment.calculate(in,receiver,"binarylocal_30min",null);
                    legacyBlock=ProjectedBaselineExperiment.calculate(in,receiver,"binarylocal_30min",null,
                            context.block,context.legacyCounts);
                } finally { if(!hadTrace)in.traces.remove(receiver.key()); }
                samePixel(legacyPoint,a.pixel,"legacy calculate vs new point",receiver.key());
                samePixel(legacyBlock,b.pixel,"legacy calculate vs new block",receiver.key());
                for(int qi=0;qi<a.rays.size();qi++) {
                    Ray ar=a.rays.get(qi), br=b.rays.get(qi), cr=c.rays.get(qi);
                    csv(rayOut,ri,receiver.row,receiver.col,qi,hex(ar.time),hex(ar.altitude),hex(ar.bearing),
                            ar.result.status,br.result.status,cr.result.status,
                            hex(ar.result.searchedDistanceGridUnits),hex(br.result.searchedDistanceGridUnits),hex(cr.result.searchedDistanceGridUnits),
                            stopIndex(ar.result,in.search),stopIndex(br.result,in.search),stopIndex(cr.result,in.search),
                            ar.result.spatialSamples,br.result.spatialSamples,cr.result.spatialSamples,br.skipped,cr.skipped,
                            a.pixel.nodes.containsKey(ar.time)?1:0,b.pixel.nodes.containsKey(ar.time)?1:0,c.pixel.nodes.containsKey(ar.time)?1:0,
                            legacyPoint.trace.get(ar.time),legacyBlock.trace.get(ar.time),legacyPoint.nodes.containsKey(ar.time)?1:0,
                            legacyBlock.nodes.containsKey(ar.time)?1:0,br.blocks,cr.blocks,
                            cr.strip[1],cr.strip[2],cr.strip[3],cr.strip[4],cr.strip[5],cr.strip[6]);
                    pointSamples+=ar.result.spatialSamples;
                    if(ar.result.status!=VisibilityStatus.VISIBLE && ar.result.status!=VisibilityStatus.SHADED)unknown++;
                }
                csv(pixels,ri,receiver.row,receiver.col,hex(a.pixel.day),hex(a.pixel.psd),hex(b.pixel.psd),hex(c.pixel.psd),
                        hex(legacyPoint.psd),hex(legacyBlock.psd),a.pixel.samples,a.pixel.nodes.size(),legacyPoint.samples,
                        legacyBlock.samples,legacyPoint.nodes.size(),legacyBlock.nodes.size(),a.pixel.reason,
                        a.pixel.refinementQueries,a.pixel.guardQueries);
                rays+=a.rays.size(); refinements+=a.pixel.refinementQueries; guards+=a.pixel.guardQueries;
                if(!"NONE".equals(a.pixel.reason))fallbackPixels++;
                if((ri+1)%2048==0)System.out.printf("VERIFY_PROGRESS pixels=%d rays=%d%n",ri+1,rays);
            }
        }
        require(context.blockCounts.blockTests==context.legacyCounts.blockTests
                && context.blockCounts.skippedSamples==context.legacyCounts.skippedSamples
                && context.blockCounts.spatialSamples==context.legacyCounts.spatialSamples,"legacy block aggregate mismatch");
        try(BufferedWriter d=Files.newBufferedWriter(output.resolve("directions.csv"),StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW)) {
            d.write("bearing_from_inclusive,bearing_to_exclusive,rays,block_tests,legacy_failures,strip_attempts,strip_reads,rescued_segments,rescued_samples,interpolated_samples,skipped_samples,numeric_fallbacks\n");
            for(int i=0;i<16;i++) {
                long[] n=context.stripCounts.byDirection[i], e=context.directionExtras[i];
                csv(d,i*22.5,(i+1)*22.5,n[0],n[1],n[2],n[3],n[4],n[5],n[6],e[0],e[1],e[2]);
            }
        }
        Map<String,Object> summary=new LinkedHashMap<>();
        summary.put("scope","real_dem_correctness_only_no_performance"); summary.put("date","2023-12-21");
        summary.put("pixels",in.receivers.size()); summary.put("rays_per_method",rays);
        summary.put("point_samples",pointSamples); summary.put("block_samples",context.blockCounts.spatialSamples);
        summary.put("strip_samples",context.stripCounts.spatialSamples);
        summary.put("block_skipped",context.blockCounts.skippedSamples); summary.put("strip_skipped",context.stripCounts.skippedSamples);
        summary.put("block_tests",context.blockCounts.blockTests); summary.put("strip_block_tests",context.stripCounts.blockTests);
        summary.put("legacy_failures",context.stripCounts.legacyBlockFailures); summary.put("strip_attempts",context.stripCounts.stripAttempts);
        summary.put("strip_reads",context.stripCounts.stripValueReads); summary.put("rescued_segments",context.stripCounts.rescuedSegments);
        summary.put("rescued_samples",context.stripCounts.rescuedSamples); summary.put("numeric_fallbacks",context.stripCounts.numericFallbackSegments);
        summary.put("unknown_queries",unknown); summary.put("fallback_pixels",fallbackPixels);
        summary.put("refinement_queries",refinements); summary.put("guard_queries",guards);
        summary.put("block_payload_bytes",context.block.payloadBytes()); summary.put("strip_payload_bytes",context.strip.payloadBytes());
        summary.put("legacy_calculate_regression",true); summary.put("correctness",true);
        summary.put("performance_measured",false); summary.put("novelty_established",false);
        writeJson(output.resolve("verification.json"),summary);
        System.out.println("T2_VERIFY_PASS pixels="+in.receivers.size()+" rays_per_method="+rays
                +" rescued_segments="+context.stripCounts.rescuedSegments+" rescued_samples="+context.stripCounts.rescuedSamples);
        System.out.println("STOP_BEFORE_PERFORMANCE awaiting_user_review=true");
    }
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if(args.length!=3 || !"--verify".equals(args[0]))
            throw new IllegalArgumentException("Only --verify config.properties NEW-results-directory is authorized/implemented");
        Path output=Path.of(args[2]).toAbsolutePath().normalize();
        Path allowed=Path.of("user_runs").toAbsolutePath().normalize();
        if(!output.startsWith(allowed) || output.equals(allowed))throw new IllegalArgumentException("new user_runs subdirectory required");
        Files.createDirectory(output);
        try { verify(new ProjectedBaselineExperiment.Inputs(Path.of(args[1])),output); }
        catch(Exception | AssertionError e) {
            Map<String,Object> failure=new LinkedHashMap<>();
            failure.put("correctness",false); failure.put("performance_measured",false);
            failure.put("error_class",e.getClass().getName()); failure.put("error",e.toString());
            if(e instanceof Failure f)failure.put("details",f.details);
            writeJson(output.resolve("first_mismatch.json"),failure);
            throw e;
        }
    }
}
