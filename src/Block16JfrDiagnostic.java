import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import jdk.jfr.*;

/** Diagnostic orchestration only. All calculation delegates to the frozen run(). */
public final class Block16JfrDiagnostic {
    @Name("thesis.Block16Iteration") @Label("Block16 core calculation")
    @StackTrace(false)
    static final class Iteration extends Event {
        int round;
        double buildSeconds, querySeconds;
    }
    private static String digest(ProjectedBaselineExperiment.Inputs in,
            ProjectedBaselineExperiment.Run run) throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        ByteBuffer b=ByteBuffer.allocate(16); b.putInt(run.pixels.size());md.update(b.array(),0,4);
        for(int i=0;i<run.pixels.size();i++) {
            var receiver=in.receivers.get(i);var pixel=run.pixels.get(i);
            if(!Double.isFinite(pixel.psd))throw new IllegalStateException("Nonfinite PSD");
            b.clear();b.putInt(receiver.row).putInt(receiver.col).putDouble(pixel.psd);md.update(b.array());
        }
        return HexFormat.of().formatHex(md.digest());
    }
    public static void main(String[] args) throws Exception {
        if(args.length==2 && args[0].equals("--probe")) {
            try(Recording r=new Recording(Configuration.getConfiguration("profile"))) {
                r.enable("thesis.Block16Iteration");r.start();
                Iteration e=new Iteration();e.begin();
                long until=System.nanoTime()+1_000_000_000L;double value=0;
                while(System.nanoTime()<until)value+=Math.sin(value+1);
                e.end();e.commit();r.stop();r.dump(Path.of(args[1]));
                if(Files.size(Path.of(args[1]))==0)throw new IllegalStateException("JFR probe empty");
                System.out.println("JFR_PROBE_OK bytes="+Files.size(Path.of(args[1]))+" value="+value);
            }
            return;
        }
        if(args.length!=2)throw new IllegalArgumentException("historical config NEW output directory");
        Path out=Path.of(args[1]).toAbsolutePath().normalize();
        Path allowed=Path.of("user_runs").toAbsolutePath().normalize();
        if(!out.startsWith(allowed)||out.equals(allowed))throw new IllegalArgumentException("user_runs child required");
        Files.createDirectory(out);
        var in=new ProjectedBaselineExperiment.Inputs(Path.of(args[0]));
        if(!in.p.getProperty("date").equals("2023-12-21")||in.receivers.size()!=16384
                ||in.grid.cellSize!=90||in.search.maxSamples!=200||in.search.sampleSpacingGridUnits!=50)
            throw new IllegalArgumentException("Historical Qinling winter contract required");
        String expected="bd611e2e8057cf68500de1dfcd6c3b907d1be539b22fc5c5a237f47e14a01fd5";
        List<String> rows=new ArrayList<>();
        rows.add("phase,round,build_sec,query_sec,compute_sec,rays,interpolations,block_tests,psd_sha256");
        for(int i=1;i<=5;i++) {
            var run=ProjectedBaselineExperiment.run(in,"block16binarylocal_30min");
            String hash=digest(in,run);if(!hash.equals(expected))throw new IllegalStateException("Warmup PSD mismatch");
            rows.add(row("warmup",i,run,hash));System.out.println("WARMUP_DONE "+i);
        }
        long accumulated=0;int rounds=0;
        try(Recording recording=new Recording(Configuration.getConfiguration("profile"))) {
            recording.setName("Qinling128 block16 diagnostic");recording.setToDisk(true);
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(2)).withStackTrace();
            recording.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(2)).withStackTrace();
            recording.enable("thesis.Block16Iteration").withThreshold(Duration.ZERO).withoutStackTrace();
            recording.start();
            while(accumulated<60_000_000_000L) {
                Iteration event=new Iteration();event.round=++rounds;event.begin();
                var run=ProjectedBaselineExperiment.run(in,"block16binarylocal_30min");
                event.buildSeconds=run.preprocessSeconds;event.querySeconds=run.querySeconds;
                event.end();event.commit();
                accumulated+=Math.round(run.computeSeconds*1e9);
                String hash=digest(in,run);if(!hash.equals(expected))throw new IllegalStateException("Profile PSD mismatch round "+rounds);
                if(run.stats.rayCalls!=862525||run.stats.spatialSamples!=17275977||run.spatialBlockTests!=10665319)
                    throw new IllegalStateException("Historical counts differ");
                rows.add(row("profile",rounds,run,hash));
            }
            recording.stop();recording.dump(out.resolve("block16_cpu.jfr"));
            if(Files.size(out.resolve("block16_cpu.jfr"))==0)
                throw new IllegalStateException("Empty JFR; profiling did not succeed");
        }
        Files.write(out.resolve("runs.csv"),rows,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        Files.writeString(out.resolve("completion.json"),"{\"warmups\":5,\"profile_rounds\":"+rounds
                +",\"core_compute_seconds\":"+accumulated/1e9+",\"all_psd_match_frozen\":true,\"psd_sha256\":\""+expected
                +"\",\"profile_is_formal_benchmark\":false}",StandardOpenOption.CREATE_NEW);
        System.out.println("PROFILE_COMPLETE rounds="+rounds+" core_seconds="+accumulated/1e9);
    }
    private static String row(String phase,int round,ProjectedBaselineExperiment.Run r,String hash) {
        return String.format(Locale.ROOT,"%s,%d,%.9f,%.9f,%.9f,%d,%d,%d,%s",phase,round,
                r.preprocessSeconds,r.querySeconds,r.computeSeconds,r.stats.rayCalls,r.stats.spatialSamples,r.spatialBlockTests,hash);
    }
}
