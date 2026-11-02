import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Artificial checks plus four selected terrain pixels; not a timing experiment. */
public final class DarkCoarseGuardChecks {
    static int checks;
    static final Set<String> POINTS=Set.of("136,176","189,213","162,226","162,228");
    static final Set<String> LEAKED_LIGHT=Set.of("136,176","189,213");
    static void require(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static boolean same(double a,double b){return Double.isNaN(a)?Double.isNaN(b):Double.isFinite(b)&&Math.abs(a-b)<1e-8;}

    static AdaptiveTimeV2Local.Result calculate(double day,AdaptiveTimeV1.Oracle oracle,boolean combined,boolean guard) {
        if(combined)return guard?SolarAdaptiveSampling.adaptiveWithDarkGuard(day,oracle,t->40).calculation
                :SolarAdaptiveSampling.adaptive(day,oracle,t->40,true).calculation;
        return guard?AdaptiveTimeV2Local.adaptiveWithDarkGuard(day,30,oracle):AdaptiveTimeV2Local.adaptive(day,30,oracle);
    }
    static void synthetic(String label,double day,AdaptiveTimeV1.Oracle oracle,boolean combined,boolean triggers) {
        AtomicInteger calls=new AtomicInteger();
        AdaptiveTimeV2Local.Result guarded=calculate(day,t->{calls.incrementAndGet();return oracle.at(t);},combined,true);
        AdaptiveTimeV2Local.Result old=calculate(day,oracle,combined,false);
        AdaptiveTimeV1.Result fine=AdaptiveTimeV1.fixed(day,1,oracle);
        require(same(guarded.psd,fine.psd),label+" fixed1 parity");
        require(guarded.fallbackReason.equals(triggers?"NO_VISIBLE_COARSE":"NONE"),label+" reason");
        require(calls.get()==guarded.evaluated.size(),label+" unique actual queries");
        if(triggers) {
            require(guarded.integrationNodes.equals(fine.integrationNodes),label+" integrate minute nodes only");
            require(guarded.guardQueries>0,label+" additional guard queries counted");
        } else {
            require(guarded.evaluated.equals(old.evaluated)&&guarded.integrationNodes.equals(old.integrationNodes),label+" untriggered behavior unchanged");
        }
        System.out.printf("SYNTHETIC %s mode=%s before=%s after=%s fixed1=%s queries=%d guard_queries=%d reason=%s%n",
                label,combined?"combined":"local",old.psd,guarded.psd,fine.psd,calls.get(),guarded.guardQueries,guarded.fallbackReason);
    }

    static Map<String,String[]> previous(String method) throws IOException {
        Map<String,String[]> result=new HashMap<>();
        Path file=Path.of("user_runs/qinling_winter_001",method+"_r1","psd.csv");
        try(BufferedReader reader=Files.newBufferedReader(file)) {
            reader.readLine();String line;
            while((line=reader.readLine())!=null){String[] p=line.split(",",-1);String key=p[0]+","+p[1];if(POINTS.contains(key))result.put(key,p);}
        }
        if(result.size()!=POINTS.size())throw new IllegalArgumentException("previous pixels missing");
        return result;
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if(args.length!=2)throw new IllegalArgumentException("winter-config new-output-directory");
        for(boolean combined:new boolean[]{false,true}) {
            synthetic("hidden_light",60,t->t>=31&&t<40?1:0,combined,true);
            synthetic("all_dark",120,t->0,combined,true);
            synthetic("all_light",120,t->1,combined,false);
            synthetic("single_window",120,t->t>=13&&t<97?1:0,combined,false);
            synthetic("fractional_dark",116.5,t->0,combined,true);
            synthetic("unknown_coarse",60,t->t==0?-1:0,combined,false);
            synthetic("unknown_fine",60,t->t==10?-1:0,combined,true);
            AdaptiveTimeV1.Oracle hidden=t->t>=3&&t<5?0:1;
            AdaptiveTimeV2Local.Result limitation=calculate(120,hidden,combined,true);
            require(limitation.psd==120&&AdaptiveTimeV1.fixed(120,1,hidden).psd==118,"hidden shade limitation retained");
            System.out.println("KNOWN_LIMITATION mode="+(combined?"combined":"local")+" hidden_shade_PSD=120 fixed1=118");
        }
        System.out.println("SYNTHETIC_CHECKS_PASS checks="+checks);

        ProjectedBaselineExperiment.Inputs in=new ProjectedBaselineExperiment.Inputs(Path.of(args[0]));
        if(!in.p.getProperty("date").equals("2023-12-21"))throw new IllegalArgumentException("winter date required");
        in.receivers.removeIf(receiver->!POINTS.contains(receiver.key()));
        if(in.receivers.size()!=POINTS.size())throw new IllegalArgumentException("four selected pixels required");
        in.traces.clear();in.traces.addAll(POINTS);
        in.methods=List.of("fixed_1min","local_30min","guardlocal_30min","combined_30min","guardcombined_30min");
        Path output=Path.of(args[1]).toAbsolutePath().normalize(),allowed=Path.of("user_runs").toAbsolutePath().normalize();
        if(!output.startsWith(allowed)||output.equals(allowed))throw new IllegalArgumentException("new user_runs directory required");
        Files.createDirectories(output.getParent());Files.createDirectory(output);
        Files.copy(in.config,output.resolve("config.properties"));
        Files.writeString(output.resolve("scope.txt"),"four pixels; five methods; one pass; not a timing benchmark\n"
                +"pixels=136,176;162,226;162,228;189,213\nrestored NO_VISIBLE_COARSE only; hidden shade not fixed\n");
        ProjectedBaselineExperiment.Run reference=null;
        System.out.println("TERRAIN_CHECK_READY pixels=4 methods=5 single_pass_not_timing_benchmark");
        for(String method:in.methods) {
            boolean guard=method.startsWith("guard");
            String base=method.equals("guardlocal_30min")?"local_30min":method.equals("guardcombined_30min")?"combined_30min":method;
            Map<String,String[]> old=previous(base);
            ProjectedBaselineExperiment.Run run=ProjectedBaselineExperiment.run(in,method);
            require(run.valid==POINTS.size()&&run.stats.unknownResults==0,"terrain valid pixels");
            if(reference==null)reference=run;
            for(int i=0;i<in.receivers.size();i++) {
                String key=in.receivers.get(i).key();ProjectedBaselineExperiment.Pixel pixel=run.pixels.get(i);
                double before=Double.parseDouble(old.get(key)[6]),fine=reference.pixels.get(i).psd;
                boolean restored=guard&&LEAKED_LIGHT.contains(key);
                require(same(pixel.psd,restored?fine:before),"terrain expected PSD "+method+" "+key);
                require(pixel.reason.equals(restored?"NO_VISIBLE_COARSE":"NONE"),"terrain expected guard reason "+key);
                if(!restored)require(pixel.samples==Integer.parseInt(old.get(key)[7]),"unchanged pixel query count "+key);
                System.out.printf("TERRAIN pixel=%s method=%s before=%.3f after=%.3f fixed1=%.3f queries=%d guard_queries=%d reason=%s%n",
                        key,method,before,pixel.psd,fine,pixel.samples,pixel.guardQueries,pixel.reason);
            }
            ProjectedBaselineExperiment.write(in,run,output.resolve(method));
        }
        System.out.println("DARK_COARSE_GUARD_CHECKS_PASS checks="+checks+" terrain_pixels=4 output="+output);
    }
}
