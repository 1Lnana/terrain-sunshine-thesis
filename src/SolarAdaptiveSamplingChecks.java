import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleUnaryOperator;

/** Synthetic checks for schedule decisions; no terrain experiment is performed. */
public final class SolarAdaptiveSamplingChecks {
    private static int checks;
    private static void require(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static SolarAdaptiveSampling.Result run(String label,double day,AdaptiveTimeV1.Oracle oracle,
                                                   DoubleUnaryOperator altitude,boolean local) {
        AtomicInteger calls=new AtomicInteger();
        SolarAdaptiveSampling.Result result=SolarAdaptiveSampling.adaptive(day,t->{calls.incrementAndGet();return oracle.at(t);},altitude,local);
        double reference=AdaptiveTimeV1.fixed(day,1,oracle).psd;
        require(Double.isNaN(reference)?Double.isNaN(result.calculation.psd):Math.abs(result.calculation.psd-reference)<1e-9,label+" integral");
        require(calls.get()==result.calculation.evaluated.size(),label+" cache");
        require(result.calculation.integrationNodes.lastKey()==day,label+" actual sunset");
        System.out.println("CASE "+label+" mode="+(local?"combined":"schedule_only")+" psd="+result.calculation.psd
                +" fixed1="+reference+" queries="+calls.get()+" steps="+result.selectedSteps+" fallback="+result.fallbackReason);
        return result;
    }
    public static void main(String[] args) {
        for(boolean local:new boolean[]{false,true}) {
            SolarAdaptiveSampling.Result low=run("low_altitude",60,t->1,t->14.9,local);
            require(low.selectedSteps.values().stream().allMatch(s->s==10),"below15 uses10");
            SolarAdaptiveSampling.Result high=run("threshold15",60,t->1,t->15,local);
            require(high.selectedSteps.values().stream().allMatch(s->s==30),"at15 uses30");
            SolarAdaptiveSampling.Result mixed=run("altitude_changes",80,t->1,t->t<20?10:25,local);
            require(mixed.selectedSteps.equals(new TreeMap<>(Map.of(0.0,10,10.0,10,20.0,30,50.0,30))),"dynamic schedule");
            SolarAdaptiveSampling.Result change=run("change_forces_next_short",90,t->t>=13?1:0,t->40,local);
            require(change.selectedSteps.get(0.0)==30&&change.selectedSteps.get(30.0)==10
                    &&change.selectedSteps.get(40.0)==30,"change shortens next interval then recovers");
            run("fractional_sunset",76.5,t->1,t->40,local);
            run("two_windows",120,t->(t>=10&&t<20)||(t>=70&&t<80)?1:0,t->40,local);
            run("unknown",60,t->t==0?-1:1,t->40,local);
        }
        SolarAdaptiveSampling.Result stable=SolarAdaptiveSampling.adaptive(120,t->1,t->40,true);
        AdaptiveTimeV2Local.Result old=AdaptiveTimeV2Local.adaptive(120,30,t->1);
        require(stable.calculation.evaluated.equals(old.evaluated),"high stable combined equals local-only");
        AdaptiveTimeV1.Oracle hidden=t->t>=3&&t<5?0:1;
        SolarAdaptiveSampling.Result missed=SolarAdaptiveSampling.adaptive(120,hidden,t->40,true);
        double reference=AdaptiveTimeV1.fixed(120,1,hidden).psd;
        require(missed.calculation.psd==120&&reference==118,"hidden-window limitation retained");
        System.out.println("KNOWN_LIMITATION combined_PSD="+missed.calculation.psd+" fixed1_PSD="+reference);
        System.out.println("SOLAR_SAMPLING_CHECKS_PASS checks="+checks+" synthetic_only");
    }
}
