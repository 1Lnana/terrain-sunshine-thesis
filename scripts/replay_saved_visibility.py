"""Offline rule analysis using saved minute states. No DEM, solar or ray calls.

The current rule is mirrored only to check replay equivalence on saved cases.
Candidates probe equal-state half intervals, then minute-fill unequal neighbours.
Counts are unique saved-state lookups, not measured terrain runtime.
"""
from pathlib import Path
import csv
import hashlib
import json
import math

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'user_runs' / 'saved_visibility_replay_003'
SOURCES = [
    ('winter', 'qinling_512_reference_002', 'fixed_1min_r1',
     'qinling_512_guardlocal_30min_20261029_120231_207', 'guardlocal_30min_r1'),
    ('spring', 'qinling_spring_001', 'fixed_1min_r1',
     'qinling_spring_001', 'guardlocal_30min_r1'),
]
RULES = ('current', 'plain_local', 'equal_midpoint', 'equal_gap5', 'equal_gap3',
         'fixed5', 'fixed10', 'fixed15', 'fixed30')


def rows(path):
    with path.open(encoding='utf-8-sig', newline='') as stream:
        yield from csv.DictReader(stream)


def integral(nodes):
    points = sorted(nodes.items())
    return sum((b-a)*(sa+sb)/2 for (a, sa), (b, sb) in zip(points, points[1:]))


def replay(states, rule):
    day = max(states)
    values, nodes = {}, {}
    if rule.startswith('fixed'):
        step = int(rule[5:])
        nodes = {min(i*float(step), day): states[min(i*float(step), day)]
                 for i in range(math.ceil(day/step)+1)}
        return integral(nodes), len(nodes), nodes, nodes.copy()

    def get(t):
        # Never interpolate an unavailable oracle state.
        assert t in states, ('saved state missing', t)
        assert states[t] in (0, 1), 'This replay covers known binary traces only'
        values[t] = states[t]
        return states[t]

    def put(t):
        nodes[t] = get(t)

    def fill(a, b):
        for minute in range(math.floor(a)+1, math.ceil(b)):
            if minute < b:
                put(float(minute))

    def half(a, b, sa, sb):
        if sa == sb:
            if rule in ('current', 'plain_local'):
                return
            if rule == 'equal_midpoint':
                probes = [float(math.floor((a+b)/2))]
            else:
                gap = 5 if rule == 'equal_gap5' else 3
                probes = [a+gap*i for i in range(1, math.ceil((b-a)/gap))]
            points = [a] + [t for t in probes if a < t < b] + [b]
            for t in points[1:-1]:
                put(t)
            for left, right in zip(points, points[1:]):
                if get(left) != get(right):
                    fill(left, right)
            return
        if rule == 'plain_local':
            # Same 30-min endpoints/midpoints and dark guard; no quarter narrowing.
            fill(a, b)
            return
        q = float(math.floor((a+b)/2))
        if q <= a or q >= b:
            fill(a, b)
            return
        put(q)
        if sa != get(q):
            fill(a, q)
        if get(q) != sb:
            fill(q, b)

    coarse = [min(i*30.0, day) for i in range(math.ceil(day/30)+1)]
    for t in coarse:
        put(t)
    if all(s == 0 for s in values.values()):
        # Preserve the existing whole-day dark guard for every candidate.
        nodes.clear()
        for t in states:
            put(t)
    else:
        for a, b in zip(coarse, coarse[1:]):
            mid = float(math.floor((a+b)/2))
            if a < mid < b:
                put(mid)
                half(a, mid, get(a), get(mid))
                half(mid, b, get(mid), get(b))
    return integral(nodes), len(values), nodes, values


def main():
    assert not OUT.exists(), 'Preserve previous replay output'
    output, input_hashes, baseline_matches = [], {}, 0
    for season, ref_run, ref_method, old_run, old_method in SOURCES:
        ref = ROOT / 'user_runs' / ref_run / ref_method
        old = ROOT / 'user_runs' / old_run / old_method
        trajectories, saved = {}, {}
        for path in (ref/'samples.csv', old/'samples.csv'):
            input_hashes[str(path.relative_to(ROOT))] = hashlib.sha256(path.read_bytes()).hexdigest()
        for r in rows(ref/'samples.csv'):
            key = (r['row'], r['col'])
            trajectories.setdefault(key, {})[float(r['minutes_since_sunrise'])] = int(r['state'])
        for r in rows(old/'samples.csv'):
            key = (r['row'], r['col'])
            saved.setdefault(key, {})[float(r['minutes_since_sunrise'])] = int(r['state'])
        needed = set(trajectories)
        old_psd = {}
        for r in rows(old/'psd.csv'):
            key = (r['row'], r['col'])
            if key in needed:
                old_psd[key] = (float(r['psd_minutes']), int(r['sample_count']))
        for key, states in trajectories.items():
            reference = integral(states)
            current_psd, count, _, values = replay(states, 'current')
            assert values == saved[key], ('current query trace mismatch', season, key)
            assert abs(current_psd-old_psd[key][0]) < 1e-8
            assert count == old_psd[key][1]
            baseline_matches += 1
            for rule in RULES:
                psd, queries, nodes, _ = replay(states, rule)
                # Sum signed errors per candidate integration interval to expose cancellation.
                positive, negative = 0.0, 0.0
                times = sorted(nodes)
                for a, b in zip(times, times[1:]):
                    exact = integral({t: s for t, s in states.items() if a <= t <= b})
                    error = (b-a)*(nodes[a]+nodes[b])/2-exact
                    positive += max(0.0, error)
                    negative += min(0.0, error)
                output.append(dict(season=season, row=key[0], col=key[1], rule=rule,
                                   reference_min=reference, psd_min=psd,
                                   signed_error_min=psd-reference,
                                   abs_error_min=abs(psd-reference), queries=queries,
                                   extra_queries=queries-count, minute_queries=len(states),
                                   positive_interval_error_min=positive,
                                   negative_interval_error_min=negative))
    OUT.mkdir()
    with (OUT/'replay.csv').open('w', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=output[0].keys())
        writer.writeheader()
        writer.writerows(output)
    audit = dict(scope='Saved selected traces only; no new terrain calls or runtime benchmark',
                 current_trace_matches=baseline_matches,
                 inputs_sha256=input_hashes,
                 rule_definition='current mirrors saved local rule; plain_local fills entire unequal half interval without quarter narrowing. Other candidates probe equal-state half intervals once or every 5/3 min. Dark guard retained. fixedN uses uniform N-min trapezoidal integration.',
                 cases_selected_for_diagnosis=True,
                 no_general_accuracy_guarantee=True)
    (OUT/'audit.json').write_text(json.dumps(audit, indent=2), encoding='utf-8')
    for r in output:
        print('{season} {row},{col} {rule}: error={signed_error_min:g} queries={queries} extra={extra_queries}'.format(**r))
    print(f'REPLAY_ANALYSIS_COMPLETE current_trace_matches={baseline_matches} output={OUT}')


if __name__ == '__main__':
    main()
