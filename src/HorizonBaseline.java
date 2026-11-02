import java.util.function.DoubleFunction;

/** 基础地平线对照：先扫描地形，再用太阳高度查询。不是HORAYZON的复现。 */
public final class HorizonBaseline {
    private static final double EPS = 1e-9;
    private HorizonBaseline() { }

    public static final class Direction {
        public final double angleDegrees;
        public final long spatialSamples;
        public final VisibilityStatus status;
        private Direction(double angle, long samples, VisibilityStatus status) {
            this.angleDegrees = angle; this.spatialSamples = samples; this.status = status;
        }
    }

    public static final class Table {
        public final int stepDegrees;
        public final long spatialSamples;
        private final double[] angles;

        // 包内构造入口也用于已知角度表的人工检查；NaN表示未知方向。
        Table(int step, double[] angles, long samples) {
            if ((step != 1 && step != 2) || angles.length != 360 / step || samples < 0)
                throw new IllegalArgumentException("expected 1 or 2 degree horizon table");
            for (double angle : angles)
                if (!Double.isNaN(angle) && (!Double.isFinite(angle) || angle <= -90 || angle >= 90))
                    throw new IllegalArgumentException("invalid horizon angle");
            this.stepDegrees = step; this.angles = angles.clone(); this.spatialSamples = samples;
        }
        public int directions() { return angles.length; }
        public long payloadBytes() { return (long)angles.length * Double.BYTES; }
        public double angleAtIndex(int index) { return angles[index]; }

        /** 输入是栅格北顺时针方位；360度回到0度，角度值本身不做圆周插值。 */
        public double at(double gridBearing) {
            double position = normalize(gridBearing) / stepDegrees;
            int left = (int)Math.floor(position);
            double fraction = position - left;
            if (fraction == 0) return angles[left];
            double a = angles[left], b = angles[(left + 1) % angles.length];
            if (!Double.isFinite(a) || !Double.isFinite(b)) return Double.NaN;
            return a * (1 - fraction) + b * fraction;
        }

        /** 1可见，0遮挡，-1未知；与原驱动一样将几何日出日落高度下限设为1e-7度。 */
        public int state(double altitudeDegrees, double gridBearing) {
            if (!Double.isFinite(altitudeDegrees) || altitudeDegrees >= 90)
                throw new IllegalArgumentException("invalid solar altitude");
            double horizon = at(gridBearing);
            if (!Double.isFinite(horizon)) return -1;
            return Math.max(1e-7, altitudeDegrees) < horizon ? 0 : 1;
        }

        public int solarState(double altitude, double trueAzimuth, double northGridBearing) {
            if (!Double.isFinite(trueAzimuth) || !Double.isFinite(northGridBearing))
                throw new IllegalArgumentException("invalid azimuth or north correction");
            return state(altitude, trueAzimuth + northGridBearing);
        }
    }

    /** 本阶段保留分钟状态，便于检查；正式驱动再负责计时和选择性输出。 */
    public static final class Day {
        public final double psdMinutes;
        public final double[] times;
        public final int[] states;
        private Day(double[] times, int[] states) {
            this.times = times; this.states = states;
            this.psdMinutes = TimeStepBaseline.integrate(times, states);
        }
        public int timeQueries() { return states.length; }
    }

    public static Table build(AsciiGrid grid, int row, int col, SearchConfig search, int step) {
        if (step != 1 && step != 2) throw new IllegalArgumentException("azimuth step must be 1 or 2 degrees");
        double[] angles = new double[360 / step];
        long samples = 0;
        for (int i = 0; i < angles.length; i++) {
            Direction result = direction(grid, row, col, search, i * (double)step);
            angles[i] = result.angleDegrees; samples += result.spatialSamples;
        }
        return new Table(step, angles, samples);
    }

