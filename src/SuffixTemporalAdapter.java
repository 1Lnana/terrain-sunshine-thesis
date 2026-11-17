import java.util.*;

/** Namespace-only adapter of the frozen Nov10 temporal search; no rule changes. */
public final class SuffixTemporalAdapter {
    private static final String PURE="two_boundary_assumption";
    record Query(double time,int state,String purpose,boolean fresh) {}
    record Pixel(double day,double psd,String status,String reason,int requests,int hits,int unique,
                         int anchor,int boundary,int other,int fallback,NavigableMap<Double,Integer> nodes,
                         List<Query> trace,NavigableMap<Double,Integer> evaluated,String branch) {}
    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }
    private static final class Memo {
        final AdaptiveTimeV1.Oracle oracle;
        final NavigableMap<Double,Integer> values=new TreeMap<>();
        final List<Query> trace;
        final int axisLimit;
        int requests,hits,anchor,boundary,fallback;
        Memo(AdaptiveTimeV1.Oracle oracle,boolean capture,double day) {
            this.oracle=oracle; this.trace=capture?new ArrayList<>():null; this.axisLimit=(int)Math.ceil(day)+1;
        }
        int get(double time,String purpose) {
            requests++; Integer state=values.get(time); boolean fresh=state==null;
            if (fresh) {
                boolean isFallback=purpose.equals("fallback");
                require(isFallback || anchor+boundary<24,"direct dual-boundary query bound exceeded");
                require(values.size()<axisLimit,"pixel minute-node budget reached");
                state=oracle.at(time); require(state>=-1 && state<=1,"invalid visibility state");
                values.put(time,state);
                if (isFallback) fallback++; else if (purpose.equals("anchor")) anchor++; else boundary++;
            } else hits++;
            if (trace!=null) trace.add(new Query(time,state,purpose,fresh));
            return state;
        }
    }
    static Pixel hybrid(double day,AdaptiveTimeV1.Oracle oracle,boolean capture) {
        Memo memo=new Memo(oracle,capture,day);
        double mid=Math.floor(day/2);
        TimeStructureSearch.Result direct=TimeStructureSearch.run(PURE,day,
                t->memo.get(t,t==mid?"anchor":"boundary"));
        double psd=direct.psd; String status=direct.status,reason="NO_DARK_FALLBACK";
        String branch=status.equals("OK")?"TWO_BOUNDARY":"UNKNOWN";
        NavigableMap<Double,Integer> nodes=direct.nodes;
        if (status.equals("NOT_APPLICABLE")) {
            require(memo.values.size()==1 && memo.values.get(mid)==0,"fallback requires observed dark midpoint");
            AdaptiveTimeV2Local.Result old=LimitedDarkBinaryRefinement.adaptive(day,
                    t->memo.get(t,"fallback"));
            require(old.integrationNodes.equals(old.evaluated),"fallback integration contract changed");
            psd=old.psd; status=Double.isFinite(psd)?"OK":"UNKNOWN";
            reason=old.fallbackReason; branch="MIDDAY_DARK_FALLBACK"; nodes=old.integrationNodes;
        }
        int unique=memo.values.size();
        require(memo.requests==memo.hits+unique && unique==memo.anchor+memo.boundary+memo.fallback,"hybrid query accounting");
        return new Pixel(day,psd,status,reason,memo.requests,memo.hits,unique,memo.anchor,memo.boundary,0,
                memo.fallback,nodes,memo.trace,memo.values,branch);
    }
    static Pixel baseline(double day,AdaptiveTimeV1.Oracle oracle) {
        AdaptiveTimeV2Local.Result result=BinaryLocalRefinement.adaptiveWithDarkGuard(day,30,oracle);
        return new Pixel(day,result.psd,Double.isFinite(result.psd)?"OK":"UNKNOWN",result.fallbackReason,
                -1,-1,result.evaluated.size(),0,0,0,0,result.integrationNodes,null,result.evaluated,"COARSE_BINARY");
    }
}
