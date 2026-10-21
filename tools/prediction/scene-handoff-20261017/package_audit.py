from pathlib import Path
import hashlib
import json
import struct
import zipfile
import xml.etree.ElementTree as ET

neo = Path(__file__).resolve().parents[3]
approved = {'PredictionTerrainProgram', 'PredictionRenderer', 'PredictionOwnershipRuns',
            'PredictionAuthoritativeCoverageView', 'PredictionPlanPreparation',
            'PredictionTileManager', 'PredictionRenderResidency'}
for root, platform, filename in [
    (neo, 'neo', 'vss-0.3.5-neoforge-1.21.1.jar'),
    (neo.parent / 'voxyserverside', 'forge', 'vss-0.3.5-forge-1.20.1.jar')
]:
    artifact = root / 'lib/scene-handoff-20261017' / filename
    baseline = root / 'lib/teleport-loading-20261017' / filename
    with zipfile.ZipFile(artifact) as fresh, zipfile.ZipFile(baseline) as old:
        names = set(fresh.namelist()); oldnames = set(old.namelist())
        changed = sorted(name for name in names & oldnames if fresh.read(name) != old.read(name))
        added = sorted(names - oldnames); removed = sorted(oldnames - names)
        def expected(name):
            return name == 'META-INF/MANIFEST.MF' or (
                name.startswith('dev/xantha/vss/client/prediction/') and name.endswith('.class')
                and name.rsplit('/', 1)[-1].split('$')[0].split('.')[0] in approved)
        unexpected = [name for name in changed + added + removed if not expected(name)]
        assert not unexpected, unexpected
        def stable_manifest(archive):
            return [line for line in archive.read('META-INF/MANIFEST.MF').decode().splitlines()
                    if not line.startswith('Implementation-Timestamp:')]
        assert stable_manifest(fresh) == stable_manifest(old)
        assert not any('/test/' in name or 'CoarseHandoffGpuTest.class' in name for name in names)
        assert b'VoxyOwnershipDistance' not in fresh.read(
            'dev/xantha/vss/client/prediction/PredictionTerrainProgram.class')
        versions = {name: struct.unpack('>H', fresh.read(
            'dev/xantha/vss/client/prediction/' + name + '.class')[6:8])[0] for name in approved}
        assert all(major == (65 if platform == 'neo' else 61) for major in versions.values()), versions
        for name, token in [('PredictionOwnershipRuns', 'hasRetirableRuns'),
                            ('PredictionAuthoritativeCoverageView', 'wholeCoverage'),
                            ('PredictionTileManager', 'uploadedOwnership'),
                            ('PredictionRenderResidency', 'uploadedReplacement')]:
            assert token.encode() in fresh.read('dev/xantha/vss/client/prediction/' + name + '.class')
    groups = {'cpu': set(), 'gpu': set()}; suites = {}
    for folder in (root / 'build-scene-handoff-20261017/test-results').iterdir():
        total = failed = skipped = 0
        for path in folder.glob('TEST-*.xml'):
            result = ET.parse(path).getroot(); total += int(result.get('tests', '0'))
            failed += int(result.get('failures', '0')) + int(result.get('errors', '0'))
            skipped += int(result.get('skipped', '0'))
            category = 'gpu' if 'gpu' in folder.name else 'cpu'
            for case in result.findall('testcase'):
                groups[category].add((case.get('classname'), case.get('name')))
        if total:
            assert failed == skipped == 0, (folder, failed, skipped)
            suites[folder.name] = {'tests': total, 'failed': failed, 'skipped': skipped}
    sha = hashlib.sha256(artifact.read_bytes()).hexdigest()
    report = {'platform': platform, 'artifact': str(artifact), 'sha256': sha, 'baseline': str(baseline),
              'changed_entries': changed, 'added_entries': added, 'removed_entries': removed,
              'unexpected_changes': unexpected, 'class_versions': versions,
              'unique_tests': {kind: len(cases) for kind, cases in groups.items()}, 'suites': suites}
    destination = root / 'tools/prediction/scene-handoff-20261017/package-audit.json'
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    (artifact.parent / 'SHA256SUMS.txt').write_text(sha + '  ' + filename + '\n', encoding='utf-8')
    print(platform, 'sha256=', sha, 'changed=', len(changed), 'added=', len(added),
          'removed=', len(removed), 'unique_tests=', report['unique_tests'])
