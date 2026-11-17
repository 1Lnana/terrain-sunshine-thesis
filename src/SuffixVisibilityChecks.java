import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Artificial regression reuses the frozen B synthetic scene generator verbatim. */
public final class SuffixVisibilityChecks {
    static long syntheticRays;static long suffixSamples;static long extraTests;
    static final Set<VisibilityStatus> statuses=EnumSet.noneOf(VisibilityStatus.class);
    static double[][] flat(){return Block16DirectionIntegrationChecks.flat();}
    static void check(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    static void scene(BufferedWriter w,String name,double[][] v,int r,int c,double altitude,double bearing,
            SearchConfig search,VisibilityStatus expected,int expectedIndex)throws IOException{
        var g=new AsciiGrid(v.length,v[0].length,0,0,1,-9999,v,ZeroPolicy.PRESERVE_AS_VALID);
        compare(w,name,g,r,c,altitude,bearing,search,expected,expectedIndex);
    }
    static void compare(BufferedWriter w,String name,AsciiGrid g,int r,int c,double altitude,double bearing,
            SearchConfig search,VisibilityStatus expected,int expectedIndex)throws IOException{
        var bc=new BlockUpperBoundVisibility.Counters();var sc=new SuffixBlockVisibility.Counters();
        var b=new Block16DirectionVisibility(g,16).visibleAt(TimeStepBaseline.contract(),r,c,altitude,bearing,AngleReference.GRID_NORTH_CLOCKWISE,search,bc);
        var n=new Block16DirectionVisibilityNoStats(g,16).visibleAt(TimeStepBaseline.contract(),r,c,altitude,bearing,AngleReference.GRID_NORTH_CLOCKWISE,search);
        var s=new SuffixBlockVisibility(g,16).visibleAt(TimeStepBaseline.contract(),r,c,altitude,bearing,AngleReference.GRID_NORTH_CLOCKWISE,search,sc);
        var sn=new SuffixBlockVisibilityNoStats(g,16).visibleAt(TimeStepBaseline.contract(),r,c,altitude,bearing,AngleReference.GRID_NORTH_CLOCKWISE,search);
        var point=ControlledVisibility.visibleAt(g,TimeStepBaseline.contract(),r,c,altitude,bearing,AngleReference.GRID_NORTH_CLOCKWISE,search,null);
        for(var actual:new VisibilityResult[]{n,s,sn,point})check(actual.status==b.status&&Double.doubleToLongBits(actual.searchedDistanceGridUnits)==Double.doubleToLongBits(b.searchedDistanceGridUnits),name+" state/stop");
        check(n.spatialSamples==b.spatialSamples&&sn.spatialSamples==s.spatialSamples,name+" stats/nostats");
        check(sc.blockTests==bc.blockTests&&sc.spatialSamples==s.spatialSamples&&bc.spatialSamples-sc.spatialSamples==sc.suffixSamples
                &&bc.skippedSamples+sc.suffixSamples==sc.skippedSamples&&sc.spatialSamples+sc.skippedSamples==point.spatialSamples,name+" sample conservation");
        if(expected!=null)check(b.status==expected&&Double.doubleToLongBits(b.searchedDistanceGridUnits)==Double.doubleToLongBits(expectedIndex*search.sampleSpacingGridUnits),name+" expected");
        if(name.equals("suffix_active"))check(sc.suffixSamples>0,"suffix must activate");
        if(name.equals("prefix_shade"))check(s.status==VisibilityStatus.SHADED&&sc.suffixSamples==0&&sc.plannedSuffixes>0,"planned suffix must not hide prefix shade");
        if(name.equals("prefix_nodata"))check(s.status==VisibilityStatus.UNKNOWN_RAY_INVALID&&sc.suffixSamples==0,"incomplete disables suffix");
        syntheticRays++;statuses.add(b.status);suffixSamples+=sc.suffixSamples;extraTests+=sc.endTests+sc.boundaryTests;
        w.write(name+","+b.status+","+b.searchedDistanceGridUnits+","+b.spatialSamples+","+s.spatialSamples+","+sc.suffixSamples+","+sc.eligibilityChecks+","+sc.endTests+","+sc.boundaryTests+"\n");
    }
    static void synthetic(Path out) throws IOException {
        try(var w=Files.newBufferedWriter(out.resolve("synthetic.csv"),StandardOpenOption.CREATE_NEW)) {
            w.write("case,status,stop_distance,b_interpolations,s_interpolations,suffix_samples,eligibility_checks,end_tests,boundary_tests\n");
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

    public static void main(String[] args)throws Exception{
        check(args.length==2&&args[0].equals("--artificial-only"),"explicit artificial gate");Path out=Path.of(args[1]);Files.createDirectory(out);
        synthetic(out);
        try(var w=Files.newBufferedWriter(out.resolve("suffix_specific.csv"),StandardOpenOption.CREATE_NEW)){
            w.write("case,status,stop_distance,b_interpolations,s_interpolations,suffix_samples,eligibility_checks,end_tests,boundary_tests\n");
            var v=flat();v[35][44]=100.5;scene(w,"suffix_active",v,40,40,5,90,new SearchConfig(12,1),VisibilityStatus.VISIBLE,12);
            v=flat();v[35][44]=100.5;v[40][43]=100.5;scene(w,"prefix_shade",v,40,40,5,90,new SearchConfig(12,1),VisibilityStatus.SHADED,3);
            v=flat();v[35][44]=100.5;v[40][43]=-9999;scene(w,"prefix_nodata",v,40,40,5,90,new SearchConfig(12,1),VisibilityStatus.UNKNOWN_RAY_INVALID,3);
            for(double h:new double[]{100+6*Math.tan(Math.toRadians(5)),Math.nextDown(100+6*Math.tan(Math.toRadians(5))),Math.nextUp(100+6*Math.tan(Math.toRadians(5)))}){
                v=flat();v[35][44]=h;scene(w,"threshold_"+Double.toHexString(h),v,40,40,5,90,new SearchConfig(12,1),VisibilityStatus.VISIBLE,12);
            }
            v=flat();for(double[] row:v)Arrays.fill(row,-200);v[35][44]=-199.5;scene(w,"negative_suffix",v,40,40,5,90,new SearchConfig(12,1),VisibilityStatus.VISIBLE,12);
            for(double bearing:new double[]{0,1e-9,45,89.999999999,90,135,180,270,359.999999999})for(double a:new double[]{1e-7,5,60,89.999999}){
                v=flat();v[35][44]=100.5;scene(w,"edge_"+a+"_"+bearing,v,40,40,a,bearing,new SearchConfig(40,.5),null,0);
            }
            v=flat();for(double[] row:v)Arrays.fill(row,1e308);
            var extreme=new AsciiGrid(96,96,0,0,1e308,-9999,v,ZeroPolicy.PRESERVE_AS_VALID);
            compare(w,"nonfinite_ray",extreme,40,40,60,90,new SearchConfig(2,1e308),null,0);
        }
        TimeStructureSearch.syntheticChecks();
        check(suffixSamples>0&&statuses.size()==5,"suffix coverage and five states");
        Files.writeString(out.resolve("completed.json"),"{\"rays\":"+syntheticRays+",\"suffix_samples\":"+suffixSamples+",\"extra_height_tests\":"+extraTests+",\"state_stop_mismatches\":0,\"temporal_synthetic_checks\":true}\n",StandardOpenOption.CREATE_NEW);
        System.out.println("SUFFIX_ARTIFICIAL_PASS rays="+syntheticRays+" suffix_samples="+suffixSamples+" mismatches=0");
    }
}
