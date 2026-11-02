"""用户运行：从全国TIFF准备128或512接收区及10km遮挡缓冲，默认秦岭128。"""

import argparse
import csv
from datetime import datetime
import hashlib
import json
import math
import re
from pathlib import Path

import numpy as np
import rasterio
from rasterio.transform import from_origin
from rasterio.warp import Resampling, reproject, transform, transform_bounds
from rasterio.windows import Window, from_bounds


ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path(r"D:\Code\Sun\China-90m.tif"))
    parser.add_argument("--output", type=Path, help="新目录，已存在时拒绝覆盖")
    parser.add_argument("--region", default="qinling", help="输出地区标签，例如 huabei_plain")
    parser.add_argument("--center-lon", type=float, default=108.5, help="中心经度，默认秦岭108.5")
    parser.add_argument("--center-lat", type=float, default=33.5, help="中心纬度，默认秦岭33.5")
    parser.add_argument("--size", type=int, choices=[128, 512], default=128, help="接收区边长，默认128；规模验证用512")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]*", args.region):
        parser.error("地区标签只能包含英文字母、数字、下划线或连字符，且以字母或数字开头")
    center_lon, center_lat = args.center_lon, args.center_lat
    if not (math.isfinite(center_lon) and math.isfinite(center_lat)
            and 108 <= center_lon < 120 and 0 < center_lat < 66):
        parser.error("当前接入支持UTM49N/50N：108≤经度<120，0<纬度<66")
    zone = int((center_lon + 180) // 6) + 1
    destination_crs = f"EPSG:{32600 + zone}"
    output = args.output or ROOT / "user_runs" / (
        args.region + datetime.now().strftime(f"_90m_{args.size}_%Y%m%d_%H%M%S_%f"))
    output = output.resolve()
    if not output.is_relative_to((ROOT / "user_runs").resolve()):
        raise ValueError("输出必须位于项目user_runs内")
    if output.exists():
        raise FileExistsError(f"请使用新输出目录：{output}")

    cell, roi, radius = 90.0, args.size, 10000.0
    margin = math.ceil(radius / cell) + 2
    size = roi + 2 * margin
    cx, cy = transform("EPSG:4326", destination_crs, [center_lon], [center_lat])
    left = math.floor(cx[0] / cell) * cell - size * cell / 2
    top = math.ceil(cy[0] / cell) * cell + size * cell / 2
    affine = from_origin(left, top, cell, cell)
    nodata = -9999.0

    with rasterio.open(args.source) as source:
        if source.crs is None or source.count != 1:
            raise ValueError("源文件需要明确坐标系且为单波段高程栅格")
        bounds = transform_bounds(
            destination_crs, source.crs, left, top - size * cell,
            left + size * cell, top, densify_pts=21,
        )
        requested = from_bounds(*bounds, transform=source.transform)
        c0, r0 = math.floor(requested.col_off) - 2, math.floor(requested.row_off) - 2
        c1 = math.ceil(requested.col_off + requested.width) + 2
        r1 = math.ceil(requested.row_off + requested.height) + 2
        if c0 < 0 or r0 < 0 or c1 > source.width or r1 > source.height:
            raise ValueError("源文件未覆盖拟选区域与缓冲")
        window = Window(c0, r0, c1 - c0, r1 - r0)
        raw = source.read(1, window=window, masked=True).astype("float64").filled(nodata)
        dem = np.full((size, size), nodata, dtype="float64")
        reproject(
            source=raw, destination=dem,
            src_transform=source.window_transform(window), src_crs=source.crs,
            src_nodata=nodata, dst_transform=affine, dst_crs=destination_crs,
            dst_nodata=nodata, resampling=Resampling.bilinear,
        )
        source_info = {
            "path": str(args.source.resolve()), "bytes": args.source.stat().st_size,
            "mtime_ns": args.source.stat().st_mtime_ns,
            "crs": str(source.crs), "resolution": list(source.res),
            "width": source.width, "height": source.height,
            "nodata": source.nodata, "elevation_unit_tag": source.units[0],
            "read_window": [c0, r0, c1 - c0, r1 - r0],
            "provenance": "项目来源记录称SRTM（NASA/NIMA，CGIAR-CSI整理）；具体版本未确认",
            "elevation_unit_assumption": "按该SRTM来源记录采用米；原TIFF未写入高程单位",
        }

    if not np.isfinite(dem).all() or np.any(dem == nodata):
        raise ValueError("投影区域含无效高程；请检查源数据或更换样区，本次不写出数据")

    # 对每个接收点保存真实经纬度及真北在投影平面中的方向。
    rr, cc = np.meshgrid(np.arange(margin, margin + roi), np.arange(margin, margin + roi), indexing="ij")
    xx = left + (cc.ravel() + 0.5) * cell
    yy = top - (rr.ravel() + 0.5) * cell
    lons, lats = transform(destination_crs, "EPSG:4326", xx.tolist(), yy.tolist())
    delta = 1e-5
    nx, ny = transform("EPSG:4326", destination_crs, lons, [lat + delta for lat in lats])
    sx, sy = transform("EPSG:4326", destination_crs, lons, [lat - delta for lat in lats])
    north_bearings = [math.degrees(math.atan2(a - b, c - d)) for a, b, c, d in zip(nx, sx, ny, sy)]
    points = list(zip(rr.ravel().tolist(), cc.ravel().tolist(), lats, lons, north_bearings))

    output.mkdir(parents=True, exist_ok=False)
    ascii_path = output / "dem.asc"
    with ascii_path.open("w", encoding="ascii", newline="\n") as stream:
        stream.write(f"ncols {size}\nnrows {size}\nxllcorner {left:.9f}\n"
                     f"yllcorner {top - size * cell:.9f}\ncellsize {cell}\nNODATA_value {nodata}\n")
        np.savetxt(stream, dem, fmt="%.6f")
    with rasterio.open(
        output / "dem_projected.tif", "w", driver="GTiff", height=size, width=size,
        count=1, dtype="float64", crs=destination_crs, transform=affine, nodata=nodata,
        compress="deflate",
    ) as target:
        target.write(dem, 1)
    with (output / "receivers.csv").open("w", encoding="utf-8", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(["row", "col", "latitude", "longitude", "true_north_grid_bearing_deg"])
        writer.writerows(points)

    # 回读ASCII，检查格式和导出精度；坐标样点正反投影检查。
    restored = np.loadtxt(ascii_path, skiprows=6)
    ascii_error = float(np.max(np.abs(restored - dem)))
    if restored.shape != dem.shape or ascii_error > 1e-6:
        raise AssertionError("ASCII回读不一致")
    back_x, back_y = transform("EPSG:4326", destination_crs, lons, lats)
    coordinate_error = float(np.max(np.hypot(np.array(back_x) - xx, np.array(back_y) - yy)))
    if coordinate_error > 0.001:
        raise AssertionError("接收点坐标正反投影误差超过1毫米")

    config = output / "experiment.properties"
    trace = ";".join(f"{margin + offset},{margin + offset}" for offset in [0, roi // 2, roi - 1])
    config.write_text(
        "dem=dem.asc\nreceivers=receivers.csv\nmetadata=metadata.json\n"
        f"projected_crs={destination_crs}\ncellsize_m={cell}\n"
        f"row_start={margin}\ncol_start={margin}\nrows={roi}\ncols={roi}\n"
        "date=2023-06-21\nmethods=fixed_1min,adaptive_30min\n"
        "search_spacing_m=50\nsearch_samples=200\nwarmups=1\nrepeats=3\n"
        f"trace_pixels={trace}\n"
        "elevation_unit_assumption=m_from_project_SRTM_record\n",
        encoding="utf-8",
    )
    roi_dem = dem[margin:margin + roi, margin:margin + roi]
    gradient_y, gradient_x = np.gradient(dem, cell, cell)
    slopes = np.degrees(np.arctan(np.hypot(gradient_x, gradient_y)))[margin:margin + roi, margin:margin + roi]
    metadata = {
        "region": args.region,
        "source": source_info, "crs": destination_crs,
        "center_requested_lon_lat": [center_lon, center_lat],
        "output_shape": [size, size], "cellsize_m": cell,
        "roi": {"row_start": margin, "col_start": margin, "rows": roi, "cols": roi},
        "buffer_pixels": margin, "buffer_m": margin * cell,
        "distance_definition": "UTM投影平面距离，射线方位按各接收点真北方向修正",
        "resampling": "bilinear", "valid_pixels": int(dem.size),
        "minimum": float(dem.min()), "maximum": float(dem.max()),
        "roi_minimum": float(dem[margin:margin + roi, margin:margin + roi].min()),
        "roi_maximum": float(dem[margin:margin + roi, margin:margin + roi].max()),
        "terrain_statistics": {
            "scope": "receiver ROI on reprojected 90m grid",
            "relief_p95_p5_m": float(np.percentile(roi_dem, 95) - np.percentile(roi_dem, 5)),
            "slope_median_deg": float(np.median(slopes)),
            "slope_p90_deg": float(np.percentile(slopes, 90)),
            "slope_method": "numpy.gradient on full metre DEM; atan(hypot(dz_dx,dz_dy)); ROI subset",
        },
        "ascii_roundtrip_max_error_m": ascii_error,
        "coordinate_roundtrip_max_error_m": coordinate_error,
        "north_grid_bearing_range_deg": [min(north_bearings), max(north_bearings)],
        "sha256": {name: hashlib.sha256((output / name).read_bytes()).hexdigest()
                   for name in ["dem.asc", "receivers.csv", "experiment.properties"]},
        "sample_pixels": [
            {"row": points[i][0], "col": points[i][1], "latitude": points[i][2],
             "longitude": points[i][3], "elevation_m": float(dem[points[i][0], points[i][1]])}
            for i in [0, len(points) // 2, len(points) - 1]
        ],
        "status": "data_prepared_no_sunshine_experiment",
    }
    (output / "metadata.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"DATA_PREPARED={output}")
    print(f"CONFIG={config}")
    print(f"REGION={args.region} CRS={destination_crs} CENTER_LON_LAT={center_lon},{center_lat}")
    print(f"RECEIVERS={len(points)} DEM_SHAPE={size}x{size} BUFFER_M={margin * cell:.0f}")
    print(f"ASCII_MAX_ERROR_M={ascii_error:.9g} COORD_MAX_ERROR_M={coordinate_error:.9g}")
    print("DATA_PREPARATION_PASS (not a sunshine experiment)")


if __name__ == "__main__":
    main()
