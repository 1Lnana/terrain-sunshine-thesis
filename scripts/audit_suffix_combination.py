"""Independent audit of saved paired rays, temporal maps, repeated scalars and costs."""
import csv
import gzip
import hashlib
import itertools
import json
import math
import statistics
import struct
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HISTORY = ROOT / 'user_runs/qinling_time_search_paired_20261110_125802_39f7a1e4'


def check(ok, message):
    if not ok:
        raise AssertionError(message)


def rows(p):
    opener = gzip.open if p.suffix == '.gz' else open
    with opener(p, 'rt', encoding='utf-8-sig', newline='') as f:
        for r in csv.DictReader(f):
            check(None not in r and all(v is not None for v in r.values()), 'CSV width ' + str(p))
            yield r


def sha(p):
    with p.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def bits(s):
    return struct.pack('>d', float.fromhex(s))


def save(p, v):
    with p.open('x', encoding='utf-8') as f:
        json.dump(v, f, ensure_ascii=False, indent=2)


def scalar_digest(pixels):
    digest = hashlib.sha256()
    for p in pixels:
        digest.update(struct.pack('>di', float.fromhex(p['psd_hex']), int(p['queries'])))
        for key in ['status', 'reason', 'branch']:
            digest.update(p[key].encode() + b'\0')
    return digest.hexdigest()


