import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Versioned engineering runner. No solar, temporal or interpolation algorithm is copied. */
public final class Block16EngineeringBaselineExperiment {
    enum Method {
        A_STATS(true), B_STATS(true), A_NOSTATS(false), B_NOSTATS(false);
        final boolean stats;
        Method(boolean stats) { this.stats=stats; }
    }
    static final class Prepared {
        final ProjectedBaselineExperiment.SpatialQuery query;final long payload;
        Prepared(ProjectedBaselineExperiment.SpatialQuery query,long payload) {this.query=query;this.payload=payload;}
    }
    static final class Trial {
        final List<ProjectedBaselineExperiment.Pixel> pixels=new ArrayList<>();
        final BlockUpperBoundVisibility.Counters counts=new BlockUpperBoundVisibility.Counters();
        final RunStatistics rays=new RunStatistics();
        long build,query,compute,payload,calls;
    }
    static void require(boolean ok,String message) {if(!ok)throw new IllegalStateException(message);}
    static boolean bits(double a,double b) {return Double.doubleToLongBits(a)==Double.doubleToLongBits(b);}
    static Prepared prepare(AsciiGrid grid,Method method) {
        switch(method) {
            case A_STATS: {var index=new BlockUpperBoundVisibility(grid,16);return new Prepared(index::visibleAt,index.payloadBytes());}
            case B_STATS: {var index=new Block16DirectionVisibility(grid,16);return new Prepared(index::visibleAt,index.payloadBytes());}
            case A_NOSTATS: {
                var index=new BlockUpperBoundVisibilityNoStats(grid,16);
                return new Prepared((c,r,k,a,b,n,s,counts)->index.visibleAt(c,r,k,a,b,n,s),index.payloadBytes());
            }
            default: {
                var index=new Block16DirectionVisibilityNoStats(grid,16);
                return new Prepared((c,r,k,a,b,n,s,counts)->index.visibleAt(c,r,k,a,b,n,s),index.payloadBytes());
            }
        }
    }
    static ProjectedBaselineExperiment.Pixel pixel(ProjectedBaselineExperiment.Inputs in,
            ProjectedBaselineExperiment.Receiver r,Method method,Prepared prepared,Trial trial) {
        return ProjectedBaselineExperiment.calculateWithSpatialQuery(in,r,"binarylocal_30min",
                method.stats?trial.rays:null,prepared.query,method.stats?trial.counts:null,method.stats);
    }
    static Trial trial(ProjectedBaselineExperiment.Inputs in,Method method) {
        Trial trial=new Trial();
        long start=System.nanoTime();Prepared prepared=prepare(in.grid,method);long ready=System.nanoTime();
        for(var receiver:in.receivers) {
            var p=pixel(in,receiver,method,prepared,trial);trial.pixels.add(p);trial.calls+=p.samples;
        }
        long finish=System.nanoTime();trial.build=ready-start;trial.query=finish-ready;trial.compute=finish-start;
        trial.payload=prepared.payload;return trial;
    }
    static String digest(ProjectedBaselineExperiment.Inputs in,Trial trial) throws Exception {
        require(trial.pixels.size()==in.receivers.size(),"pixel count mismatch");
        MessageDigest md=MessageDigest.getInstance("SHA-256");ByteBuffer b=ByteBuffer.allocate(16);
        b.putInt(trial.pixels.size());md.update(b.array(),0,4);
        for(int i=0;i<trial.pixels.size();i++) {
            var r=in.receivers.get(i);double psd=trial.pixels.get(i).psd;require(Double.isFinite(psd),"nonfinite PSD");
            b.clear();b.putInt(r.row).putInt(r.col).putDouble(psd);md.update(b.array());
        }
        return HexFormat.of().formatHex(md.digest());
    }
    static void writePixels(ProjectedBaselineExperiment.Inputs in,Trial trial,Path path) throws IOException {
        try(var w=Files.newBufferedWriter(path,StandardOpenOption.CREATE_NEW)) {
            w.write("row,col,day_hex,psd_hex,query_count,fallback_reason,refinement_queries,guard_queries\n");
            for(int i=0;i<trial.pixels.size();i++) {
                var r=in.receivers.get(i);var p=trial.pixels.get(i);
                w.write(r.row+","+r.col+","+Double.toHexString(p.day)+","+Double.toHexString(p.psd)+","+p.samples+","+p.reason+","+p.refinementQueries+","+p.guardQueries+"\n");
            }
        }
    }
    static void validateConfig(ProjectedBaselineExperiment.Inputs in) {
        require(in.methods.equals(List.of("binarylocal_30min")),"this engineering runner supports binarylocal_30min only");
        require("block16_direction_v1".equals(in.p.getProperty("spatial_implementation")),"unknown spatial implementation");
        require("paired_stats_and_nostats".equals(in.p.getProperty("statistics")),"unknown statistics policy");
        require(in.warmups==5 && in.repeats==10,"5 warmups and 10 balanced pairs required");
        require(in.p.getProperty("engineering_variants").equals("A_STATS,B_STATS,A_NOSTATS,B_NOSTATS"),"unknown variant");
    }
    static void benchmark(ProjectedBaselineExperiment.Inputs in,Path out,String expected) throws Exception {
        try(var w=Files.newBufferedWriter(out.resolve("runs.csv"),StandardOpenOption.CREATE_NEW)) {
            w.write("group,phase,round,position,method,statistics_enabled,per_ray_timing_enabled,build_sec,query_sec,compute_sec,csv_write_sec,rays,block_tests,skipped_samples,interpolations,psd_sha256,index_payload_bytes\n");
            for(boolean stats:new boolean[]{true,false}) {
                Method a=stats?Method.A_STATS:Method.A_NOSTATS,b=stats?Method.B_STATS:Method.B_NOSTATS;
                for(int pass=0;pass<in.warmups+in.repeats;pass++) {
                    boolean formal=pass>=in.warmups;int round=formal?pass-in.warmups+1:pass+1;
                    Method[] order=round%2==1?new Method[]{a,b}:new Method[]{b,a};
                    for(int position=0;position<2;position++) {
                        Method method=order[position];Trial trial=trial(in,method);String hash=digest(in,trial);
                        require(expected.equals(hash),"benchmark digest mismatch: "+method+" round="+round);
                        require(trial.calls==862525,"benchmark queries differ");
                        if(stats)require(trial.rays.rayCalls==trial.calls && trial.counts.blockTests==10665319
                                && trial.counts.skippedSamples==93217471 && trial.counts.spatialSamples==17275977
                                && trial.rays.spatialSamples==trial.counts.spatialSamples,"stats counters differ");
                        // Output and digest are strictly outside build/query computation.
                        long writeStart=System.nanoTime();
                        if(formal && round==1)writePixels(in,trial,out.resolve(method+"_pixels.csv"));
                        long writeElapsed=System.nanoTime()-writeStart;
                        w.write((stats?"STATS":"NOSTATS")+","+(formal?"formal":"warmup")+","+round+","+(position+1)+","+method+","+stats+","+stats
                                +","+trial.build/1e9+","+trial.query/1e9+","+trial.compute/1e9+","+writeElapsed/1e9+","+trial.calls
                                +","+(stats?trial.counts.blockTests:"null")+","+(stats?trial.counts.skippedSamples:"null")
                                +","+(stats?trial.counts.spatialSamples:"null")+","+hash+","+trial.payload+"\n");w.flush();
                        System.out.println("TRIAL group="+(stats?"STATS":"NOSTATS")+" phase="+(formal?"formal":"warmup")
                                +" round="+round+" method="+method+" compute_sec="+trial.compute/1e9);
                    }
                }
            }
        }
    }
    public static void main(String[] args) throws Exception {
        require(args.length==2,"config and NEW user_runs output required");
        Path out=Path.of(args[1]).toAbsolutePath().normalize(),allowed=Path.of("user_runs").toAbsolutePath().normalize();
        require(out.startsWith(allowed) && !out.equals(allowed),"user_runs child required");Files.createDirectory(out);
        try {
            var in=new ProjectedBaselineExperiment.Inputs(Path.of(args[0]));validateConfig(in);
            Files.copy(in.config,out.resolve("effective.properties"));
            Files.writeString(out.resolve("environment.txt"),"java="+System.getProperty("java.version")+"\nvm="+System.getProperty("java.vm.name")
                    +"\nflags="+ManagementFactory.getRuntimeMXBean().getInputArguments()+"\nos="+System.getProperty("os.name")
                    +"\nclasses="+Block16EngineeringBaselineExperiment.class.getProtectionDomain().getCodeSource().getLocation()
                    +"\ntemporal_method=binarylocal_30min\nspatial_algorithm=block16_upper_bound\nimplementation_B=block16_direction_v1\nblock_size=16\n",StandardOpenOption.CREATE_NEW);
            String expected=Block16DirectionIntegrationChecks.verify(in,out);
            benchmark(in,out,expected);
            // Control uses a test-only namespace copy of pre-integration calculate; no old main is executed.
            Block16DirectionIntegrationChecks.adapterBenchmark(in,out,expected);
            Files.writeString(out.resolve("completed.txt"),"Correctness and all paired performance groups completed.\n",StandardOpenOption.CREATE_NEW);
        } catch(Exception|AssertionError e) {
            try(var w=new PrintWriter(Files.newBufferedWriter(out.resolve("failure.txt"),StandardOpenOption.CREATE_NEW))) {e.printStackTrace(w);}
            throw e;
        }
    }
}
