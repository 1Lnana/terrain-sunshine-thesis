import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Synthetic three-way correctness checks only: no DEM input, PSD, warmup or benchmark.
 * Usage: StripVisibilityChecks <new-output-directory>. Never overwrites an existing output.
 */
public final class StripVisibilityChecks {
    private static final CoordinateContract CONTRACT = contract();
    private static final AngleReference NORTH = AngleReference.GRID_NORTH_CLOCKWISE;
    private static long checks, rays, rescuedSegments, rescuedSamples;
    private static final long[][] directions = new long[16][7];
    private static final EnumSet<VisibilityStatus> statuses = EnumSet.noneOf(VisibilityStatus.class);
    private static PrintWriter cases;

    private static CoordinateContract contract() {
        Properties p=new Properties();
        p.setProperty("scenario_id","synthetic_strip_checks");
        p.setProperty("coordinate_mode","artificial_local_meters");
        p.setProperty("horizontal_unit","m"); p.setProperty("latitude_degrees","34");
        p.setProperty("grid_north_alignment","true_north");
        p.setProperty("single_time_azimuth_reference","GRID_NORTH_CLOCKWISE");
        return CoordinateContract.from(p);
    }
    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static double[][] flat(int rows, int cols, double z) {
        double[][] v=new double[rows][cols];
        for (double[] row:v) Arrays.fill(row,z);
        return v;
    }
    private static AsciiGrid grid(double[][] v) {
        return new AsciiGrid(v.length,v[0].length,0,0,1,-9999,v,ZeroPolicy.PRESERVE_AS_VALID);
    }
    private static final class Scene {
        final AsciiGrid grid;
        final BlockUpperBoundVisibility block;
        final StripUpperBoundVisibility strip;
        Scene(AsciiGrid g) {
            grid=g; block=new BlockUpperBoundVisibility(g,16);
            strip=new StripUpperBoundVisibility(g);
            long blocks=((g.rows-1)/16+1L)*((g.cols-1)/16+1L);
            require(strip.payloadBytes()==81*blocks,"payload formula");
        }
    }
    private static boolean same(VisibilityResult a, VisibilityResult b) {
        return a.status==b.status && Double.doubleToLongBits(a.searchedDistanceGridUnits)
                ==Double.doubleToLongBits(b.searchedDistanceGridUnits);
    }
    private static StripUpperBoundVisibility.Counters compare(String name, Scene scene,
            int row, int col, double altitude, double bearing, SearchConfig search,
            VisibilityStatus expected, int expectedIndex, boolean rescueRequired) {
        VisibilityResult original=ControlledVisibility.visibleAt(scene.grid,CONTRACT,row,col,
                altitude,bearing,NORTH,search,null);
        BlockUpperBoundVisibility.Counters bc=new BlockUpperBoundVisibility.Counters();
        VisibilityResult block=scene.block.visibleAt(CONTRACT,row,col,altitude,bearing,NORTH,search,bc);
        StripUpperBoundVisibility.Counters sc=new StripUpperBoundVisibility.Counters();
        VisibilityResult strip=scene.strip.visibleAt(CONTRACT,row,col,altitude,bearing,NORTH,search,sc);
        rays++;
        cases.printf(Locale.ROOT,
                "%s,%d,%d,%.17g,%.17g,%d,%.17g,%s,%s,%s,%.17g,%.17g,%.17g,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                name,row,col,altitude,bearing,search.maxSamples,search.sampleSpacingGridUnits,
                original.status,block.status,strip.status,original.searchedDistanceGridUnits,
                block.searchedDistanceGridUnits,strip.searchedDistanceGridUnits,original.spatialSamples,
                block.spatialSamples,strip.spatialSamples,bc.skippedSamples,sc.skippedSamples,
                sc.legacyBlockFailures,sc.stripAttempts,sc.stripValueReads,sc.rescuedSegments,
                sc.rescuedSamples,sc.numericFallbackSegments,expectedIndex);
        cases.flush(); // Failure retains the offending ray and all three results.
        require(same(original,block),name+" original vs block status/first stop");
        require(same(original,strip),name+" original vs strip status/first stop");
        if (expected!=null) {
            require(original.status==expected,name+" independent expected status");
            require(original.searchedDistanceGridUnits==expectedIndex*search.sampleSpacingGridUnits,
                    name+" independent first sample index");
        }
        require(original.spatialSamples==block.spatialSamples+bc.skippedSamples,name+" block conservation");
        require(original.spatialSamples==strip.spatialSamples+sc.skippedSamples,name+" strip conservation");
        require(sc.spatialSamples==strip.spatialSamples,name+" interpolation counter");
        require(sc.rescuedSegments<=sc.legacyBlockFailures,name+" rescue denominator");
        require(sc.rescuedSamples<=sc.skippedSamples,name+" rescue sample counter");
        require(sc.stripValueReads<=6*sc.stripAttempts,name+" bound read budget");
        if (rescueRequired) {
            require(sc.rescuedSegments>0,name+" must rescue old-block failure");
            require(strip.spatialSamples<block.spatialSamples,name+" actual interpolation reduction");
        }
        long[] sums=new long[7];
        for (int b=0;b<16;b++) for (int k=0;k<7;k++) {
            directions[b][k]+=sc.byDirection[b][k]; sums[k]+=sc.byDirection[b][k];
        }
        long[] expectedCounts={1,sc.blockTests,sc.legacyBlockFailures,sc.stripAttempts,
                sc.stripValueReads,sc.rescuedSegments,sc.rescuedSamples};
        require(Arrays.equals(sums,expectedCounts),name+" direction counter conservation");
        statuses.add(original.status);
        rescuedSegments+=sc.rescuedSegments; rescuedSamples+=sc.rescuedSamples;
        return sc;
    }
    private static void fixture(String name, double[][] v, int r, int c, double alt, double bearing,
            int count, double spacing, VisibilityStatus expected, int stop, boolean rescue) {
        compare(name,new Scene(grid(v)),r,c,alt,bearing,new SearchConfig(count,spacing),expected,stop,rescue);
    }
    private static void fixtures() {
        for (double bearing:new double[]{0,90,180,270}) {
            double[][] v=flat(96,96,100); v[35][35]=140;
            fixture("off_path_peak_"+(int)bearing,v,40,40,5,bearing,12,1,
                    VisibilityStatus.VISIBLE,12,true);
        }
        double[][] v=flat(96,96,100); v[40][43]=140;
        StripUpperBoundVisibility.Counters onPath=compare("on_path_peak",new Scene(grid(v)),40,40,
                5,90,new SearchConfig(12,1),VisibilityStatus.SHADED,3,false);
        require(onPath.rescuedSegments==0,"on-path peak must not be excluded by strips");
        v=flat(96,96,100); v[31][31]=140;
        StripUpperBoundVisibility.Counters diagonal=compare("full_strip_diagonal_fallback",new Scene(grid(v)),
                31,16,5,45,new SearchConfig(20,1),VisibilityStatus.VISIBLE,20,false);
        require(diagonal.stripAttempts==0 && diagonal.stripValueReads==0,
                "full coverage must avoid pointless strip reads");
        v=flat(96,96,100); v[35][35]=-9999;
        StripUpperBoundVisibility.Counters offPathMissing=compare("off_path_nodata_keeps_complete_rule",
                new Scene(grid(v)),40,40,5,90,new SearchConfig(6,1),VisibilityStatus.VISIBLE,6,false);
        require(offPathMissing.stripAttempts==0 && offPathMissing.skippedSamples==0,
                "invalid block support cannot be rescued even if missing node is off-path");
        v=flat(96,96,100); v[40][48]=140;
        fixture("interpolation_across_block_boundary",v,40,46,5,90,8,1.5,
                VisibilityStatus.SHADED,1,false);
        v=flat(96,96,100); v[40][48]=140;
        fixture("peak_exactly_on_block_boundary",v,40,40,5,90,12,1,
                VisibilityStatus.SHADED,8,false);
        v=flat(96,96,100); v[40][42]=-9999; v[40][44]=200;
        fixture("nodata_before_shade",v,40,40,5,90,12,1,VisibilityStatus.UNKNOWN_RAY_INVALID,2,false);
        v=flat(96,96,100); v[40][42]=200; v[40][44]=-9999;
        fixture("shade_before_nodata",v,40,40,5,90,12,1,VisibilityStatus.SHADED,2,false);
        v=flat(96,96,100); v[40][41]=1000; v[41][41]=-9999;
        fixture("same_sample_nodata_precedes_shade",v,40,40,5,135,12,1,
                VisibilityStatus.UNKNOWN_RAY_INVALID,1,false);
        v=flat(96,96,100); v[40][40]=-9999;
        fixture("receiver_nodata",v,40,40,5,90,12,1,VisibilityStatus.UNKNOWN_RECEIVER_INVALID,0,false);
        fixture("receiver_outside",flat(96,96,100),-1,40,5,90,12,1,
                VisibilityStatus.UNKNOWN_RECEIVER_INVALID,0,false);
        fixture("north_exit",flat(96,96,100),2,40,5,0,12,1,
                VisibilityStatus.UNKNOWN_OUT_OF_BOUNDS,3,false);
        fixture("east_exit",flat(96,96,100),40,94,5,90,12,1,
                VisibilityStatus.UNKNOWN_OUT_OF_BOUNDS,2,false);
        fixture("partial_edge_block",flat(67,69,100),40,65,5,90,2,1,
                VisibilityStatus.VISIBLE,2,false);
        v=flat(96,96,100); v[40][41]=100+Math.tan(Math.toRadians(30));
        fixture("exact_tangent",v,40,40,30,90,4,1,VisibilityStatus.VISIBLE,4,false);
        fixture("below_tangent",v,40,40,30-1e-6,90,4,1,VisibilityStatus.SHADED,1,false);
        fixture("above_tangent",v,40,40,30+1e-6,90,4,1,VisibilityStatus.VISIBLE,4,false);
        fixture("negative_flat",flat(96,96,-200),40,40,5,90,12,1,
                VisibilityStatus.VISIBLE,12,false);
        v=flat(96,96,-200); v[35][35]=140;
        fixture("signed_support_off_path_peak",v,40,40,5,90,12,1,
                VisibilityStatus.VISIBLE,12,true);
        // Single exactRow uses floor(row), not rint(row); test the inherited branch explicitly.
        v=flat(96,96,0); v[23][21]=100;
        fixture("single_exact_row_below_integer",v,24,20,5,90-1e-8,1,1.25,
                VisibilityStatus.SHADED,1,false);
        fixture("outside_exact_row_tolerance",v,24,20,5,90-1e-6,1,1.25,
                VisibilityStatus.VISIBLE,1,false);
        v=flat(96,96,0); v[23][21]=-9999;
        fixture("single_exact_row_nodata_support",v,24,20,5,90-1e-8,1,1.25,
                VisibilityStatus.UNKNOWN_RAY_INVALID,1,false);
        fixture("near_integer_outer_boundary",flat(96,96,100),0,40,5,90-1e-8,1,1.25,
                VisibilityStatus.UNKNOWN_OUT_OF_BOUNDS,1,false);
        v=flat(96,96,0); v[25][19]=100;
        fixture("single_exact_col_below_integer",v,24,20,5,180+1e-8,1,1.25,
                VisibilityStatus.SHADED,1,false);
        v=flat(96,96,0); v[25][19]=100;
        fixture("both_exact_use_rint",v,24,20,5,180+1e-8,1,1,
                VisibilityStatus.VISIBLE,1,false);
        StripUpperBoundVisibility.Counters extreme=compare("extreme_finite_fallback",
                new Scene(grid(flat(96,96,Double.MAX_VALUE/8))),40,40,5,90,
                new SearchConfig(12,1),VisibilityStatus.VISIBLE,12,false);
        require(extreme.numericFallbackSegments>0,"extreme amplitude must fall back");
        AsciiGrid nonfinite=grid(flat(96,96,100));
        // Construct a new grid before either index is built; never mutate an indexed DEM.
        v=flat(96,96,100); v[40][42]=Double.NaN;
        fixture("nan_nodata",v,40,40,5,90,12,1,VisibilityStatus.UNKNOWN_RAY_INVALID,2,false);
        v=flat(96,96,100); v[40][42]=Double.POSITIVE_INFINITY;
        fixture("infinite_nodata",v,40,40,5,90,12,1,VisibilityStatus.UNKNOWN_RAY_INVALID,2,false);
        Scene normalized=new Scene(nonfinite);
        for (double bearing:new double[]{-90,450,720,-Double.MIN_VALUE})
            compare("normalized_bearing_"+Double.toHexString(bearing),normalized,40,40,5,bearing,
                    new SearchConfig(12,1),VisibilityStatus.VISIBLE,12,false);
        invalidArguments(normalized);
    }
    private static void invalidArguments(Scene s) {
        for (double altitude:new double[]{0,90,-1,Double.NaN,Double.POSITIVE_INFINITY})
            invalid(s,altitude,0);
        invalid(s,5,Double.NaN); invalid(s,5,Double.POSITIVE_INFINITY);
    }
    private static void invalid(Scene s, double altitude, double bearing) {
        for (int method=0;method<3;method++) {
            boolean rejected=false;
            try {
                if (method==0) ControlledVisibility.visibleAt(s.grid,CONTRACT,40,40,altitude,bearing,NORTH,
                        new SearchConfig(12,1),null);
                else if (method==1) s.block.visibleAt(CONTRACT,40,40,altitude,bearing,NORTH,
                        new SearchConfig(12,1),new BlockUpperBoundVisibility.Counters());
                else s.strip.visibleAt(CONTRACT,40,40,altitude,bearing,NORTH,
                        new SearchConfig(12,1),new StripUpperBoundVisibility.Counters());
            } catch (IllegalArgumentException e) { rejected=true; }
            require(rejected,"invalid angle rejection method="+method);
        }
    }
    private static void syntheticSweep() {
        Random random=new Random(20261031L);
        for (int terrain=0;terrain<5;terrain++) {
            double[][] v=flat(67,69,100);
            for (int r=0;r<v.length;r++) for (int c=0;c<v[0].length;c++) {
                if (terrain==1) v[r][c]=0.3*r-0.4*c-100;
                if (terrain==2) v[r][c]=100+random.nextDouble()*60;
                if (terrain==3) v[r][c]=random.nextDouble()<0.025 ? 200 : 100;
                if (terrain==4) v[r][c]=random.nextDouble()<0.015 ? -9999 : 100;
            }
            Scene scene=new Scene(grid(v));
            for (int i=0;i<320;i++) {
                double bearing=(i%16)*22.5+(i%3==0 ? 0 : random.nextDouble()*22.5);
                double spacing=new double[]{0.5,1,1.25,1.5,2.25}[i%5];
                compare("sweep_"+terrain+"_"+i,scene,random.nextInt(67),random.nextInt(69),
                        new double[]{0.01,5,30,80}[i%4],bearing,new SearchConfig(32,spacing),null,-1,false);
            }
        }
    }
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length!=1) throw new IllegalArgumentException("expected NEW output directory");
        Path output=Path.of(args[0]);
        Files.createDirectory(output); // Fail on reuse; preserve prior results.
        try (PrintWriter writer=new PrintWriter(Files.newBufferedWriter(output.resolve("cases.csv"),
                StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW))) {
            cases=writer;
            cases.println("case,row,col,altitude_deg,bearing_deg,max_samples,spacing,original_status,block_status,strip_status,original_stop,block_stop,strip_stop,original_samples,block_samples,strip_samples,block_skipped,strip_skipped,legacy_failed_segments,strip_attempts,strip_value_reads,rescued_segments,rescued_samples,numeric_fallback_segments,expected_first_stop_index");
            fixtures(); syntheticSweep();
            require(statuses.size()==VisibilityStatus.values().length,"all result statuses covered");
            require(rescuedSegments>0 && rescuedSamples>0,"nontrivial bound tightening exercised");
        }
        try (PrintWriter writer=new PrintWriter(Files.newBufferedWriter(output.resolve("directions.csv"),
                StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW))) {
            writer.println("bearing_from_inclusive,bearing_to_exclusive,rays,block_tests,legacy_failed_segments,strip_attempts,strip_value_reads,rescued_segments,rescued_samples");
            for (int b=0;b<16;b++) {
                writer.printf(Locale.ROOT,"%.1f,%.1f",b*22.5,(b+1)*22.5);
                for (long count:directions[b]) writer.print(","+count);
                writer.println();
            }
        }
        String summary="scope=synthetic_correctness_only_not_real_terrain_or_performance\n"
                +"seed=20261031\nrays="+rays+"\nassertions="+checks+"\nstatuses="+statuses
                +"\nrescued_segments="+rescuedSegments+"\nrescued_samples="+rescuedSamples
                +"\ncorrectness=true\nperformance=not_measured\nnovelty=not_established\n";
        Files.writeString(output.resolve("summary.properties"),summary,StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        System.out.print(summary);
        System.out.println("STRIP_CHECKS_PASS output="+output.toAbsolutePath());
    }
}
