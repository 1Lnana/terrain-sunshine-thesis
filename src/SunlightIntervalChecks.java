import java.nio.file.*;
import java.util.*;

/** 用户运行：人工序列和已有分钟轨迹检查，不计算新的地形射线。 */
public final class SunlightIntervalChecks {
    private static int checks;
    private static void require(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }
    private static void near(double a, double b, String label) {
        require(Double.isFinite(a) && Math.abs(a-b) < 1e-8, label);
    }
    private static void example(String name, double[] t, int[] s, int segments, int changes,
                                double psd, double span, double shade) {
        var r = SunlightIntervalStats.analyze(t, s);
        require(r.lightIntervals() == segments && r.transitions() == changes, name + " counts");
        require(r.category().equals(segments == 0 ? "DARK" : segments == 1 ? "SINGLE" : "MULTI"), name + " category");
        near(r.psdMinutes(), psd, name + " psd");
        near(r.spanMinutes(), span, name + " span");
        near(r.internalShadeMinutes(), shade, name + " shade");
        System.out.printf("CASE %s intervals=%d transitions=%d psd=%.3f span=%.3f internal_shade=%.3f%n",
                name, segments, changes, r.psdMinutes(), r.spanMinutes(), r.internalShadeMinutes());
    }
    private static void rejected(double[] t, int[] s) {
        try { SunlightIntervalStats.analyze(t, s); }
        catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("invalid input accepted");
    }
    private static void recorded(Path folder) throws Exception {
        Map<String, List<String[]>> points = new LinkedHashMap<>();
        List<String> rows = Files.readAllLines(folder.resolve("samples.csv"));
        require(rows.get(0).equals("row,col,minutes_since_sunrise,state,used_for_integration"), "trace header");
        for (String line : rows.subList(1, rows.size())) {
            String[] p = line.split(",");
            require(p[4].equals("1"), "fixed1 node used for integration");
            points.computeIfAbsent(p[0] + "," + p[1], k -> new ArrayList<>()).add(p);
        }
        Map<String, Double> reference = new HashMap<>();
        List<String> totals = Files.readAllLines(folder.resolve("psd.csv"));
        for (String line : totals.subList(1, totals.size())) {
            String[] p = line.split(",");
            reference.put(p[0] + "," + p[1], Double.parseDouble(p[6]));
        }
        require(!points.isEmpty(), "recorded points present");
        for (var entry : points.entrySet()) {
            List<String[]> samples = entry.getValue();
            double[] t = new double[samples.size()]; int[] s = new int[t.length];
            for (int i = 0; i < t.length; i++) {
                t[i] = Double.parseDouble(samples.get(i)[2]);
                s[i] = samples.get(i)[3].equals("U") ? -1 : Integer.parseInt(samples.get(i)[3]);
                if (i > 0) require(t[i]-t[i-1] <= 1 + 1e-8, "minute grid");
            }
            var r = SunlightIntervalStats.analyze(t, s);
            near(r.psdMinutes(), reference.get(entry.getKey()), "recorded PSD unchanged");
            require(r.internalShadeMinutes() >= -1e-8, "nonnegative internal shade");
            if (r.lightIntervals() <= 1) near(r.internalShadeMinutes(), 0, "single interval gap");
            System.out.printf("RECORDED pixel=%s intervals=%d psd=%.3f internal_shade=%.3f source=%s%n",
                    entry.getKey(), r.lightIntervals(), r.psdMinutes(), r.internalShadeMinutes(), folder);
        }
    }
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        example("all_dark", new double[]{0,1,2}, new int[]{0,0,0}, 0,0,0,0,0);
        example("all_light", new double[]{0,1,2}, new int[]{1,1,1}, 1,0,2,2,0);
        example("isolated_light", new double[]{0,1,2}, new int[]{0,1,0}, 1,2,1,1,0);
        example("two_windows", new double[]{0,1,2,3,4}, new int[]{0,1,0,1,0}, 2,4,2,3,1);
        example("hidden_shade", new double[]{0,1,2,3,4}, new int[]{1,1,0,1,1}, 2,2,3,4,1);
        example("fractional_sunset", new double[]{0,1,1.5}, new int[]{0,1,0}, 1,2,.75,.75,0);
        example("sunrise_light", new double[]{0,1,2}, new int[]{1,0,0}, 1,1,.5,.5,0);
        example("sunset_light", new double[]{0,1,1.5}, new int[]{0,0,1}, 1,1,.25,.25,0);
        var unknown = SunlightIntervalStats.analyze(new double[]{0,1,2}, new int[]{0,-1,1});
        require(unknown.category().equals("UNKNOWN") && unknown.lightIntervals() == -1
                && unknown.transitions() == -1 && Double.isNaN(unknown.psdMinutes())
                && Double.isNaN(unknown.spanMinutes()), "unknown excluded");
        rejected(new double[]{0,0}, new int[]{0,1});
        rejected(new double[]{0,Double.NaN}, new int[]{0,1});
        rejected(new double[]{0,1}, new int[]{0,2});
        rejected(new double[]{0,1}, new int[]{0});
        for (String folder : args) recorded(Path.of(folder));
        System.out.println("SUNLIGHT_INTERVAL_CHECKS_PASS checks=" + checks + " no_new_ray_queries");
    }
}
