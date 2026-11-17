/** 单层空间筛选原型：最大高程只用于排除，叶部仍按原50米等距节点插值。
 * 索引建立后调用方不得修改 grid.elevation / grid.valid。
 */
public final class BlockUpperBoundVisibilityNoStats {
    private static final double EPS = 1e-9;
    private final AsciiGrid grid;
    private final int blockSize, blockCols;
    private final double[] maxima;
    private final boolean[] complete;


    public BlockUpperBoundVisibilityNoStats(AsciiGrid grid, int blockSize) {
        if (blockSize < 1 || blockSize > Math.max(grid.rows, grid.cols))
            throw new IllegalArgumentException("invalid block size");
        this.grid = grid; this.blockSize = blockSize;
        blockCols = (grid.cols - 1) / blockSize + 1;
        int blockRows = (grid.rows - 1) / blockSize + 1;
        maxima = new double[Math.multiplyExact(blockRows, blockCols)];
        complete = new boolean[maxima.length];
        for (int br = 0; br < blockRows; br++) for (int bc = 0; bc < blockCols; bc++) {
            int index = br * blockCols + bc;
            int r0 = br * blockSize, c0 = bc * blockSize;
            int r1 = Math.min(grid.rows, r0 + blockSize);
            int c1 = Math.min(grid.cols, c0 + blockSize);
            // 一圈节点覆盖双线性支撑及原核心的近整数EPS分支；越界块不排除。
            complete[index] = r0 > 0 && c0 > 0 && r1 < grid.rows && c1 < grid.cols;
            double max = Double.NEGATIVE_INFINITY;
            for (int r = Math.max(0, r0 - 1); r <= Math.min(grid.rows - 1, r1 + 1); r++)
                for (int c = Math.max(0, c0 - 1); c <= Math.min(grid.cols - 1, c1 + 1); c++) {
                    complete[index] &= grid.valid[r][c];
                    max = Math.max(max, grid.elevation[r][c]);
                }
            maxima[index] = max;
        }
    }

    /** 仅两组原始数组的有效载荷估计，不含JVM对象、数组头或DEM。 */
    public long payloadBytes() { return 9L * maxima.length; }

    public VisibilityResult visibleAt(CoordinateContract contract, int receiverRow, int receiverCol,
            double altitudeDegrees, double bearingDegrees, AngleReference reference,
            SearchConfig config) {
        contract.checkAngleReference(reference);
        if (!Double.isFinite(altitudeDegrees) || !Double.isFinite(bearingDegrees)
                || altitudeDegrees <= 0 || altitudeDegrees >= 90)
            throw new IllegalArgumentException("altitude must be strictly between 0 and 90 degrees");
        if (!grid.inside(receiverRow, receiverCol) || !grid.valid[receiverRow][receiverCol])
            return new VisibilityResult(VisibilityStatus.UNKNOWN_RECEIVER_INVALID, 0, 0);
        double normalized = bearingDegrees % 360;
        double bearing = Math.toRadians(normalized < 0 ? normalized + 360 : normalized);
        double rise = Math.tan(Math.toRadians(altitudeDegrees));
        double receiver = grid.elevation[receiverRow][receiverCol];
        double dr = -config.sampleSpacingGridUnits / grid.cellSize * Math.cos(bearing);
        double dc = config.sampleSpacingGridUnits / grid.cellSize * Math.sin(bearing);
        long samples = 0;
        int j = 1;
        while (j <= config.maxSamples) {
            double distance = j * config.sampleSpacingGridUnits;
            // 顺序与冻结核心相同，避免改变原节点的浮点坐标。
            double row = receiverRow - distance / grid.cellSize * Math.cos(bearing);
            double col = receiverCol + distance / grid.cellSize * Math.sin(bearing);
            int r = (int)Math.floor(row), c = (int)Math.floor(col);
            int end = j;
            boolean skip = false;
            if (grid.inside(r, c)) {
                int br = r / blockSize, bc = c / blockSize;
                int index = br * blockCols + bc;
                end = Math.min(config.maxSamples, Math.min(
                        segmentEnd(j, row, dr, br * blockSize, (br + 1.0) * blockSize, config.maxSamples),
                        segmentEnd(j, col, dc, bc * blockSize, (bc + 1.0) * blockSize, config.maxSamples)));
                // 端点复核防止取整误差跨块；射线坐标单调，首尾同块则中间同块。
                while (end > j && !sameBlock(receiverRow, receiverCol, end, config, bearing, br, bc)) end--;
                double ray = receiver + distance * rise;
                double pad = 1e-8 * Math.max(1, Math.max(Math.abs(maxima[index]), Math.abs(ray)));
                skip = complete[index] && Double.isFinite(ray) && maxima[index] + pad < ray;
            }
            if (skip) {
                // Statistics omitted; original schedule advances below.
            } else {
                for (int k = j; k <= end; k++) {
                    double d = k * config.sampleSpacingGridUnits;
                    double rr = receiverRow - d / grid.cellSize * Math.cos(bearing);
                    double cc = receiverCol + d / grid.cellSize * Math.sin(bearing);
                    Sample sample = interpolate(grid, rr, cc);
                    samples++;
                    if (sample.status != null) return new VisibilityResult(sample.status, samples, d);
                    if (sample.value > receiver + d * rise)
                        return new VisibilityResult(VisibilityStatus.SHADED, samples, d);
                }
            }
            if (end == config.maxSamples) break;
            j = end + 1;
        }
        return new VisibilityResult(VisibilityStatus.VISIBLE, samples,
                config.maxSamples * config.sampleSpacingGridUnits);
    }

