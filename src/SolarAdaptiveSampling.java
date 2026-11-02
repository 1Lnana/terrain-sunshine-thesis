import java.util.*;
import java.util.function.DoubleUnaryOperator;

/** Solar-altitude schedule, with either V1 interval handling or local refinement. */
public final class SolarAdaptiveSampling {
    public static final class Result {
        public final AdaptiveTimeV2Local.Result calculation;
        public final NavigableMap<Double,Integer> selectedSteps;
        public final String fallbackReason;
        Result(AdaptiveTimeV2Local.Worker worker,TreeMap<Double,Integer> steps,String reason) {
            calculation=new AdaptiveTimeV2Local.Result(worker);
            selectedSteps=new TreeMap<>(steps);fallbackReason=reason;
        }
    }
    private static Result fallback(double day,AdaptiveTimeV2Local.Worker w,
                                   TreeMap<Double,Integer> steps,String reason) {
        w.nodes.clear();
        for(int i=0;i<=Math.ceil(day);i++){double t=Math.min(i,day);w.nodes.put(t,w.get(t));}
        return new Result(w,steps,reason);
    }
    private static boolean changed(NavigableMap<Double,Integer> values,double a,double b) {
        int previous=-1;
        for(int state:values.subMap(a,true,b,true).values()) {
            if(state>=0&&previous>=0&&state!=previous)return true;
            previous=state;
        }
        return false;
    }
    public static Result adaptive(double day,AdaptiveTimeV1.Oracle oracle,
                                  DoubleUnaryOperator altitude,boolean localRefinement) {
        return adaptive(day,oracle,altitude,localRefinement,false);
    }
    public static Result adaptiveWithDarkGuard(double day,AdaptiveTimeV1.Oracle oracle,DoubleUnaryOperator altitude) {
        return adaptive(day,oracle,altitude,true,true);
    }
    private static Result adaptive(double day,AdaptiveTimeV1.Oracle oracle,
                                   DoubleUnaryOperator altitude,boolean localRefinement,boolean darkGuard) {
        if(!Double.isFinite(day)||day<=0||day>1440)throw new IllegalArgumentException("invalid daylight time axis");
        AdaptiveTimeV2Local.Worker w=new AdaptiveTimeV2Local.Worker(oracle);
        TreeMap<Double,Integer> steps=new TreeMap<>();
        double a=0;boolean nextShort=false,coarseVisible=false;
        int sa=w.get(0);w.nodes.put(0.0,sa);coarseVisible=sa==1;
        boolean coarseUnknown=sa<0;
        while(a<day) {
            double height=altitude.applyAsDouble(a);
            if(!Double.isFinite(height))throw new IllegalArgumentException("nonfinite solar altitude");
            int step=height<15||nextShort?10:30;
            steps.put(a,step);
            double b=Math.min(a+step,day);int sb=w.get(b);w.nodes.put(b,sb);
            coarseVisible|=sb==1;
            coarseUnknown|=sb<0;
            if(localRefinement) {
                w.interval(a,b);
            } else {
                // V1 interval decisions retained; the coarse endpoints are now generated in time order.
                if(sa<0||sb<0)return fallback(day,w,steps,"UNKNOWN_COARSE");
                if(sa==sb) {
                    double mid=(a+b)/2;int sm=w.get(mid);w.nodes.put(mid,sm);
                    if(sm<0)return fallback(day,w,steps,"UNKNOWN_MIDPOINT");
                    if(sm!=sa)return fallback(day,w,steps,"STABILITY_PROBE_DISAGREES");
                } else {
                    w.minuteFill(a,b);
                    for(int state:w.values.subMap(a,true,b,true).values())
                        if(state<0)return fallback(day,w,steps,"UNKNOWN_REFINEMENT");
                }
                if(AdaptiveTimeV1.sunRuns(w.values.values())>1)
                    return fallback(day,w,steps,"MULTI_WINDOW");
            }
            nextShort=changed(w.values,a,b);
            a=b;sa=sb;
        }
        if(!localRefinement&&!coarseVisible)return fallback(day,w,steps,"NO_VISIBLE_COARSE");
        if(darkGuard&&!coarseVisible&&!coarseUnknown) {
            w.fixedMinuteFallback(day);
            return new Result(w,steps,w.fallbackReason);
        }
        return new Result(w,steps,"NONE");
    }
}
