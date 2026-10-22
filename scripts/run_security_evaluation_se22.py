#!/usr/bin/env python3
"""One heavy process at a time; immutable registration; X checkpoint before expansion."""
import argparse
import json
import os
from pathlib import Path
import subprocess
from run_security_evaluation_se21 import child, sha, load, write, rel, ROOT

OUT = ROOT / 'target/security-evaluation-se22'
DOC = ROOT / 'docs/security-evaluation-se22'
SOURCE = ROOT / 'target/security-evaluation-se2'
JAVA = r'C:\Program Files\Java\jdk-21.0.10\bin\java.exe'
JAVAC = r'C:\Program Files\Java\jdk-21.0.10\bin\javac.exe'
MAIN = 'org.contactaware.security.simulation.SecurityEvaluationSe22Main'
RULES = ('CANONICAL', 'CONTINUE_WAIT')
GRID = {'X': ('none', 'original', 'p70', 'p90', 'gap-midpoint'),
        'iss3-cp5400': ('none', 'original', 'p30'), 'Y': ('none', 'original'),
        'iss3-cp7200': ('none', 'original', 'p30')}

def verify():
    m = load(OUT / 'manifest.json')
    for path, digest in m['preservedInputs'].items():
        if sha(ROOT / path) != digest: raise ValueError('Preserved input changed: ' + path)
    for path, digest in m['executableInputs'].items():
        if sha(ROOT / path) != digest: raise ValueError('Registered code/input changed: ' + path)
    return m

def register():
    if (OUT / 'manifest.json').exists(): raise ValueError('Already registered')
    build = load(OUT / 'build.json')
    old = load(SOURCE / 'summary.json')
    cases, models = [], {}
    for fixture, points in GRID.items():
        for point in points:
            for policy in ('next', 'multi'):
                source = f'{fixture}-{policy}-' + ('none' if point == 'none' else point+'-detected')
                spec = old[source]
                model_file = spec['modelFile']
                path = OUT / 'models' / model_file
                path.parent.mkdir(parents=True, exist_ok=True)
                content = (SOURCE / model_file).read_bytes()
                if path.exists() and path.read_bytes() != content: raise ValueError('Model mismatch')
                path.write_bytes(content)
                models[rel(path)] = sha(path)
                for rule in RULES:
                    name = source + '-' + rule.lower()
                    cases.append({'name': name, 'sourceCase': source, 'fixture': fixture,
                        'policy': policy, 'horizon': 1 if policy == 'next' else 2, 'rule': rule,
                        'mode': spec['mode'], 'attackPoint': spec['attackPoint'], 'attackAt': spec['attackAt'],
                        'modelFile': 'models/' + model_file, 'stage': 'x' if fixture == 'X' else 'controls',
                        'baselineCase': f'{fixture}-{policy}-none-{rule.lower()}'})
    inputs = {rel(Path(__file__)): sha(__file__), rel(DOC / 'protocol.md'): sha(DOC / 'protocol.md'),
              build['jar']: build['jarSha256'], **models}
    for p in (ROOT / 'docs/reviews/research-audit-checks/java').rglob('*.java'): inputs[rel(p)] = sha(p)
    for p in (DOC / 'audit-adapter').glob('*.java'): inputs[rel(p)] = sha(p)
    manifest = {'version': 'SE2.2', 'registeredRuns': len(cases), 'cases': cases,
                'build': build, 'preservedInputs': load(OUT / 'preserved-inputs.json'), 'executableInputs': inputs,
                'limits': {'nodes': 2000000, 'searchSeconds': 30, 'controllerSeconds': 60, 'watchdogSeconds': 90},
                'mechanismProbes': [{'index': i, 'horizon': h} for i in (3,12) for h in (1,2)],
                'audit': ['original TieProbe X2', 'original TieBreakSensitivity.run X original H1/H2 canonical/lazy via adapter'],
                'traversal': 'same accelerated RENEWAL_FIRST with original canonical secondary order for both rules'}
    write(OUT / 'manifest.json', manifest)
    write(DOC / 'manifest.json', manifest)
    write(OUT / 'summary.json', {s['name']: {**s, 'status': 'NOT_RUN'} for s in cases})
    verify()
    print('Registered', len(cases), 'controllers and four fixed-root probes', flush=True)

