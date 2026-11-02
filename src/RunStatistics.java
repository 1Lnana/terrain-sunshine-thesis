/** 累计单时刻遮挡查询的次数、空间采样量、状态数量和纳秒耗时。 */
public final class RunStatistics {
    public long rayCalls, spatialSamples, visibleResults, shadedResults, unknownResults, elapsedNanos;
    public void record(VisibilityStatus status,long samples,long nanos) {
        rayCalls++; spatialSamples+=samples; elapsedNanos+=nanos;
        if(status==VisibilityStatus.VISIBLE) visibleResults++;
        else if(status==VisibilityStatus.SHADED) shadedResults++;
        else unknownResults++;
    }
}
