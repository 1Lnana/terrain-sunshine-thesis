import java.util.Arrays;

/** Independent candidate: support-padded row/column bounds, fixed legacy sample schedule.
 * DEM and valid arrays must remain immutable after construction. Not wired to production runs.
 */
public final class StripUpperBoundVisibility {
    private static final double EPS = 1e-9;
    private static final int BLOCK = 16, WIDTH = 4, STRIPS = 4;
    public static final int DIRECTION_BINS = 16;
    // Direction counters: rays, block tests, legacy failures, strip attempts, value reads,
    // extra skipped segments, extra skipped samples. Bins are [b*22.5,(b+1)*22.5) grid bearings.
    public static final class Counters {
        public long blockTests, legacyBlockFailures, stripAttempts, stripValueReads;
        public long rescuedSegments, rescuedSamples, spatialSamples, skippedSamples;
        public long numericFallbackSegments;
        public final long[][] byDirection = new long[DIRECTION_BINS][7];
    }
    private final AsciiGrid grid;
    private final int blockSize = BLOCK, blockCols;
    private final double[] maxima, maxAbs, rowMax, colMax;
    private final boolean[] complete;

    public StripUpperBoundVisibility(AsciiGrid grid) {
        this.grid = grid;
        blockCols = (grid.cols - 1) / BLOCK + 1;
        int blockRows = (grid.rows - 1) / BLOCK + 1;
        int n = Math.multiplyExact(blockRows, blockCols);
        maxima = new double[n]; maxAbs = new double[n]; complete = new boolean[n];
        rowMax = new double[Math.multiplyExact(n, STRIPS)];
        colMax = new double[rowMax.length];
        Arrays.fill(maxima, Double.NEGATIVE_INFINITY);
        Arrays.fill(rowMax, Double.NEGATIVE_INFINITY);
        Arrays.fill(colMax, Double.NEGATIVE_INFINITY);
        for (int br=0; br<blockRows; br++) for (int bc=0; bc<blockCols; bc++) {
            int index=br*blockCols+bc, r0=br*BLOCK, c0=bc*BLOCK;
            int r1=Math.min(grid.rows,r0+BLOCK), c1=Math.min(grid.cols,c0+BLOCK);
            complete[index]=r0>0 && c0>0 && r1<grid.rows && c1<grid.cols;
            for (int r=Math.max(0,r0-1); r<=Math.min(grid.rows-1,r1+1); r++)
                for (int c=Math.max(0,c0-1); c<=Math.min(grid.cols-1,c1+1); c++) {
                    double z=grid.elevation[r][c];
                    complete[index] &= grid.valid[r][c];
                    maxima[index]=Math.max(maxima[index],z);
                    maxAbs[index]=Math.max(maxAbs[index],Math.abs(z));
                    // A halo node can belong to more than one strip; update all memberships.
                    for (int s=0; s<STRIPS; s++) {
                        int ra=r0+s*WIDTH, rb=Math.min(r1,ra+WIDTH);
                        int ca=c0+s*WIDTH, cb=Math.min(c1,ca+WIDTH);
                        if (ra<r1 && r>=ra-1 && r<=rb+1)
                            rowMax[index*STRIPS+s]=Math.max(rowMax[index*STRIPS+s],z);
                        if (ca<c1 && c>=ca-1 && c<=cb+1)
                            colMax[index*STRIPS+s]=Math.max(colMax[index*STRIPS+s],z);
                    }
                }
        }
    }

    /** Primitive array payload only; not heap peak or total application memory. */
    public long payloadBytes() { return 81L * maxima.length; }

    private static boolean below(double upper, double ray, double a) {
        if (!Double.isFinite(upper) || !Double.isFinite(ray) || !Double.isFinite(a)
                || a>Double.MAX_VALUE/16) return false;
        double pad=Math.max(1e-8*Math.max(1,Math.max(a,Math.abs(ray))),64*Math.ulp(a));
        return Math.nextUp(upper+pad)<ray;
    }

