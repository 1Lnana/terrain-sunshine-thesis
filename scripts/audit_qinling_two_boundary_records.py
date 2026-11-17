"""Audit saved Qinling comparison CSVs only; no Java, solar calculation or terrain queries."""
import collections
import csv
import hashlib
import json
import math
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RUN = ROOT / "user_runs/qinling_two_boundary_compare_20261109_192443_209c0500"
OUT = ROOT / "user_runs/qinling_two_boundary_review_20261109_192443_209c0500"
BASE, CAND = "binarylocal30", "two_boundary_assumption"
EPS = 1e-8


def rows(path):
    with path.open(encoding="utf-8-sig", newline="") as stream:
        yield from csv.DictReader(stream)


def near(a, b):
    return abs(float(a) - float(b)) < EPS


def keyed(path):
    result = {}
    for row in rows(path):
        key = (row["row"], row["col"])
        assert key not in result, (path, key)
        result[key] = row
    return result


def check_nodes(method, pixels):
    totals = {}
    selected = {}
    current_key, nodes = None, []

    def finish(key, values):
        if key is None:
            return
        p = pixels[key]
        assert len(values) == int(p["unique"]), (method, key, "node/query count")
        assert all(0 <= t <= float(p["daylight_minutes"]) + EPS and s in (-1, 0, 1) for t, s in values)
        assert all(a[0] < b[0] for a, b in zip(values, values[1:]))
        if p["status"] == "OK":
            assert near(values[0][0], 0) and near(values[-1][0], p["daylight_minutes"])
            assert all(s >= 0 for _, s in values)
            integral = sum((b - a) * (x + y) / 2 for (a, x), (b, y) in zip(values, values[1:]))
            assert near(integral, p["psd_minutes"]), (method, key, "integral")
            light = [i for i, (_, s) in enumerate(values) if s == 1]
            windows = sum(s == 1 and (i == 0 or values[i - 1][1] != 1) for i, (_, s) in enumerate(values))
            assert windows == int(p["light_intervals"])
            if light:
                first, last = light[0], light[-1]
                begin = values[0][0] if first == 0 else (values[first - 1][0] + values[first][0]) / 2
                end = values[-1][0] if last == len(values) - 1 else (values[last][0] + values[last + 1][0]) / 2
                assert near(begin, p["first_boundary"]) and near(end, p["last_boundary"])
        elif p["status"] == "NOT_APPLICABLE":
            assert method == CAND and len(values) == 1 and values[0][1] == 0
            assert near(values[0][0], math.floor(float(p["daylight_minutes"]) / 2))
            assert p["psd_minutes"] == p["absolute_error"] == p["first_boundary"] == p["last_boundary"] == ""
        totals[key] = len(values)
        if key in trace_keys:
            selected[key] = values

    for row in rows(RUN / method / "integration_nodes.csv"):
        key = (row["row"], row["col"])
        if key != current_key:
            finish(current_key, nodes)
            assert key not in totals, (method, key, "noncontiguous nodes")
            current_key, nodes = key, []
        nodes.append((float(row["minute_since_sunrise"]), int(row["state"])))
    finish(current_key, nodes)
    assert totals.keys() == pixels.keys()
    queries = collections.defaultdict(list)
    for row in rows(RUN / method / "queries.csv"):
        queries[row["row"], row["col"]].append(row)
    assert queries.keys() == trace_keys
    for key, records in queries.items():
        observed = {}
        purpose = collections.Counter()
        for i, row in enumerate(records, 1):
            assert int(row["request_index"]) == i
            t, state = float(row["minute_since_sunrise"]), int(row["state"])
            fresh = t not in observed
            assert fresh == (row["is_new"] == "true")
            if fresh:
                observed[t] = state
                purpose[row["purpose"]] += 1
            else:
                assert observed[t] == state
        assert sorted(observed.items()) == selected[key]
        p = pixels[key]
        assert len(records) == int(p["requests"])
        for field in ("anchor", "boundary", "other", "fallback"):
            assert purpose[field] == int(p[field])
    return sum(totals.values()), selected


