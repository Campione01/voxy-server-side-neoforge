"""Apply only this fix's deltas, preserving platform-specific sources. Default: dry run."""
from pathlib import Path
from difflib import SequenceMatcher
import json
import sys

evidence = Path(__file__).resolve().parent
neo = evidence.parents[2]
forge = neo.parent / 'voxyserverside'
baseline = evidence / 'before-files'

def read(path):
    return path.read_text(encoding='utf-8-sig')

paths = {p.relative_to(baseline) for p in baseline.rglob('*') if p.is_file()}
testdir = Path('src/test/java/dev/xantha/vss/client/prediction')
paths.update(testdir / (name + '.java') for name in (
    'LegacyResidentCoverage', 'LegacyOwnershipRuns', 'LegacyOwnershipData', 'LegacyMetadataCache',
    'PredictionVisibilityStabilityTest', 'PredictionVisibilityStabilityGpuTest'))
paths.add(Path('tools/prediction/visibility-stability-tests.gradle'))
plan = []
for relative in sorted(paths):
    source, target, before = neo / relative, forge / relative, baseline / relative
    current = read(target) if target.exists() else None
    after = read(source) if source.exists() else None
    if current == after:
        continue
    if not source.exists():
        if not before.exists() or current != read(before):
            raise RuntimeError(f'Delete requires an unchanged baseline: {relative}')
        result = None
    elif current is None:
        result = after
    else:
        if not before.exists():
            raise RuntimeError(f'New fixture unexpectedly exists on Forge: {relative}')
        oldlines = read(before).splitlines(keepends=True)
        newlines = after.splitlines(keepends=True)
        result = current
        groups = SequenceMatcher(None, oldlines, newlines, autojunk=False).get_grouped_opcodes(3)
        for group in reversed(list(groups)):
            old = ''.join(oldlines[group[0][1]:group[-1][2]])
            new = ''.join(newlines[group[0][3]:group[-1][4]])
            if result.count(old) != 1:
                if relative.name == 'LegacyPassFixture.java':
                    old = old.replace('.arenaSlice()', '.arenaSlice(water)')
                    new = new.replace('.arenaSlice()', '.arenaSlice(water)')
                lines = result.splitlines(keepends=True)
                expected = [line.rstrip() for line in old.splitlines(keepends=True)]
                normalized = [line.rstrip() for line in lines]
                matches = [i for i in range(len(lines) - len(expected) + 1)
                           if normalized[i:i + len(expected)] == expected]
                if len(matches) != 1:
                    raise RuntimeError(f'Non-unique/missing delta context: {relative}: {old[:220]!r}')
                old = ''.join(lines[matches[0]:matches[0] + len(expected)])
            result = result.replace(old, new, 1)
    plan.append((relative, result, current))

# Validate every delta before writing anything.
for relative, result, current in plan:
    print(('DELETE' if result is None else 'ADD' if current is None else 'PATCH'), relative)
print(f'Validated {len(plan)} file operations; apply={"--apply" in sys.argv}')
if '--apply' in sys.argv:
    for relative, result, current in plan:
        target = forge / relative
        backup = forge / 'tools/prediction/visibility-stability-20261017/before-files' / relative
        if current is not None and not backup.exists():
            backup.parent.mkdir(parents=True, exist_ok=True)
            backup.write_text(current, encoding='utf-8', newline='\n')
        if result is None:
            target.unlink()
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(result, encoding='utf-8', newline='\n')
    manifest = [dict(path=str(r).replace('\\','/'), action='delete' if s is None else 'add' if c is None else 'patch')
                for r, s, c in plan]
    (evidence / 'forge-sync.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