    public VisibilityResult visibleAt(CoordinateContract contract, int receiverRow, int receiverCol,
            double altitudeDegrees, double bearingDegrees, AngleReference reference,
            SearchConfig config, Counters counts) {
        contract.checkAngleReference(reference);
        if (!Double.isFinite(altitudeDegrees) || !Double.isFinite(bearingDegrees)
                || altitudeDegrees<=0 || altitudeDegrees>=90)
            throw new IllegalArgumentException("altitude must be strictly between 0 and 90 degrees");
        double normalized=bearingDegrees%360;
        if (normalized<0) normalized+=360;
        int bin=((int)(normalized/22.5))%DIRECTION_BINS;
        long[] direction=counts.byDirection[bin]; direction[0]++;
        if (!grid.inside(receiverRow,receiverCol) || !grid.valid[receiverRow][receiverCol])
            return new VisibilityResult(VisibilityStatus.UNKNOWN_RECEIVER_INVALID,0,0);
        double bearing=Math.toRadians(normalized);
        double rise=Math.tan(Math.toRadians(altitudeDegrees));
        double receiver=grid.elevation[receiverRow][receiverCol];
        double dr=-config.sampleSpacingGridUnits/grid.cellSize*Math.cos(bearing);
        double dc=config.sampleSpacingGridUnits/grid.cellSize*Math.sin(bearing);
        long samples=0;
        int j=1;
        while (j<=config.maxSamples) {
            double distance=j*config.sampleSpacingGridUnits;
            double row=receiverRow-distance/grid.cellSize*Math.cos(bearing);
            double col=receiverCol+distance/grid.cellSize*Math.sin(bearing);
            int r=(int)Math.floor(row), c=(int)Math.floor(col), end=j;
            boolean skip=false;
            if (grid.inside(r,c)) {
                int br=r/BLOCK, bc=c/BLOCK, index=br*blockCols+bc;
                end=Math.min(config.maxSamples,Math.min(
                    segmentEnd(j,row,dr,br*BLOCK,(br+1.0)*BLOCK,config.maxSamples),
                    segmentEnd(j,col,dc,bc*BLOCK,(bc+1.0)*BLOCK,config.maxSamples)));
                while (end>j && !sameBlock(receiverRow,receiverCol,end,config,bearing,br,bc)) end--;
                counts.blockTests++; direction[1]++;
                double ray=receiver+distance*rise;
                // Exact old predicate retained for attribution, not used as the safety guard.
                double oldPad=1e-8*Math.max(1,Math.max(Math.abs(maxima[index]),Math.abs(ray)));
                boolean legacySkip=complete[index] && Double.isFinite(ray) && maxima[index]+oldPad<ray;
                if (!legacySkip) { counts.legacyBlockFailures++; direction[2]++; }
                skip=complete[index] && below(maxima[index],ray,maxAbs[index]);
                if (!skip && complete[index]) {
                    if (!Double.isFinite(maxAbs[index]) || maxAbs[index]>Double.MAX_VALUE/16
                            || !Double.isFinite(ray)) {
                        counts.numericFallbackSegments++;
                    } else {
                        double endD=end*config.sampleSpacingGridUnits;
                        double er=receiverRow-endD/grid.cellSize*Math.cos(bearing);
                        double ec=receiverCol+endD/grid.cellSize*Math.sin(bearing);
                        if (Double.isFinite(row) && Double.isFinite(col) && Double.isFinite(er)
                                && Double.isFinite(ec)
                                && sameBlock(receiverRow,receiverCol,end,config,bearing,br,bc)) {
                            int rlo=(Math.min(r,(int)Math.floor(er))-br*BLOCK)/WIDTH;
                            int rhi=(Math.max(r,(int)Math.floor(er))-br*BLOCK)/WIDTH;
                            int clo=(Math.min(c,(int)Math.floor(ec))-bc*BLOCK)/WIDTH;
                            int chi=(Math.max(c,(int)Math.floor(ec))-bc*BLOCK)/WIDTH;
                            if (rlo>=0 && rhi<STRIPS && clo>=0 && chi<STRIPS
                                    && (rhi-rlo<STRIPS-1 || chi-clo<STRIPS-1)) {
                                counts.stripAttempts++; direction[3]++;
                                if (rhi-rlo<STRIPS-1)
                                    skip=below(stripUpper(rowMax,index,rlo,rhi,counts,direction),ray,maxAbs[index]);
                                if (!skip && chi-clo<STRIPS-1)
                                    skip=below(stripUpper(colMax,index,clo,chi,counts,direction),ray,maxAbs[index]);
                                if (skip && !legacySkip) {
                                    long skipped=(long)end-j+1;
                                    counts.rescuedSegments++; counts.rescuedSamples+=skipped;
                                    direction[5]++; direction[6]+=skipped;
                                }
                            }
                        }
                    }
                }
            }
            if (skip) counts.skippedSamples+=(long)end-j+1;
            else for (int k=j; k<=end; k++) {
                double d=k*config.sampleSpacingGridUnits;
                double rr=receiverRow-d/grid.cellSize*Math.cos(bearing);
                double cc=receiverCol+d/grid.cellSize*Math.sin(bearing);
                Sample sample=interpolate(grid,rr,cc);
                samples++; counts.spatialSamples++;
                if (sample.status!=null) return new VisibilityResult(sample.status,samples,d);
                if (sample.value>receiver+d*rise)
                    return new VisibilityResult(VisibilityStatus.SHADED,samples,d);
            }
            if (end==config.maxSamples) break;
            j=end+1;
        }
        return new VisibilityResult(VisibilityStatus.VISIBLE,samples,
                config.maxSamples*config.sampleSpacingGridUnits);
    }

    private static double stripUpper(double[] table, int index, int lo, int hi,
            Counters counts, long[] direction) {
        double upper=Double.NEGATIVE_INFINITY;
        for (int s=lo; s<=hi; s++) {
            upper=Math.max(upper,table[index*STRIPS+s]);
            counts.stripValueReads++; direction[4]++;
        }
        return upper;
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