if __name__ == "__main__":
    assert not OUT.exists(), "keep existing audit output; choose a new directory for another review"
    manifest = list(rows(RUN / "manifest.csv"))
    assert len({r["path"] for r in manifest}) == len(manifest)
    for r in manifest:
        assert hashlib.sha256((ROOT / r["path"]).read_bytes()).hexdigest() == r["sha256"]
    protected = list(rows(Path(str(RUN) + ".protected_before.csv")))
    after = list(rows(Path(str(RUN) + ".protected_after.csv")))
    assert {r["Path"]: r["Hash"] for r in protected} == {r["Path"]: r["Hash"] for r in after}
    for r in protected:
        assert hashlib.sha256(Path(r["Path"]).read_bytes()).hexdigest().upper() == r["Hash"]
    reference = keyed(ROOT / "user_runs/qinling_spatial_ablation_001/fixed_1min_r1/intervals.csv")
    history = keyed(ROOT / "user_runs/qinling_binary_local_001/binarylocal_30min_r1/psd.csv")
    pixels = {m: keyed(RUN / m / "pixels.csv") for m in (BASE, CAND)}
    assert len(reference) == 16384 and pixels[BASE].keys() == pixels[CAND].keys() == reference.keys() == history.keys()
    config = dict(line.split("=", 1) for line in (RUN / "config.properties").read_text().splitlines() if line and not line.startswith("#"))
    trace_keys = {tuple(s.split(",")) for s in config["trace_pixels"].split(";")}
    total_nodes, selected = {}, {}
    for method in (BASE, CAND):
        for key, p in pixels[method].items():
            assert int(p["requests"]) == int(p["unique"]) + int(p["cache_hits"])
            assert int(p["unique"]) == sum(int(p[f]) for f in ("anchor", "boundary", "other", "fallback"))
            assert near(p["reference_psd"], reference[key]["psd_minutes"])
            if p["status"] == "OK":
                error = float(p["psd_minutes"]) - float(p["reference_psd"])
                assert near(error, p["signed_error"]) and near(abs(error), p["absolute_error"])
            if method == BASE:
                assert near(p["psd_minutes"], history[key]["psd_minutes"])
                assert int(p["unique"]) == int(history[key]["sample_count"])
            else:
                assert int(p["unique"]) <= 22 and int(p["fallback"]) == 0
        total_nodes[method], selected[method] = check_nodes(method, pixels[method])
    assert total_nodes[BASE] == 862525 and total_nodes[CAND] <= 344064
    historical_nodes = collections.defaultdict(list)
    for r in rows(ROOT / "user_runs/qinling_binary_local_001/binarylocal_30min_r1/samples.csv"):
        historical_nodes[r["row"], r["col"]].append((float(r["minutes_since_sunrise"]), int(r["state"])))
    for key, nodes in historical_nodes.items():
        assert len(nodes) == len(selected[BASE][key])
        assert all(near(a[0], b[0]) and a[1] == b[1] for a, b in zip(nodes, selected[BASE][key]))
    replay = collections.defaultdict(list)
    for r in rows(ROOT / "user_runs/time_structure_minimal_20261109_134054_3953499c/integration_nodes.csv"):
        if r["case_id"].startswith("qinling128_winter_") and r["method"] == CAND:
            key = tuple(r["case_id"].removeprefix("qinling128_winter_").split("_"))
            replay[key].append((float(r["time"]), int(r["state"])))
    assert len(replay) == 3
    for key, nodes in replay.items():
        assert len(nodes) == len(selected[CAND][key])
        assert all(near(a[0], b[0]) and a[1] == b[1] for a, b in zip(nodes, selected[CAND][key]))
    comparison = keyed(RUN / "comparison.csv")
    assert comparison.keys() == reference.keys()
    common = {k for k in reference if all(pixels[m][k]["status"] == "OK" for m in (BASE, CAND))}
    for key, r in comparison.items():
        a, b = pixels[BASE][key], pixels[CAND][key]
        assert (r["common_ok"] == "true") == (key in common)
        assert int(r["query_difference_baseline_minus_candidate"]) == int(a["unique"]) - int(b["unique"])
        assert r["baseline_compute_nanos"] == a["compute_nanos"] and r["candidate_compute_nanos"] == b["compute_nanos"]
    for r in rows(RUN / "summary.csv"):
        method, scope = r["method"], r["scope"]
        keys = reference.keys() if scope == "ALL_ATTEMPTED" else common if scope == "COMMON_OK" else {k for k in reference if comparison[k]["reference_group"] == scope}
        ps = [pixels[method][k] for k in keys]
        valid = [p for p in ps if p["status"] == "OK"]
        errors = sorted(float(p["absolute_error"]) for p in valid)
        assert int(r["attempted_pixels"]) == len(ps) and int(r["ok_pixels"]) == len(valid)
        assert int(r["not_applicable_pixels"]) == sum(p["status"] == "NOT_APPLICABLE" for p in ps)
        assert int(r["unknown_pixels"]) == sum(p["status"] == "UNKNOWN" for p in ps)
        assert int(r["real_queries"]) == sum(int(p["unique"]) for p in ps)
        assert int(r["error_evaluated_pixels"]) == len(valid)
        if ps:
            assert near(r["completion_fraction"], len(valid) / len(ps))
        if errors:
            for field, value in (("MAE_min", sum(errors) / len(errors)), ("MAX_abs_error_min", errors[-1]),
                                 ("P95_abs_error_min", errors[math.ceil(.95 * len(errors)) - 1]),
                                 ("P99_abs_error_min", errors[math.ceil(.99 * len(errors)) - 1])):
                assert near(r[field], value), (method, scope, field)
        for threshold in (1, 5):
            assert int(r[f"error_gt{threshold}_pixels"]) == sum(e > threshold + EPS for e in errors)
        assert int(r["window_count_mismatch_pixels"]) == sum(p["light_intervals"] != p["reference_light_intervals"] for p in valid)
        for side in ("first", "last"):
            boundary_errors = [abs(float(p[f"{side}_error"])) for p in valid if p[f"{side}_error"]]
            assert int(r[f"{side}_boundary_error_pixels"]) == len(boundary_errors)
            if boundary_errors:
                assert near(r[f"{side}_boundary_MAE_min"], sum(boundary_errors) / len(boundary_errors))
                assert near(r[f"{side}_boundary_MAX_min"], max(boundary_errors))
        if scope != "ALL_ATTEMPTED":
            assert near(r["compute_sec"], sum(int(p["compute_nanos"]) for p in ps) / 1e9)
    report = {"status": "SAVED_RECORD_AUDIT_PASS", "manifest_hashes_verified": len(manifest),
              "protected_unchanged": len(protected), "pixels_per_method": 16384,
              "integration_nodes_checked": total_nodes, "common_ok_pixels": len(common),
              "historical_trace_regressions": len(historical_nodes), "candidate_replay_regressions": len(replay),
              "na_by_reference_group": dict(collections.Counter(comparison[k]["reference_group"] for k, p in pixels[CAND].items() if p["status"] == "NOT_APPLICABLE")),
              "candidate_nonzero_duration_errors": [{"row": k[0], "col": k[1], "signed_error": p["signed_error"], "first_error": p["first_error"], "last_error": p["last_error"]} for k, p in pixels[CAND].items() if p["absolute_error"] and float(p["absolute_error"]) > EPS],
              "timing_limit": "Single pass, baseline first, includes prototype bookkeeping; no stable performance inference",
              "no_search_or_visibility_executed": True}
    OUT.mkdir()
    (OUT / "independent_record_audit.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=True))
