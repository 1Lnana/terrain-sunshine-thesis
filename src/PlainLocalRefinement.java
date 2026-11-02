/** Ablation baseline: same coarse/midpoint probes and dark guard, without quarter narrowing.
 * This is a controlled local-refinement baseline, not a reproduction of a published tool.
 */
public final class PlainLocalRefinement {
    private PlainLocalRefinement() { }

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
            // Only this transition handling differs from the conditional-quarter method.
            if (sa != sm) w.minuteFill(a, mid);
            if (sm != sb) w.minuteFill(mid, b);
        }
        return new AdaptiveTimeV2Local.Result(w);
    }
}
