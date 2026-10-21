from pathlib import Path
import hashlib
import json
import shutil

neo = Path(__file__).resolve().parents[3]
forge = neo.parent / 'voxyserverside'
java = Path('src/main/java/dev/xantha/vss/client/prediction')
backup = forge / 'tools/prediction/gpu-selection-retained/pre-integration'
backup.mkdir(parents=True, exist_ok=True)

renderer = forge / java / 'PredictionRenderer.java'
old = renderer.read_text(encoding='utf-8')
new = (neo / java / 'PredictionRenderer.java').read_text(encoding='utf-8')

def replace_once(text, before, after):
    if before == after or after in text:
        return text
    assert text.count(before) == 1, before[:160]
    return text.replace(before, after, 1)

old_marker = '    private static long drawPasses(Minecraft minecraft, Frame event,'
end_marker = '    /**\n     * Select MDI from storage residency only.'
current = old[old.index(old_marker):old.index(end_marker,old.index(old_marker))]
desired = new[new.index(old_marker):new.index(end_marker,new.index(old_marker))]
patched = replace_once(old, current, desired)
patched = replace_once(patched,
    '    private static long drawNormalPass(Minecraft minecraft, Frame event,\n'
    '                                      List<Draw> draws, Vec3 camera,',
    '    private static long drawNormalPass(Minecraft minecraft, Frame event,\n'
    '                                      List<Draw> draws, List<Draw> ordinaryDraws, Vec3 camera,')
fallback_start = new.index('                if (!water && pass == residentOpaquePass && !indirectBatch.gpuSelection()) {')
fallback_end = new.index('\n                }', fallback_start) + len('\n                }')
fallback = new[fallback_start:fallback_end]
patched = replace_once(patched,
    '                } else indirectBatch.begin(program, false,\n'
    '                        renderResidency.revision(), submissionCoverage.revision());',
    '                } else indirectBatch.begin(program, false,\n'
    '                        renderResidency.revision(), submissionCoverage.revision());\n' + fallback)
patched = replace_once(patched,
    '    private static final PreparedPass opaquePass = new PreparedPass(false);',
    '    private static final PreparedPass opaquePass = new PreparedPass(false);\n'
    '    private static final PreparedPass residentOpaquePass = new PreparedPass(false);\n'
    '    private static final PredictionGpuSelectionInput gpuSelectionInput = new PredictionGpuSelectionInput();\n'
    '    private static long lastGpuSelectionUse;')
patched = replace_once(patched,
    '        boolean references(PredictionGpuTile gpu) { return publishedGpu.containsKey(gpu); }',
    '        boolean references(PredictionGpuTile gpu) { return publishedGpu.containsKey(gpu); }\n'
    '        List<Draw> residentDraws() { return visible.residents(); }')
patched = replace_once(patched,
    '        List<Draw> residentDraws() { return visible.residents(); }',
    '        List<Draw> residentDraws() { return visible.residents(); }\n'
    '        int residentDrawCount() { return visible.residentCount(); }')
patched = replace_once(patched,
    '        opaquePass.clear();',
    '        opaquePass.clear();\n        residentOpaquePass.clear();\n'
    '        gpuSelectionInput.clear();\n        lastGpuSelectionUse = 0;')
if not (backup / renderer.name).exists(): shutil.copy2(renderer, backup / renderer.name)
renderer.write_text(patched, encoding='utf-8')

expected = json.loads((neo/'tools/prediction/gpu-traversal-experiments/baseline-hashes.json').read_text())['voxyserverside']
for name in ['PredictionIndirectBatch.java','PredictionVisiblePlan.java','PredictionOcclusionCuller.java']:
    target = forge/java/name
    source = neo/java/name
    if target.read_bytes() == source.read_bytes(): continue
    if not (backup/name).exists():
        assert hashlib.sha256(target.read_bytes()).hexdigest() == expected[name.removesuffix('.java')], name
        shutil.copy2(target,backup/name)
    shutil.copy2(source,target)
for name in ['PredictionGpuCommandSelector.java','PredictionGpuSelectionPolicy.java','PredictionGpuSelectionInput.java']:
    shutil.copy2(neo/java/name,forge/java/name)

for name in ['PredictionGpuSelectionPolicyTest.java','PredictionGpuSelectionInputTest.java']:
    test = Path('src/test/java/dev/xantha/vss/client/prediction')/name
    shutil.copy2(neo/test, forge/test)
tool = Path('tools/prediction/gpu-selection-retained')
shutil.copytree(neo/tool/'baseline-src', forge/tool/'baseline-src', dirs_exist_ok=True)
shutil.copy2(neo/'tools/prediction/gpu-selection-retained-tests.gradle', forge/'tools/prediction/gpu-selection-retained-tests.gradle')
print('Synchronized bounded selection, preserving Forge renderer platform hooks and previous edits.')
