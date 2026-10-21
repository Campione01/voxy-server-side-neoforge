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

def edit(path, change):
    backup(path)
    old = path.read_text(encoding='utf-8')
    new = change(old)
    if new != old: path.write_text(new, encoding='utf-8', newline='\n')

def replace(s, old, new):
    assert old in s, old[:160]
    return s.replace(old, new)

view = main / 'PredictionAuthoritativeCoverageView.java'
backup(view)
# Keep the retired implementation only as a regression/benchmark reference.
legacy = view.read_text(encoding='utf-8').replace('PredictionAuthoritativeCoverageView', 'LegacyResidentCoverage')
legacy = legacy.replace('/**\n * Render-thread coverage view shared by scene selection, submission and residency.',
    '/**\n * Frozen reproduction of the retired upload-only pruning bug. TEST SOURCES ONLY.\n * Render-thread coverage view previously shared by scene selection, submission and residency.')
(tests / 'LegacyResidentCoverage.java').write_text(legacy, encoding='utf-8')
view.unlink()

def renderer(s):
    s = replace(s, '    private static final PredictionAuthoritativeCoverageView submissionCoverage = new PredictionAuthoritativeCoverageView();\n', '')
    s = replace(s, '        private long meshVersion, ownershipVersion, gpuVersion;\n        private final PredictionAuthoritativeCoverageView.Selection ownershipSelection = new PredictionAuthoritativeCoverageView.Selection();',
                '        private long meshVersion, gpuVersion;')
    s = s.replace('        private long preparedOwnershipRevision = Long.MIN_VALUE;\n', '')
    s = s.replace('                    && preparedOwnershipRevision == submissionCoverage.revision()\n', '')
    s = replace(s, '                long ownershipVersion = submissionCoverage.selectionVersion(packed,\n                        draw.tile().baseBlockX(), draw.tile().baseBlockZ(), water, entry.ownershipSelection);\n', '')
    s = s.replace('                        || entry.ownershipVersion != ownershipVersion\n', '')
    s = replace(s, '                    entry.ownershipRanges = submissionCoverage.filter(packed, selected, draw.tile().baseBlockX(),\n                            draw.tile().baseBlockZ(), water, draw.faces());',
                '                    // Residency does not establish visibility. Keep the selected geometry\n                    // for the current-frame depth/vanilla-mask test in the terrain program.\n                    entry.ownershipRanges = selected;')
    s = s.replace('; entry.ownershipVersion = ownershipVersion', '')
    s = s.replace('            preparedOwnershipRevision = submissionCoverage.revision();\n', '')
    s = s.replace('            preparedOwnershipRevision = Long.MIN_VALUE;\n', '')
    s = replace(s, '    private static final Scene scene = new Scene(submissionCoverage);', '    private static final Scene scene = new Scene();')
    s = replace(s, '        submissionCoverage.update(exactMask.submissionSnapshot(), camera,\n                PredictionVoxyOwnership.radiusBlocks(ModCompat.getVoxyViewDistanceChunks().orElse(0)), level.dimension());\n', '')
    s = replace(s, '        boolean owned = packed != null && renderResidency.hasFallback(tile.key())\n                && submissionCoverage.fullyOwned(packed, tile.baseBlockX(), tile.baseBlockZ());',
                '        // Uploaded Voxy nodes can be unselected or occluded; only a resident\n        // prediction parent may back a budget eviction.\n        boolean owned = false;')
    s = replace(s, '            boolean owned = submissionCoverage.fullyOwned(packed, tile.baseBlockX(), tile.baseBlockZ());', '            boolean owned = false;')
    start = s.index('    /** Retains fallback resources while excluding fully handed-off terrain before visibility selection. */')
    end = s.index('        boolean boundaryPending()', start)
    s = s[:start] + '''    /** Stable prediction scene. Voxy uploads affect scheduling, never draw membership. */
    static final class Scene {
        private final PredictionSeamPreparation boundaries;
        private final Map<PredictionTileManager.PredictionTileKey, Draw> publishedTerrain = new java.util.HashMap<>();
        private final IdentityHashMap<PredictionGpuTile, Integer> publishedGpu = new IdentityHashMap<>();
        private final Set<PredictionTileManager.PredictionTileKey> stagedKeys = new HashSet<>();
        private long boundaryVersion, publishedBoundaryVersion;
        private List<PredictionRealBoundarySeams.Edge> boundaryEdges = List.of();
        Scene() { this(new PredictionSeamPreparation()); }
        Scene(java.util.concurrent.Executor executor) { this(new PredictionSeamPreparation(executor)); }
        private Scene(PredictionSeamPreparation preparation) {
            visible = new PredictionVisiblePlan<>(Draw::bounds);
            boundaries = preparation;
        }
        private List<Draw> select(Vec3 camera, Matrix4f modelView, Matrix4f projection,
                                  Frustum frustum, Set<DrawKey> candidates) {
            return visible.selectStable(camera, modelView, projection, frustum, candidates);
        }
''' + s[end:]
    s = s.replace('        private long gpuCoverage = -1, gpuEpoch = -1, gpuRetryAt;', '        private long gpuEpoch = -1, gpuRetryAt;')
    s = s.replace('            long coverage = authoritativeCoverage == null ? 0 : authoritativeCoverage.revision();\n', '')
    s = s.replace(' && coverage == gpuCoverage', '')
    s = s.replace(' gpuCoverage = coverage;', '')
    s = replace(s, '                if (geo == null || !shouldRender(new Draw(tile, gpu, null, false, VssLodFaceGroup.ALL, 0, geo.culling()))) continue;',
                '                if (geo == null) continue;')
    s = s.replace('        submissionCoverage.clear();\n', '')
    s = s.replace('submissionCoverage.revision()', 'ClientPredictionState.exactCoverageRemovalRevision()')
    # The cell ownership mask still selects prediction parents/children. It is
    # independent from Voxy residency, so name this range accordingly.
    s = s.replace('ownershipRanges', 'selectedRanges')
    s = s.replace('        private long ownershipSkipped;\n', '')
    s = s.replace('            ownershipSkipped = 0;\n', '')
    s = s.replace('                ownershipSkipped += entry.displayQuads - entry.selectedRanges.quads;\n', '')
    s = s.replace(' + ",voxyExcludedQuads=" + ownershipSkipped', '')
    s = s.replace('result.put("voxyExcluded",ownershipSkipped); ', '')
    assert 'submissionCoverage' not in s and 'authoritativeCoverage' not in s
    return s
