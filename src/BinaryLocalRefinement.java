/** Controlled neighbour: same coarse/midpoint schedule, dark guard and integration
 * as PlainLocalRefinement; recursively bisect only opposite-state brackets.
 * Inspired by boundary search, not a reproduction of CN103003818B.
 * Equal-state brackets can contain hidden windows; no completeness guarantee.
 */
public final class BinaryLocalRefinement {
    private BinaryLocalRefinement() { }

    private static void bisect(AdaptiveTimeV2Local.Worker w,
                               double a, double b, int sa, int sb) {
        if (sa < 0 || sb < 0 || sa == sb) return;
        double mid = Math.max(Math.floor(a) + 1, Math.floor((a + b) / 2));
        // No integer-minute node remains strictly inside this bracket.
        if (mid <= a || mid >= b) return;
        if (!w.values.containsKey(mid)) w.refinementQueries++;
        int sm = w.get(mid);
        w.nodes.put(mid, sm);
        if (sm < 0) return;
        bisect(w, a, mid, sa, sm);
        bisect(w, mid, b, sm, sb);
    }

    public static AdaptiveTimeV2Local.Result adaptiveWithDarkGuard(
            double day, int coarseMinutes, AdaptiveTimeV1.Oracle oracle) {
        if (!Double.isFinite(day) || day <= 0 || day > 1440)
            throw new IllegalArgumentException("invalid daylight time axis");
        if (coarseMinutes != 20 && coarseMinutes != 30)
            throw new IllegalArgumentException("coarse step must be20 or30");
        AdaptiveTimeV2Local.Worker w = new AdaptiveTimeV2Local.Worker(oracle);
        int n = (int)Math.ceil(day / coarseMinutes);
        for (int i = 0; i <= n; i++) {
            double t = Math.min(i * (double)coarseMinutes, day);
            w.nodes.put(t, w.get(t));
        }
        if (w.values.values().stream().allMatch(state -> state == 0)) {
            w.fixedMinuteFallback(day);
            return new AdaptiveTimeV2Local.Result(w);
        }
        for (int i = 0; i < n; i++) {
            double a = i * (double)coarseMinutes;
            double b = Math.min((i + 1) * (double)coarseMinutes, day);
            int sa = w.get(a), sb = w.get(b);
            if (sa < 0 || sb < 0) continue;
            double mid = Math.floor((a + b) / 2);
            if (mid <= a || mid >= b) continue;
            int sm = w.get(mid);
            w.nodes.put(mid, sm);
            if (sm < 0) continue;
            bisect(w, a, mid, sa, sm);
            bisect(w, mid, b, sm, sb);
        }
        return new AdaptiveTimeV2Local.Result(w);
    }
}
