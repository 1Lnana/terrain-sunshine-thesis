import java.util.Locale;
import java.util.Properties;

/** 显式记录水平坐标和方位角约定；不会仅凭数值推断 DEM 的 CRS。 */
public final class CoordinateContract {
    public final String scenarioId, mode, horizontalUnit, campusAngleReference;
    public final Double latitudeDegrees;
    public final boolean gridNorthEqualsTrueNorth;

    private CoordinateContract(String id, String mode, String unit, Double latitude, boolean aligned, String reference) {
        this.scenarioId=id; this.mode=mode; this.horizontalUnit=unit; this.latitudeDegrees=latitude;
        this.gridNorthEqualsTrueNorth=aligned; this.campusAngleReference=reference;
    }
    public static CoordinateContract from(Properties p) {
        String id=required(p,"scenario_id"), mode=required(p,"coordinate_mode"), unit=required(p,"horizontal_unit");
        String alignment=required(p,"grid_north_alignment"), latText=required(p,"latitude_degrees");
        Double lat="unset".equalsIgnoreCase(latText) ? null : Double.parseDouble(latText);
        if (lat != null && (!Double.isFinite(lat) || lat < -90 || lat > 90)) throw new IllegalArgumentException("latitude_degrees must be in [-90,90]");
        boolean aligned="true_north".equalsIgnoreCase(alignment);
        // 人工局地坐标允许声明真北；未知 CRS 的网格不得冒称已知纬度或真北方向。
        if ("artificial_local_meters".equals(mode)) {
            if (!"m".equals(unit) || lat == null || !aligned) throw new IllegalArgumentException("artificial input needs metres, latitude and true-north alignment");
        } else if ("unknown_crs_grid".equals(mode)) {
            if (lat != null || aligned) throw new IllegalArgumentException("unknown CRS input must not claim latitude or true-north alignment");
        } else throw new IllegalArgumentException("unsupported coordinate_mode: " + mode);
        return new CoordinateContract(id,mode,unit,lat,aligned,required(p,"single_time_azimuth_reference").toUpperCase(Locale.ROOT));
    }
    public void checkAngleReference(AngleReference reference) {
        // 调用射线计算前，确认传入的方位角参考方向与坐标约定一致。
        if (reference == AngleReference.TRUE_NORTH_CLOCKWISE && !gridNorthEqualsTrueNorth)
            throw new IllegalArgumentException("true-north bearing is not allowed without a confirmed grid/true-north relation");
        if (reference == AngleReference.GRID_NORTH_CLOCKWISE && !"GRID_NORTH_CLOCKWISE".equals(campusAngleReference) && !gridNorthEqualsTrueNorth)
            throw new IllegalArgumentException("the input contract does not allow a grid-north bearing");
    }
    private static String required(Properties p, String key) { String v=p.getProperty(key); if(v==null||v.trim().isEmpty()) throw new IllegalArgumentException("missing "+key); return v.trim(); }
}
