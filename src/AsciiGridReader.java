import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 读取六行头信息的 ASCII DEM，并按文件行顺序保存高程矩阵。 */
public final class AsciiGridReader {
    private AsciiGridReader() { }
    public static AsciiGrid read(Path path, ZeroPolicy policy) throws IOException {
        try (BufferedReader rd = Files.newBufferedReader(path)) {
            // 前六行分别给出网格尺寸、原点、像元大小和 NoData 值；键名不区分大小写。
            Map<String,String> h = new HashMap<>();
            for (int i=0; i<6; i++) {
                String line=rd.readLine();
                if (line == null) throw new IllegalArgumentException("missing header line " + (i+1));
                String[] p=line.trim().split("\\s+");
                if (p.length != 2 || h.put(p[0].toLowerCase(),p[1]) != null)
                    throw new IllegalArgumentException("invalid or duplicate header line " + (i+1));
            }
            int cols=integer(h,"ncols"), rows=integer(h,"nrows");
            // 数据必须恰好有 rows 行、每行 cols 个数；拒绝多余的非空数据行。
            double[][] values=new double[rows][cols];
            for (int r=0; r<rows; r++) {
                String line=rd.readLine();
                if (line == null) throw new IllegalArgumentException("expected " + rows + " data rows, found " + r);
                String[] p=line.trim().split("\\s+");
                if (p.length != cols) throw new IllegalArgumentException("row " + r + " has " + p.length + " values, expected " + cols);
                for (int c=0; c<cols; c++) {
                    try { values[r][c]=Double.parseDouble(p[c]); }
                    catch (NumberFormatException e) { throw new IllegalArgumentException("non-numeric value at row " + r + ", col " + c,e); }
                    if (!Double.isFinite(values[r][c])) throw new IllegalArgumentException("non-finite value at row " + r + ", col " + c);
                }
            }
            String extra;
            while ((extra=rd.readLine()) != null) if (!extra.trim().isEmpty())
                throw new IllegalArgumentException("extra data after declared " + rows + " rows");
            return new AsciiGrid(rows,cols,number(h,"xllcorner"),number(h,"yllcorner"),number(h,"cellsize"),number(h,"nodata_value"),values,policy);
        }
    }
    private static int integer(Map<String,String> h,String key) {
        double v=number(h,key);
        if (v != Math.rint(v) || v <= 0 || v > Integer.MAX_VALUE) throw new IllegalArgumentException("invalid " + key);
        return (int)v;
    }
    private static double number(Map<String,String> h,String key) {
        String raw=h.get(key);
        if (raw == null) throw new IllegalArgumentException("missing " + key);
        double v=Double.parseDouble(raw);
        if (!Double.isFinite(v)) throw new IllegalArgumentException("invalid " + key);
        return v;
    }
}