edit(main / 'PredictionRenderer.java', renderer)

def residency(s):
    s = replace(s, '    private final PredictionPreviewHandoff.Uploads voxyUploads;\n    PredictionRenderResidency() { this(PredictionPreviewHandoff.VOXY); }\n    PredictionRenderResidency(PredictionPreviewHandoff.Uploads uploads) { voxyUploads = uploads; }\n', '')
    s = s.replace('    private long retainedUploadRevision = -1;\n', '')
    s = replace(s, '        long uploadRevision = voxyUploads.revision();\n        if (source == retainedSource && revision == retainedRevision\n                && (retiring.isEmpty() || uploadRevision == retainedUploadRevision)) return;',
                '        if (source == retainedSource && revision == retainedRevision) return;')
    s = replace(s, '                    || distanceReduced && !source.tiles().containsKey(key)\n                    || source.voxyOwned().contains(key) && uploadedReplacement(tiles.get(key))) {',
                '                    || distanceReduced && !source.tiles().containsKey(key)) {')
    s = s.replace('        retainedUploadRevision = uploadRevision;\n', '')
    start = s.index('    private boolean uploadedReplacement(')
    end = s.index('    private void releaseDelta(', start)
    s = s[:start] + s[end:]
    s = s.replace('retainedRevision=retainedUploadRevision=-1', 'retainedRevision=-1')
    assert 'voxyUploads' not in s and 'retainedUploadRevision' not in s
    return s
edit(main / 'PredictionRenderResidency.java', residency)

def shader(s):
    old = '''                bool surfaceVisible = diagonal || dot(faceNormal, relative) < 0.0;
                vSurfaceVisible = surfaceVisible ? 1.0 : 0.0;
'''
    s = replace(s, old, '')
    anchor = '''                vFluidPlane = fluid != 0u && constantFacePlane
'''
    s = replace(s, anchor, '''                // An axis normal proves back-facing only for a constant plane.
                // Sloped/morphed tops can cross eye height; a flat varying from
                // their provoking vertex used to delete just one triangle.
                bool surfaceVisible = diagonal || !constantFacePlane || dot(faceNormal, relative) < 0.0;
                vSurfaceVisible = surfaceVisible ? 1.0 : 0.0;
''' + anchor)
    return s
edit(main / 'PredictionTerrainProgram.java', shader)

for path in (root / 'src/test/java').rglob('*.java'):
    # Old experiments explicitly refer to the frozen test-only implementation;
    # production regressions below are updated to exercise the live renderer.
    old = path.read_text(encoding='utf-8')
    if 'PredictionAuthoritativeCoverageView' in old and path.name != 'LegacyResidentCoverage.java':
        edit(path, lambda s: re.sub(r'\bPredictionAuthoritativeCoverageView\b', 'LegacyResidentCoverage', s))
config = root / 'tools/prediction/visibility-stability-tests.gradle'
edit(config, lambda s: replace(s, "sourceSets.meridianImplementation.java.include '**/PredictionVisibilityStabilityTest.java',", "sourceSets.meridianImplementation.java.include '**/LegacyResidentCoverage.java', '**/PredictionVisibilityStabilityTest.java',"))
