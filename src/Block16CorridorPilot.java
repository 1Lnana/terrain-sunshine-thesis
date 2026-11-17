import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Offline support-node audit only. Never participates in visibility or time scheduling. */
public final class Block16CorridorPilot {
    static void check(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    static boolean bits(double a,double b){return Double.doubleToLongBits(a)==Double.doubleToLongBits(b);}
    static final class Support {
        final String branch;final int[][] nodes;
        Support(String branch,int[][] nodes){this.branch=branch;this.nodes=nodes;}
    }
    static Support support(double row,double col){
        int nr=(int)Math.rint(row),nc=(int)Math.rint(col);
        boolean er=Math.abs(row-nr)<1e-9,ec=Math.abs(col-nc)<1e-9;
        if(er&&ec)return new Support("POINT",new int[][]{{nr,nc}});
        int r=(int)Math.floor(row),c=(int)Math.floor(col);
        // B uses FLOOR here even when the near-integer EPS test used RINT.
        if(er)return new Support("ROW_LINEAR",new int[][]{{r,c},{r,c+1}});
        if(ec)return new Support("COL_LINEAR",new int[][]{{r,c},{r+1,c}});
        return new Support("BILINEAR",new int[][]{{r,c},{r+1,c},{r,c+1},{r+1,c+1}});
    }
    static String nodesText(int[][] nodes){StringJoiner s=new StringJoiner(";");for(int[] n:nodes)s.add(n[0]+":"+n[1]);return s.toString();}
    static double supportValue(AsciiGrid g,Support s,double row,double col){
        double[] v=new double[s.nodes.length];for(int i=0;i<v.length;i++)v[i]=g.elevation[s.nodes[i][0]][s.nodes[i][1]];
        if(v.length==1)return v[0];
        double fr=row-Math.floor(row),fc=col-Math.floor(col);
        if(s.branch.equals("ROW_LINEAR"))return v[0]*(1-fc)+v[1]*fc;
        if(s.branch.equals("COL_LINEAR"))return v[0]*(1-fr)+v[1]*fr;
        return v[0]*(1-fr)*(1-fc)+v[1]*fr*(1-fc)+v[2]*(1-fr)*fc+v[3]*fr*fc;
    }
    static final class Corridor {double maximum=Double.NEGATIVE_INFINITY;boolean valid=true;long nodes;}
    static Corridor corridor(AsciiGrid g,Block16UpperBoundDiagnostic.Segment segment,String id,BufferedWriter w)throws IOException{
        Corridor c=new Corridor();
        for(var ob:segment.observations){
            Support support=support(ob.row,ob.col);boolean valid=true;double local=Double.NEGATIVE_INFINITY;int slot=0;
            for(int[] n:support.nodes){
                boolean inside=g.inside(n[0],n[1]),good=inside&&g.valid[n[0]][n[1]];
                double height=inside?g.elevation[n[0]][n[1]]:Double.NaN;
                c.valid&=good;valid&=good;if(good){c.maximum=Math.max(c.maximum,height);local=Math.max(local,height);}c.nodes++;
                if(w!=null)w.write(id+","+ob.index+","+Double.toHexString(ob.row)+","+Double.toHexString(ob.col)+","+support.branch+","+(slot++)+","+n[0]+","+n[1]+","+Double.toHexString(height)+","+good+","+inside+","+Double.toHexString(ob.value)+"\n");
            }
            if(valid){
                check(ob.status==null,"valid supports but observation UNKNOWN");
                check(bits(supportValue(g,support,ob.row,ob.col),ob.value),"support interpolation differs from original B branch");
                check(ob.value<=local+segment.pad,"sample exceeds support upper bound with original pad");
            }
        }
        if(!c.valid)c.maximum=Double.NaN;
        if(c.valid&&segment.complete)check(c.maximum<=segment.maximum,"corridor support escaped original halo bound");
        return c;
    }
    static String classify(Block16UpperBoundDiagnostic.Segment s,Corridor c){
        if(s.decision==Block16UpperBoundDiagnostic.Decision.SAFETY)return "SAFETY";
        check(s.decision==Block16UpperBoundDiagnostic.Decision.BOUND_FAIL&&s.outcome==Block16UpperBoundDiagnostic.Outcome.CLEAR,"target must be BOUND_FAIL CLEAR");
        check(c.valid&&s.complete&&Double.isFinite(s.ray),"target lacks safe corridor");
        return c.maximum+s.pad<s.ray?"EXCLUDABLE_POSTHOC":"NOT_EXCLUDABLE";
    }
    static void manual(Path out)throws Exception{
        try(var w=Files.newBufferedWriter(out.resolve("manual_support.csv"),StandardOpenOption.CREATE_NEW)){
            w.write("case,row_hex,col_hex,expected_nodes,actual_nodes,branch\n");
            Object[][] cases={
                {"point",40.0,41.0,"40:41"},{"row_linear",40.0,41.25,"40:41;40:42"},
                {"col_linear",40.25,41.0,"40:41;41:41"},{"bilinear",40.25,41.75,"40:41;41:41;40:42;41:42"},
                {"eps_row_below",40-5e-10,41.25,"39:41;39:42"},{"eps_row_above",40+5e-10,41.25,"40:41;40:42"},
                {"eps_col_below",40.25,41-5e-10,"40:40;41:40"},{"eps_both",40-5e-10,41+5e-10,"40:41"},
                {"outside_eps",40-2e-9,41.25,"39:41;40:41;39:42;40:42"},
                {"halo",40.25,47.75,"40:47;41:47;40:48;41:48"},{"boundary",40.0,48.0,"40:48"}
            };
            for(Object[] x:cases){double r=(double)x[1],c=(double)x[2];Support s=support(r,c);String actual=nodesText(s.nodes);
                check(actual.equals(x[3]),"hand support "+x[0]);w.write(x[0]+","+Double.toHexString(r)+","+Double.toHexString(c)+","+x[3]+","+actual+","+s.branch+"\n");}
        }
        try(var w=Files.newBufferedWriter(out.resolve("manual_scenes.csv"),StandardOpenOption.CREATE_NEW)){
            w.write("case,status,expected_class,found_class,checked_clear_segments\n");
            for(String name:new String[]{"off_path_peak","tangent","off_path_nodata","halo_ridge","bilinear_peak","block_boundary"}){
                double[][] values=Block16DirectionIntegrationChecks.flat();int row=40,col=40;double bearing=90,spacing=1;
                if(name.equals("off_path_peak"))values[35][44]=140;
                if(name.equals("tangent"))values[40][41]=100+Math.tan(Math.toRadians(5));
                if(name.equals("off_path_nodata"))values[35][44]=-9999;
                if(name.equals("halo_ridge")){col=46;spacing=1.5;values[40][48]=140;}
                if(name.equals("bilinear_peak")){bearing=135;values[42][42]=140;}
                if(name.equals("block_boundary"))values[40][48]=140;
                var g=new AsciiGrid(96,96,0,0,1,-9999,values,ZeroPolicy.PRESERVE_AS_VALID);var search=new SearchConfig(12,spacing);
                var trace=new Block16UpperBoundDiagnostic.Trace();var dc=new BlockUpperBoundVisibility.Counters();var bc=new BlockUpperBoundVisibility.Counters();
                var d=new Block16UpperBoundDiagnostic(g,16).visibleAt(TimeStepBaseline.contract(),row,col,5,bearing,AngleReference.GRID_NORTH_CLOCKWISE,search,dc,trace);
                var b=new Block16DirectionVisibility(g,16).visibleAt(TimeStepBaseline.contract(),row,col,5,bearing,AngleReference.GRID_NORTH_CLOCKWISE,search,bc);
                check(d.status==b.status&&bits(d.searchedDistanceGridUnits,b.searchedDistanceGridUnits)&&d.spatialSamples==b.spatialSamples,"manual B pair");
                Set<String> found=new HashSet<>();int n=0;
                for(var s:trace.segments)if(s.decision!=Block16UpperBoundDiagnostic.Decision.SKIP&&s.outcome==Block16UpperBoundDiagnostic.Outcome.CLEAR){found.add(classify(s,corridor(g,s,name,null)));n++;}
                String expected=name.equals("off_path_peak")?"EXCLUDABLE_POSTHOC":name.equals("tangent")?"NOT_EXCLUDABLE":name.equals("off_path_nodata")?"SAFETY":"SHADED";
                check(expected.equals("SHADED")?d.status==VisibilityStatus.SHADED:found.contains(expected),"manual class "+name);
                w.write(name+","+d.status+","+expected+","+(expected.equals("SHADED")?"SHADED":expected)+","+n+"\n");
            }
        }
        System.out.println("CORRIDOR_MANUAL_PASS support_cases=11 scene_cases=6");
    }
    static int pos(List<String> h,String key){int n=h.indexOf(key);check(n>=0,"missing selection "+key);return n;}
    public static void main(String[] args)throws Exception{
        check(args.length==4&&args[0].equals("--small-pilot"),"explicit --small-pilot CONFIG SELECTION NEW_OUTPUT required");
        Path out=Path.of(args[3]).toAbsolutePath().normalize();check(out.startsWith(Path.of("user_runs").toAbsolutePath().normalize()),"user_runs child required");Files.createDirectory(out);
        try{
            manual(out); // A-specific node and classification checks BEFORE any real DEM replay.
            Map<Long,List<String[]>> groups=new LinkedHashMap<>();List<String> header;
            try(var r=Files.newBufferedReader(Path.of(args[2]))){header=List.of(r.readLine().split(","));String line;int rows=0;
                while((line=r.readLine())!=null){String[] v=line.split(",",-1);check(v.length==header.size(),"selection schema");groups.computeIfAbsent(Long.parseLong(v[pos(header,"ray_id")]),k->new ArrayList<>()).add(v);rows++;}
                check(rows>0&&rows<=342&&groups.size()<=342,"small pilot hard limit");}
            var in=new ProjectedBaselineExperiment.Inputs(Path.of(args[1]));
            check(in.search.maxSamples==200&&in.search.sampleSpacingGridUnits==50&&in.grid.rows==356&&in.grid.cols==356
                    &&in.grid.cellSize==90&&in.methods.equals(List.of("binarylocal_30min")),"frozen old binary workload");
            var bIndex=new Block16DirectionVisibility(in.grid,16);var dIndex=new Block16UpperBoundDiagnostic(in.grid,16);
            long selected=0,rayCount=0;long[][][] stats=new long[6][3][2];
            try(var sw=Files.newBufferedWriter(out.resolve("segments.csv"),StandardOpenOption.CREATE_NEW);
                    var nw=Files.newBufferedWriter(out.resolve("support_nodes.csv"),StandardOpenOption.CREATE_NEW);
                    var rw=Files.newBufferedWriter(out.resolve("replayed_rays.csv"),StandardOpenOption.CREATE_NEW)){
                sw.write("sample_id,domain,bin,ray_id,segment_sequence,local_ordinal,start,end,block_row,block_col,outcome,safety_reason,complete,HB_hex,HC_hex,entry_ray_hex,pad_hex,actual_calls,support_node_visits,classification,N_h,n_h,pi\n");
                nw.write("sample_id,k,row_hex,col_hex,branch,node_slot,node_row,node_col,height_hex,node_valid,inside,interpolated_hex\n");
                rw.write("ray_id,status,stop_hex,stop_index,interpolations,block_tests,skipped_samples,bound_clear_segments,safety_segments,b_match\n");
                for(var entry:groups.entrySet()){
                    var requests=entry.getValue();String[] v=requests.get(0);int row=Integer.parseInt(v[pos(header,"row")]),col=Integer.parseInt(v[pos(header,"col")]);
                    double a=Double.parseDouble(v[pos(header,"altitude_hex")]),bearing=Double.parseDouble(v[pos(header,"bearing_hex")]);
                    var bc=new BlockUpperBoundVisibility.Counters();var dc=new BlockUpperBoundVisibility.Counters();var trace=new Block16UpperBoundDiagnostic.Trace();
                    var b=bIndex.visibleAt(in.contract,row,col,a,bearing,AngleReference.GRID_NORTH_CLOCKWISE,in.search,bc);
                    var d=dIndex.visibleAt(in.contract,row,col,a,bearing,AngleReference.GRID_NORTH_CLOCKWISE,in.search,dc,trace);
                    check(b.status==d.status&&bits(b.searchedDistanceGridUnits,d.searchedDistanceGridUnits)&&b.spatialSamples==d.spatialSamples
                            &&bc.blockTests==dc.blockTests&&bc.skippedSamples==dc.skippedSamples&&bc.spatialSamples==dc.spatialSamples,"B replay pair");
                    check(d.status.toString().equals(v[pos(header,"diag_status")])&&bits(d.searchedDistanceGridUnits,Double.parseDouble(v[pos(header,"stop_hex")]))
                            &&d.spatialSamples==Long.parseLong(v[pos(header,"interpolations")])&&dc.blockTests==Long.parseLong(v[pos(header,"block_tests")])
                            &&dc.skippedSamples==Long.parseLong(v[pos(header,"skipped_samples")]),"frozen B ray behavior");
                    Block16UpperBoundRealDiagnostic.observe(trace,d,dc,in.search);
                    int clear=0,safety=0,sequence=0;Set<String> matched=new HashSet<>();
                    for(var s:trace.segments){
                        sequence++;String domain=null;int ordinal=-1;
                        if(s.decision==Block16UpperBoundDiagnostic.Decision.BOUND_FAIL&&s.outcome==Block16UpperBoundDiagnostic.Outcome.CLEAR){domain="BOUND_CLEAR";ordinal=clear++;}
                        else if(s.decision==Block16UpperBoundDiagnostic.Decision.SAFETY){domain="SAFETY";ordinal=safety++;}
                        if(domain==null)continue;
                        for(String[] request:requests)if(request[pos(header,"domain")].equals(domain)&&Integer.parseInt(request[pos(header,"local_ordinal")])==ordinal){
                            String id=request[pos(header,"sample_id")];check(matched.add(id),"duplicate selected segment");
                            Corridor corridor=corridor(in.grid,s,id,nw);String cls=classify(s,corridor);int bin=Integer.parseInt(request[pos(header,"bin")]);
                            int ci=cls.equals("EXCLUDABLE_POSTHOC")?0:cls.equals("NOT_EXCLUDABLE")?1:2;
                            stats[bin][ci][0]++;stats[bin][ci][1]+=s.observations.size();selected++;
                            sw.write(id+","+domain+","+bin+","+entry.getKey()+","+sequence+","+ordinal+","+s.start+","+s.end+","+s.blockRow+","+s.blockCol+","+s.outcome+","+s.safety+","+s.complete+","+Double.toHexString(s.maximum)+","+Double.toHexString(corridor.maximum)+","+Double.toHexString(s.ray)+","+Double.toHexString(s.pad)+","+s.observations.size()+","+corridor.nodes+","+cls+","+request[pos(header,"N_h")]+","+request[pos(header,"n_h")]+","+request[pos(header,"pi")]+"\n");
                        }
                    }
                    check(matched.size()==requests.size(),"selected ordinal missing");
                    check(clear==Integer.parseInt(v[pos(header,"bound_clear_segments")])&&safety==Integer.parseInt(v[pos(header,"safety_segments")]),"parent domain counts differ");
                    rw.write(entry.getKey()+","+d.status+","+Double.toHexString(d.searchedDistanceGridUnits)+","+trace.stopIndex+","+d.spatialSamples+","+dc.blockTests+","+dc.skippedSamples+","+clear+","+safety+",true\n");rayCount++;
                }
            }
            try(var w=Files.newBufferedWriter(out.resolve("classified_counts.csv"),StandardOpenOption.CREATE_NEW)){
                w.write("bin,classification,selected_segments,actual_calls\n");for(int bin=0;bin<6;bin++)for(int c=0;c<3;c++)w.write(bin+","+new String[]{"EXCLUDABLE_POSTHOC","NOT_EXCLUDABLE","SAFETY"}[c]+","+stats[bin][c][0]+","+stats[bin][c][1]+"\n");}
            Files.writeString(out.resolve("completion.json"),"{\"status\":\"SMALL_PILOT_REPLAY_PASS\",\"selected_segments\":"+selected+",\"replayed_rays\":"+rayCount+",\"b_mismatches\":0,\"time_schedule_invoked\":false,\"full_roi_diagnostic\":false,\"performance_measured\":false}\n",StandardOpenOption.CREATE_NEW);
            System.out.println("CORRIDOR_PILOT_PASS selected_segments="+selected+" replayed_rays="+rayCount+" no_time_schedule no_full_roi no_speed_claim");
        }catch(Exception|AssertionError e){try(var w=new PrintWriter(Files.newBufferedWriter(out.resolve("failure.txt"),StandardOpenOption.CREATE_NEW))){e.printStackTrace(w);}throw e;}
    }
}