def java_command(m, mode, destination, flags):
    destination.mkdir(parents=True, exist_ok=True)
    args = [JAVA, '-Xmx1024m', '-Xlog:gc*,safepoint:file='+rel(destination/'gc.log')+':time,uptimemillis,level,tags',
            '-cp', str(ROOT/m['build']['jar']), MAIN, '--mode', mode, '--out', str(destination)]
    for key, value in flags.items(): args += ['--'+key, str(value)]
    return args

def audits(m):
    directory = OUT / 'claude-replay'
    if directory.exists(): raise ValueError('Audit repetition already exists')
    directory.mkdir()
    classes = directory / 'classes'; classes.mkdir()
    originals = ROOT / 'docs/reviews/research-audit-checks/java/org/contactaware/security/simulation'
    legacy = ROOT / 'target/security-evaluation-se21-build/se21.jar'
    compile_cmd = [JAVAC, '--release', '21', '-encoding', 'UTF-8', '-cp', str(legacy), '-d', str(classes),
                   str(originals/'TieProbe.java'), str(originals/'TieBreakSensitivity.java'),
                   str(DOC/'audit-adapter/ClaudeTieReplay.java')]
    compilation = child(compile_cmd, 30, directory/'compile.log')
    if compilation['processStatus'] != 'EXITED': raise ValueError('Audit compilation failed')
    cp = os.pathsep.join(map(str, (classes, legacy)))
    results = {'compilation': compilation, 'sourceJarSha256': sha(legacy)}
    results['TieProbe-X2'] = child([JAVA, '-Xmx1024m', '-cp', cp, 'org.contactaware.security.simulation.TieProbe', 'X', '2'],
                                    120, directory/'TieProbe-X2.log')
    write(directory/'summary.json', results)
    for h in (1,2):
        for lazy in (False,True):
            name = f'X-original-H{h}-' + ('lazy' if lazy else 'canonical')
            result = child([JAVA, '-Xmx1024m', '-cp', cp, 'org.contactaware.security.simulation.ClaudeTieReplay',
                            str(h), str(lazy).lower(), str(directory/(name+'.json'))], 90, directory/(name+'.log'))
            results[name] = result
            write(directory/'summary.json', results)
            print('AUDIT', name, result['processStatus'], flush=True)

def probes(m):
    for s in m['mechanismProbes']:
        name = f'index{s["index"]}-H{s["horizon"]}'
        dest = OUT/'probes'/name
        if dest.exists(): raise ValueError('Probe exists: ' + name)
        outcome = child(java_command(m, 'probe', dest, s), 150, OUT/(name+'.log'))
        write(dest/'process.json', outcome)
        print('PROBE', name, outcome['processStatus'], flush=True)

def run(stage):
    m = verify()
    if stage == 'x':
        audits(m)
        probes(m)
    elif not (DOC/'x-intermediate.json').exists():
        raise ValueError('Save and validate the X checkpoint before extension')
    elif load(DOC/'x-intermediate.json')['status'] != 'PASS':
        raise ValueError('X checkpoint did not pass; no expansion')
    summary = load(OUT/'summary.json')
    for spec in m['cases']:
        if spec['stage'] != stage: continue
        name = spec['name']
        if summary[name]['status'] != 'NOT_RUN': raise ValueError('Refusing rerun: '+name)
        dest = OUT/'runs'/name
        print('START', name, flush=True)
        outcome = child(java_command(m, 'controller', dest, {'case': spec['sourceCase'], 'rule': spec['rule']}),
                        90, OUT/(name+'.log'))
        result = load(dest/'result.json') if (dest/'result.json').exists() else None
        row = {**spec, 'process': outcome, 'status': (result or {}).get('status', outcome['processStatus']),
               'result': result, 'checkpoint': rel(dest/'checkpoint.json') if (dest/'checkpoint.json').exists() else None}
        if result:
            export = result['summary']
            row.update(eventsFile='runs/'+name+'/'+export['actualEventsFile'],
                actualFile='runs/'+name+'/'+export['actualResultFile'], decisionsFile='runs/'+name+'/'+export['compactDecisionsFile'],
                simulationMetrics=result['simulationMetrics'], securityMetrics=result['securityMetrics'])
        summary[name] = row
        write(OUT/'summary.json', summary)
        print('DONE', name, row['status'], flush=True)
    verify()

def main():
    p = argparse.ArgumentParser()
    p.add_argument('mode', choices=['register','x','controls','verify'])
    a = p.parse_args()
    if a.mode == 'register': register()
    elif a.mode == 'verify': verify(); print('Frozen inputs PASS')
    else: run(a.mode)

if __name__ == '__main__': main()
