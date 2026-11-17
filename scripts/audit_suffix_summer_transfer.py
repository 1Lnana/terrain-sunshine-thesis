"""Audit saved summer pairs and fixed1 accuracy, without visibility or time scheduling."""
import csv
import gzip
import json
import math
import random
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path
from audit_suffix_combination import check, rows, sha, bits, scalar_digest, save

ROOT = Path(__file__).resolve().parents[1]
WINTER = ROOT / 'user_runs/suffix_combination_20261111_144944_5bf789cd'


def qtile(v, q):
    s = sorted(v)
    x = (len(s) - 1) * q
    lo = math.floor(x)
    hi = math.ceil(x)
    return s[lo] + (s[hi] - s[lo]) * (x - lo)


def describe(v):
    return {'median': statistics.median(v), 'mean': statistics.mean(v), 'min': min(v), 'max': max(v),
            'sample_sd': statistics.stdev(v), 'q25': qtile(v, .25), 'q75': qtile(v, .75),
            'iqr': qtile(v, .75) - qtile(v, .25)}


def main():
    out = Path(sys.argv[1]).resolve()
    check(out.is_relative_to(ROOT / 'user_runs') and not (out / 'record_audit.json').exists(), 'new audit only')
    result = out / 'results'
    frozen = json.loads((out / 'frozen_version.json').read_text(encoding='utf-8'))
    check(frozen['status'] == 'REVIEW_AND_ARTIFICIAL_GATES_PASS_FROZEN_BEFORE_SUMMER' and not frozen['rules_changed'], 'review/freeze gate')
    protected = json.loads((out / 'protected_before.json').read_text(encoding='utf-8'))
    check(protected == frozen['sources_configs_scripts'] == json.loads((out / 'protected_after.json').read_text(encoding='utf-8')), 'frozen version unchanged')
    for name, digest in protected.items():
        check(sha(ROOT / name) == digest, 'source/config/script changed ' + name)
    for name, digest in frozen['compiled_classes'].items():
        check(sha(out / name) == digest, 'compiled class changed')
    for name, digest in frozen['inputs_and_historical_records'].items():
        check(sha(Path(name)) == digest, 'input/history changed ' + name)
    review = json.loads((out / 'winter_record_review/record_audit.json').read_text(encoding='utf-8'))
    check(review['status'] == 'PASS_CONTROLLED_SUFFIX_RECORDS', 'winter records re-audit')
    padded = list(rows(out / 'padded_boundary_gate/padded_threshold.csv'))
    check(len(padded) == 5, 'padded boundary count')
    for r in padded:
        check(r['expected_suffix_samples'] == r['actual_suffix_samples'], 'padded count')
        if r['case'] in ['below', 'equal', 'above']:
            limit, ray = float.fromhex(r['padded_limit_hex']), float.fromhex(r['R6_hex'])
            check((limit < ray if r['case'] == 'below' else limit == ray if r['case'] == 'equal' else limit > ray), 'strict padded boundary')
    total = Counter()
    bins = defaultdict(Counter)
    ray_maps = defaultdict(dict)
    ray_query_count = Counter()
    for r in rows(result / 'paired_rays.csv.gz'):
        i = int(r['receiver_index'])
        check(int(r['query_index']) == ray_query_count[i], 'fresh query order')
        ray_query_count[i] += 1
        check(r['b_status'] == r['s_status'] == r['point_status'], 'B/suffix/point state')
        check(bits(r['b_stop_hex']) == bits(r['s_stop_hex']) == bits(r['point_stop_hex']), 'first stop bits')
        b, s, point, suffix = [int(r[k]) for k in ['b_interpolations', 's_interpolations', 'point_interpolations', 'suffix_samples']]
        check(b - s == suffix and int(r['s_skipped']) - int(r['b_skipped']) == suffix, 'actual suffix delta')
        check(b + int(r['b_skipped']) == s + int(r['s_skipped']) == point, 'full prefix conservation')
        check(point * 50.0 == float.fromhex(r['b_stop_hex']), 'stop index')
        tm = r['time_hex']
        state = 1 if r['b_status'] == 'VISIBLE' else 0 if r['b_status'] == 'SHADED' else -1
        check(tm not in ray_maps[i], 'oracle query must be fresh')
        ray_maps[i][tm] = state
        b0 = sum(float.fromhex(r['altitude_hex']) >= x for x in [1, 5, 10, 20, 40])
        metrics = {'rays': 1, 'b_interpolations': b, 's_interpolations': s, 'suffix_samples': suffix,
                   'blocks': int(r['blocks']), 'eligibility_checks': int(r['eligibility_checks']), 'end_tests': int(r['end_tests']),
                   'boundary_tests': int(r['boundary_tests']), 'planned_suffixes': int(r['planned_suffixes']),
                   'actual_suffix_segments': int(r['actual_suffix_segments']), 'visible': state == 1, 'shaded': state == 0, 'unknown': state == -1}
        bins[b0].update(metrics)
        total.update(metrics)
        total['point_interpolations'] += point
        total['b_skipped'] += int(r['b_skipped'])
        total['s_skipped'] += int(r['s_skipped'])
    saved = next(rows(result / 'counts_summer.csv'))
    for name, value in total.items():
        if name != 'point_interpolations':
            check(int(saved[name]) == value, 'saved totals ' + name)
    for r in rows(result / 'altitude_counts.csv'):
        for name, v in r.items():
            if name != 'bin':
                check(int(v) == bins[int(r['bin'])][name], 'altitude count ' + name)
    pixels = list(rows(result / 'paired_pixels.csv'))
    check(len(pixels) == 16384 and sum(ray_query_count.values()) == total['rays'], 'pixel/ray coverage')
    reference = list(rows(ROOT / 'user_runs/qinling_fixed1_v1_001/fixed_1min_r1/psd.csv'))
    node_groups = defaultdict(list)
    for r in rows(result / 'paired_nodes.csv.gz'):
        check(r['b_state'] == r['s_state'], 'paired temporal node state')
        node_groups[int(r['receiver_index'])].append(r)
    request_groups = defaultdict(list)
    for r in rows(result / 'paired_requests.csv.gz'):
        check(r['pair_match'] == 'true', 'paired actual request trace')
        request_groups[int(r['receiver_index'])].append(r)
    canonical = []
    errors = []
    error_counts = Counter()
    tile = defaultdict(list)
    branches = Counter()
    large = []
    integration_nodes = 0
    request_total = 0
    for i, (p, ref) in enumerate(zip(pixels, reference)):
        check(int(p['receiver_index']) == i and p['row'] == ref['row'] and p['col'] == ref['col'], 'pixel/reference identity')
        check(bits(p['b_psd_hex']) == bits(p['s_psd_hex']) and bits(p['reference_psd_hex']) == bits(float(ref['psd_minutes']).hex()), 'spatial PSD/reference bits')
        check(int(p['queries']) == ray_query_count[i], 'actual temporal query count')
        eval_map = {r['time_hex']: int(r['b_state']) for r in node_groups[i]}
        check(len(eval_map) == len(node_groups[i]) and eval_map == ray_maps[i], 'evaluated map vs actual query values')
        nodes = [r for r in node_groups[i] if r['integration_node'] == '1']
        integration_nodes += len(nodes)
        accum = 0.0
        for a, b in zip(nodes, nodes[1:]):
            check(float.fromhex(b['time_hex']) > float.fromhex(a['time_hex']), 'integration sorted')
            if int(a['b_state']) < 0 or int(b['b_state']) < 0:
                accum = math.nan
                break
            accum += (int(a['b_state']) + int(b['b_state'])) * .5 * (float.fromhex(b['time_hex']) - float.fromhex(a['time_hex']))
        actual = float.fromhex(p['b_psd_hex'])
        check((math.isnan(accum) and math.isnan(actual)) or abs(accum - actual) < 1e-8, 'independent integration')
        trace = request_groups[i]
        check(len(trace) == int(p['requests']) and [int(r['request_index']) for r in trace] == list(range(len(trace))), 'request count/order')
        fresh = [r for r in trace if r['fresh'] == 'true']
        check(len(fresh) == int(p['queries']) and len(trace) - len(fresh) == int(p['hits']), 'memo hits/fresh')
        check({r['time_hex']: int(r['state']) for r in fresh} == ray_maps[i], 'memo fresh vs actual oracle')
        purposes = Counter(r['purpose'] for r in fresh)
        check(purposes['anchor'] == int(p['anchor']) and purposes['boundary'] == int(p['boundary']) and purposes['fallback'] == int(p['fallback']), 'memo purpose costs')
        request_total += len(trace)
        err = abs(actual - float(ref['psd_minutes']))
        check((math.isnan(err) and math.isnan(float.fromhex(p['absolute_error_hex']))) or bits(err.hex()) == bits(p['absolute_error_hex']), 'absolute error raw value')
        label = 'UNKNOWN' if not math.isfinite(err) else 'NUMERIC_ZERO' if err <= 1e-8 else 'LE1' if err <= 1 else 'GT1_LE5' if err <= 5 else 'GT5_LE10' if err <= 10 else 'GT10_LE30' if err <= 30 else 'GT30'
        check(p['error_bin'] == label, 'error bin')
        error_counts[label] += 1
        branches[p['branch'] + '/' + p['reason']] += 1
        if math.isfinite(err):
            errors.append(err)
            tile[(int(p['row']) - 114) // 32, (int(p['col']) - 114) // 32].append(err)
        if math.isfinite(err) and err > 1:
            large.append(p)
        canonical.append({'receiver_index': str(i), 'psd_hex': p['b_psd_hex'], 'queries': p['queries'],
                          'status': p['status'], 'reason': p['reason'], 'branch': p['branch']})
    expected_digest = scalar_digest(canonical)
    run_rows = list(rows(result / 'runs.csv'))
    check(len(run_rows) == 26, 'exactly 3 warmup and 10 formal pairs')
    by_method = defaultdict(list)
    for phase, count in [('warmup', 3), ('formal', 10)]:
        for n in range(1, count + 1):
            pair = [r for r in run_rows if r['phase'] == phase and int(r['round']) == n]
            check([r['method'] for r in pair] == (['dual_b', 'dual_suffix'] if n % 2 else ['dual_suffix', 'dual_b']), 'balanced pair order')
    for r in run_rows:
        check(int(r['rays']) == total['rays'] and int(r['interpolations']) == total['s_interpolations' if r['method'] == 'dual_suffix' else 'b_interpolations'], 'repeat query/sample totals')
        check(all(int(r[k]) == total[k] for k in ['visible', 'shaded', 'unknown']), 'repeat states')
        check(int(r['index_payload_bytes']) == 4761, 'same index payload')
        check(abs(float(r['algorithm_sec']) - float(r['build_sec']) - float(r['compute_sec'])) < 1e-12, 'algorithm accounting')
        check(float(r['full_sec']) + 1e-12 >= sum(float(r[k]) for k in ['load_sec', 'build_sec', 'compute_sec', 'write_sec']), 'method full accounting')
        values = list(rows(result / (r['phase'] + '_' + r['round'] + '_' + r['method'] + '_pixels.csv')))
        check(len(values) == 16384 and scalar_digest(values) == expected_digest == r['scalar_sha256'], 'all repeat scalar files')
        if r['phase'] == 'formal':
            by_method[r['method']].append(r)
    summary = {'methods': {}, 'paired': {}, 'reference_is_conditional': True, 'full_is_not_JVM_end_to_end': True}
    for method, rr in by_method.items():
        rr.sort(key=lambda r: int(r['round']))
        summary['methods'][method] = {k: describe([float(r[k]) for r in rr]) for k in ['load_sec', 'build_sec', 'compute_sec', 'algorithm_sec', 'query_sec', 'write_sec', 'full_sec']}
    paired_rows = []
    for a, b in zip(by_method['dual_b'], by_method['dual_suffix']):
        d = {'pair': int(a['round']), 'order': 'B_FIRST' if int(a['position']) == 1 else 'SUFFIX_FIRST'}
        for k in ['algorithm_sec', 'full_sec', 'query_sec']:
            d[k + '_B'] = float(a[k])
            d[k + '_suffix'] = float(b[k])
            d[k + '_difference_B_minus_suffix'] = float(a[k]) - float(b[k])
            d[k + '_fraction_saving'] = 1 - float(b[k]) / float(a[k])
        paired_rows.append(d)
    with (out / 'paired_timings.csv').open('x', encoding='utf-8', newline='') as f:
        w = csv.DictWriter(f, paired_rows[0].keys())
        w.writeheader()
        w.writerows(paired_rows)
    for k in ['algorithm_sec', 'full_sec', 'query_sec']:
        diffs = [r[k + '_difference_B_minus_suffix'] for r in paired_rows]
        fractions = [r[k + '_fraction_saving'] for r in paired_rows]
        rng = random.Random(20261111)
        boot = [statistics.median([diffs[rng.randrange(10)] for _ in range(10)]) for _ in range(10000)]
        summary['paired'][k] = {'difference_B_minus_suffix': describe(diffs), 'paired_fraction_saving': describe(fractions),
             'positive_pairs': sum(v > 0 for v in diffs), 'negative_pairs': sum(v < 0 for v in diffs),
             'ratio_of_method_medians_saving': 1 - summary['methods']['dual_suffix'][k]['median'] / summary['methods']['dual_b'][k]['median'],
             'exploratory_bootstrap_median_difference_95_interval': [qtile(boot, .025), qtile(boot, .975)],
             'bootstrap_seed': 20261111, 'bootstrap_resamples': 10000, 'not_independent_cross_scene_evidence': True}
    accuracy = {'reference': 'existing summer fixed1, conditional discrete reference, reused not rerun',
                'total_pixels': len(pixels), 'common_valid': len(errors), 'unknown_or_invalid': len(pixels) - len(errors),
                'MAE_minutes': math.fsum(errors) / len(errors) if errors else None,
                'RMSE_minutes': math.sqrt(math.fsum(e * e for e in errors) / len(errors)) if errors else None,
                'MAX_minutes': max(errors) if errors else None, 'error_bins': dict(error_counts),
                'above_threshold_counts': {str(t): sum(e > t for e in errors) for t in [.1, 1, 5, 10, 30]},
                'branch_reason_counts': dict(branches), 'two_spatial_methods_equal': True}
    save(out / 'accuracy_summary.json', accuracy)
    with (out / 'error_pixels_gt1.csv').open('x', encoding='utf-8', newline='') as f:
        w = csv.DictWriter(f, pixels[0].keys())
        w.writeheader()
        w.writerows(sorted(large, key=lambda p: float.fromhex(p['absolute_error_hex']), reverse=True))
    with (out / 'accuracy_by_tile.csv').open('x', encoding='utf-8', newline='') as f:
        w = csv.writer(f)
        w.writerow(['tile_row', 'tile_col', 'row_start', 'col_start', 'valid_pixels', 'gt1', 'gt5', 'gt10', 'max_error_minutes', 'MAE_minutes'])
        for (r, c), v in sorted(tile.items()):
            w.writerow([r, c, 114 + 32 * r, 114 + 32 * c, len(v), sum(e > 1 for e in v), sum(e > 5 for e in v), sum(e > 10 for e in v), max(v), math.fsum(v) / len(v)])
    native = defaultdict(list)
    for r in rows(out / 'process_memory_samples.csv'):
        native[r['phase']].append(r)
    for method, rr in by_method.items():
        observations = []
        for r in rr:
            v = native['formal_' + r['round'] + '_' + method]
            observations.append({'pair': int(r['round']), 'samples': len(v),
                'observed_max_working_set': max((int(p['working_set_bytes']) for p in v), default=None),
                'observed_max_private_usage': max((int(p['private_usage_bytes']) for p in v), default=None),
                'heap_used_end': int(r['heap_used_end']), 'heap_pool_peak_sum': int(r['heap_pool_peak_sum'])})
        summary['methods'][method]['memory_observations'] = observations
    # Describe winter/summer workload change using the already saved winter ray records.
    wb = defaultdict(Counter)
    for r in rows(WINTER / 'results/verify_dual_rays.csv.gz'):
        b = sum(float.fromhex(r['altitude_hex']) >= x for x in [1, 5, 10, 20, 40])
        for k in ['b_interpolations', 's_interpolations', 'suffix_samples', 'eligibility_checks', 'end_tests', 'boundary_tests', 'planned_suffixes', 'actual_suffix_segments', 'blocks']:
            wb[b][k] += int(r[k])
        wb[b]['rays'] += 1
    with (out / 'winter_summer_altitude_comparison.csv').open('x', encoding='utf-8', newline='') as f:
        w = csv.DictWriter(f, ['scene', 'bin', 'rays', 'b_interpolations', 's_interpolations', 'suffix_samples', 'blocks', 'eligibility_checks', 'end_tests', 'boundary_tests', 'planned_suffixes', 'actual_suffix_segments'])
        w.writeheader()
        for scene, bb in [('winter', wb), ('summer', bins)]:
            for b in range(6):
                w.writerow({k: scene if k == 'scene' else b if k == 'bin' else bb[b][k] for k in w.fieldnames})
    save(out / 'analysis_summary.json', summary)
    audit = {'status': 'PASS_FROZEN_SUMMER_TRANSFER_RECORDS', 'rays': total['rays'], 'pixels': len(pixels),
             'formal_pairs': 10, 'warmup_pairs': 3, 'paired_ray_status_stop_mismatches': 0,
             'actual_time_sequence_checked_in_Java': True, 'all_requests_nodes_and_pixel_records_match': True,
             'integration_nodes': integration_nodes, 'memo_requests': request_total,
             'all_26_repeat_scalar_files_match': True, 'protected_files': len(protected), 'rules_unchanged': True,
             'winter_original_record_reaudit': 'PASS', 'padded_equality_gate_cases': 5,
             'visibility_or_time_queries_in_this_audit': 0, 'totals': dict(total),
             'accuracy': accuracy, 'memory_not_exact_peak': True}
    save(out / 'record_audit.json', audit)
    print(json.dumps({k: v for k, v in audit.items() if k not in ['totals', 'accuracy']}, ensure_ascii=False))
    print(json.dumps({'paired': summary['paired'], 'accuracy': accuracy}, ensure_ascii=False))
    # Existing plotting dependency only. Figures visualize saved records, never new experiments.
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    import numpy as np
    error_map = np.full((128, 128), np.nan)
    for p in pixels:
        error_map[int(p['row']) - 114, int(p['col']) - 114] = float.fromhex(p['absolute_error_hex'])
    fig, axes = plt.subplots(1, 2, figsize=(12, 4.8), constrained_layout=True)
    im = axes[0].imshow(error_map, cmap='magma', vmin=0, vmax=max(1, max(errors) if errors else 1), extent=[113.5, 241.5, 241.5, 113.5], interpolation='nearest')
    axes[0].set(title='Dual-end absolute error vs fixed1', xlabel='DEM column', ylabel='DEM row')
    fig.colorbar(im, ax=axes[0], label='Absolute error (minutes)')
    xx = list(range(1, 11))
    axes[1].plot(xx, [r['full_sec_B'] for r in paired_rows], 'o-', label='Dual + B')
    axes[1].plot(xx, [r['full_sec_suffix'] for r in paired_rows], 'o-', label='Dual + suffix')
    axes[1].set(title='Method-function full cost (10 pairs)', xlabel='Formal pair', ylabel='Seconds', xticks=xx)
    axes[1].legend()
    axes[1].grid(alpha=.25)
    fig.suptitle('Qinling 128 summer | 2023-06-21 | frozen spatial rules')
    fig.savefig(out / 'summer_accuracy_and_pairs.png', dpi=180)
    fig.savefig(out / 'summer_accuracy_and_pairs.svg')
    plt.close(fig)
    save(out / 'figure_status.json', {'status': 'GENERATED_FROM_AUDITED_RECORDS', 'new_dependencies_installed': False})


if __name__ == '__main__':
    main()
