#!/usr/bin/env python3
"""Reproducible, sequential builds and client checks for the pinned compatibility matrix."""
import argparse
import datetime
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
CONFIG = json.loads((ROOT / 'compatibility.json').read_text(encoding='utf-8'))

def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--versions', nargs='+', choices=list(CONFIG['targets']), default=list(CONFIG['targets']))
    parser.add_argument('--profiles', nargs='+', choices=['baseline', 'latest'], default=['baseline', 'latest'])
    parser.add_argument('--verify', action='store_true', help='Run the full client gameplay and detection suite in disposable worlds')
    parser.add_argument('--compile-only', action='store_true', help='Only compile main and test Java sources')
    parser.add_argument('--keep-going', action='store_true')
    args = parser.parse_args()
    wrapper = ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    logs = ROOT / 'build' / 'matrix-logs'
    logs.mkdir(parents=True, exist_ok=True)
    report_path=logs/'results.json'
    prior=json.loads(report_path.read_text(encoding='utf-8'))['results'] if report_path.exists() else []
    requested={(v,p) for v in args.versions for p in args.profiles}
    results=[r for r in prior if (r['minecraft'],r['profile']) not in requested]
    attempted=[]
    for version in args.versions:
        for profile in args.profiles:
            target = CONFIG['targets'][version]
            tasks = ['compileJava', 'compileGametestJava'] if args.compile_only else ['build']
            if args.verify:
                tasks += ['runClientGameTest', '-PnotebotTestSuite=matrix']
            command = [str(wrapper), *tasks, f'-PmcVersion={version}', f'-PfabricProfile={profile}', '--console=plain']
            log_path = logs / f'{version}-{profile}.log'
            print(f'START {version} / {profile}', flush=True)
            started = time.monotonic()
            with log_path.open('w', encoding='utf-8') as log:
                code = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT).returncode
            log_text = log_path.read_text(encoding='utf-8', errors='replace')
            expected_loader = CONFIG['latestLoader'] if profile == 'latest' else target['minLoader']
            expected_api = target['latestApi'] if profile == 'latest' else target['minApi']
            environment_ok = bool(re.search(r'Loading Minecraft '+re.escape(version)+r' with Fabric Loader '+re.escape(expected_loader)+r'(?:\s|$)', log_text)) and bool(re.search(r'- fabric-api '+re.escape(expected_api)+r'(?:\s|$)', log_text))
            passed = code == 0 and (not args.verify or ('NOTEBOT_MATRIX_OK' in log_text and environment_ok))
            result = dict(minecraft=version, profile=profile,
                          loader=CONFIG['latestLoader'] if profile == 'latest' else target['minLoader'],
                          fabricApi=target['latestApi'] if profile == 'latest' else target['minApi'],
                          passed=passed, clientVerified=args.verify and passed,
                          seconds=round(time.monotonic()-started, 1), log=log_path.relative_to(ROOT).as_posix())
            results.append(result)
            attempted.append(result)
            (logs / 'results.json').write_text(json.dumps(dict(checkedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),results=results),indent=2)+'\n',encoding='utf-8')
            print(('PASS' if passed else 'FAIL')+f' {version} / {profile} ({result["seconds"]}s)',flush=True)
            if not passed:
                print('\n'.join(log_text.splitlines()[-55:]), flush=True)
                if not args.keep_going:
                    return 1
    return 0 if all(r['passed'] for r in attempted) else 1

if __name__ == '__main__':
    sys.exit(main())
