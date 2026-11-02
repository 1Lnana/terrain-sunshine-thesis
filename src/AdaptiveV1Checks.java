import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic time oracles test control flow only, never masquerade as DEM evidence. */
public final class AdaptiveV1Checks {
    static int checks=0;
    static void require(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static void compare(double day,int coarse,AdaptiveTimeV1.Oracle oracle,String reason) {
        AdaptiveTimeV1.Result a=AdaptiveTimeV1.adaptive(day,coarse,oracle),f=AdaptiveTimeV1.fixed(day,1,oracle);
        require(a.fallbackReason.equals(reason),reason);
        require(Double.isNaN(f.psd)?Double.isNaN(a.psd):Math.abs(a.psd-f.psd)<1e-9,"fallback/fine integral parity "+reason);
    }
    public static void main(String[] args) {
        for(int coarse:new int[]{20,30}) {
            AdaptiveTimeV1.Oracle single=t->t>=73&&t<641?1:0;
            compare(816.38,coarse,single,"NONE");
            compare(816.38,coarse,t->1,"NONE");
            compare(816.38,coarse,t->0,"NO_VISIBLE_COARSE");
            compare(816.38,coarse,t->t<641?1:0,"NONE");
            compare(816.38,coarse,t->t>=73?1:0,"NONE");
            compare(120,coarse,t->(t>=20&&t<40)||(t>=80&&t<100)?1:0,"MULTI_WINDOW_COARSE");
            compare(120,coarse,t->t==0?-1:single.at(t),"UNKNOWN_COARSE");
            compare(120,coarse,t->t==coarse/2.0?-1:(t>=73?1:0),"UNKNOWN_MIDPOINT");
            compare(120,coarse,t->t==71?-1:(t>=73?1:0),"UNKNOWN_REFINEMENT");
            compare(120,coarse,t->(t>=5&&t<=coarse-5)||(t>=80&&t<100)?1:0,"STABILITY_PROBE_DISAGREES");
            compare(120,coarse,t->t>=70&&t<72||t>=74&&t<110?1:0,"MULTI_WINDOW_REFINEMENT");
            AtomicInteger calls=new AtomicInteger();
            AdaptiveTimeV1.Result result=AdaptiveTimeV1.adaptive(816.38,coarse,t->{calls.incrementAndGet();return single.at(t);});
            require(calls.get()==result.evaluated.size(),"unique calls include all work");
            require(calls.get()<AdaptiveTimeV1.fixed(816.38,1,single).evaluated.size(),"synthetic single-window work reduction");
            // Deliberate limitation test: narrow hidden Shadow missed by endpoints AND midpoint.
            AdaptiveTimeV1.Oracle hidden=t->t>=3&&t<5?0:1;
            AdaptiveTimeV1.Result h=AdaptiveTimeV1.adaptive(120,coarse,hidden),hf=AdaptiveTimeV1.fixed(120,1,hidden);
            require(h.fallbackReason.equals("NONE")&&Math.abs(h.psd-hf.psd)>1,"documented midpoint blind spot, not a guarantee");
            System.out.println("KNOWN_LIMITATION coarse="+coarse+" adaptive_PSD="+h.psd+" fixed1_PSD="+hf.psd+" fallback="+h.fallbackReason);
            double day=116.5,lastStart=Math.floor(day/coarse)*coarse,unknownMid=(lastStart+day)/2;
            AdaptiveTimeV1.Oracle offGridUnknown=t->t==unknownMid?-1:(t>=20&&t<80?1:0);
            AtomicInteger spent=new AtomicInteger();
            AdaptiveTimeV1.Result fallback=AdaptiveTimeV1.adaptive(day,coarse,t->{spent.incrementAndGet();return offGridUnknown.at(t);});
            AdaptiveTimeV1.Result dense=AdaptiveTimeV1.fixed(day,1,offGridUnknown);
            require(fallback.fallbackReason.equals("UNKNOWN_MIDPOINT"),"off-grid unknown triggers fallback");
            require(Math.abs(fallback.psd-dense.psd)<1e-9,"fallback integrates fixed1 nodes only");
            require(spent.get()==fallback.evaluated.size(),"fallback pre-work included in actual call count");
            require(fallback.evaluated.size()==dense.evaluated.size()+1,"off-grid unknown probe remains in cost, excluded from fine integral");
        }
        for(double bad:new double[]{0,-1,Double.NaN,1441}) {
            boolean rejected=false;try{AdaptiveTimeV1.adaptive(bad,20,t->1);}catch(IllegalArgumentException e){rejected=true;}
            require(rejected,"invalid boundary/time axis rejected");
        }
        compare(120,20,t->t==0?7:1,"UNKNOWN_COARSE");
        System.out.println("ADAPTIVE_V1_CHECKS_PASS checks="+checks+" includes_fallbacks_and_explicit_hidden_window_counterexample");
    }
}
