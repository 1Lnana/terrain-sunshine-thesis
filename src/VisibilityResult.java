/** 单次射线查询的结果：状态、实际空间采样数和已搜索距离。 */
public final class VisibilityResult {
    public final VisibilityStatus status;
    public final long spatialSamples;
    public final double searchedDistanceGridUnits;
    VisibilityResult(VisibilityStatus status,long samples,double distance) {
        this.status=status; this.spatialSamples=samples; this.searchedDistanceGridUnits=distance;
    }
}
