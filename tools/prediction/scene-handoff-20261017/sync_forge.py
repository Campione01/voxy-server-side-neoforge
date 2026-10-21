from pathlib import Path
from difflib import SequenceMatcher, unified_diff

neo = Path(__file__).resolve().parents[3]
forge = neo.parent / 'voxyserverside'
evidence = neo / 'tools/prediction/scene-handoff-20261017'
backups = forge / 'tools/prediction/scene-handoff-20261017/before-src'
backups.mkdir(parents=True, exist_ok=True)

def read(path):
    return path.read_text(encoding='utf-8-sig')

def write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding='utf-8', newline='\n')

def patch_current(relative, baseline):
    target = forge / relative
    before = read(baseline).splitlines(keepends=True)
    after = read(neo / relative).splitlines(keepends=True)
    current = read(target)
    write(backups / target.name, current)
    for group in reversed(list(SequenceMatcher(None, before, after, autojunk=False).get_grouped_opcodes(3))):
        old = ''.join(before[group[0][1]:group[-1][2]])
        new = ''.join(after[group[0][3]:group[-1][4]])
        if current.count(old) != 1:
            # Forge stores mixed column keys; preserve that platform-specific representation.
            old = old.replace('Map.copyOf(relief), Set.copyOf(editedCells), false,',
                              'Map.copyOf(relief), Set.copyOf(editedCells), true,')
            new = new.replace('Map.copyOf(relief), Set.copyOf(editedCells), false,',
                              'Map.copyOf(relief), Set.copyOf(editedCells), true,')
        if current.count(old) != 1:
            raise RuntimeError(f'Non-unique/missing Forge patch context: {relative}: {old[:120]!r}')
        current = current.replace(old, new, 1)
    write(target, current)
    print('PATCH', relative)

main = 'src/main/java/dev/xantha/vss/client/prediction/'
for name in ['PredictionAuthoritativeCoverageView', 'PredictionOwnershipRuns', 'PredictionPlanPreparation',
             'PredictionRenderResidency', 'PredictionTileManager', 'PredictionRenderer', 'PredictionTerrainProgram']:
    patch_current(main + name + '.java', evidence / 'before-src' / (name + '.java'))
patch_current('src/test/java/dev/xantha/vss/client/prediction/PredictionDemandTestSupport.java',
              evidence / 'before-src/PredictionDemandTestSupport.java')

tests = 'src/test/java/dev/xantha/vss/client/prediction/'
for name in ['PredictionCoarseHandoffTest', 'PredictionCoarsePlanHandoffTest', 'PredictionCoarseHandoffGpuTest']:
    write(forge / tests / (name + '.java'), read(neo / tests / (name + '.java')))
write(forge / 'tools/prediction/scene-handoff-tests.gradle', read(neo / 'tools/prediction/scene-handoff-tests.gradle'))

# Print remaining fixture differences for review before copying any existing test.
for name in ['PredictionAuthoritativeCoverageViewTest', 'PredictionAuthoritativeCoverageViewGpuTest',
             'PredictionFeatureHandoffTest', 'PredictionOwnershipMemoryTest', 'PredictionProgressiveBenefitTest',
             'PredictionProgressiveSubmissionTest', 'PredictionSubmissionCoverageTest', 'PredictionPreviewHandoffTest',
             'PredictionMainReductionTest', 'PredictionRegionalOwnershipTest', 'PredictionPreparedPassLocalTest',
             'PredictionResourceInvalidationTest', 'PredictionRenderTargetGpuTest']:
    relative = tests + name + '.java'
    difference = list(unified_diff(read(forge / relative).splitlines(), read(neo / relative).splitlines(),
                                   fromfile='forge/' + name, tofile='neo/' + name, n=2))
    print('\n'.join(difference))