    /** 扫描完整方向，记录最大高差/距离，最后仅做一次atan；遇未知不当作无遮挡。 */
    public static Direction direction(AsciiGrid grid, int row, int col, SearchConfig search, double bearing) {
        double radians = Math.toRadians(normalize(bearing));
        if (!grid.inside(row, col) || !grid.valid[row][col])
            return new Direction(Double.NaN, 0, VisibilityStatus.UNKNOWN_RECEIVER_INVALID);
        double receiver = grid.elevation[row][col], maximumSlope = Double.NEGATIVE_INFINITY;
        for (int j = 1; j <= search.maxSamples; j++) {
            double distance = j * search.sampleSpacingGridUnits;
            double r = row - distance / grid.cellSize * Math.cos(radians);
            double c = col + distance / grid.cellSize * Math.sin(radians);
            Sample sample = interpolate(grid, r, c);
            if (sample.status != null) return new Direction(Double.NaN, j, sample.status);
            maximumSlope = Math.max(maximumSlope, (sample.value - receiver) / distance);
        }
        // status为null代表方向计算有效，不代表该方向对所有太阳高度均可见。
        return new Direction(Math.toDegrees(Math.atan(maximumSlope)), search.maxSamples, null);
    }

    public static Day day(Table table, double latitude, double declination, double northGridBearing) {
        double daylight = TimeStepBaseline.daylight(latitude, declination);
        double sunrise = 720 - daylight / 2;
        return sampleDay(table, daylight,
                t -> TimeStepBaseline.sun(latitude, declination, sunrise + t), northGridBearing);
    }

    static Day sampleDay(Table table, double daylight, DoubleFunction<double[]> solar, double northGridBearing) {
        if (!Double.isFinite(daylight) || daylight <= 0 || daylight > 1440)
            throw new IllegalArgumentException("invalid daylight duration");
        int intervals = (int)Math.ceil(daylight);
        double[] times = new double[intervals + 1];
        int[] states = new int[intervals + 1];
        for (int i = 0; i <= intervals; i++) {
            times[i] = Math.min((double)i, daylight);
            double[] angles = solar.apply(times[i]);
            states[i] = table.solarState(angles[0], angles[1], northGridBearing);
        }
        return new Day(times, states);
    }

    private static double normalize(double angle) {
        if (!Double.isFinite(angle)) throw new IllegalArgumentException("invalid bearing");
        double value = angle % 360;
        double result = value < 0 ? value + 360 : value;
        return result == 360 ? 0 : result;
    }

    // 以下采样分支逐项对齐冻结的ControlledVisibility.interpolate，包括近整数分支。
    private static Sample interpolate(AsciiGrid g, double row, double col) {
        int nr = (int)Math.rint(row), nc = (int)Math.rint(col);
        boolean exactRow = Math.abs(row - nr) < EPS, exactCol = Math.abs(col - nc) < EPS;
        if (exactRow && exactCol) return one(g, nr, nc);
        int r0 = (int)Math.floor(row), c0 = (int)Math.floor(col);
        if (exactRow) return linear(g, r0, c0, r0, c0 + 1, col - c0);
        if (exactCol) return linear(g, r0, c0, r0 + 1, c0, row - r0);
        Sample a = one(g,r0,c0), b = one(g,r0+1,c0), c = one(g,r0,c0+1), d = one(g,r0+1,c0+1);
        if (a.status != null) return a;
        if (b.status != null) return b;
        if (c.status != null) return c;
        if (d.status != null) return d;
        double fr = row - r0, fc = col - c0;
        return new Sample(a.value*(1-fr)*(1-fc)+b.value*fr*(1-fc)+c.value*(1-fr)*fc+d.value*fr*fc, null);
    }
    private static Sample linear(AsciiGrid g, int r1, int c1, int r2, int c2, double f) {
        Sample a = one(g,r1,c1), b = one(g,r2,c2);
        if (a.status != null) return a;
        if (b.status != null) return b;
        return new Sample(a.value*(1-f)+b.value*f, null);
    }
    private static Sample one(AsciiGrid g, int r, int c) {
        if (!g.inside(r,c)) return new Sample(Double.NaN, VisibilityStatus.UNKNOWN_OUT_OF_BOUNDS);
        if (!g.valid[r][c]) return new Sample(Double.NaN, VisibilityStatus.UNKNOWN_RAY_INVALID);
        return new Sample(g.elevation[r][c], null);
    }
    private static final class Sample {
        final double value;
        final VisibilityStatus status;
        Sample(double value, VisibilityStatus status) { this.value = value; this.status = status; }
    }
}
