"""Inspect final release contents against the installed scene-handoff build."""
from pathlib import Path
import hashlib
import json
import struct
import zipfile

neo = Path(__file__).resolve().parents[3]
evidence = Path(__file__).resolve().parent
removed = {'PredictionAuthoritativeCoverageView', 'PredictionOwnershipRuns',
           'PredictionOwnershipData', 'PredictionMetadataCache'}
changed = {'PredictionFeatureHandoff', 'PredictionGpuLodGraph', 'PredictionGpuTile',
           'PredictionMeshRestore', 'PredictionPackedMesh', 'PredictionRenderer',
           'PredictionRenderResidency', 'PredictionTerrainProgram', 'PredictionTileManager'}
results = []
for root, major in ((neo, 65), (neo.parent / 'voxyserverside', 61)):
    directory = root / 'lib/visibility-stability-20261017'
    jars = list(directory.glob('vss-*.jar'))
    assert len(jars) == 1, jars
    path = jars[0]
    previous = root / 'lib/scene-handoff-20261017' / path.name
    with zipfile.ZipFile(path) as jar, zipfile.ZipFile(previous) as old:
        entries, old_entries = set(jar.namelist()), set(old.namelist())
        added = sorted(entries - old_entries)
        deleted = sorted(old_entries - entries)
        modified = sorted(n for n in entries & old_entries if jar.read(n) != old.read(n))
        assert not added, ('unexpected entries', added)
        def owner(name):
            return name.rsplit('/', 1)[-1].split('$', 1)[0].removesuffix('.class')
        assert all(owner(n) in removed for n in deleted), deleted
        assert all(owner(n) in changed or n in {'META-INF/MANIFEST.MF', 'vss.refmap.json'}
                   for n in modified), ('unexpected changes', modified)
        vss_classes = [n for n in entries if n.startswith('dev/xantha/vss/') and n.endswith('.class')]
        assert all(struct.unpack('>H', jar.read(n)[6:8])[0] == major for n in vss_classes)
        retired = [n for n in vss_classes if owner(n) in removed or owner(n) in
                   {'LegacyResidentCoverage', 'LegacyOwnershipRuns', 'LegacyOwnershipData', 'LegacyMetadataCache'}
                   or owner(n).endswith('Test')]
        assert not retired, ('retired or test classes leaked into the release', retired)
        # Changes to refmap formatting must preserve its parsed semantics.
        if 'vss.refmap.json' in modified:
            assert json.loads(jar.read('vss.refmap.json')) == json.loads(old.read('vss.refmap.json'))
        result = dict(repository=root.name, jar=str(path), bytes=path.stat().st_size,
                      sha256=hashlib.sha256(path.read_bytes()).hexdigest(), previous_sha256=hashlib.sha256(previous.read_bytes()).hexdigest(),
                      java_major=major, vss_classes=len(vss_classes), added=added, deleted=deleted, modified=modified)
        results.append(result)
        (directory / 'SHA256SUMS.txt').write_text(result['sha256'] + '  ' + path.name + '\n', encoding='utf-8')
        print(root.name, 'OK', 'changed', len(modified), 'deleted', len(deleted), result['sha256'])

(evidence / 'artifact-audit.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
