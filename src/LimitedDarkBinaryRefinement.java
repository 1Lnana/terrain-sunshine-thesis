import java.util.*;

/** Independent neighbour of the frozen binary search. Only the all-dark coarse branch changes.
 * A five-minute sweep finds anchors; opposite-state brackets are refined to adjacent minutes.
 * No found light is an estimate, not a proof of full-day shade. UNKNOWN remains unknown.
 */
public final class LimitedDarkBinaryRefinement {
    private LimitedDarkBinaryRefinement() {}

    private static void bisect(AdaptiveTimeV2Local.Worker w,double a,double b,int sa,int sb) {
        if (sa<0 || sb<0 || sa==sb) return;
        double mid=Math.max(Math.floor(a)+1,Math.floor((a+b)/2));
        if (mid<=a || mid>=b) return;
        if (!w.values.containsKey(mid)) w.refinementQueries++;
        int sm=w.get(mid); w.nodes.put(mid,sm);
        if (sm<0) return;
        bisect(w,a,mid,sa,sm); bisect(w,mid,b,sm,sb);
    }

    public static AdaptiveTimeV2Local.Result adaptive(double day,AdaptiveTimeV1.Oracle oracle) {
        if (!Double.isFinite(day) || day<=0 || day>1440) throw new IllegalArgumentException("invalid day");
        Objects.requireNonNull(oracle);
        AdaptiveTimeV2Local.Worker w=new AdaptiveTimeV2Local.Worker(oracle);
        int n=(int)Math.ceil(day/30);
        for (int i=0;i<=n;i++) {
            double t=Math.min(i*30.0,day); w.nodes.put(t,w.get(t));
        }
        if (w.values.values().stream().allMatch(state->state==0)) {
            w.nodes.clear(); int count=(int)Math.ceil(day/5);
            double[] times=new double[count+1]; int[] states=new int[count+1];
            for (int i=0;i<=count;i++) {
                double t=Math.min(i*5.0,day); times[i]=t;
                if (!w.values.containsKey(t)) w.guardQueries++;
                states[i]=w.get(t); w.nodes.put(t,states[i]);
            }
            for (int i=0;i<count;i++) bisect(w,times[i],times[i+1],states[i],states[i+1]);
            w.fallbackReason=w.values.containsValue(-1)?"DARK_5MIN_UNKNOWN":
                    w.values.containsValue(1)?"DARK_5MIN_FOUND":"DARK_5MIN_ESTIMATED_ZERO";
            return new AdaptiveTimeV2Local.Result(w);
        }
        // Same coarse/midpoint/opposite-state schedule as the frozen baseline.
        for (int i=0;i<n;i++) {
            double a=i*30.0,b=Math.min((i+1)*30.0,day);
            int sa=w.get(a),sb=w.get(b);
            if (sa<0 || sb<0) continue;
            double mid=Math.floor((a+b)/2);
            if (mid<=a || mid>=b) continue;
            int sm=w.get(mid); w.nodes.put(mid,sm);
            if (sm<0) continue;
            bisect(w,a,mid,sa,sm); bisect(w,mid,b,sm,sb);
        }
        return new AdaptiveTimeV2Local.Result(w);
    }
}
