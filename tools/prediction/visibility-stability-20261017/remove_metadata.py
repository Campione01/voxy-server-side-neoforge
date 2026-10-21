from pathlib import Path
import re
import shutil

root = Path(__file__).resolve().parents[3]
main = root / 'src/main/java/dev/xantha/vss/client/prediction'
tests = root / 'src/test/java/dev/xantha/vss/client/prediction'
evidence = Path(__file__).parent
def backup(path):
    target = evidence / 'before-files' / path.relative_to(root)
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists(): shutil.copy2(path, target)
def write(path, text):
    backup(path)
    path.write_text(text, encoding='utf-8', newline='\n')

names = {'PredictionOwnershipRuns':'LegacyOwnershipRuns', 'PredictionOwnershipData':'LegacyOwnershipData',
         'PredictionMetadataCache':'LegacyMetadataCache'}
for source, target in names.items():
    path = main / (source + '.java')
    text = path.read_text(encoding='utf-8')
    for old, new in names.items(): text = text.replace(old, new)
    if source == 'PredictionOwnershipRuns':
        anchor = 'final class LegacyOwnershipRuns {'
        text = text.replace(anchor, anchor + '''
    // Frozen upload-only pruning metadata. Never included in production.
    private static final java.util.WeakHashMap<PredictionPackedMesh, LegacyOwnershipRuns> FIXTURES = new java.util.WeakHashMap<>();
    static LegacyOwnershipRuns of(PredictionPackedMesh mesh) {
        synchronized (FIXTURES) {
            return FIXTURES.computeIfAbsent(mesh, value -> new LegacyOwnershipRuns(value.restoreWords()));
        }
    }
''')
    (tests / (target + '.java')).write_text(text, encoding='utf-8')
    backup(path)
    if source != 'PredictionOwnershipRuns': path.unlink()

(main / 'PredictionOwnershipRuns.java').write_text('''package dev.xantha.vss.client.prediction;

/** Immutable feature presence only. Voxy residency never removes draw ranges. */
final class PredictionOwnershipRuns {
    final boolean hasFeatures;
    final boolean hasPreviews;

    PredictionOwnershipRuns(int[] words) {
        boolean features = false, previews = false;
        for (int p = 0; p < words.length; p += PredictionPackedMesh.STRIDE_INTS) {
            int flags = words[p + 10];
            features |= (flags & PredictionPackedMesh.FEATURE) != 0;
            previews |= (flags & (PredictionPackedMesh.FEATURE | PredictionPackedMesh.PREVIEW))
                    == (PredictionPackedMesh.FEATURE | PredictionPackedMesh.PREVIEW);
        }
        hasFeatures = features; hasPreviews = previews;
    }
    long retainedHeapBytes() { return 16; }
}
''', encoding='utf-8')

for name in ('PredictionPackedMesh', 'PredictionMeshRestore', 'PredictionTileManager', 'PredictionGpuTile'):
    path = main / (name + '.java')
    text = path.read_text(encoding='utf-8')
    text = text.replace('        if (!ownershipRuns.ready()) { ownershipRuns.requestRestore(); return false; }\n', '')
    text = text.replace('        PredictionMetadataCache.SHARED.clear();\n', '')
    text = text.replace('                + ",metadataCache={" + PredictionMetadataCache.SHARED.diagnostics() + "}"\n', '')
    text = text.replace('''            if (!desired && !near && !scoped && !pending.contains(key) && now - lastUse >= PredictionCpuCachePolicy.COLD_AGE_NANOS) {
                var packed = tile.mesh().gpuPayload();
                if (packed != null) packed.ownershipRuns.cool();
            }
''','')
    if name == 'PredictionGpuTile':
        text = text.replace('        if (!next.ownershipRuns.pin()) { next.ownershipRuns.requestRestore(); return false; }\n', '')
        text = text.replace('        if (!next.ownershipRuns.pin()) { next.ownershipRuns.requestRestore(); return 0; }\n', '')
        text = text.replace('            if (packed != null) packed.ownershipRuns.unpin();\n', '')
        text = text.replace('        if (packed != null) packed.ownershipRuns.unpin();\n', '')
        text = text.replace('        boolean installed = false;\n        try {\n', '        {\n')
        text = text.replace('            installed = true;\n', '')
        text = text.replace('        } finally { if (!installed) next.ownershipRuns.unpin(); }', '        }')
    write(path, text)

pattern = re.compile(r'((?:\b\w+\([^()]*\)|\b\w+)(?:\.\w+(?:\([^()]*\))?)*)\.ownershipRuns\b')
for path in tests.glob('*.java'):
    if path.stem in names.values(): continue
    text = path.read_text(encoding='utf-8')
    changed = text
    for old, new in names.items(): changed = changed.replace(old, new)
    changed = pattern.sub(r'LegacyOwnershipRuns.of(\1)', changed)
    if changed != text: write(path, changed)

path = root / 'tools/prediction/visibility-stability-tests.gradle'
text = path.read_text(encoding='utf-8').replace("'**/LegacyResidentCoverage.java',",
    "'**/LegacyResidentCoverage.java', '**/LegacyOwnershipRuns.java', '**/LegacyOwnershipData.java', '**/LegacyMetadataCache.java',")
write(path, text)
