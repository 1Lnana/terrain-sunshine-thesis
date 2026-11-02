import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 用户运行的一次集中检查：人工几何＋3个真实像元，不是整区精度/性能实验。 */
public final class HorizonBaselineChecks {
    private static int checks;
    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void near(double value, double expected, String message) {
        require(Double.isFinite(value) && Math.abs(value - expected) < 1e-8, message);
    }
    private static void report(PrintWriter log, String text) {
        System.out.println(text); log.println(text); log.flush();
    }
    private static AsciiGrid grid(double[][] values) {
        return new AsciiGrid(values.length, values[0].length, 0, 0, 1, -9999,
                values, ZeroPolicy.PRESERVE_AS_VALID);
    }
    private static double[][] plane(double northSlope, double eastSlope) {
        double[][] values = new double[21][21];
        for (int r = 0; r < 21; r++) for (int c = 0; c < 21; c++)
            values[r][c] = 100 + (10-r)*northSlope + (c-10)*eastSlope;
        return values;
    }
    private static int ray(AsciiGrid grid, int row, int col, SearchConfig search, double altitude, double bearing) {
        VisibilityStatus status = ControlledVisibility.visibleAt(grid, TimeStepBaseline.contract(), row, col,
                Math.max(1e-7, altitude), bearing, AngleReference.GRID_NORTH_CLOCKWISE, search, null).status;
        return status == VisibilityStatus.VISIBLE ? 1 : status == VisibilityStatus.SHADED ? 0 : -1;
    }
    private static void rejected(Runnable operation, String message) {
        try { operation.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(message);
    }

    private static void synthetic(PrintWriter log) {
        SearchConfig search = new SearchConfig(4, 1);
        HorizonBaseline.Table flat = HorizonBaseline.build(grid(plane(0,0)), 10, 10, search, 2);
        for (int i = 0; i < flat.directions(); i++) near(flat.angleAtIndex(i), 0, "flat horizon");
        require(flat.spatialSamples == 720 && flat.payloadBytes() == 1440, "table counters");
        HorizonBaseline.Day day = HorizonBaseline.sampleDay(flat, 120.5, t -> new double[]{20, t}, 0);
        near(day.psdMinutes, 120.5, "fractional sunset light");
        require(day.timeQueries() == 122 && day.times[121] == 120.5, "minute grid with sunset tail");
        HorizonBaseline.Day integerDay = HorizonBaseline.sampleDay(flat, 120, t -> new double[]{20, 0}, 0);
        require(integerDay.timeQueries() == 121, "integer sunset not duplicated");

        // 平面坡度给出解析地平线，覆盖四个轴向和非轴向的双线性插值。
        AsciiGrid slope = grid(plane(.5, .25));
        for (double bearing : new double[]{0,45,90,135,180,225,270,315,17.3}) {
            double rad = Math.toRadians(bearing);
            HorizonBaseline.Direction d = HorizonBaseline.direction(slope,10,10,search,bearing);
            double expected = Math.toDegrees(Math.atan(.5*Math.cos(rad)+.25*Math.sin(rad)));
            near(d.angleDegrees, expected, "analytic slope at " + bearing);
            require(d.status == null && d.spatialSamples == 4, "complete direction");
            for (double altitude : new double[]{5,20,40})
                require(ray(slope,10,10,search,altitude,bearing) == (altitude < d.angleDegrees ? 0 : 1),
                        "direct ray vs directional horizon");
        }

        // 北方两米处高一米的山脊，应给出atan(1/2)；太阳略高/略低时状态相反。
        double[][] ridgeValues = plane(0,0); ridgeValues[8][10] = 101;
        AsciiGrid ridge = grid(ridgeValues);
        HorizonBaseline.Table ridgeTable = HorizonBaseline.build(ridge,10,10,search,1);
        double h = Math.toDegrees(Math.atan(.5));
        near(ridgeTable.at(0),h,"ridge elevation");
        for (double altitude : new double[]{h-.01,h+.01})
            require(ridgeTable.state(altitude,0) == ray(ridge,10,10,search,altitude,0), "ridge side of threshold");
        require(ridgeTable.state(h,0) == 1,"equal horizon is visible by angle convention");
        report(log,"BOUNDARY ridge_equal horizon_state="+ridgeTable.state(h,0)
                +" ray_state="+ray(ridge,10,10,search,h,0)+" floating_boundary_report_only");

        double[][] peak = plane(0,0); peak[10][10] = 200;
        near(HorizonBaseline.direction(grid(peak),10,10,search,0).angleDegrees,
                Math.toDegrees(Math.atan(-100.0/4)),"negative horizon retained");

        double[] angles = new double[180]; angles[0] = 10; angles[179] = 30;
        HorizonBaseline.Table known = new HorizonBaseline.Table(2,angles,0);
        near(known.at(359),20,"wrap interpolation");
        near(known.at(-1),20,"negative azimuth wrap");
        near(known.at(360),10,"360 exact node");
        require(known.solarState(15,1,-2) == 0 && known.solarState(15,1,1) == 1,"north correction sign");
        require(known.state(20,359) == 1 && known.state(19,359) == 0,"strict angle comparison");
        angles[1] = Double.NaN;
        HorizonBaseline.Table unknown = new HorizonBaseline.Table(2,angles,0);
        near(unknown.at(0),10,"exact node does not need unknown neighbour");
        require(Double.isNaN(unknown.at(1)) && unknown.state(20,1) == -1,"unknown interpolation");
        require(Double.isNaN(HorizonBaseline.sampleDay(unknown,2.5,t -> new double[]{20,1},0).psdMinutes),
                "unknown day is NaN");
        double[] high = new double[180]; Arrays.fill(high,80);
        near(HorizonBaseline.sampleDay(new HorizonBaseline.Table(2,high,0),2.5,
                t -> new double[]{20,0},0).psdMinutes,0,"all shaded");
        // 以已知分钟状态独立算出1.5分钟，检查状态转换处的梯形权重。
        near(HorizonBaseline.sampleDay(known,3,t -> new double[]{t < 2 ? 20 : 0,0},0).psdMinutes,
                1.5,"transition trapezoid");

        double[][] invalid = plane(0,0); invalid[9][10] = -9999;
        HorizonBaseline.Direction bad = HorizonBaseline.direction(grid(invalid),10,10,search,0);
        require(bad.status == VisibilityStatus.UNKNOWN_RAY_INVALID && bad.spatialSamples == 1,"NoData ray");
        require(HorizonBaseline.direction(grid(invalid),9,10,search,0).status
                == VisibilityStatus.UNKNOWN_RECEIVER_INVALID,"NoData receiver");
        require(HorizonBaseline.direction(slope,0,10,search,0).status
                == VisibilityStatus.UNKNOWN_OUT_OF_BOUNDS,"outside ray");
        rejected(() -> HorizonBaseline.build(slope,10,10,search,3),"bad azimuth step");
        rejected(() -> flat.at(Double.NaN),"bad bearing");
        rejected(() -> flat.state(Double.NaN,0),"bad altitude");
        rejected(() -> HorizonBaseline.sampleDay(flat,0,t -> new double[]{20,0},0),"bad duration");
        report(log,"HORIZON_SYNTHETIC_PASS checks="+checks);
    }

    private static void terrain(Path config, Path output, PrintWriter log) throws Exception {
        ProjectedBaselineExperiment.Inputs in = new ProjectedBaselineExperiment.Inputs(config);
        Set<String> wanted = Set.of("162,226","162,228","189,213");
        List<ProjectedBaselineExperiment.Receiver> selected = new ArrayList<>();
        for (ProjectedBaselineExperiment.Receiver r : in.receivers) if (wanted.contains(r.key())) selected.add(r);
        require(selected.size()==3,"expected three Qinling receivers");
        report(log,"TERRAIN_READY pixels=3 azimuth_steps=1,2 single_pass_not_timing_benchmark");
        try (PrintWriter detail = new PrintWriter(Files.newBufferedWriter(output.resolve("minute_states.csv")));
             PrintWriter summary = new PrintWriter(Files.newBufferedWriter(output.resolve("pixel_summary.csv")))) {
            detail.println("row,col,azimuth_step_deg,minute,true_azimuth_deg,grid_azimuth_deg,altitude_deg,horizon_deg,ray_state,horizon_state");
            summary.println("row,col,azimuth_step_deg,fixed1_minutes,horizon_minutes,error_minutes,state_mismatches,precompute_samples,time_queries");
            for (ProjectedBaselineExperiment.Receiver r : selected) {
                double daylight = TimeStepBaseline.daylight(r.lat,in.dec), sunrise = 720-daylight/2;
                int count = (int)Math.ceil(daylight)+1;
                double[] times = new double[count]; int[] reference = new int[count];
                for (int i=0;i<count;i++) {
                    times[i]=Math.min((double)i,daylight);
                    double[] solar=TimeStepBaseline.sun(r.lat,in.dec,sunrise+times[i]);
                    reference[i]=ray(in.grid,r.row,r.col,in.search,solar[0],solar[1]+r.north);
                }
                double fixed1=TimeStepBaseline.integrate(times,reference);
                require(Double.isFinite(fixed1),"valid real reference");
                for (int step : new int[]{1,2}) {
                    HorizonBaseline.Table table=HorizonBaseline.build(in.grid,r.row,r.col,in.search,step);
                    require(table.spatialSamples==(long)(360/step)*in.search.maxSamples,"full terrain scan count");
                    for(int i=0;i<table.directions();i++) require(Double.isFinite(table.angleAtIndex(i)),"valid full table");
                    for(double bearing : new double[]{0,44,90,180,270,358}) {
                        double h=table.at(bearing);
                        for(double altitude : new double[]{h-.01,h+.01}) {
                            if(altitude>0 && altitude<90)
                                require(table.state(altitude,bearing)==ray(in.grid,r.row,r.col,in.search,altitude,bearing),
                                        "real exact-direction threshold sides "+r.key());
                        }
                    }
                    HorizonBaseline.Day result=HorizonBaseline.day(table,r.lat,in.dec,r.north);
                    require(result.timeQueries()==count && Double.isFinite(result.psdMinutes),"valid day output");
                    int differences=0;
                    for(int i=0;i<count;i++) {
                        near(result.times[i],times[i],"shared minute grid");
                        double[] solar=TimeStepBaseline.sun(r.lat,in.dec,sunrise+times[i]);
                        double bearing=solar[1]+r.north;
                        if(reference[i]!=result.states[i])differences++;
                        detail.printf(Locale.ROOT,"%d,%d,%d,%.12f,%.12f,%.12f,%.12f,%.12f,%d,%d%n",
                                r.row,r.col,step,times[i],solar[1],bearing,solar[0],table.at(bearing),reference[i],result.states[i]);
                    }
                    summary.printf(Locale.ROOT,"%d,%d,%d,%.12f,%.12f,%.12f,%d,%d,%d%n",r.row,r.col,step,
                            fixed1,result.psdMinutes,result.psdMinutes-fixed1,differences,table.spatialSamples,result.timeQueries());
                    report(log,String.format(Locale.ROOT,"TERRAIN pixel=%s azimuth_step=%d fixed1=%.3f horizon=%.3f error=%.3f state_mismatches=%d",
                            r.key(),step,fixed1,result.psdMinutes,result.psdMinutes-fixed1,differences));
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("qinling-config new-output-directory");
        Path config=Path.of(args[0]).toAbsolutePath().normalize();
        Path output=Path.of(args[1]).toAbsolutePath().normalize();
        Path allowed=Path.of("user_runs").toAbsolutePath().normalize();
        if(!output.startsWith(allowed)||output.equals(allowed))throw new IllegalArgumentException("new user_runs subdirectory required");
        Files.createDirectories(output.getParent()); Files.createDirectory(output);
        Files.copy(config,output.resolve("config.properties"));
        Files.writeString(output.resolve("input_sources.txt"),"config="+config+"\n");
        try(PrintWriter log=new PrintWriter(Files.newBufferedWriter(output.resolve("checks.log")))) {
            synthetic(log);
            terrain(config,output,log);
            report(log,"HORIZON_BASELINE_CHECKS_PASS checks="+checks+" terrain_pixels=3 not_accuracy_acceptance output="+output);
        }
    }
}
