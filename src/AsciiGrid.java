/** 内存中的 ASCII DEM：行号从上到下增加，valid 标记哪些高程可用于射线计算。 */
public final class AsciiGrid {
    public final int rows, cols;
    public final double xllcorner, yllcorner, cellSize, noDataValue;
    public final double[][] elevation; // elevation[row][col]，单位沿用输入文件。
    public final boolean[][] valid;
    public final ZeroPolicy zeroPolicy;

    public AsciiGrid(int rows, int cols, double xll, double yll, double cell, double noData,
                     double[][] elevation, ZeroPolicy policy) {
        if (rows <= 0 || cols <= 0 || !Double.isFinite(cell) || cell <= 0)
            throw new IllegalArgumentException("invalid grid dimensions or cell size");
        this.rows=rows; this.cols=cols; this.xllcorner=xll; this.yllcorner=yll;
        this.cellSize=cell; this.noDataValue=noData; this.elevation=elevation; this.zeroPolicy=policy;
        // NoData、非有限值，以及所选策略排除的零值均不可参与插值。
        this.valid=new boolean[rows][cols];
        if (elevation.length != rows) throw new IllegalArgumentException("row count mismatch");
        for (int r=0; r<rows; r++) {
            if (elevation[r].length != cols) throw new IllegalArgumentException("column count mismatch at row " + r);
            for (int c=0; c<cols; c++) {
                double v=elevation[r][c];
                valid[r][c]=Double.isFinite(v) && v != noData && !(policy == ZeroPolicy.EXCLUDE_ZERO_FROM_CONTROLLED_RUNS && v == 0.0);
            }
        }
    }
    public boolean inside(int r, int c) { return r >= 0 && r < rows && c >= 0 && c < cols; }
}
