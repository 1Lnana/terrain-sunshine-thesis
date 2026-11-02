/** 从已有分钟状态统计日照结构；不调用太阳位置或射线函数。 */
public final class SunlightIntervalStats {
    public record Result(String category, int lightIntervals, int transitions,
                         double firstBoundary, double lastBoundary, double spanMinutes,
                         double psdMinutes, double internalShadeMinutes) {}

    public static Result analyze(double[] times, int[] states) {
        if (times == null || states == null || times.length != states.length || times.length < 2)
            throw new IllegalArgumentException("matching arrays with at least two nodes required");
        boolean unknown = false;
        int first = -1, last = -1, intervals = 0, transitions = 0;
        for (int i = 0; i < times.length; i++) {
            if (!Double.isFinite(times[i]) || times[i] < 0 || (i > 0 && times[i] <= times[i-1]))
                throw new IllegalArgumentException("finite increasing nonnegative times required");
            if (states[i] < -1 || states[i] > 1)
                throw new IllegalArgumentException("state must be -1, 0 or 1");
            unknown |= states[i] == -1;
            if (states[i] == 1) {
                if (first < 0) first = i;
                last = i;
                if (i == 0 || states[i-1] != 1) intervals++;
            }
            if (i > 0 && states[i] != states[i-1]) transitions++;
        }
        // UNKNOWN不能混入全天暗或单段类别，也不输出不完整的段数。
        if (unknown) return new Result("UNKNOWN", -1, -1, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN);
        double psd = TimeStepBaseline.integrate(times, states);
        if (first < 0) return new Result("DARK", 0, 0, Double.NaN, Double.NaN, 0, psd, 0);
        // 与梯形积分一致：状态转换取两节点中点，日出日落保留真实端点。
        double start = first == 0 ? times[0] : (times[first-1] + times[first]) / 2;
        double end = last == times.length-1 ? times[last] : (times[last] + times[last+1]) / 2;
        double span = end - start;
        return new Result(intervals == 1 ? "SINGLE" : "MULTI", intervals, transitions,
                start, end, span, psd, span - psd);
    }
}
