/** Fixed-spacing ray test. Unknown is never silently converted to visible. */
public final class ControlledVisibility {
    private static final double EPS=1e-9;
    private ControlledVisibility() { }

    public static VisibilityResult visibleAt(AsciiGrid grid, CoordinateContract contract,
            int receiverRow, int receiverCol, double altitudeDegrees, double bearingDegrees,
            AngleReference reference, SearchConfig config, RunStatistics stats) {
        long start=System.nanoTime(), samples=0;
        contract.checkAngleReference(reference);
        if(!Double.isFinite(altitudeDegrees)||!Double.isFinite(bearingDegrees)||altitudeDegrees<=0||altitudeDegrees>=90)
            throw new IllegalArgumentException("altitude must be strictly between 0 and 90 degrees");
        if(!grid.inside(receiverRow,receiverCol)||!grid.valid[receiverRow][receiverCol])
            return finish(VisibilityStatus.UNKNOWN_RECEIVER_INVALID,samples,0,start,stats);
        double bearing=Math.toRadians(normalize(bearingDegrees));
        double rise=Math.tan(Math.toRadians(altitudeDegrees));
        double receiver=grid.elevation[receiverRow][receiverCol];
        for(int j=1;j<=config.maxSamples;j++) {
            double distance=j*config.sampleSpacingGridUnits;
            // Row index grows southward; bearing is clockwise from the selected north reference.
            double row=receiverRow-distance/grid.cellSize*Math.cos(bearing);
            double col=receiverCol+distance/grid.cellSize*Math.sin(bearing);
            Sample sample=interpolate(grid,row,col); samples++;
            if(sample.status!=null) return finish(sample.status,samples,distance,start,stats);
            if(sample.value>receiver+distance*rise)
                return finish(VisibilityStatus.SHADED,samples,distance,start,stats);
        }
        return finish(VisibilityStatus.VISIBLE,samples,config.maxSamples*config.sampleSpacingGridUnits,start,stats);
    }

    private static VisibilityResult finish(VisibilityStatus status,long samples,double distance,long start,RunStatistics stats) {
        if(stats!=null) stats.record(status,samples,System.nanoTime()-start);
        return new VisibilityResult(status,samples,distance);
    }
    private static Sample interpolate(AsciiGrid g,double row,double col) {
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
    private static double normalize(double d){double v=d%360;return v<0?v+360:v;}
    private static final class Sample { final double value; final VisibilityStatus status; Sample(double value,VisibilityStatus status){this.value=value;this.status=status;} }
}
