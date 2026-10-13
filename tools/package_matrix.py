#!/usr/bin/env python3
"""Package only fully verified matrix artifacts; reject mixed or incomplete builds."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import zipfile
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def payload_digest(path):
    """Keep every class/resource; ignore only Loom's compiler provenance stamps."""
    result=hashlib.sha256()
    with zipfile.ZipFile(path) as archive:
        for name in sorted(archive.namelist()):
            data=archive.read(name)
            if name=='META-INF/MANIFEST.MF':
                data='\n'.join(line for line in data.decode('utf-8').splitlines()
                               if not line.startswith(('Fabric-Loader-Version:', 'Fabric-Mixin-Version:'))).encode('utf-8')
            result.update(name.encode('utf-8')+b'\0'+data+b'\0')
    return result.hexdigest()

def main():
    config=json.loads((ROOT/'compatibility.json').read_text(encoding='utf-8'))
    report=json.loads((ROOT/'build/matrix-logs/results.json').read_text(encoding='utf-8'))
    results={(r['minecraft'],r['profile']):r for r in report['results']}
    output=ROOT/'build/releases'
    output.mkdir(parents=True,exist_ok=True)
    files=[]
    for mc,target in config['targets'].items():
        binaries=[]
        for profile in ['baseline','latest']:
            result=results.get((mc,profile))
            if not result or not result['passed'] or not result['clientVerified']:
                raise RuntimeError(f'Missing verified client result: {mc}/{profile}')
            unit_reports=list((ROOT/f'build/{mc}/{profile}/test-results/test').glob('TEST-*.xml'))
            if not unit_reports:
                raise RuntimeError(f'Missing unit test results: {mc}/{profile}')
            for unit_report in unit_reports:
                suite=ET.parse(unit_report).getroot()
                if int(suite.get('failures','0')) or int(suite.get('errors','0')):
                    raise RuntimeError(f'Failed unit tests: {unit_report}')
            log=(ROOT/result['log']).read_text(encoding='utf-8',errors='replace')
            loader=config['latestLoader'] if profile=='latest' else target['minLoader']
            api=target['latestApi'] if profile=='latest' else target['minApi']
            if 'NOTEBOT_MATRIX_OK' not in log or not re.search(r'Loading Minecraft '+re.escape(mc)+r' with Fabric Loader '+re.escape(loader)+r'(?:\s|$)',log) or not re.search(r'- fabric-api '+re.escape(api)+r'(?:\s|$)',log):
                raise RuntimeError(f'Runtime environment was not verified: {mc}/{profile}')
            jars=list((ROOT/f'build/{mc}/{profile}/libs').glob('*.jar'))
            jars=[p for p in jars if not p.name.endswith('-sources.jar')]
            if len(jars)!=1:raise RuntimeError(f'Expected one binary: {mc}/{profile}')
            jar=jars[0]
            with zipfile.ZipFile(jar) as z:
                metadata=json.loads(z.read('fabric.mod.json'))
                expected={'minecraft':mc,'fabricloader':'>='+target['minLoader'],'fabric-api':'>='+target['minApi'].split('+')[0],'java':'>=25'}
                if metadata['depends']!=expected:raise RuntimeError(f'Wrong dependency guards: {jar}')
                if any(n.startswith('com/pomelene/notebot/') and ('Test' in n or 'TestConnection' in n) for n in z.namelist()):raise RuntimeError('Test code leaked into binary')
            binaries.append(jar)
        if payload_digest(binaries[0])!=payload_digest(binaries[1]):
            raise RuntimeError(f'Baseline and latest did not test identical mod classes/resources: {mc}')
        for jar in [binaries[0],binaries[0].with_name(binaries[0].stem+'-sources.jar')]:
            shutil.copy2(jar,output/jar.name);files.append(output/jar.name)
    for name in ['compatibility.json','README.md','COMPATIBILITY.md','CHANGELOG.md']:
        shutil.copy2(ROOT/name,output/name);files.append(output/name)
    shutil.copy2(ROOT/'build/matrix-logs/results.json',output/'verification-results.json')
    files.append(output/'verification-results.json')
    (output/'verification').mkdir(exist_ok=True)
    for evidence in (ROOT/'verification').glob('*.json'):
        destination=output/'verification'/evidence.name
        shutil.copy2(evidence,destination);files.append(destination)
    mod_version=re.search(r'^mod_version=(.+)$',(ROOT/'gradle.properties').read_text(encoding='utf-8'),re.M).group(1).strip()
    archive=output/f'pomelene-notebot-{mod_version}-diverse-source.zip'
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
        for name in ['src','gradle','tools','verification']:
            for p in (ROOT/name).rglob('*'):
                if p.is_file() and '__pycache__' not in p.parts:z.write(p,p.relative_to(ROOT).as_posix())
        for name in ['build.gradle','settings.gradle','gradle.properties','gradlew','gradlew.bat','LICENSE','.gitignore','.gitattributes','compatibility.json','README.md','COMPATIBILITY.md','CHANGELOG.md']:
            z.write(ROOT/name,name)
    files.append(archive)
    (output/'SHA256SUMS.txt').write_text(''.join(digest(p)+'  '+p.relative_to(output).as_posix()+'\n' for p in files),encoding='utf-8')
    print(f'Packaged {len(config["targets"])} verified targets into {output}')

if __name__=='__main__':main()
