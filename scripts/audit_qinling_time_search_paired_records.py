"""Audit saved Qinling paired records; no Java, solar or terrain queries."""
import argparse
import itertools
import json
import math
import statistics
from pathlib import Path

from audit_time_search_paired_records import EPS, key, keyed, properties, rows, same, sha


METHODS = {"binarylocal_30min": (862525, 110493448),
           "two_boundary_limited5": (310488, 34102634)}


def numeric(value):
    return float(value) if value else math.nan


def close(a, b):
    a, b = numeric(a), numeric(b)
    return (math.isnan(a) and math.isnan(b)) or abs(a - b) < EPS


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    run = args.run.resolve()
    assert run.parent == root / "user_runs" and run.name.startswith("qinling_time_search_paired_")
    report_path = run / "record_audit.json"
    assert not report_path.exists(), "preserve prior audit"
    config = properties(run / "config.properties")
    reference = root / "configs" / config["reference_run"]
    history = root / "configs" / config["regression_run"]
    prior = root / "configs" / config["candidate_regression_run"] / "two_boundary_limited5"
    ref = keyed(reference / "fixed_1min_r1/psd.csv")
    intervals = keyed(reference / "fixed_1min_r1/intervals.csv")
    baseline_old = keyed(history / "binarylocal_30min_r1/psd.csv")
    candidate_old = keyed(prior / "pixels.csv")
    timings = list(rows(run / "run_timings.csv"))
    expected = [("WARMUP_DIAGNOSTIC_EXCLUDED", 0, 1, "two_boundary_limited5"),
                ("WARMUP_DIAGNOSTIC_EXCLUDED", 0, 2, "binarylocal_30min")]
    for pair in range(1, 4):
        order = list(METHODS) if pair != 2 else list(reversed(METHODS))
        expected.extend(("FORMAL", pair, i, method) for i, method in enumerate(order, 1))
    assert len(timings) == len(expected) == 8
    cumulative = 0
    for t, expected_row in zip(timings, expected):
        assert (t["phase"], int(t["pair"]), int(t["order"]), t["method"]) == expected_row
        queries, spatial = METHODS[t["method"]]
        assert int(t["pixels"]) == 16384
        assert int(t["real_queries"]) == queries and int(t["spatial_samples"]) == spatial
        assert int(t["visible"]) + int(t["shaded"]) + int(t["unknown"]) == queries
        assert int(t["unknown"]) == 0
        assert 0 < int(t["ray_elapsed_nanos"]) <= float(t["compute_sec"]) * 1e9 + 1
        cumulative += queries
        assert cumulative == int(t["cumulative_real_queries"])
    assert cumulative == 4692052
    summaries = {r["method"]: r for r in rows(run / "summary.csv")}
    assert set(summaries) == set(METHODS)
    accuracy = {}
    scalar_checks = node_count = evaluated_count = 0
    for method, (queries, spatial) in METHODS.items():
        pixels = keyed(run / f"{method}_pixels.csv")
        assert list(pixels) == list(ref) == list(intervals)
        assert sum(int(p["unique"]) for p in pixels.values()) == queries
        integration = itertools.groupby(rows(run / f"{method}_integration_nodes.csv"), key=key)
        evaluated = itertools.groupby(rows(run / f"{method}_evaluated_nodes.csv"), key=key)
        seen = []
        method_nodes = method_evaluated = 0
        for left, right in itertools.zip_longest(integration, evaluated):
            assert left is not None and right is not None and left[0] == right[0]
            k = left[0]
            seen.append(k)
            p = pixels[k]
            nodes = [(float(n["minute_since_sunrise"]), int(n["state"])) for n in left[1]]
            observations = [(float(n["minute_since_sunrise"]), int(n["state"])) for n in right[1]]
            observed = dict(observations)
            assert len(observed) == len(observations) == int(p["unique"])
            assert all(a < b for (a, _), (b, _) in zip(observations, observations[1:]))
            assert all(s in (0, 1) for _, s in observations)
            times, states = zip(*nodes)
            assert all(a < b for a, b in zip(times, times[1:]))
            assert all(observed[t] == s for t, s in nodes)
            same(times[0], 0); same(times[-1], p["daylight_minutes"])
            integral = sum((b - a) * (sa + sb) / 2 for (a, sa), (b, sb) in zip(nodes, nodes[1:]))
            same(integral, p["psd_minutes"])
            lit = [i for i, s in enumerate(states) if s == 1]
            if lit:
                first, last = lit[0], lit[-1]
                start = times[0] if first == 0 else (times[first - 1] + times[first]) / 2
                end = times[-1] if last == len(times) - 1 else (times[last] + times[last + 1]) / 2
            else:
                start = end = math.nan
            windows = sum(s == 1 and (i == 0 or states[i - 1] != 1) for i, s in enumerate(states))
            assert close(str(start), p["first_boundary"]) and close(str(end), p["last_boundary"])
            assert windows == int(p["light_intervals"])
            method_nodes += len(nodes); method_evaluated += len(observations)
        assert seen == list(pixels) and method_evaluated == queries
        node_count += method_nodes; evaluated_count += method_evaluated
        errors, first_errors, last_errors = [], [], []
        dual = dark = limited = estimated = boundary_mismatch = window_mismatch = 0
        for k, p in pixels.items():
            r, s = ref[k], intervals[k]
            assert p["status"] == "OK"
            same(p["reference_psd"], r["psd_minutes"])
            assert close(p["reference_first_boundary"], s["first_boundary_min"])
            assert close(p["reference_last_boundary"], s["last_boundary_min"])
            same(p["daylight_minutes"], r["daylight_minutes"])
            error = abs(float(p["psd_minutes"]) - float(r["psd_minutes"]))
            same(error, p["absolute_error"])
            errors.append(error)
            for field, ref_field, output in [("first_boundary", "first_boundary_min", first_errors),
                                              ("last_boundary", "last_boundary_min", last_errors)]:
                a, b = numeric(p[field]), numeric(s[ref_field])
                if math.isfinite(a) and math.isfinite(b):
                    output.append(abs(a - b))
            boundary_mismatch += not (close(p["first_boundary"], s["first_boundary_min"]) and close(p["last_boundary"], s["last_boundary_min"]))
            assert p["reference_light_intervals"] == s["light_intervals"]
            window_mismatch += p["light_intervals"] != s["light_intervals"]
            original = method == "binarylocal_30min"
            old = baseline_old[k] if original else candidate_old[k]
            same(p["psd_minutes"], old["psd_minutes"])
            assert p["unique"] == old["sample_count" if original else "unique"]
            assert p["reason"] == old["fallback_reason" if original else "reason"]
            if not original:
                for field in ("status", "branch", "light_intervals", "estimated_zero"):
                    assert p[field] == old[field]
                assert close(p["first_boundary"], old["first_boundary"]) and close(p["last_boundary"], old["last_boundary"])
            dual += p["branch"] == "TWO_BOUNDARY"
            dark += p["branch"] == "MIDDAY_DARK_FALLBACK"
            limited += p["reason"].startswith("DARK_5MIN_")
            estimated += p["reason"] == "DARK_5MIN_ESTIMATED_ZERO"
            assert (p["estimated_zero"] == "true") == (p["reason"] == "DARK_5MIN_ESTIMATED_ZERO")
        for pair in range(1, 4):
            formal = keyed(run / f"{method}_formal_{pair}.csv")
            assert list(formal) == list(pixels)
            for k, p in pixels.items():
                same(formal[k]["psd_minutes"], p["psd_minutes"])
                for field in ("unique", "status", "reason", "branch"):
                    assert formal[k][field] == p[field]
                scalar_checks += 1
        summary = summaries[method]
        seconds = [float(t["compute_sec"]) for t in timings if t["phase"] == "FORMAL" and t["method"] == method]
        for field, value in [("median_sec", statistics.median(seconds)), ("mean_sec", statistics.mean(seconds)),
                             ("min_sec", min(seconds)), ("max_sec", max(seconds)), ("MAE_min", statistics.mean(errors)),
                             ("MAX_abs_error_min", max(errors)), ("first_boundary_MAX_min", max(first_errors)),
                             ("last_boundary_MAX_min", max(last_errors))]:
            same(summary[field], value)
        totals = {"repeats": 3, "queries_per_pass": queries, "spatial_samples_per_pass": spatial, "valid_pixels": 16384,
                  "unknown_pixels": 0, "error_gt1_pixels": sum(e > 1 + EPS for e in errors),
                  "error_gt5_pixels": sum(e > 5 + EPS for e in errors), "boundary_mismatch_pixels": boundary_mismatch,
                  "window_mismatch_pixels": window_mismatch, "two_boundary_pixels": dual, "dark_fallback_pixels": dark,
                  "limited5_pixels": limited, "estimated_zero_pixels": estimated}
        for field, value in totals.items():
            assert int(summary[field]) == value, (method, field, value)
        assert summary["timing_role"] == "NEW_SAME_JVM_PAIRED"
        accuracy[method] = dict(totals, MAE_min=statistics.mean(errors), MAX_abs_error_min=max(errors),
                                first_boundary_MAX_min=max(first_errors), last_boundary_MAX_min=max(last_errors),
                                integration_nodes=method_nodes, evaluated_nodes=method_evaluated)
    for filename in ("integration_nodes.csv", "evaluated_nodes.csv"):
        for new, old in itertools.zip_longest(rows(run / f"two_boundary_limited5_{filename}"), rows(prior / filename)):
            assert new is not None and old is not None and new == old, (filename, "candidate node regression")
    pairs = list(rows(run / "paired_timings.csv")); assert len(pairs) == 3
    savings = []
    for i, pair in enumerate(pairs, 1):
        a, b = [next(float(t["compute_sec"]) for t in timings if t["phase"] == "FORMAL" and int(t["pair"]) == i and t["method"] == m) for m in METHODS]
        assert int(pair["pair"]) == i and pair["order"] == ("candidate_then_baseline" if i == 2 else "baseline_then_candidate")
        same(a, pair["baseline_sec"]); same(b, pair["candidate_sec"])
        same(a - b, pair["saved_sec"]); same(1 - b / a, pair["saved_fraction"]); same(a / b, pair["speedup"])
        assert int(pair["baseline_queries"]) == 862525 and int(pair["candidate_queries"]) == 310488
        savings.append(1 - b / a)
    manifest = list(rows(run / "manifest.csv"))
    assert len({r["path"] for r in manifest}) == len(manifest)
    for entry in manifest:
        assert sha(root / entry["path"]) == entry["sha256"], entry["path"]
    before = list(rows(Path(str(run) + ".protected_before.csv")))
    after = list(rows(Path(str(run) + ".protected_after.csv")))
    assert before == after and len(before) == 408
    for entry in before:
        assert sha(Path(entry["Path"])).upper() == entry["Hash"].upper(), entry["Path"]
    build = next((root / r["path"]).parent for r in manifest if r["path"].endswith("build_sources.sha256"))
    build_counts = {}
    for category in ("sources", "classes"):
        fingerprints = [line.split("\t", 1) for line in (build / f"build_{category}.sha256").read_text(encoding="utf-8-sig").splitlines()]
        for digest, path in fingerprints:
            assert sha(Path(path)) == digest, path
        actual = {str(p.resolve()) for p in ((root / "src").glob("*.java") if category == "sources" else (build / "classes").rglob("*.class"))}
        assert actual == {str(Path(path).resolve()) for _, path in fingerprints}
        build_counts[category] = len(fingerprints)
    assert "QINLING_TIME_SEARCH_PAIRED_RECORD_PASS" in (run / "verification.txt").read_text(encoding="utf-8")
    report = {"status": "PASS", "run": str(run.relative_to(root)), "new_scientific_queries_in_audit": 0,
              "recorded_experiment_queries": cumulative, "formal_pairs": 3, "formal_pixel_scalar_checks": scalar_checks,
              "integration_nodes_reconstructed": node_count, "evaluated_nodes_checked": evaluated_count,
              "candidate_integration_and_evaluated_nodes_equal_prior": True, "accuracy": accuracy,
              "all_pairs_candidate_faster": all(s > 0 for s in savings), "paired_saved_fraction_range": [min(savings), max(savings)],
              "median_time_saved_fraction": 1 - float(summaries["two_boundary_limited5"]["median_sec"]) / float(summaries["binarylocal_30min"]["median_sec"]),
              "manifest_fingerprints_checked": len(manifest), "protected_unchanged": len(before), "build": build_counts,
              "audit_script_sha256": sha(Path(__file__)), "audit_helper_sha256": sha(root / "scripts/audit_time_search_paired_records.py"),
              "limits": ["Saved-record audit does not independently recompute solar or terrain visibility.",
                         "Diagnostic warmup nodes are fully audited; formal rounds retain scalar results only.",
                         "Known duration and edge errors are outcomes, not a zero-error gate.",
                         "Qinling is the development scene; repetitions do not independently validate five-minute generalization.",
                         "Fixed1 remains a conditional reference; no new spatial-block combination is tested."]}
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
