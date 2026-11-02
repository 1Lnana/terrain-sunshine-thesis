import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Artificial time sequences; these checks do not measure DEM performance. */
public final class AdaptiveV2LocalChecks {
    private static int checks;
    private static void require(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static void compare(String label,double day,AdaptiveTimeV1.Oracle oracle) {
        AtomicInteger calls=new AtomicInteger();
        AdaptiveTimeV2Local.Result v=AdaptiveTimeV2Local.adaptive(day,30,t->{calls.incrementAndGet();return oracle.at(t);});
        AdaptiveTimeV1.Result f=AdaptiveTimeV1.fixed(day,1,oracle);
        require(Math.abs(v.psd-f.psd)<1e-9,label+" integral");
        require(calls.get()==v.evaluated.size(),label+" cache");
        require(v.integrationNodes.keySet().stream().allMatch(t->t==day||t==Math.floor(t)),label+" minute alignment");
        System.out.println("CASE "+label+" psd="+v.psd+" fixed1="+f.psd+" queries="+calls.get()+" quarter="+v.quarterProbes+" refinement="+v.refinementQueries);
    }
    public static void main(String[] args) {
        compare("all_light",120,t->1);
        compare("all_dark",120,t->0);
        compare("single_window",120,t->t>=13&&t<97?1:0);
        compare("midpoint_shadow",120,t->t>=10&&t<20?0:1);
        compare("two_windows",120,t->(t>=10&&t<20)||(t>=70&&t<80)?1:0);
        compare("coarse_two_windows",120,t->(t>=20&&t<40)||(t>=80&&t<100)?1:0);
        compare("fractional_sunset",116.5,t->1);
        AdaptiveTimeV2Local.Result stable=AdaptiveTimeV2Local.adaptive(120,30,t->1);
        require(stable.evaluated.size()==9&&stable.quarterProbes==0&&stable.refinementQueries==0,"stable intervals have midpoint only");
        AdaptiveTimeV1.Oracle shadow=t->t>=10&&t<20?0:1;
        AdaptiveTimeV2Local.Result local=AdaptiveTimeV2Local.adaptive(120,30,shadow);
        AdaptiveTimeV1.Result old=AdaptiveTimeV1.adaptive(120,30,shadow);
        require(old.fallbackReason.equals("STABILITY_PROBE_DISAGREES"),"V1 whole-day fallback example");
        require(local.evaluated.size()<old.evaluated.size(),"local work reduction in artificial fallback example");
        System.out.println("LOCAL_VS_V1 local_queries="+local.evaluated.size()+" v1_queries="+old.evaluated.size()+" v1_fallback="+old.fallbackReason);
        AdaptiveTimeV2Local.Result unknown=AdaptiveTimeV2Local.adaptive(120,30,t->t==15?-1:1);
        require(Double.isNaN(unknown.psd),"UNKNOWN preserved");
        AdaptiveTimeV1.Oracle hidden=t->t>=3&&t<5?0:1;
        AdaptiveTimeV2Local.Result h=AdaptiveTimeV2Local.adaptive(120,30,hidden);
        double reference=AdaptiveTimeV1.fixed(120,1,hidden).psd;
        require(h.psd==120&&reference==118,"explicit hidden-window limitation");
        System.out.println("KNOWN_LIMITATION local_PSD="+h.psd+" fixed1_PSD="+reference);
        System.out.println("ADAPTIVE_V2_LOCAL_CHECKS_PASS checks="+checks+" synthetic_only");
    }
}
