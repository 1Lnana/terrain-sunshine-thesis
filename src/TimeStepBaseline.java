import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** 固定时间步长基线：逐像元、逐时刻查询地形遮挡，再累计潜在日照分钟数。 */
public final class TimeStepBaseline {
    /** 由年内日期近似计算太阳赤纬，返回弧度。 */
    static double declination(LocalDate date) {
        double a=2*Math.PI*(date.getDayOfYear()-1)/(date.isLeapYear()?366:365);
        return .006894-.399512*Math.cos(a)+.072075*Math.sin(a)-.006799*Math.cos(2*a)
            +.000896*Math.sin(2*a)-.002689*Math.cos(3*a)+.001516*Math.sin(3*a);
    }
    /** 输入纬度、赤纬和地方太阳时分钟；返回[太阳高度角, 从北顺时针的方位角]，单位均为度。 */
    static double[] sun(double lat, double dec, double minute) {
        double p=Math.toRadians(lat), h=Math.toRadians((minute-720)/4);
        double up=Math.sin(p)*Math.sin(dec)+Math.cos(p)*Math.cos(dec)*Math.cos(h);
        double east=-Math.cos(dec)*Math.sin(h);
        double north=Math.cos(p)*Math.sin(dec)-Math.sin(p)*Math.cos(dec)*Math.cos(h);
        return new double[]{Math.toDegrees(Math.asin(Math.max(-1,Math.min(1,up)))),
            (Math.toDegrees(Math.atan2(east,north))+360)%360};
    }
    /** 按太阳高度角为零的几何日出日落，求白昼长度（分钟）；这里不处理极昼和极夜。 */
    static double daylight(double lat,double dec) {
        double x=-Math.tan(Math.toRadians(lat))*Math.tan(dec);
        if(x<=-1||x>=1) throw new IllegalArgumentException("polar day/night unsupported in this regional driver");
        return 8*Math.toDegrees(Math.acos(x));
    }
    /** 对 0/1 可见状态做梯形积分；任一节点未知（-1）时，整条序列返回 NaN。 */
    static double integrate(double[] times,int[] states) {
        double total=0;
        for(int i=1;i<times.length;i++) {
            if(states[i]<0||states[i-1]<0)return Double.NaN;
            total+=(states[i]+states[i-1])*.5*(times[i]-times[i-1]);
        }
        return total;
    }
    /** 声明当前 DEM 的坐标约定：CRS 和网格北向未确认，方位角按网格北使用。 */
    static CoordinateContract contract() {
        Properties p=new Properties();
        p.setProperty("scenario_id","DALI_PAPER_CONSTRAINED_LOCAL_50M");
        p.setProperty("coordinate_mode","unknown_crs_grid");p.setProperty("horizontal_unit","m");
        p.setProperty("latitude_degrees","unset");p.setProperty("grid_north_alignment","unknown");
        p.setProperty("single_time_azimuth_reference","GRID_NORTH_CLOCKWISE");
        return CoordinateContract.from(p);
    }
    static void require(boolean v,String text){if(!v)throw new AssertionError(text);}
    /** 内置的小规模控制检查；仅验证程序流程和构造场景，不代表真实 DEM 精度。 */
    static void checks() {
        double lat=25.655,dec=declination(LocalDate.parse("2023-06-21"));
        double noon=sun(lat,dec,720)[0];
        require(noon>87&&noon<89,"summer noon altitude");
        require(sun(lat,dec,240)[0]<0&&sun(lat,dec,1200)[0]<0,"night negative altitude");
        require(noon>sun(lat,declination(LocalDate.parse("2023-12-21")),720)[0],"season direction");
        require(sun(lat,dec,480)[1]>0&&sun(lat,dec,480)[1]<180,"morning east");
        require(sun(lat,dec,960)[1]>180,"afternoon west");
        double dl=daylight(lat,dec),rise=720-dl/2;
        require(Math.abs(sun(lat,dec,rise)[0])<1e-10,"analytic sunrise");
        for(int dt:new int[]{1,5,10,20,30,60}){
            double[] t={0,dt,dt+7};int[] s={1,1,1};
            require(integrate(t,s)==dt+7,"actual minute weights");
        }
        require(Double.isNaN(integrate(new double[]{0,10},new int[]{1,-1})),"unknown retained");
        require(integrate(new double[]{0,10},new int[]{0,1})==5,"trapezoid");
        double[][] z=new double[9][9];
        AsciiGrid flat=new AsciiGrid(9,9,0,0,1,-9999,z,ZeroPolicy.PRESERVE_AS_VALID);
        require(ControlledVisibility.visibleAt(flat,contract(),4,4,45,0,AngleReference.GRID_NORTH_CLOCKWISE,new SearchConfig(3,1),new RunStatistics()).status==VisibilityStatus.VISIBLE,"single instant flat");
        z[2][4]=10;
        require(ControlledVisibility.visibleAt(flat,contract(),4,4,45,0,AngleReference.GRID_NORTH_CLOCKWISE,new SearchConfig(3,1),new RunStatistics()).status==VisibilityStatus.SHADED,"single instant obstacle");
        AsciiGrid level=new AsciiGrid(9,9,0,0,1,-9999,new double[9][9],ZeroPolicy.PRESERVE_AS_VALID);
        for(int dt:new int[]{1,5,10,20,30,60}) {
            int n=(int)Math.ceil(dl/dt);double[] times=new double[n+1];int[] states=new int[n+1];
            for(int i=0;i<=n;i++) {
                times[i]=rise+Math.min(i*(double)dt,dl);double[] a=sun(lat,dec,times[i]);
                states[i]=ControlledVisibility.visibleAt(level,contract(),4,4,Math.max(1e-7,a[0]),a[1],AngleReference.GRID_NORTH_CLOCKWISE,new SearchConfig(3,1),new RunStatistics()).status==VisibilityStatus.VISIBLE?1:0;
            }
            require(Math.abs(integrate(times,states)-dl)<1e-9,"flat whole day equals daylight for every step");
        }
        System.out.println("DRIVER_CHECKS_PASS solar=6 integration=9 geometry=2 flat_whole_day=6");
    }
    static int integer(Properties p,String k){return Integer.parseInt(p.getProperty(k));}
    public static void main(String[] args)throws Exception {
        Locale.setDefault(Locale.ROOT);
        // --test 只运行内置检查；正常模式依次接收配置文件和输出目录。
        if(args.length==1&&args[0].equals("--test")){checks();return;}
        if(args.length!=2)throw new IllegalArgumentException("config.properties output-directory");
        Properties p=new Properties();try(Reader r=Files.newBufferedReader(Path.of(args[0]))){p.load(r);}
        Path out=Path.of(args[1]);Files.createDirectories(out);
        int dt=integer(p,"time_step_minutes"),r0=integer(p,"row_start"),c0=integer(p,"col_start"),nr=integer(p,"rows"),nc=integer(p,"cols"),max=integer(p,"search_samples");
        if(!Set.of(1,5,10,20,30,60).contains(dt)||nr<1||nc<1)throw new IllegalArgumentException("invalid step or ROI");
        if(!p.getProperty("coordinate_contract").equals("paper_constrained_local_50m")||!p.getProperty("nodata_policy").equals("strict_unknown"))throw new IllegalArgumentException("unsupported contract/policy");
        // 读取 ASCII DEM，并检查本基线所用的 50 m 网格与 ROI 是否匹配。
        AsciiGrid g=AsciiGridReader.read(Path.of(p.getProperty("dem")),ZeroPolicy.PRESERVE_AS_VALID);
        if(g.cellSize!=50||!g.inside(r0,c0)||!g.inside(r0+nr-1,c0+nc-1))throw new IllegalArgumentException("DEM/ROI mismatch");
        double dec=declination(LocalDate.parse(p.getProperty("date")));
        double lat0=Double.parseDouble(p.getProperty("latitude_center")),spacing=Double.parseDouble(p.getProperty("search_spacing_m"));
        if(!Double.isFinite(lat0)||Math.abs(lat0)>=66)throw new IllegalArgumentException("invalid latitude");
        SearchConfig search=new SearchConfig(max,spacing);CoordinateContract cc=contract();RunStatistics stats=new RunStatistics();
        // 计时从逐像元计算开始；DEM 读取和参数检查不计入 runtime_sec。
        long start=System.nanoTime();long sampleCount=0;int sampleMin=Integer.MAX_VALUE,sampleMax=0;double total=0;int valid=0;
        try(PrintWriter ps=new PrintWriter(Files.newBufferedWriter(out.resolve("psd.csv")));PrintWriter ss=new PrintWriter(Files.newBufferedWriter(out.resolve("states.csv")))) {
            ps.println("row,col,elevation_m,latitude,longitude,daylight_minutes,psd_minutes");
            ss.println("row,col,sunrise_solar_minutes,sunset_solar_minutes,states");
            for(int r=r0;r<r0+nr;r++)for(int c=c0;c<c0+nc;c++) {
                // 以 DEM 中心和配置的中心经纬度作局地近似换算；不是对 DEM 的 CRS 认证。
                double lat=lat0+Math.toDegrees((g.rows/2.0-r-.5)*50/6371008.8);
                double lon=Double.parseDouble(p.getProperty("longitude_center"))+Math.toDegrees((c+.5-g.cols/2.0)*50/(6371008.8*Math.cos(Math.toRadians(lat0))));
                double day=daylight(lat,dec),rise=720-day/2;
                // 包含日出和日落端点；最后一段不足 dt 时使用实际的剩余分钟数。
                int intervals=(int)Math.ceil(day/dt);sampleMin=Math.min(sampleMin,intervals+1);sampleMax=Math.max(sampleMax,intervals+1);double[] times=new double[intervals+1];int[] states=new int[intervals+1];StringBuilder seq=new StringBuilder();
                for(int i=0;i<=intervals;i++){
                    times[i]=rise+Math.min(i*(double)dt,day);sampleCount++;
                    {
                        double[] angle=sun(lat,dec,times[i]);
                        // 几何日出/日落的高度角按白昼侧微小正角处理，避免平地端点被判为夜间。
                        VisibilityStatus s=ControlledVisibility.visibleAt(g,cc,r,c,Math.max(1e-7,angle[0]),angle[1],AngleReference.GRID_NORTH_CLOCKWISE,search,stats).status;
                        // 1=可见，0=遮挡，-1=未知；未知不能当成可见或遮挡。
                        states[i]=s==VisibilityStatus.VISIBLE?1:s==VisibilityStatus.SHADED?0:-1;
                    }
                    seq.append(states[i]<0?'U':(char)('0'+states[i]));
                }
                double v=integrate(times,states);if(Double.isFinite(v)){total+=v;valid++;}
                // PSD 是该像元的潜在日照分钟数；states 保留各采样节点的原始状态。
                ps.printf("%d,%d,%.9f,%.12f,%.12f,%.12f,%.12f%n",r,c,g.elevation[r][c],lat,lon,day,v);
                ss.printf("%d,%d,%.12f,%.12f,%s%n",r,c,rise,rise+day,seq);
            }
        }
        double seconds=(System.nanoTime()-start)/1e9;
        // time_sample_count 最终表示单像元最大节点数；total_time_samples 才是全 ROI 总节点数。
        String runtime=String.format("{\"runtime_sec\":%.9f,\"time_sample_count\":%d,\"rayCalls\":%d,\"spatialSamples\":%d,\"unknown_count\":%d,\"valid_pixels\":%d,\"mean_PSD\":%.12f,\"visible\":%d,\"shaded\":%d}",seconds,sampleCount,stats.rayCalls,stats.spatialSamples,stats.unknownResults,valid,valid==0?0:total/valid,stats.visibleResults,stats.shadedResults);
        runtime=runtime.substring(0,runtime.length()-1)+String.format(",\"total_time_samples\":%d,\"time_sample_count_min_per_pixel\":%d,\"time_sample_count_max_per_pixel\":%d,\"time_sample_count_definition\":\"max samples per pixel including sunrise and sunset\"}",sampleCount,sampleMin,sampleMax);
        runtime=runtime.replace("\"time_sample_count\":"+sampleCount+",","\"time_sample_count\":"+sampleMax+",");
        Files.writeString(out.resolve("runtime.json"),runtime+"\n");
        Files.copy(Path.of(args[0]),out.resolve("config.properties"),StandardCopyOption.REPLACE_EXISTING);
        System.out.println(runtime);
    }
}
