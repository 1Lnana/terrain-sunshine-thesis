/** 射线搜索设置：最多取样次数，以及相邻空间样本的距离。 */
public final class SearchConfig {
    public final int maxSamples;
    public final double sampleSpacingGridUnits;
    public SearchConfig(int maxSamples, double sampleSpacingGridUnits) {
        if (maxSamples < 1 || !Double.isFinite(sampleSpacingGridUnits) || sampleSpacingGridUnits <= 0)
            throw new IllegalArgumentException("invalid search configuration");
        this.maxSamples=maxSamples; this.sampleSpacingGridUnits=sampleSpacingGridUnits;
    }
    /** 旧接口的半径估算：由高差和太阳高度角换算取样次数，再受 cap 限制。 */
    public static SearchConfig legacyRadius(double heightDifference, double altitudeDegrees, double cellSize, int cap) {
        if (heightDifference < 0 || altitudeDegrees <= 0 || altitudeDegrees >= 90 || cap < 1)
            throw new IllegalArgumentException("invalid legacy-compatible radius inputs");
        int count=(int)(heightDifference / Math.tan(Math.toRadians(altitudeDegrees)) / cellSize)+1;
        return new SearchConfig(Math.min(count,cap),cellSize);
    }
}
