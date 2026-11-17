import java.nio.file.*;
import java.io.*;

/** New gate only: frozen algorithm is not edited. Tests the actual padded strict threshold. */
public final class SuffixFreezeBoundaryChecks {
    static void check(boolean b,String m){if(!b)throw new IllegalStateException(m);}
    static double limit(double h,double r1,double re){return h+1e-8*Math.max(1,Math.max(Math.abs(h),Math.max(Math.abs(r1),Math.abs(re))));}
    public static void main(String[] args)throws Exception{
        check(args.length==2&&args[0].equals("--review-gate"),"explicit review gate required");
        Path out=Path.of(args[1]);Files.createDirectory(out);
        double rise=Math.tan(Math.toRadians(5)),r1=100+1*rise,r6=100+6*rise,re=100+7*rise;
        double equal=r6-1e-8*re;
        for(int i=0;i<16&&limit(equal,r1,re)!=r6;i++)equal=limit(equal,r1,re)<r6?Math.nextUp(equal):Math.nextDown(equal);
        check(limit(equal,r1,re)==r6,"cannot construct exact padded equality: stop freeze");
        double below=equal,above=equal;
        for(int i=0;i<16&&!(limit(below,r1,re)<r6);i++)below=Math.nextDown(below);
        for(int i=0;i<16&&!(limit(above,r1,re)>r6);i++)above=Math.nextUp(above);
        check(limit(below,r1,re)<r6&&limit(above,r1,re)>r6,"padded threshold sides");
        try(var w=Files.newBufferedWriter(out.resolve("padded_threshold.csv"),StandardOpenOption.CREATE_NEW)){
            w.write("case,HB_hex,padded_limit_hex,R6_hex,expected_suffix_samples,actual_suffix_samples,status,stop_hex\n");
            String[] names={"below","equal","above","prefix_shade","prefix_unknown"};double[] heights={below,equal,above,below,below};
            for(int i=0;i<names.length;i++){
                double[][] values=Block16DirectionIntegrationChecks.flat();values[35][44]=heights[i];
                if(i==3)values[40][44]=heights[i];if(i==4)values[40][43]=-9999;
                var g=new AsciiGrid(96,96,0,0,1,-9999,values,ZeroPolicy.PRESERVE_AS_VALID);var search=new SearchConfig(12,1);
                var c=new SuffixBlockVisibility.Counters();
                var b=new Block16DirectionVisibilityNoStats(g,16).visibleAt(TimeStepBaseline.contract(),40,40,5,90,AngleReference.GRID_NORTH_CLOCKWISE,search);
                var s=new SuffixBlockVisibility(g,16).visibleAt(TimeStepBaseline.contract(),40,40,5,90,AngleReference.GRID_NORTH_CLOCKWISE,search,c);
                var n=new SuffixBlockVisibilityNoStats(g,16).visibleAt(TimeStepBaseline.contract(),40,40,5,90,AngleReference.GRID_NORTH_CLOCKWISE,search);
                var p=ControlledVisibility.visibleAt(g,TimeStepBaseline.contract(),40,40,5,90,AngleReference.GRID_NORTH_CLOCKWISE,search,null);
                for(var actual:new VisibilityResult[]{s,n,p})check(actual.status==b.status&&Double.doubleToLongBits(actual.searchedDistanceGridUnits)==Double.doubleToLongBits(b.searchedDistanceGridUnits),"behavior "+names[i]);
                long expected=i==0?2:i<3?1:0;
                check(c.suffixSamples==expected&&b.spatialSamples-s.spatialSamples==expected&&n.spatialSamples==s.spatialSamples,"actual suffix "+names[i]);
                check(i!=3||s.status==VisibilityStatus.SHADED,"first shade");check(i!=4||s.status==VisibilityStatus.UNKNOWN_RAY_INVALID,"first unknown");
                w.write(names[i]+","+Double.toHexString(heights[i])+","+Double.toHexString(limit(heights[i],r1,re))+","+Double.toHexString(r6)+","+expected+","+c.suffixSamples+","+s.status+","+Double.toHexString(s.searchedDistanceGridUnits)+"\n");
            }
        }
        Files.writeString(out.resolve("completion.json"),"{\"padded_threshold_cases\":5,\"equal_is_not_safe_at_R6\":true,\"algorithm_changed\":false}\n",StandardOpenOption.CREATE_NEW);
        System.out.println("PADDED_BOUNDARY_REVIEW_PASS cases=5 algorithm_unchanged");
    }
}
