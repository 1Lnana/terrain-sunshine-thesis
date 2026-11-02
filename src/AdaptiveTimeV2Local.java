import java.util.*;

/** Fixed coarse schedule: conditional probes and local minute refinement only. */
public final class AdaptiveTimeV2Local {
    public static final class Result {
        public final double psd;
        public final NavigableMap<Double,Integer> evaluated, integrationNodes;
        public final int quarterProbes, refinementQueries, guardQueries;
        public final String fallbackReason;
        Result(Worker w) {
            evaluated=new TreeMap<>(w.values);integrationNodes=new TreeMap<>(w.nodes);
            quarterProbes=w.quarterProbes;refinementQueries=w.refinementQueries;
            guardQueries=w.guardQueries;fallbackReason=w.fallbackReason;
            double[] times=new double[w.nodes.size()];int[] states=new int[times.length];int i=0;
            for(Map.Entry<Double,Integer> e:w.nodes.entrySet()){times[i]=e.getKey();states[i]=e.getValue();i++;}
            psd=TimeStepBaseline.integrate(times,states);
        }
    }
    static final class Worker {
        final AdaptiveTimeV1.Oracle oracle;
        final TreeMap<Double,Integer> values=new TreeMap<>(),nodes=new TreeMap<>();
        int quarterProbes,refinementQueries,guardQueries;
        String fallbackReason="NONE";
        Worker(AdaptiveTimeV1.Oracle oracle){this.oracle=oracle;}
        int get(double t){return values.computeIfAbsent(t,k->{int s=oracle.at(k);return s==0||s==1?s:-1;});}
        void fixedMinuteFallback(double day) {
            nodes.clear();fallbackReason="NO_VISIBLE_COARSE";
            for(int i=0;i<=Math.ceil(day);i++) {
                double t=Math.min(i,day);
                if(!values.containsKey(t))guardQueries++;
                nodes.put(t,get(t));
            }
        }
        void minuteFill(double a,double b) {
            for(int m=(int)Math.floor(a)+1;m<b;m++) {
                if(!values.containsKey((double)m))refinementQueries++;
                nodes.put((double)m,get(m));
            }
        }
        void half(double a,double b,int sa,int sb) {
            if(sa<0||sb<0||sa==sb)return;
            double q=Math.floor((a+b)/2);
            if(q<=a||q>=b){minuteFill(a,b);return;}
            if(!values.containsKey(q))quarterProbes++;
            int sq=get(q);nodes.put(q,sq);
            if(sq<0)return;
            if(sa!=sq)minuteFill(a,q);
            if(sq!=sb)minuteFill(q,b);
        }
        void interval(double a,double b) {
            int sa=get(a),sb=get(b);
            if(sa<0||sb<0)return;
            double mid=Math.floor((a+b)/2);
            if(mid<=a||mid>=b)return;
            int sm=get(mid);nodes.put(mid,sm);
            if(sm<0)return;
            // Equal states are an empirical acceptance, not a no-transition certificate.
            half(a,mid,sa,sm);half(mid,b,sm,sb);
        }
    }
    public static Result adaptive(double day,int coarseMinutes,AdaptiveTimeV1.Oracle oracle) {
        return adaptive(day,coarseMinutes,oracle,false);
    }
    public static Result adaptiveWithDarkGuard(double day,int coarseMinutes,AdaptiveTimeV1.Oracle oracle) {
        return adaptive(day,coarseMinutes,oracle,true);
    }
    private static Result adaptive(double day,int coarseMinutes,AdaptiveTimeV1.Oracle oracle,boolean darkGuard) {
        if(!Double.isFinite(day)||day<=0||day>1440)throw new IllegalArgumentException("invalid daylight time axis");
        if(coarseMinutes!=20&&coarseMinutes!=30)throw new IllegalArgumentException("coarse step must be20 or30");
        Worker w=new Worker(oracle);int n=(int)Math.ceil(day/coarseMinutes);
        for(int i=0;i<=n;i++){double t=Math.min(i*(double)coarseMinutes,day);w.nodes.put(t,w.get(t));}
        if(darkGuard&&w.values.values().stream().allMatch(state->state==0)) {
            w.fixedMinuteFallback(day);
            return new Result(w);
        }
        for(int i=0;i<n;i++)w.interval(i*(double)coarseMinutes,Math.min((i+1)*(double)coarseMinutes,day));
        return new Result(w);
    }
}
