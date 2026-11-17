"""Audit saved paired records only; never run a solar or visibility calculation."""
import argparse
import csv
import hashlib
import itertools
import json
import math
import statistics
from pathlib import Path


EPS = 1e-8
METHODS = {"binarylocal_30min": (927941, 173287091),
           "two_boundary_limited5": (333606, 55986429)}


def rows(path):
    with path.open(encoding="utf-8-sig", newline="") as stream:
        yield from csv.DictReader(stream)


def key(row):
    return row["row"], row["col"]


def keyed(path):
    result = {}
    for row in rows(path):
        assert key(row) not in result, (path, "duplicate pixel", key(row))
        result[key(row)] = row
    assert len(result) == 16384, (path, len(result))
    return result


def same(a, b):
    a, b = float(a), float(b)
    assert math.isfinite(a) and math.isfinite(b) and abs(a - b) < EPS, (a, b)


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1048576), b""):
            digest.update(chunk)
    return digest.hexdigest()


def properties(path):
    return dict(line.split("=", 1) for line in path.read_text(encoding="utf-8-sig").splitlines()
                if line and not line.startswith("#") and "=" in line)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    run = args.run.resolve()
    assert run.parent == root / "user_runs", "existing isolated result directory required"
    report_path = run / "record_audit.json"
    assert not report_path.exists(), "do not overwrite an existing audit"
    config = properties(run / "config.properties")
    reference = root / "configs" / config["reference_run"]
    history = root / "configs" / config["regression_run"]
    prior = root / "configs" / config["candidate_regression_run"]
    ref = keyed(reference / "fixed_1min_r1/psd.csv")
    intervals = keyed(reference / "fixed_1min_r1/intervals.csv")
    baseline_old = keyed(history / "binarylocal_30min_r1/psd.csv")
    candidate_old = keyed(prior / "pixels.csv")
    timings = list(rows(run / "run_timings.csv"))
    assert len(timings) == 8
    expected_order = [("WARMUP_DIAGNOSTIC_EXCLUDED", 0, 1, "two_boundary_limited5"),
                      ("WARMUP_DIAGNOSTIC_EXCLUDED", 0, 2, "binarylocal_30min")]
    for pair in range(1, 4):
        order = list(METHODS) if pair != 2 else list(reversed(METHODS))
        expected_order.extend(("FORMAL", pair, i, method) for i, method in enumerate(order, 1))
    cumulative = 0
    for row, expected in zip(timings, expected_order):
        assert (row["phase"], int(row["pair"]), int(row["order"]), row["method"]) == expected
        queries, spatial = METHODS[row["method"]]
        assert int(row["pixels"]) == 16384
        assert int(row["real_queries"]) == queries and int(row["spatial_samples"]) == spatial
        assert int(row["visible"]) + int(row["shaded"]) + int(row["unknown"]) == queries
        assert int(row["unknown"]) == 0 and float(row["compute_sec"]) > 0
        assert 0 < int(row["ray_elapsed_nanos"]) <= float(row["compute_sec"]) * 1e9 + 1
        cumulative += queries
        assert int(row["cumulative_real_queries"]) == cumulative
    assert cumulative == 5046188
    summaries = {r["method"]: r for r in rows(run / "summary.csv")}
    assert set(summaries) == set(METHODS)
    accuracy = {}
    scalar_checks = node_count = 0
    for method, (queries, spatial) in METHODS.items():
        pixels = keyed(run / f"{method}_pixels.csv")
        assert list(pixels) == list(ref) == list(intervals)
        assert sum(int(p["unique"]) for p in pixels.values()) == queries
        node_file = run / f"{method}_integration_nodes.csv"
        assert sha(node_file) == sha(run / f"{method}_evaluated_nodes.csv"), "unexpected extra evaluated probes"
        # Reconstruct integration, windows and endpoints from saved nodes, independently of Java.
        groups = itertools.groupby(rows(node_file), key=key)
        seen = []
        for pixel_key, group in groups:
            seen.append(pixel_key)
            p = pixels[pixel_key]
            nodes = [(float(n["minute_since_sunrise"]), int(n["state"])) for n in group]
            times, states = zip(*nodes)
            assert len(nodes) == int(p["unique"]) and all(s in (0, 1) for s in states)
            assert all(a < b for a, b in zip(times, times[1:]))
            same(times[0], 0); same(times[-1], p["daylight_minutes"])
            integral = sum((b - a) * (sa + sb) / 2 for (a, sa), (b, sb) in zip(nodes, nodes[1:]))
            same(integral, p["psd_minutes"])
            lit = [i for i, s in enumerate(states) if s == 1]
            assert lit, "this fixed Huabei batch historically has a visible interval for every pixel"
            first, last = lit[0], lit[-1]
            left = times[0] if first == 0 else (times[first - 1] + times[first]) / 2
            right = times[-1] if last == len(times) - 1 else (times[last] + times[last + 1]) / 2
            windows = sum(s == 1 and (i == 0 or states[i - 1] != 1) for i, s in enumerate(states))
            same(left, p["first_boundary"]); same(right, p["last_boundary"])
            assert windows == int(p["light_intervals"])
            node_count += len(nodes)
        assert seen == list(pixels)
        errors, first_errors, last_errors = [], [], []
        dark = limited = dual = 0
        for k, p in pixels.items():
            r, s = ref[k], intervals[k]
            assert p["status"] == "OK" and p["estimated_zero"] == "false"
            same(p["reference_psd"], r["psd_minutes"])
            same(p["reference_first_boundary"], s["first_boundary_min"])
            same(p["reference_last_boundary"], s["last_boundary_min"])
            same(p["daylight_minutes"], r["daylight_minutes"])
            error = abs(float(p["psd_minutes"]) - float(r["psd_minutes"]))
            same(error, p["absolute_error"])
            errors.append(error)
            first_errors.append(abs(float(p["first_boundary"]) - float(s["first_boundary_min"])))
            last_errors.append(abs(float(p["last_boundary"]) - float(s["last_boundary_min"])))
            assert int(p["light_intervals"]) == int(s["light_intervals"]) == 1
            old = baseline_old[k] if method == "binarylocal_30min" else candidate_old[k]
            same(p["psd_minutes"], old["psd_minutes"])
            assert p["unique"] == old["sample_count" if method == "binarylocal_30min" else "unique"]
            dark += p["branch"] == "MIDDAY_DARK_FALLBACK"
            dual += p["branch"] == "TWO_BOUNDARY"
            limited += p["reason"].startswith("DARK_5MIN_")
        for pair in range(1, 4):
            formal = keyed(run / f"{method}_formal_{pair}.csv")
            assert list(formal) == list(pixels)
            for k, p in pixels.items():
                f = formal[k]
                same(f["psd_minutes"], p["psd_minutes"])
                for field in ("unique", "status", "reason", "branch"):
                    assert f[field] == p[field]
                scalar_checks += 1
        summary = summaries[method]
        seconds = [float(t["compute_sec"]) for t in timings if t["method"] == method and t["phase"] == "FORMAL"]
        for field, value in [("median_sec", statistics.median(seconds)), ("mean_sec", statistics.mean(seconds)),
                             ("min_sec", min(seconds)), ("max_sec", max(seconds)),
                             ("MAE_min", statistics.mean(errors)), ("MAX_abs_error_min", max(errors)),
                             ("first_boundary_MAX_min", max(first_errors)), ("last_boundary_MAX_min", max(last_errors))]:
            same(summary[field], value)
        assert summary["timing_role"] == "NEW_SAME_JVM_PAIRED" and int(summary["repeats"]) == 3
        assert int(summary["queries_per_pass"]) == queries and int(summary["spatial_samples_per_pass"]) == spatial
        assert int(summary["valid_pixels"]) == 16384 and int(summary["unknown_pixels"]) == 0
        assert int(summary["two_boundary_pixels"]) == dual and int(summary["dark_fallback_pixels"]) == dark
        assert int(summary["limited5_pixels"]) == limited and int(summary["estimated_zero_pixels"]) == 0
        assert max(errors + first_errors + last_errors) < EPS
        assert all(int(summary[f]) == 0 for f in ("error_gt1_pixels", "error_gt5_pixels", "boundary_mismatch_pixels", "window_mismatch_pixels"))
        accuracy[method] = {"valid_pixels": 16384, "max_duration_error_min": max(errors),
                            "first_boundary_max_error_min": max(first_errors), "last_boundary_max_error_min": max(last_errors),
                            "dual_pixels": dual, "dark_pixels": dark, "limited5_pixels": limited}
    # Compare every new candidate node with the saved previous candidate: regression only, no query.
    for new, old in itertools.zip_longest(rows(run / "two_boundary_limited5_integration_nodes.csv"), rows(prior / "integration_nodes.csv")):
        assert new is not None and old is not None and new == old, "candidate node regression"
    pairs = list(rows(run / "paired_timings.csv")); assert len(pairs) == 3
    saved = []
    for i, pair in enumerate(pairs, 1):
        a, b = [next(float(t["compute_sec"]) for t in timings if t["phase"] == "FORMAL" and int(t["pair"]) == i and t["method"] == m) for m in METHODS]
        same(a, pair["baseline_sec"]);same(b, pair["candidate_sec"])
        same(a - b, pair["saved_sec"]);same(1 - b / a, pair["saved_fraction"]);same(a / b, pair["speedup"])
        saved.append(1 - b / a)
    manifest = list(rows(run / "manifest.csv"))
    for entry in manifest:
        assert sha(root / entry["path"]) == entry["sha256"], entry["path"]
    before = list(rows(Path(str(run) + ".protected_before.csv")))
    after = list(rows(Path(str(run) + ".protected_after.csv")))
    assert before == after and len(before) == 362
    for entry in before:
        assert sha(Path(entry["Path"])).upper() == entry["Hash"].upper(), entry["Path"]
    build = root / "user_runs/time_search_paired_compile_20261110_105403_98ada197"
    build_counts = {}
    for category in ("sources", "classes"):
        fingerprints = [line.split("\t", 1) for line in (build / f"build_{category}.sha256").read_text(encoding="utf-8-sig").splitlines()]
        for digest, path in fingerprints:
            assert sha(Path(path)) == digest, path
        actual = {str(p.resolve()) for p in ((root / "src").glob("*.java") if category == "sources" else (build / "classes").rglob("*.class"))}
        assert actual == {str(Path(p).resolve()) for _, p in fingerprints}
        build_counts[category] = len(fingerprints)
    assert "TIME_SEARCH_PAIRED_RECORD_PASS" in (run / "verification.txt").read_text(encoding="utf-8")
    report = {"status": "PASS", "run": str(run.relative_to(root)), "new_scientific_queries_in_audit": 0,
              "recorded_experiment_queries": cumulative, "timing_rows": 8, "formal_pairs": 3,
              "formal_pixel_scalar_checks": scalar_checks, "integration_nodes_reconstructed": node_count,
              "candidate_nodes_equal_prior": True, "accuracy": accuracy, "all_pairs_candidate_faster": all(s > 0 for s in saved),
              "paired_saved_fraction_range": [min(saved), max(saved)],
              "median_time_saved_fraction": 1 - float(summaries["two_boundary_limited5"]["median_sec"]) / float(summaries["binarylocal_30min"]["median_sec"]),
              "manifest_fingerprints_checked": len(manifest), "protected_unchanged": len(before), "build": build_counts,
              "audit_script_sha256": sha(Path(__file__)),
              "limits": ["Audit parses saved records; it does not independently recalculate terrain visibility.",
                         "Full node/edge validation covers diagnostic warmups; formal rounds retain scalar results only.",
                         "Fixed1 is a conditional reference; Huabei has no dark/limited5 branch coverage.",
                         "Three local pairs do not establish universal speedup or physical exactness."]}
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
