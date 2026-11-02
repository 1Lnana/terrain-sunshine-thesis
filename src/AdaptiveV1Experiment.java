import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Common compute-only timing and output protocol for five fixed and two adaptive methods. */
public final class AdaptiveV1Experiment {
    static final String[] METHODS={"fixed_1min","fixed_5min","fixed_10min","fixed_20min","fixed_30min","adaptive_20min","adaptive_30min"};
    static final class Pixel {
        int row,col,count;double day,rise,psd;AdaptiveTimeV1.Result result;double[] fixedTimes;int[] fixedStates;String fallbackReason;
        Pixel(int r,int c,double day,double rise,AdaptiveTimeV1.Result result){row=r;col=c;this.day=day;this.rise=rise;this.result=result;psd=result.psd;count=result.evaluated.size();fallbackReason=result.fallbackReason;}
        Pixel(int r,int c,double day,double rise,double[] times,int[] states){row=r;col=c;this.day=day;this.rise=rise;fixedTimes=times;fixedStates=states;psd=TimeStepBaseline.integrate(times,states);count=times.length;fallbackReason="NONE";}
    }
    static String state(int s){return s<0?"U":Integer.toString(s);}
    static void run(AsciiGrid grid,String method,boolean warmup,Path out,PrintWriter log)throws Exception {
        int step=Integer.parseInt(method.substring(method.indexOf('_')+1,method.length()-3));boolean adaptive=method.startsWith("adaptive");
        double dec=TimeStepBaseline.declination(LocalDate.parse("2023-06-21"));RunStatistics stats=new RunStatistics();
        CoordinateContract contract=TimeStepBaseline.contract();SearchConfig search=new SearchConfig(200,50);
        List<Pixel> pixels=new ArrayList<>();Map<String,Integer> reasons=new TreeMap<>();int min=Integer.MAX_VALUE,max=0,valid=0,fallback=0;long total=0;double sum=0;
        long start=System.nanoTime();
        for(int r=480;r<=511;r++)for(int c=544;c<=575;c++) {
            final int row=r,col=c;double lat=25.655+Math.toDegrees((grid.rows/2.0-r-.5)*50/6371008.8);
            double day=TimeStepBaseline.daylight(lat,dec),rise=720-day/2;
            AdaptiveTimeV1.Oracle oracle=t->{
                double[] a=TimeStepBaseline.sun(lat,dec,rise+t);
                VisibilityStatus status=ControlledVisibility.visibleAt(grid,contract,row,col,Math.max(1e-7,a[0]),a[1],AngleReference.GRID_NORTH_CLOCKWISE,search,stats).status;
                return status==VisibilityStatus.VISIBLE?1:status==VisibilityStatus.SHADED?0:-1;
            };
            Pixel pixel;
            if(adaptive)pixel=new Pixel(row,col,day,rise,AdaptiveTimeV1.adaptive(day,step,oracle));
            else {
                // Preserve the baseline's array loop, not an artificially expensive map-based fixed reference.
                int n=(int)Math.ceil(day/step);double[] times=new double[n+1];int[] states=new int[n+1];
                for(int i=0;i<=n;i++){times[i]=Math.min(i*(double)step,day);states[i]=oracle.at(times[i]);}
                pixel=new Pixel(row,col,day,rise,times,states);
            }
            pixels.add(pixel);int count=pixel.count;total+=count;min=Math.min(min,count);max=Math.max(max,count);
            if(Double.isFinite(pixel.psd)){valid++;sum+=pixel.psd;}
            if(!pixel.fallbackReason.equals("NONE")){fallback++;reasons.merge(pixel.fallbackReason,1,Integer::sum);}
        }
        double seconds=(System.nanoTime()-start)/1e9;
        if(total!=stats.rayCalls||stats.rayCalls!=stats.visibleResults+stats.shadedResults+stats.unknownResults)throw new AssertionError("work accounting mismatch");
        if(warmup){log.println("WARMUP_DONE "+method+" rayCalls="+stats.rayCalls);log.flush();return;}
        Files.createDirectories(out);
        try(PrintWriter ps=new PrintWriter(Files.newBufferedWriter(out.resolve("psd.csv")));PrintWriter traces=new PrintWriter(Files.newBufferedWriter(out.resolve("samples.csv")))) {
            ps.println("row,col,daylight_minutes,psd_minutes,sample_count,fallback_reason");
            traces.println("row,col,minutes_since_sunrise,state,used_for_integration");
            for(Pixel p:pixels) {
                ps.printf("%d,%d,%.12f,%.12f,%d,%s%n",p.row,p.col,p.day,p.psd,p.count,p.fallbackReason);
                if(p.result!=null)for(Map.Entry<Double,Integer> e:p.result.evaluated.entrySet())traces.printf("%d,%d,%.12f,%s,%d%n",p.row,p.col,e.getKey(),state(e.getValue()),p.result.integrationNodes.containsKey(e.getKey())?1:0);
                else for(int i=0;i<p.fixedTimes.length;i++)traces.printf("%d,%d,%.12f,%s,1%n",p.row,p.col,p.fixedTimes[i],state(p.fixedStates[i]));
            }
        }
        String json=String.format("{\"method\":\"%s\",\"runtime_sec\":%.9f,\"time_sample_count\":%d,\"time_sample_count_min_per_pixel\":%d,\"total_time_samples\":%d,\"rayCalls\":%d,\"spatialSamples\":%d,\"unknown_count\":%d,\"visible_results\":%d,\"shaded_results\":%d,\"valid_pixels\":%d,\"mean_PSD\":%.12f,\"fallback_pixels\":%d}",method,seconds,max,min,total,stats.rayCalls,stats.spatialSamples,stats.unknownResults,stats.visibleResults,stats.shadedResults,valid,sum/valid,fallback);
        Files.writeString(out.resolve("runtime.json"),json+"\n");
        StringBuilder reasonsJson=new StringBuilder("{");for(Map.Entry<String,Integer> e:reasons.entrySet()){if(reasonsJson.length()>1)reasonsJson.append(',');reasonsJson.append('"').append(e.getKey()).append("\":").append(e.getValue());}reasonsJson.append('}');Files.writeString(out.resolve("fallback_summary.json"),reasonsJson+"\n");
        log.println("FORMAL_DONE "+out.getFileName()+" "+json);log.flush();System.out.println("FORMAL_DONE "+method+" runtime_sec="+seconds+" rays="+stats.rayCalls+" fallback="+fallback);
    }
    public static void main(String[] args)throws Exception {
        Locale.setDefault(Locale.ROOT);if(args.length!=2)throw new IllegalArgumentException("DEM output-root");
        AsciiGrid grid=AsciiGridReader.read(Path.of(args[0]),ZeroPolicy.PRESERVE_AS_VALID);if(grid.rows!=1159||grid.cols!=1273||grid.cellSize!=50)throw new IllegalArgumentException("frozen grid mismatch");
        Path out=Path.of(args[1]);Files.createDirectories(out);
        try(PrintWriter log=new PrintWriter(Files.newBufferedWriter(out.resolve("execution.log")))) {
            for(String method:METHODS)run(grid,method,true,out,log);
            Random random=new Random(20261011);
            for(int repeat=1;repeat<=3;repeat++) {
                List<String> order=new ArrayList<>(Arrays.asList(METHODS));Collections.shuffle(order,random);log.println("ORDER repeat="+repeat+" "+order);log.flush();
                for(String method:order)run(grid,method,false,out.resolve(method+"_r"+repeat),log);
            }
        }
    }
}
