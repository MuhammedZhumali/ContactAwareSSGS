#!/usr/bin/env python3
"""Frozen, bounded SE2.1 repeats. One child JVM at a time; no adaptive retries."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / 'target/security-evaluation-se2'
OUT = ROOT / 'target/security-evaluation-se21'
DOC = ROOT / 'docs/security-evaluation-se21'
OLD = ROOT / 'target/security-evaluation-se2-build/se2.jar'
NEW = ROOT / 'target/security-evaluation-se21-build/se21.jar'
MAIN = 'org.contactaware.security.simulation.SecurityEvaluationSe21Main'
TARGETS = [('X-next-none', 1, 1), ('X-multi-none', 1, 1),
           ('iss3-cp5400-alt-wait-next-none', 1819, 2),
           ('iss3-cp5400-alt-wait-next-original-undetected', 816, 2),
           ('iss3-cp5400-alt-wait-multi-none', 3, 2),
           ('iss3-cp5400-moderate-multi-none', 3, 2),
           ('iss3-cp5400-saturated-multi-none', 3, 2)]

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def load(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))

def write(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')
    temporary.replace(path)

def rel(path):
    return Path(path).relative_to(ROOT).as_posix()

def preserve_se2():
    evidence = load(ROOT / 'docs/security-evaluation-se2/evidence-file-hashes.json')
    selected = {p: h for p, h in evidence.items() if p.startswith(('target/security-evaluation-se2/',
                 'docs/security-evaluation-se2/', 'target/security-evaluation-se2-build/'))}
    artifacts = load(ROOT / 'docs/security-evaluation-se2/artifact-manifest.json')
    for archive in artifacts['archives']:
        selected['docs/security-evaluation-se2/' + archive['file']] = archive['sha256']
    for path, expected in selected.items():
        if sha(ROOT / path) != expected:
            raise ValueError('Frozen SE2 input changed: ' + path)
    return selected

def child(command, timeout, log, cwd=ROOT):
    """Kill only this owned child process, preserving its last atomic checkpoint."""
    started = time.monotonic_ns()
    with Path(log).open('wb') as stream:
        process = subprocess.Popen(command, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT,
                                   creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        killed = False
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            killed = True
            process.kill()
            code = process.wait(timeout=10)
    return {'processStatus': 'PROCESS_TIMEOUT' if killed else ('EXITED' if code == 0 else 'PROCESS_ERROR'),
            'exitCode': code, 'processElapsedNanos': time.monotonic_ns() - started,
            'externalLimitSeconds': timeout, 'pid': process.pid}

def command(java, mode, destination, flags, variant='current', diagnostics=False):
    jars = str(NEW) if variant == 'current' else os.pathsep.join(map(str, (OLD, NEW)))
    args = [java, '-Xmx1024m']
    if diagnostics:
        destination.mkdir(parents=True, exist_ok=True)
        # Relative paths avoid the drive-letter colon in JVM logging syntax.
        args += ['-Xlog:gc*,safepoint:file=' + rel(destination / 'gc.log') + ':time,uptimemillis,level,tags',
                 '-XX:StartFlightRecording=filename=' + rel(destination / 'trace.jfr') + ',settings=profile,dumponexit=true']
    args += ['-cp', jars, MAIN, '--mode', mode, '--out', str(destination)]
    for k, v in flags.items():
        args += ['--' + k, str(v)]
    return args

def register(java):
    if (OUT / 'manifest.json').exists():
        raise ValueError('Registration exists; refusing replacement')
    preserved = preserve_se2()
    OUT.mkdir(parents=True, exist_ok=True)
    summary = load(RAW / 'summary.json')
    captures, attempts = [], []
    for name, index, repeats in TARGETS:
        dest = OUT / 'captures' / name
        outcome = child(command(java, 'capture', dest, {'case': name, 'index': index}),
                        30, OUT / (name + '-capture.log'))
        if outcome['processStatus'] != 'EXITED':
            raise ValueError('Snapshot capture failed: ' + name)
        capture = {'case': name, 'index': index, 'timing': summary[name]['timingModel'],
                   'files': {rel(p): sha(p) for p in sorted(dest.glob('*.json'))}, 'process': outcome}
        captures.append(capture)
        for variant in ('legacy', 'current'):
            for repeat in range(1, repeats + 1):
                attempts.append({'id': f'{name}-{variant}-{repeat}', 'mode': 'search', 'case': name,
                                 'variant': variant, 'repeat': repeat, 'externalSeconds': 20,
                                 'searchNodes': 100000, 'searchSeconds': 5})
    for name in ('X-next-none', 'X-multi-none', 'X-next-original-detected', 'X-multi-original-detected'):
        attempts.append({'id': name + '-controller', 'mode': 'controller', 'case': name, 'variant': 'current',
                         'controllerSeconds': 60, 'externalSeconds': 90})
    attempts.append({'id': 'iss3-cp5400-alt-wait-next-none-controller-budget', 'mode': 'controller',
                     'case': 'iss3-cp5400-alt-wait-next-none', 'variant': 'current',
                     'controllerSeconds': 10, 'externalSeconds': 30})
    attempts.append({'id': 'injected-watchdog-probe', 'mode': 'watchdog-probe', 'variant': 'current', 'externalSeconds': 2})
    manifest = {'version': 'se2.1', 'protocolSha256': sha(DOC / 'protocol.md'),
                'runnerSha256': sha(__file__), 'executables': {rel(OLD): sha(OLD), rel(NEW): sha(NEW)},
                'java': java, 'javaVersion': subprocess.check_output([java, '-version'], stderr=subprocess.STDOUT).decode(),
                'captures': captures, 'attempts': attempts, 'preservedSE2': preserved,
                'timingCaveat': 'Search elapsed and search-thread CPU are separate from model CPU and child-process wall time. '
                                'GC deltas are JVM-wide. JFR/GC logs add instrumentation overhead in both variants.'}
    write(OUT / 'manifest.json', manifest)
    write(DOC / 'manifest.json', manifest)
    print('REGISTERED', len(attempts), 'attempts;', len(captures), 'replayed snapshots', flush=True)

def run(java):
    manifest = load(OUT / 'manifest.json')
    if (OUT / 'summary.json').exists():
        raise ValueError('Attempt summary already exists; no silent reruns')
    for path, expected in {**manifest['executables'], **manifest['preservedSE2']}.items():
        if sha(ROOT / path) != expected:
            raise ValueError('Frozen input changed: ' + path)
    for capture in manifest['captures']:
        for path, expected in capture['files'].items():
            if sha(ROOT / path) != expected: raise ValueError('Capture changed: ' + path)
    if sha(DOC / 'protocol.md') != manifest['protocolSha256'] or sha(__file__) != manifest['runnerSha256']:
        raise ValueError('Registered protocol/runner changed')
    if java != manifest['java']:
        raise ValueError('Registered Java path changed')
    captures = {c['case']: c for c in manifest['captures']}
    results = {}
    write(OUT / 'summary.json', results)
    for spec in manifest['attempts']:
        dest = OUT / 'runs' / spec['id']
        if spec['mode'] == 'search':
            source = OUT / 'captures' / spec['case']
            flags = {'observation': source / 'observation.json', 'checkpoint': source / 'checkpoint.json',
                     'timing': captures[spec['case']]['timing'], 'nodes': spec['searchNodes'], 'seconds': spec['searchSeconds']}
        elif spec['mode'] == 'controller':
            flags = {'case': spec['case'], 'controller-seconds': spec['controllerSeconds']}
        else:
            flags = {'checkpoint': OUT / 'captures/X-next-none/checkpoint.json'}
        print('START', spec['id'], flush=True)
        outcome = child(command(java, spec['mode'], dest, flags, spec['variant'], spec['mode'] != 'watchdog-probe'),
                        spec['externalSeconds'], OUT / (spec['id'] + '.log'))
        outcome.update(spec)
        result = load(dest / 'result.json') if (dest / 'result.json').exists() else None
        outcome['result'] = result
        outcome['status'] = outcome['processStatus'] if outcome['processStatus'] != 'EXITED' else (result or {}).get('status', 'MISSING_RESULT')
        checkpoint = dest / 'checkpoint.json'
        outcome['checkpointFile'] = rel(checkpoint) if checkpoint.exists() else None
        outcome['checkpointSha256'] = sha(checkpoint) if checkpoint.exists() else None
        outcome['files'] = {rel(p): sha(p) for p in sorted(dest.glob('*')) if p.is_file()}
        if outcome['processStatus'] == 'PROCESS_TIMEOUT' and checkpoint.exists():
            restored = OUT / 'restoration-checks' / spec['id']
            check = child(command(java, 'check-checkpoint', restored, {'checkpoint': checkpoint}),
                          15, OUT / (spec['id'] + '-restore.log'))
            outcome['checkpointRestoration'] = {'process': check,
                'result': load(restored / 'result.json') if (restored / 'result.json').exists() else None}
        results[spec['id']] = outcome
        write(OUT / 'summary.json', results)
        print('DONE', spec['id'], outcome['status'], flush=True)
    preserve_se2()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['register', 'run'])
    parser.add_argument('--java', default=str(Path(os.environ.get('JAVA_HOME', r'C:\Program Files\Java\jdk-21.0.10')) / 'bin/java.exe'))
    args = parser.parse_args()
    (register if args.mode == 'register' else run)(args.java)

if __name__ == '__main__':
    main()
