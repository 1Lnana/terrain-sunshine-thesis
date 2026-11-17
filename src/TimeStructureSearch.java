import java.util.*;

/** Offline-only search. The supplied oracle owns the reference; searches never see its container. */
public final class TimeStructureSearch {
    private TimeStructureSearch() {}
    public static final class MissingData extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public MissingData(String message) { super(message); }
    }
    public static final class Query {
        public final double time;
        public final int state;
        public final String purpose;
        public final boolean isNew;
        Query(double t, int s, String p, boolean n) { time=t; state=s; purpose=p; isNew=n; }
    }
    public static final class Result {
        public String status="OK", left="", right="";
        public double psd=Double.NaN;
        public final NavigableMap<Double,Integer> nodes=new TreeMap<>();
        public final List<Query> queries=new ArrayList<>();
        public int requests, hits, anchor, boundary, other, fallback;
        public int unique() { return anchor+boundary+other+fallback; }
    }
    private static final class Sampler {
        final AdaptiveTimeV1.Oracle oracle;
        final Result out;
        final NavigableMap<Double,Integer> values=new TreeMap<>();
        boolean missing;
        Sampler(AdaptiveTimeV1.Oracle oracle, Result out) { this.oracle=oracle; this.out=out; }
        int get(double t, String purpose) {
            out.requests++;
            Integer state=values.get(t);
            if (state != null) {
                out.hits++;
                out.queries.add(new Query(t,state,purpose,false));
                return state;
            }
            int s;
            try { s=oracle.at(t); }
            catch (MissingData ex) { s=-2; missing=true; }
            if (s != -2 && s != -1 && s != 0 && s != 1)
                throw new IllegalArgumentException("oracle state must be -1, 0 or 1");
            if (s == -2) missing=true;
            values.put(t,s);
            switch (purpose) {
                case "anchor": out.anchor++; break;
                case "boundary": out.boundary++; break;
                case "fallback": out.fallback++; break;
                case "other": out.other++; break;
                default: throw new IllegalArgumentException("unknown purpose");
            }
            out.queries.add(new Query(t,s,purpose,true));
            return s;
        }
    }
    private static double midpoint(double a,double b) {
        return Math.max(Math.floor(a)+1,Math.floor((a+b)/2));
    }
    private static boolean bad(Result out,int s) {
        if (s == -2) { out.status="MISSING_DATA"; return true; }
        if (s == -1) { out.status="UNKNOWN"; return true; }
        return false;
    }
    private static boolean bracket(Sampler sampler,double a,int sa,double b,int sb) {
        // Only the opposite-state bracket can contain the assumed transition.
        while (sa != sb) {
            double m=midpoint(a,b);
            if (!(m>a && m<b)) break;
            int sm=sampler.get(m,"boundary");
            if (bad(sampler.out,sm)) return false;
            sampler.out.nodes.put(m,sm);
            if (sm==sa) { a=m; sa=sm; } else { b=m; sb=sm; }
        }
        return true;
    }
    private static void finish(Result out) {
        if (out.requests != out.hits+out.unique()) throw new AssertionError("query accounting");
        if (!out.status.equals("OK")) return;
        double[] t=new double[out.nodes.size()]; int[] s=new int[t.length]; int i=0;
        for (Map.Entry<Double,Integer> e:out.nodes.entrySet()) { t[i]=e.getKey(); s[i]=e.getValue(); i++; }
        out.psd=TimeStepBaseline.integrate(t,s);
    }
    private static final class Pair {
        final double a,b;
        Pair(double a,double b) { this.a=a; this.b=b; }
    }
    private static final class Found {
        final double time; final int state;
        Found(double time,int state) { this.time=time; this.state=state; }
    }
    private static Found findAnchor(Sampler s,double a,double b,int outerState,double outer) {
        if (outerState == 1) return new Found(outer,1);
        // FIFO ensures level order; ordered children preserve ascending time within a level.
        ArrayDeque<Pair> queue=new ArrayDeque<>(); queue.add(new Pair(a,b));
        while (!queue.isEmpty()) {
            Pair p=queue.removeFirst(); double m=midpoint(p.a,p.b);
            if (!(m>p.a && m<p.b)) continue;
            int state=s.get(m,"anchor");
            if (bad(s.out,state)) return null;
            s.out.nodes.put(m,state);
            if (state==1) return new Found(m,1);
            queue.addLast(new Pair(p.a,m)); queue.addLast(new Pair(m,p.b));
        }
        return null;
    }
    public static Result run(String method,double day,AdaptiveTimeV1.Oracle oracle) {
        if (!Double.isFinite(day) || day<=0 || day>1440) throw new IllegalArgumentException("invalid day");
        Objects.requireNonNull(oracle);
        Result out=new Result(); Sampler s=new Sampler(oracle,out);
        if (method.equals("binarylocal30")) {
            // Core caches internally: only calls reaching the oracle are observable.
            AdaptiveTimeV2Local.Result base=BinaryLocalRefinement.adaptiveWithDarkGuard(day,30,
                    t -> s.get(t,"other"));
            out.nodes.putAll(base.integrationNodes);
            if (s.missing) out.status="MISSING_DATA";
            else if (s.values.containsValue(-1)) out.status="UNKNOWN";
            out.psd=base.psd;
            if (out.requests != out.hits+out.unique()) throw new AssertionError("query accounting");
            return out;
        }
        if (!method.equals("two_boundary_assumption") && !method.equals("two_window_assumption"))
            throw new IllegalArgumentException("unknown method");
        double mid=Math.floor(day/2);
        int sm=s.get(mid,"anchor"); out.nodes.put(mid,sm);
        if (bad(out,sm)) { finish(out); return out; }
        boolean one=method.equals("two_boundary_assumption");
        if ((one && sm==0) || (!one && sm==1)) {
            out.status="NOT_APPLICABLE"; finish(out); return out;
        }
        String endpointPurpose=one ? "boundary" : "anchor";
        int start=s.get(0,endpointPurpose); out.nodes.put(0.0,start);
        if (bad(out,start)) { finish(out); return out; }
        int end=s.get(day,endpointPurpose); out.nodes.put(day,end);
        if (bad(out,end)) { finish(out); return out; }
        if (one) {
            if (bracket(s,0,start,mid,sm)) bracket(s,mid,sm,day,end);
        } else {
            Found left=findAnchor(s,0,mid,start,0);
            if (!out.status.equals("OK")) { finish(out); return out; }
            out.left=left==null ? "EXHAUSTED" : "FOUND";
            Found right=findAnchor(s,mid,day,end,day);
            if (!out.status.equals("OK")) { finish(out); return out; }
            out.right=right==null ? "EXHAUSTED" : "FOUND";
            if (left==null || right==null) out.status="NOT_APPLICABLE";
            else if (bracket(s,0,start,left.time,1)
                    && bracket(s,left.time,1,mid,sm)
                    && bracket(s,mid,sm,right.time,1)) bracket(s,right.time,1,day,end);
        }
        finish(out); return out;
    }
    private static void require(boolean condition,String label) {
        if (!condition) throw new AssertionError(label);
    }
    /** Invoked only by the user-run offline validation entry; never at compile time. */
    public static void syntheticChecks() {
        for (double day : new double[]{120,120.5}) {
            AdaptiveTimeV1.Oracle single=t -> t>=20 && t<100 ? 1 : 0;
            Result one=run("two_boundary_assumption",day,single);
            require(one.status.equals("OK") && Math.abs(one.psd-80)<1e-8,"single fractional day");
            AdaptiveTimeV1.Oracle two=t -> (t>=10 && t<40)||(t>=80 && t<110) ? 1 : 0;
            Result pair=run("two_window_assumption",day,two);
            require(pair.status.equals("OK") && Math.abs(pair.psd-60)<1e-8,"two windows");
        }
        Result light=run("two_boundary_assumption",120.5,t -> 1);
        require(light.status.equals("OK") && light.psd==120.5,"visible endpoints");
        Result dark=run("two_window_assumption",120,t -> 0);
        require(dark.status.equals("NOT_APPLICABLE") && dark.unique()==121 && dark.left.equals("EXHAUSTED") && dark.right.equals("EXHAUSTED"),"full dark exhaustion");
        require(run("two_boundary_assumption",120,t -> t==60 ? -1 : 1).status.equals("UNKNOWN"),"unknown midpoint");
        require(run("two_window_assumption",120,t -> 1).status.equals("NOT_APPLICABLE"),"bright midpoint");
        require(run("two_boundary_assumption",120,t -> t<30 ? 1 : 0).status.equals("NOT_APPLICABLE"),"single without midpoint");
        Result hidden=run("two_boundary_assumption",120,t -> t>=10 && t<12 ? 0 : 1);
        require(hidden.status.equals("OK") && hidden.psd==120,"hidden dark counterexample");
        Result missed=run("two_boundary_assumption",120,t -> { throw new MissingData("control"); });
        require(missed.status.equals("MISSING_DATA"),"missing data");
    }
}
