"""Explicit isolated suffix candidate gate and one fixed four-arm timing protocol."""
import argparse
import csv
import ctypes
from ctypes import wintypes
import hashlib
import json
import os
import platform
import subprocess
import sys
import threading
import time
import uuid
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
B = ROOT / 'user_runs/block16_bound_stage_b_20261111_130312_755669bc'
C = ROOT / 'user_runs/block16_corridor_pilot_20261111_132248_2c3a85ac'
HISTORY = ROOT / 'user_runs/qinling_time_search_paired_20261110_125802_39f7a1e4'


def sha(p):
    with p.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def save(p, data):
    with p.open('x', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def source_check():
    # Original constructor/helpers remain byte-for-byte as in B's NoStats source.
    base = (ROOT / 'src/Block16DirectionVisibilityNoStats.java').read_text(encoding='utf-8-sig')
    for name in ['SuffixBlockVisibility', 'SuffixBlockVisibilityNoStats']:
        text = (ROOT / ('src/' + name + '.java')).read_text(encoding='utf-8-sig')
        assert base[base.index('    private static int segmentEnd'):] == text[text.index('    private static int segmentEnd'):], 'geometry/interpolation helper drift'
        assert base[base.index('        if (blockSize <'):base.index('    /**', base.index('        if (blockSize <'))] == text[text.index('        if (blockSize <'):text.index('    /**', text.index('        if (blockSize <'))], 'index construction drift'
        assert 'skip = complete[index] && Double.isFinite(ray) && maxima[index] + pad < ray;' in text
    original = (ROOT / 'src/QinlingTimeSearchPairedBenchmark.java').read_text(encoding='utf-8-sig')
    adapter = (ROOT / 'src/SuffixTemporalAdapter.java').read_text(encoding='utf-8-sig')
    memo = original[original.index('    private static final class Memo'):original.index('    private static final String BASELINE')].replace('private static Pixel hybrid', 'static Pixel hybrid')
    baseline = original[original.index('    private static Pixel baseline'):original.index('    private static Pass calculate')].replace('private static Pixel baseline', 'static Pixel baseline')
    assert memo in adapter and baseline in adapter, 'temporal adapter differs from frozen logic'
    return {'original_B_geometry_interpolation_constructor_and_whole_skip_unchanged': True,
            'temporal_memo_hybrid_baseline_source_slices_unchanged_except_access': True,
            'memo_hybrid_sha256': hashlib.sha256(memo.encode()).hexdigest(),
            'baseline_sha256': hashlib.sha256(baseline.encode()).hexdigest()}


class MemorySampler:
    def __init__(self, pid, target):
        self.pid, self.target = pid, target
        self.stop = threading.Event()
        self.phase = 'verification_or_idle'
        self.error = None

    def start(self):
        self.thread = threading.Thread(target=self.collect, daemon=True)
        self.thread.start()

    def collect(self):
        try:
            class PMC(ctypes.Structure):
                _fields_ = [('cb', wintypes.DWORD), ('PageFaultCount', wintypes.DWORD)] + [(name, ctypes.c_size_t) for name in
                    ['PeakWorkingSetSize', 'WorkingSetSize', 'QuotaPeakPagedPoolUsage', 'QuotaPagedPoolUsage',
                     'QuotaPeakNonPagedPoolUsage', 'QuotaNonPagedPoolUsage', 'PagefileUsage', 'PeakPagefileUsage', 'PrivateUsage']]
            kernel = ctypes.WinDLL('kernel32', use_last_error=True)
            psapi = ctypes.WinDLL('psapi', use_last_error=True)
            kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
            kernel.OpenProcess.restype = wintypes.HANDLE
            kernel.CloseHandle.argtypes = [wintypes.HANDLE]
            psapi.GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(PMC), wintypes.DWORD]
            psapi.GetProcessMemoryInfo.restype = wintypes.BOOL
            handle = kernel.OpenProcess(0x0410, False, self.pid)
            if not handle:
                raise OSError(ctypes.get_last_error(), 'OpenProcess')
            begin = time.monotonic()
            try:
                with self.target.open('x', newline='', encoding='utf-8') as f:
                    writer = csv.writer(f)
                    writer.writerow(['seconds_since_sampler_start', 'phase', 'working_set_bytes', 'private_usage_bytes', 'process_lifetime_peak_working_set_bytes'])
                    while not self.stop.is_set():
                        m = PMC()
                        m.cb = ctypes.sizeof(m)
                        if not psapi.GetProcessMemoryInfo(handle, ctypes.byref(m), m.cb):
                            raise OSError(ctypes.get_last_error(), 'GetProcessMemoryInfo')
                        writer.writerow([time.monotonic() - begin, self.phase, m.WorkingSetSize, m.PrivateUsage, m.PeakWorkingSetSize])
                        f.flush()
                        self.stop.wait(0.1)
            finally:
                kernel.CloseHandle(handle)
        except BaseException as e:
            self.error = repr(e)

    def finish(self):
        self.stop.set()
        self.thread.join(timeout=5)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--controlled-four-arm', action='store_true', required=True)
    parser.parse_args()
    out = ROOT / ('user_runs/suffix_combination_' + datetime.now().strftime('%Y%m%d_%H%M%S') + '_' + uuid.uuid4().hex[:8])
    out.mkdir()
    commands = []

    def run(args, logname, monitor=False):
        command = {'argv': list(map(str, args)), 'cwd': str(ROOT)}
        commands.append(command)
        sampler = None
        started = time.monotonic()
        with (out / logname).open('x', encoding='utf-8') as log:
            proc = subprocess.Popen(command['argv'], cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    text=True, encoding='utf-8', errors='replace')
            if monitor:
                sampler = MemorySampler(proc.pid, out / 'process_memory_samples.csv')
                sampler.start()
            try:
                for line in proc.stdout:
                    if sampler and line.startswith('RUN_START '):
                        sampler.phase = line.strip().split()[1]
                    print(line.rstrip(), flush=True)
                    log.write(line)
                    log.flush()
                    if sampler and line.startswith('RUN_END '):
                        sampler.phase = 'verification_or_idle'
                command['exit_code'] = proc.wait()
            finally:
                if sampler:
                    sampler.finish()
                    save(out / 'memory_sampling_status.json', {'interval_seconds': 0.1, 'error': sampler.error,
                         'phase_window_marked_by_stdout': True, 'exact_algorithm_peak': False,
                         'lifetime_peak_is_not_per_trial_peak': True})
        command['wall_seconds'] = time.monotonic() - started
        if command['exit_code']:
            raise RuntimeError('Stopped after failed command: ' + logname)

    try:
        protected = {str(p.relative_to(ROOT)): sha(p) for folder in ['src', 'configs', 'scripts']
                     for p in (ROOT / folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts}
        save(out / 'protected_before.json', protected)
        for name, digest in json.loads((C / 'protected_before.json').read_text(encoding='utf-8')).items():
            if sha(ROOT / name) != digest:
                raise RuntimeError('Original protected implementation changed: ' + name)
        with (HISTORY / 'manifest.csv').open(encoding='utf-8-sig', newline='') as f:
            rows = list(csv.DictReader(f))
        checked = []
        for row in rows:
            p = Path(row['path'])
            if p.parts[0] == 'src':
                if sha(ROOT / p) != row['sha256']:
                    raise RuntimeError('Frozen temporal-source history changed: ' + str(p))
                checked.append(str(p))
        save(out / 'source_static_check.json', {**source_check(), 'nov10_sources_checked': checked})
        history_files = [B / 'results/rays.csv.gz', B / 'results/counts.csv', B / 'record_audit.json',
                         ROOT / 'user_runs/qinling_128_strip_t2_verify_001/results/rays.csv.gz',
                         ROOT / 'configs/qinling_128_block16_bound_diagnostic.properties']
        for entry in json.loads((B / 'inputs.json').read_text(encoding='utf-8')).values():
            if sha(Path(entry['path'])) != entry['sha256']:
                raise RuntimeError('Original input differs')
            history_files.append(Path(entry['path']).resolve())
        history_files.extend(p for p in HISTORY.rglob('*') if p.is_file())
        frozen = {str(p.resolve()): sha(p) for p in history_files}
        save(out / 'frozen_inputs.json', frozen)
        save(out / 'protocol.json', {'correctness': 'artificial then full frozen old workload; dual adapter and maps gated against accepted Nov10 results',
             'arms': ['old_b', 'old_suffix', 'dual_b', 'dual_suffix'], 'warmups': 2, 'formal_rounds': 3,
             'orders': ['OB,OS,DB,DS', 'DS,DB,OS,OB', 'OB,OS,DB,DS', 'DS,DB,OS,OB', 'OB,OS,DB,DS'],
             'each_trial': 'reload identical DEM/ROI, build exactly one index, shared solar/temporal functions, common per-ray timer, validate and write scalars',
             'primary_cost': 'index build plus entire temporal/solar/spatial compute',
             'full_cost': 'load plus build plus compute plus scalar checks/digest plus write',
             'extra_judgments': 'separate correctness Stats pass; formal original B NoStats and candidate NoStats',
             'stable_benefit_rule': 'report all three paired algorithm/full costs and median; no acceleration claim from sample count or fastest run; no automatic extra rounds/optimization',
             'accepted_time_accuracy': {'old_day_MAX_minutes': 18, 'dual_day_MAX_minutes': 27, 'dual_first_boundary_MAX_minutes': 57,
                                        'hidden_windows_and_selected_5min_rule_limits_retained': True},
             'memory': 'Windows working set/private bytes sampled at 100ms plus per-trial JVM end/heap pool peaks; not exact algorithm peak',
             'host': {'platform': platform.platform(), 'machine': platform.machine(), 'processor': platform.processor(), 'logical_cpus': os.cpu_count()},
             'index': 'same maxima/complete arrays as B; no BVH/strips/multilevel', 'stop_after_this_batch': True})
        classes = out / 'classes'
        classes.mkdir()
        jdk = Path('D:/Develop/jdk-17.0.20.101-hotspot/bin')
        sources = ['src/SuffixBlockVisibility.java', 'src/SuffixBlockVisibilityNoStats.java', 'src/SuffixTemporalAdapter.java',
                   'src/SuffixVisibilityChecks.java', 'src/SuffixCombinationExperiment.java']
        run([jdk / 'javac.exe', '-encoding', 'UTF-8', '-Xlint:all', '-sourcepath', 'src', '-d', classes, *sources], 'compile.log')
        save(out / 'compiled_classes.json', {str(p.relative_to(out)): sha(p) for p in classes.rglob('*.class')})
        java = [jdk / 'java.exe', '-Dfile.encoding=UTF-8', '-Xms64m', '-Xmx512m', '-cp', classes]
        run([*java, 'SuffixVisibilityChecks', '--artificial-only', out / 'artificial'], 'artificial.log')
        run([*java, 'SuffixCombinationExperiment', '--controlled-four-arm', out / 'results'], 'experiment.log', monitor=True)
        after = {name: sha(ROOT / name) for name in protected}
        save(out / 'protected_after.json', after)
        if after != protected or any(sha(Path(p)) != digest for p, digest in frozen.items()):
            raise RuntimeError('Original source/input/history mutated')
        save(out / 'completion.json', {'status': 'CONTROLLED_EXPERIMENT_EXECUTED_AUDIT_PENDING', 'protected_files': len(protected),
             'extra_performance_batches': 0, 'old_algorithms_changed': False})
        print('OUTPUT=' + str(out), flush=True)
    except BaseException as e:
        save(out / 'failure.json', {'type': type(e).__name__, 'message': str(e), 'automatic_retry': False})
        print('FAILED_OUTPUT=' + str(out), flush=True)
        raise
    finally:
        save(out / 'commands.json', commands)


if __name__ == '__main__':
    main()
