import java.util.*;

/** V1 only: fixed coarse scan, midpoint heuristic, minute-grid transition refinement, fixed-grid fallback. */
public final class AdaptiveTimeV1 {
    @FunctionalInterface public interface Oracle { int at(double minutesSinceSunrise); }
    public static final class Result {
        public final double psd;
        public final NavigableMap<Double,Integer> evaluated, integrationNodes;
        public final String fallbackReason;
        Result(NavigableMap<Double,Integer> evaluated,NavigableMap<Double,Integer> nodes,String reason) {
            this.evaluated=new TreeMap<>(evaluated);this.integrationNodes=new TreeMap<>(nodes);this.fallbackReason=reason;
            double[] times=new double[nodes.size()];int[] states=new int[nodes.size()];int i=0;
            for(Map.Entry<Double,Integer> e:nodes.entrySet()){times[i]=e.getKey();states[i]=e.getValue();i++;}
            this.psd=TimeStepBaseline.integrate(times,states);
        }
    }
    private static final class Sampler {
        final Oracle oracle;final TreeMap<Double,Integer> values=new TreeMap<>();
        Sampler(Oracle oracle){this.oracle=oracle;}
        int get(double t){return values.computeIfAbsent(t,k->{int s=oracle.at(k);return s==0||s==1?s:-1;});}
    }
    private static void checkDay(double day) {
        if(!Double.isFinite(day)||day<=0||day>1440)throw new IllegalArgumentException("invalid daylight time axis");
    }
    public static int sunRuns(Collection<Integer> states) {
        int runs=0,previous=0;
        for(int state:states){if(state==1&&previous!=1)runs++;previous=state;}
        return runs;
    }
    public static Result fixed(double day,int minutes,Oracle oracle) {
        checkDay(day);if(minutes<1)throw new IllegalArgumentException("positive fixed step required");
        return fixed(day,minutes,new Sampler(oracle),"NONE");
    }
    private static Result fixed(double day,int minutes,Sampler sampler,String reason) {
        TreeMap<Double,Integer> nodes=new TreeMap<>();int n=(int)Math.ceil(day/minutes);
        for(int i=0;i<=n;i++){double t=Math.min(i*(double)minutes,day);nodes.put(t,sampler.get(t));}
        return new Result(sampler.values,nodes,reason);
    }
    private static Result fallback(double day,Sampler sampler,String reason) {return fixed(day,1,sampler,reason);}
    public static Result adaptive(double day,int coarseMinutes,Oracle oracle) {
        checkDay(day);if(coarseMinutes!=20&&coarseMinutes!=30)throw new IllegalArgumentException("coarse step must be20 or30");
        Sampler sampler=new Sampler(oracle);int n=(int)Math.ceil(day/coarseMinutes);
        double[] time=new double[n+1];int[] state=new int[n+1];
        for(int i=0;i<=n;i++) {
            time[i]=Math.min(i*(double)coarseMinutes,day);state[i]=sampler.get(time[i]);
            if(state[i]<0)return fallback(day,sampler,"UNKNOWN_COARSE");
        }
        if(sunRuns(sampler.values.values())>1)return fallback(day,sampler,"MULTI_WINDOW_COARSE");
        // No coarse visible sample: the entire Sun interval could have fallen between samples.
        if(sunRuns(sampler.values.values())==0)return fallback(day,sampler,"NO_VISIBLE_COARSE");
        for(int i=0;i<n;i++) {
            if(!(time[i+1]>time[i]))return fallback(day,sampler,"ABNORMAL_INTERVAL");
            if(state[i]==state[i+1]) {
                double mid=(time[i]+time[i+1])/2;int probe=sampler.get(mid);
                if(probe<0)return fallback(day,sampler,"UNKNOWN_MIDPOINT");
                if(probe!=state[i])return fallback(day,sampler,"STABILITY_PROBE_DISAGREES");
                // Empirical acceptance, NOT proof that all unsampled times have the same state.
            } else {
                // Fine nodes match baseline sunrise+k minutes; include actual sunset remainder.
                for(int minute=(int)Math.floor(time[i])+1;minute<time[i+1];minute++) {
                    if(sampler.get((double)minute)<0)return fallback(day,sampler,"UNKNOWN_REFINEMENT");
                }
                if(sunRuns(sampler.values.values())>1)return fallback(day,sampler,"MULTI_WINDOW_REFINEMENT");
            }
        }
        if(sunRuns(sampler.values.values())>1)return fallback(day,sampler,"MULTI_WINDOW_AFTER_PROBES");
        return new Result(sampler.values,sampler.values,"NONE");
    }
}