    private static int segmentEnd(int j, double value, double delta, double low, double high, int cap) {
        if (delta == 0) return cap;
        double steps = delta > 0 ? (high - value) / delta : (low - value) / delta;
        // 少取一个样本，块边界交给下一段；不改变任何原采样序号。
        return j + (int)Math.max(0, Math.min((double)cap - j, Math.floor(steps) - 1));
    }

    private boolean sameBlock(int r, int c, int j, SearchConfig config, double bearing, int br, int bc) {
        double d = j * config.sampleSpacingGridUnits;
        double row = r - d / grid.cellSize * Math.cos(bearing);
        double col = c + d / grid.cellSize * Math.sin(bearing);
        return row >= br * blockSize && row < (br + 1.0) * blockSize
                && col >= bc * blockSize && col < (bc + 1.0) * blockSize;
    }

    // 以下插值分支逐项保留ControlledVisibility语义；冻结核心不修改。
    private static Sample interpolate(AsciiGrid g, double row, double col) {
        int nr=(int)Math.rint(row), nc=(int)Math.rint(col);
        boolean exactRow=Math.abs(row-nr)<EPS, exactCol=Math.abs(col-nc)<EPS;
        if(exactRow&&exactCol)return one(g,nr,nc);
        int r0=(int)Math.floor(row),c0=(int)Math.floor(col);
        if(exactRow)return linear(g,r0,c0,r0,c0+1,col-c0);
        if(exactCol)return linear(g,r0,c0,r0+1,c0,row-r0);
        Sample a=one(g,r0,c0),b=one(g,r0+1,c0),c=one(g,r0,c0+1),d=one(g,r0+1,c0+1);
        if(a.status!=null)return a;if(b.status!=null)return b;if(c.status!=null)return c;if(d.status!=null)return d;
        double fr=row-r0,fc=col-c0;
        return new Sample(a.value*(1-fr)*(1-fc)+b.value*fr*(1-fc)+c.value*(1-fr)*fc+d.value*fr*fc,null);
    }
    private static Sample linear(AsciiGrid g,int r1,int c1,int r2,int c2,double f) {
        Sample a=one(g,r1,c1),b=one(g,r2,c2);
        if(a.status!=null)return a;if(b.status!=null)return b;
        return new Sample(a.value*(1-f)+b.value*f,null);
    }
    private static Sample one(AsciiGrid g,int r,int c) {
        if(!g.inside(r,c))return new Sample(Double.NaN,VisibilityStatus.UNKNOWN_OUT_OF_BOUNDS);
        if(!g.valid[r][c])return new Sample(Double.NaN,VisibilityStatus.UNKNOWN_RAY_INVALID);
        return new Sample(g.elevation[r][c],null);
    }
    private static final class Sample {
        final double value; final VisibilityStatus status;
        Sample(double value, VisibilityStatus status) { this.value=value; this.status=status; }
    }
}
