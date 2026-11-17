"""Review/freeze gates then ONE 3-warmup/10-pair Qinling summer transfer batch."""
import argparse
import csv
import json
import os
import platform
import shutil
import subprocess
import sys
import time
import uuid
from datetime import datetime
from pathlib import Path
from run_suffix_combination import MemorySampler, sha, save, source_check

ROOT = Path(__file__).resolve().parents[1]
WINTER = ROOT / 'user_runs/suffix_combination_20261111_144944_5bf789cd'
CONFIG = ROOT / 'configs/qinling_spatial_transfer_summer.properties'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--review-freeze-summer', action='store_true', required=True)
    parser.parse_args()
    out = ROOT / ('user_runs/suffix_summer_transfer_' + datetime.now().strftime('%Y%m%d_%H%M%S') + '_' + uuid.uuid4().hex[:8])
    out.mkdir()
    commands = []

    def run(args, logname, monitor=False):
        c = {'argv': list(map(str, args)), 'cwd': str(ROOT)}
        commands.append(c)
        started = time.monotonic()
        sampler = None
        with (out / logname).open('x', encoding='utf-8') as log:
            process = subprocess.Popen(c['argv'], cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                       text=True, encoding='utf-8', errors='replace')
            if monitor:
                sampler = MemorySampler(process.pid, out / 'process_memory_samples.csv')
                sampler.start()
            try:
                for line in process.stdout:
                    if sampler and line.startswith('RUN_START '):
                        sampler.phase = line.strip().split()[1]
                    log.write(line)
                    log.flush()
                    print(line.rstrip(), flush=True)
                    if sampler and line.startswith('RUN_END '):
                        sampler.phase = 'verification_or_idle'
                c['exit_code'] = process.wait()
            finally:
                if sampler:
                    sampler.finish()
                    save(out / 'memory_sampling_status.json', {'interval_seconds': 0.1, 'error': sampler.error,
                         'exact_algorithm_peak': False, 'lifetime_peak_is_not_per_trial_peak': True})
        c['wall_seconds'] = time.monotonic() - started
        if c['exit_code']:
            raise RuntimeError('STOP: gate/experiment failed; no algorithm repair or automatic continuation: ' + logname)

    try:
        protected = {str(p.relative_to(ROOT)): sha(p) for folder in ['src', 'configs', 'scripts']
                     for p in (ROOT / folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts}
        save(out / 'protected_before.json', protected)
        for n, digest in json.loads((WINTER / 'protected_before.json').read_text(encoding='utf-8')).items():
            if sha(ROOT / n) != digest:
                raise RuntimeError('Frozen winter implementation differs: ' + n)
        static = source_check()
        winter_source = (ROOT / 'src/SuffixCombinationExperiment.java').read_text(encoding='utf-8-sig')
        summer_source = (ROOT / 'src/SuffixSummerTransferExperiment.java').read_text(encoding='utf-8-sig')
        body = winter_source[winter_source.index('    @FunctionalInterface interface Spatial'):winter_source.index('    public static void main')]
        if body not in summer_source:
            raise RuntimeError('Winter timing/digest/trial body was altered in summer wrapper')
        save(out / 'static_review.json', {**static, 'decision': 'PASS_WITHIN_FIXED_CONTRACT_SUBJECT_TO_PADDED_BOUNDARY_GATE',
             'contract': '16 grid cells; spacing 50m; cap200; cell90m; finite immutable prepared DEM and valid arrays',
             'independent_read_only_review': 'no blocking visibility risk; original test nextUp/down did not directly cross padded threshold',
             'padding_argument': 'finite nondecreasing Java ray height; absolute max at endpoints; constant envelope >= every original pad',
             'coverage_argument': 'original complete halo + original segment end and extra same-block endpoint; exact/linear/bilinear EPS support contained',
             'first_exit': 'original prefix evaluated in order; suffix counted only after prefix completes',
             'new_tests_only': 'padded threshold below/equal/above and prefix SHADED/UNKNOWN; no rule modified',
             'timing_review': 'full is method-function load-to-pixel-write interval, excludes pool reset/post-memory/runs.csv/log and JVM start',
             'denominator_review': 'method medians ratio differs from median paired saving; interpolation baseline is engineering B not point core',
             'winter_trial_body_copied_verbatim': True, 'general_extreme_integer_or_mutable_DEM_claim': False})
        # Audit copies only; no winter Java/visibility/timing rerun and no historical overwrite.
        review = out / 'winter_record_review'
        review.mkdir()
        for folder in ['classes', 'results', 'artificial']:
            shutil.copytree(WINTER / folder, review / folder)
        for name in ['protected_before.json', 'protected_after.json', 'frozen_inputs.json', 'compiled_classes.json',
                     'process_memory_samples.csv', 'memory_sampling_status.json']:
            shutil.copy2(WINTER / name, review / name)
        run([sys.executable, 'scripts/audit_suffix_combination.py', review], 'winter_record_review.log')
        save(out / 'winter_review_copy_manifest.json', {str(p.relative_to(review)): sha(p) for p in review.rglob('*') if p.is_file()})
        # Input-only config is reused verbatim; its legacy warmups/method registry are not the summer runner protocol.
        props = dict(line.split('=', 1) for line in CONFIG.read_text(encoding='utf-8-sig').splitlines() if line.strip() and not line.startswith('#'))
        expected = {'date': '2023-06-21', 'cellsize_m': '90.0', 'row_start': '114', 'col_start': '114',
                    'rows': '128', 'cols': '128', 'search_spacing_m': '50', 'search_samples': '200'}
        if any(props.get(k) != v for k, v in expected.items()):
            raise RuntimeError('Summer contract differs')
        files = [CONFIG]
        for key in ['dem', 'receivers', 'metadata']:
            files.append((CONFIG.parent / props[key]).resolve())
        ref = (CONFIG.parent / props['reference_run']).resolve()
        files.extend(ref / n for n in ['config.properties', 'receivers.csv', 'data_metadata.json', 'fixed_1min_r1/psd.csv'])
        files.extend(p for p in WINTER.rglob('*') if p.is_file())
        frozen = {str(p.resolve()): sha(p) for p in files}
        save(out / 'frozen_inputs.json', frozen)
        save(out / 'protocol.json', {'arms': ['dual_b', 'dual_suffix'], 'warmups_per_method': 3, 'formal_pairs': 10,
             'order': 'odd round B then suffix; even round suffix then B; parity resets for formal phase',
             'config_reused_verbatim': str(CONFIG.relative_to(ROOT)), 'input_contract': expected,
             'legacy_config_methods_warmups_repeats': 'loader compatibility only; executed arms/counts fixed by this independent summer wrapper',
             'algorithm_classes_unchanged': ['SuffixBlockVisibility', 'SuffixBlockVisibilityNoStats', 'Block16DirectionVisibility',
                  'Block16DirectionVisibilityNoStats', 'SuffixTemporalAdapter', 'TimeStructureSearch', 'LimitedDarkBinaryRefinement'],
             'correctness': 'B/point/candidate Stats/NoStats paired rays; separately execute candidate hybrid and compare actual solar query order, all requests/cache, maps and pixels',
             'performance': 'verbatim winter trial body; shared NoStats/per-ray clock; reload and build once per method run',
             'full_time_scope': 'load through checked/digested pixel write; not entire JVM end-to-end; excludes pool reset, subsequent JVM reads, outer runs CSV and log',
             'accuracy': 'reuse summer fixed1 conditional reference after original loader snapshot/hash checks; no fixed1 rerun',
             'analysis': 'all paired differences/fractions, method medians ratio, ranges/IQR and exploratory fixed-seed bootstrap paired-median interval; no reruns/tuning',
             'memory': 'same 100ms Windows sampler and JVM pool/end readings as winter; not exact peak',
             'host': {'platform': platform.platform(), 'machine': platform.machine(), 'processor': platform.processor(), 'logical_cpus': os.cpu_count()},
             'stop_after_batch': True})
        classes = out / 'classes'
        classes.mkdir()
        jdk = Path('D:/Develop/jdk-17.0.20.101-hotspot/bin')
        run([jdk / 'javac.exe', '-encoding', 'UTF-8', '-Xlint:all', '-sourcepath', 'src', '-d', classes,
             'src/SuffixFreezeBoundaryChecks.java', 'src/SuffixSummerTransferExperiment.java', 'src/SuffixVisibilityChecks.java'], 'compile.log')
        compiled = {str(p.relative_to(out)): sha(p) for p in classes.rglob('*.class')}
        save(out / 'compiled_classes.json', compiled)
        java = [jdk / 'java.exe', '-Dfile.encoding=UTF-8', '-Xms64m', '-Xmx512m', '-cp', classes]
        run([*java, 'SuffixVisibilityChecks', '--artificial-only', out / 'artificial'], 'artificial.log')
        run([*java, 'SuffixFreezeBoundaryChecks', '--review-gate', out / 'padded_boundary_gate'], 'padded_boundary_gate.log')
        # Freeze AFTER all review/artificial gates and BEFORE any new summer real query.
        if any(sha(ROOT / n) != d for n, d in protected.items()) or any(sha(Path(n)) != d for n, d in frozen.items()):
            raise RuntimeError('Changed before freeze')
        save(out / 'frozen_version.json', {'status': 'REVIEW_AND_ARTIFICIAL_GATES_PASS_FROZEN_BEFORE_SUMMER',
             'sources_configs_scripts': protected, 'compiled_classes': compiled, 'inputs_and_historical_records': frozen,
             'rules_changed': False, 'review_boundary_gate': json.loads((out / 'padded_boundary_gate/completion.json').read_text(encoding='utf-8'))})
        run([*java, 'SuffixSummerTransferExperiment', '--summer-frozen-pair', out / 'results'], 'summer_experiment.log', monitor=True)
        after = {n: sha(ROOT / n) for n in protected}
        save(out / 'protected_after.json', after)
        if after != protected or any(sha(Path(n)) != d for n, d in frozen.items()):
            raise RuntimeError('Frozen version/input/history changed during summer experiment')
        save(out / 'completion.json', {'status': 'SUMMER_BATCH_EXECUTED_AUDIT_PENDING', 'frozen_algorithm_changed': False,
             'performance_batches': 1, 'extra_rounds': 0, 'protected_files': len(protected)})
        print('OUTPUT=' + str(out), flush=True)
    except BaseException as e:
        save(out / 'failure.json', {'type': type(e).__name__, 'message': str(e), 'algorithm_repaired': False,
             'automatic_continuation': False})
        print('FAILED_OUTPUT=' + str(out), flush=True)
        raise
    finally:
        save(out / 'commands.json', commands)


if __name__ == '__main__':
    main()