def main():
    out = Path(sys.argv[1]).resolve()
    check(out.is_relative_to(ROOT / 'user_runs') and not (out / 'record_audit.json').exists(), 'fresh audit required')
    results = out / 'results'
    protected = json.loads((out / 'protected_before.json').read_text(encoding='utf-8'))
    check(protected == json.loads((out / 'protected_after.json').read_text(encoding='utf-8')), 'protected before/after')
    for name, digest in protected.items():
        check(sha(ROOT / name) == digest, 'protected source ' + name)
    for name, digest in json.loads((out / 'frozen_inputs.json').read_text(encoding='utf-8')).items():
        check(sha(Path(name)) == digest, 'frozen input/history ' + name)
    for name, digest in json.loads((out / 'compiled_classes.json').read_text(encoding='utf-8')).items():
        check(sha(out / name) == digest, 'compiled class ' + name)
    # Source-level boundary: original index and interpolation helpers are unchanged.
    original = (ROOT / 'src/Block16DirectionVisibilityNoStats.java').read_text(encoding='utf-8-sig')
    for name in ['SuffixBlockVisibility', 'SuffixBlockVisibilityNoStats']:
        text = (ROOT / ('src/' + name + '.java')).read_text(encoding='utf-8-sig')
        check(original[original.index('    private static int segmentEnd'):] == text[text.index('    private static int segmentEnd'):], 'geometry/interpolation preserved')
        check('skip = complete[index] && Double.isFinite(ray) && maxima[index] + pad < ray;' in text, 'whole-segment predicate')
    artificial = list(rows(out / 'artificial/synthetic.csv')) + list(rows(out / 'artificial/suffix_specific.csv'))
    check(len({r['status'] for r in artificial}) == 5, 'five status artificial coverage')
    for r in artificial:
        check(int(r['b_interpolations']) - int(r['s_interpolations']) == int(r['suffix_samples']), 'artificial count conservation')
    check(int(next(r for r in artificial if r['case'] == 'suffix_active')['suffix_samples']) > 0, 'suffix activation')
    completion = json.loads((results / 'completion.json').read_text(encoding='utf-8'))
    check(completion['formal_rounds'] == 3 and completion['warmups_per_method'] == 2 and not completion['automatic_extra_rounds'], 'fixed protocol')
    tags = ['old', 'dual'] if completion['dual_correct'] else ['old']
    totals = {}
    verified_pixels = {}
    for tag in tags:
        total = Counter()
        pixel_query_counts = Counter()
        query_index = Counter()
        node_states = []
        frozen_iter = rows(ROOT / 'user_runs/qinling_128_strip_t2_verify_001/results/rays.csv.gz') if tag == 'old' else None
        for r in rows(results / ('verify_' + tag + '_rays.csv.gz')):
            i = int(r['receiver_index'])
            check(int(r['query_index']) == query_index[i], 'query index')
            query_index[i] += 1
            check(r['b_status'] == r['s_status'] and bits(r['b_stop_hex']) == bits(r['s_stop_hex']), 'paired ray behavior')
            b, s, suffix = [int(r[k]) for k in ['b_interpolations', 's_interpolations', 'suffix_samples']]
            check(b - s == suffix and int(r['s_skipped']) - int(r['b_skipped']) == suffix, 'actual suffix savings')
            check(b + int(r['b_skipped']) == s + int(r['s_skipped']), 'old/new covered prefix')
            stop = float.fromhex(r['b_stop_hex'])
            check((b + int(r['b_skipped'])) * 50 == stop, 'stop and original scheduled indices')
            if frozen_iter is not None:
                f = next(frozen_iter)
                check(i == int(f['receiver_index']) and int(r['query_index']) == int(f['query_index']), 'frozen old order')
                for key in ['time_hex', 'altitude_hex', 'bearing_hex']:
                    check(bits(r[key]) == bits(f[key]), 'frozen old parameter ' + key)
                check(r['b_status'] == f['b_status'] and bits(r['b_stop_hex']) == bits(f['b_stop_hex']) and b == int(f['b_samples']), 'frozen old ray')
            total.update({'rays': 1, 'b_interpolations': b, 's_interpolations': s, 'suffix_samples': suffix,
                          'b_skipped': int(r['b_skipped']), 's_skipped': int(r['s_skipped']),
                          'blocks': int(r['blocks']), 'eligibility_checks': int(r['eligibility_checks']),
                          'end_tests': int(r['end_tests']), 'boundary_tests': int(r['boundary_tests']),
                          'planned_suffixes': int(r['planned_suffixes']), 'actual_suffix_segments': int(r['actual_suffix_segments']),
                          'visible': r['b_status'] == 'VISIBLE', 'shaded': r['b_status'] == 'SHADED',
                          'unknown': r['b_status'] not in ['VISIBLE', 'SHADED']})
            pixel_query_counts[i] += 1
        if frozen_iter is not None:
            check(next(frozen_iter, None) is None, 'unconsumed frozen old rays')
        saved = next(rows(results / ('counts_' + tag + '.csv')))
        check(all(int(saved[k]) == v for k, v in total.items()), 'saved paired-ray aggregates')
        pixels = list(rows(results / ('verify_' + tag + '_pixels.csv')))
        check(len(pixels) == 16384, 'verification pixel count')
        verified_pixels[tag] = pixels
        history_method = 'binarylocal_30min' if tag == 'old' else 'two_boundary_limited5'
        history_pixels = list(rows(HISTORY / (history_method + '_pixels.csv')))
        for i, (p, h) in enumerate(zip(pixels, history_pixels)):
            check(int(p['receiver_index']) == i and int(p['queries']) == pixel_query_counts[i] == int(h['unique']), 'pixel fresh queries')
            check(p['row'] == h['row'] and p['col'] == h['col'] and p['status'] == h['status'] and p['reason'] == h['reason'] and p['branch'] == h['branch'], 'history temporal fields')
            check(abs(float.fromhex(p['psd_hex']) - float(h['psd_minutes'])) < 1e-8, 'history accepted PSD')
        # Reconstruct every saved integration and evaluated map; compare old point-core history.
        with (HISTORY / (history_method + '_evaluated_nodes.csv')).open(encoding='utf-8-sig', newline='') as ef, (HISTORY / (history_method + '_integration_nodes.csv')).open(encoding='utf-8-sig', newline='') as nf:
            expected_eval, expected_node = csv.DictReader(ef), csv.DictReader(nf)
            integration_total = 0
            for sid, group in itertools.groupby(rows(results / ('verify_' + tag + '_nodes.csv.gz')), key=lambda r: int(r['receiver_index'])):
                p = pixels[sid]
                prev_t = None
                prev_s = None
                psd = 0.0
                evaluated_count = 0
                for node in group:
                    time_value = float.fromhex(node['time_hex'])
                    state = int(node['state'])
                    e = next(expected_eval)
                    check(e['row'] == p['row'] and e['col'] == p['col'] and abs(time_value - float(e['minute_since_sunrise'])) < 1e-8 and state == int(e['state']), 'all evaluated nodes match')
                    evaluated_count += 1
                    if node['integration_node'] == '1':
                        n = next(expected_node)
                        check(n['row'] == p['row'] and n['col'] == p['col'] and abs(time_value - float(n['minute_since_sunrise'])) < 1e-8 and state == int(n['state']), 'all integration nodes match')
                        if prev_t is not None:
                            check(state >= 0 and prev_s >= 0, 'unexpected UNKNOWN integration')
                            psd += (state + prev_s) * .5 * (time_value - prev_t)
                        prev_t, prev_s = time_value, state
                        integration_total += 1
                check(evaluated_count == int(p['queries']) and abs(psd - float.fromhex(p['psd_hex'])) < 1e-8, 'independent integration')
            check(next(expected_eval, None) is None and next(expected_node, None) is None, 'unconsumed historical maps')
        total['integration_nodes'] = integration_total
        totals[tag] = dict(total)
    all_runs = list(rows(results / 'runs.csv'))
    check(len(all_runs) == 5 * 2 * len(tags), 'fixed number of runs')
    by_method = defaultdict(list)
    expected_digests = {tag: scalar_digest(verified_pixels[tag]) for tag in tags}
    for r in all_runs:
        method = r['method']
        tag = 'dual' if method.startswith('dual') else 'old'
        source = totals[tag]
        check(int(r['rays']) == source['rays'] and int(r['interpolations']) == source['s_interpolations' if method.endswith('suffix') else 'b_interpolations'], 'repeat totals')
        check(int(r['index_payload_bytes']) == 4761, 'same index payload')
        for key in ['visible', 'shaded', 'unknown']:
            check(int(r[key]) == source[key], 'repeat ray states')
        check(abs(float(r['algorithm_sec']) - float(r['build_sec']) - float(r['compute_sec'])) < 1e-12, 'algorithm time accounting')
        check(float(r['full_sec']) + 1e-12 >= sum(float(r[k]) for k in ['load_sec', 'build_sec', 'compute_sec', 'write_sec']), 'full time accounting')
        file = results / (r['phase'] + '_' + r['round'] + '_' + method + '_pixels.csv')
        pixels = list(rows(file))
        check(len(pixels) == 16384 and scalar_digest(pixels) == expected_digests[tag] == r['scalar_sha256'], 'all warmup/formal scalars match')
        if r['phase'] == 'formal':
            by_method[method].append(r)
    # Independent phase-order and paired benefit summaries, never fastest-run selection.
    for phase, count in [('warmup', 2), ('formal', 3)]:
        for round_no in range(1, count + 1):
            group = [r for r in all_runs if r['phase'] == phase and int(r['round']) == round_no]
            forward = (round_no % 2 == 1)
            expected = ['old_b', 'old_suffix', 'dual_b', 'dual_suffix'] if forward else ['dual_suffix', 'dual_b', 'old_suffix', 'old_b']
            expected = [m for m in expected if m.split('_')[0] in tags]
            check([r['method'] for r in group] == expected, 'alternating method order')
    summary = {'methods': {}, 'paired': {}, 'no_automatic_extra_rounds': True}
    for method, group in by_method.items():
        check(len(group) == 3, 'three formal runs')
        summary['methods'][method] = {'rays': int(group[0]['rays']), 'interpolations': int(group[0]['interpolations']),
                                     **{key + '_median': statistics.median(float(r[key]) for r in group) for key in ['load_sec', 'build_sec', 'compute_sec', 'algorithm_sec', 'query_sec', 'write_sec', 'full_sec']},
                                     'heap_used_end_range': [min(int(r['heap_used_end']) for r in group), max(int(r['heap_used_end']) for r in group)],
                                     'heap_pool_peak_sum_range': [min(int(r['heap_pool_peak_sum']) for r in group), max(int(r['heap_pool_peak_sum']) for r in group)],
                                     'index_payload_bytes': int(group[0]['index_payload_bytes'])}
    for tag in tags:
        base = by_method[tag + '_b']
        suffix = by_method[tag + '_suffix']
        pair = {}
        for key in ['algorithm_sec', 'full_sec', 'query_sec']:
            changes = [1 - float(s[key]) / float(b[key]) for b, s in zip(base, suffix)]
            pair[key] = {'fraction_saving_each_pair': changes, 'all_three_suffix_faster': all(c > 0 for c in changes),
                         'median_fraction_saving': 1 - statistics.median(float(s[key]) for s in suffix) / statistics.median(float(b[key]) for b in base)}
        summary['paired'][tag] = pair
    native = out / 'process_memory_samples.csv'
    process = defaultdict(list)
    if native.exists():
        for r in rows(native):
            process[r['phase']].append(r)
    for method, group in by_method.items():
        maxima = []
        for r in group:
            key = 'formal_' + r['round'] + '_' + method
            samples = process[key]
            maxima.append({'round': int(r['round']), 'samples': len(samples),
                           'observed_max_working_set': max((int(v['working_set_bytes']) for v in samples), default=None),
                           'observed_max_private_usage': max((int(v['private_usage_bytes']) for v in samples), default=None)})
        summary['methods'][method]['process_observations'] = maxima
    save(out / 'analysis_summary.json', summary)
    audit = {'status': 'PASS_CONTROLLED_SUFFIX_RECORDS', 'temporal_arms': tags, 'paired_ray_checks': sum(v['rays'] for v in totals.values()),
             'artificial_rays': len(artificial), 'formal_runs': 3 * 2 * len(tags), 'warmup_runs': 2 * 2 * len(tags),
             'source_and_history_unchanged': True, 'protected_files': len(protected), 'totals': totals,
             'all_states_stops_and_temporal_maps_match': True, 'all_repeat_scalar_files_match': True,
             'memory_not_exact_algorithm_peak': True, 'new_visibility_or_solar_queries_in_audit': 0,
             'accuracy_limits': 'same accepted old MAX18 and dual MAX27; not equal to fixed1 or no-hidden-window proof'}
    save(out / 'record_audit.json', audit)
    print(json.dumps({k: v for k, v in audit.items() if k != 'totals'}, ensure_ascii=False))
    print(json.dumps(summary['paired'], ensure_ascii=False))


if __name__ == '__main__':
    main()
