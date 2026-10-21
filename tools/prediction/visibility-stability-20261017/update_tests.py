from pathlib import Path
import shutil

root = Path(__file__).resolve().parents[3]
tests = root / 'src/test/java/dev/xantha/vss/client/prediction'
evidence = Path(__file__).parent
def edit(name, change):
    path = tests / name
    backup = evidence / 'before-files' / path.relative_to(root)
    backup.parent.mkdir(parents=True, exist_ok=True)
    if not backup.exists(): shutil.copy2(path, backup)
    text = path.read_text(encoding='utf-8')
    path.write_text(change(text), encoding='utf-8', newline='\n')

def resource(s):
    s = s.replace('ownershipWithdrawalAndMorphStillChangeTheCachedSubmission', 'diskCoverageCannotDeleteDrawsAndMorphStillInvalidatesCachedSubmission')
    start = s.index('        var coverage = ')
    end = s.index('        var packed = ', start)
    s = s[:start] + '        var coverage = new LegacyResidentCoverage(PredictionCoarseHandoffTest.ready());\n' + s[end:]
    s = s.replace('assertNull(pass.ranges(draw), "settled ownership removes the selected quad");',
                  'assertEquals(1, pass.ranges(draw).quads, "disk/upload metadata is not frame visibility");')
    s = s.replace('            assertNull(pass.ranges(draw));', '            assertEquals(1, pass.ranges(draw).quads);')
    s = s.replace('            provider.set(coverage, previousProvider);\n', '')
    return s
edit('PredictionResourceInvalidationTest.java', resource)

def prepared(s):
    start = s.index('    private PredictionPreviewHandoff.Uploads previousUploads;')
    end = s.index('    static final class Pass', start)
    s = s[:start] + s[end:]
    start = s.index('    static LegacyResidentCoverage coverage()')
    end = s.index('    @Test void replacementMember', start)
    s = s[:start] + s[end:]
    start = s.index('    @Test void coverageWithdrawalMorphAndThreeDimensionalDistanceRestoreCurrentFrame()')
    end = s.index('    @Test void waterMoves', start)
    s = s[:start] + '''    @Test void residentCoverageChangesCannotDeleteGeometryOrInvalidateStaticCommands() throws Exception {
        var local = draw(0,0,PredictionAuthoritativeCoverageViewTest.mesh(0));
        var far = draw(100,0,mesh()); var pass=new Pass(false); var draws=List.of(local,far);
        var camera=new Vec3(4,68,4);
        pass.prepare(draws,1,camera); assertEquals(1,pass.ranges(local).quads);
        long built=pass.counter("rangeBuilds");
        for (int frame=0;frame<120;frame++) {
            dev.xantha.vss.compat.StrictLodVisibility.updateRenderWindow(Level.OVERWORLD,frame*16,0,32);
            pass.prepare(draws,1,camera);
            assertEquals(1,pass.ranges(local).quads);
            assertEquals(built,pass.counter("rangeBuilds"),"Voxy scheduling metadata is not a draw version");
        }
        local.gpu().packed().morph(new float[]{0},-64,1000);
        pass.prepare(draws,1,camera); assertEquals(1,pass.ranges(local).quads);
        assertEquals(built+1,pass.counter("rangeBuilds"));
        dev.xantha.vss.compat.StrictLodVisibility.reset();
    }

''' + s[end:]
    return s
edit('PredictionPreparedPassLocalTest.java', prepared)

def batch(s):
    start = s.index('        var coverageField = PredictionRenderer.class.getDeclaredField("submissionCoverage");')
    end = s.index('        var passClass = ', start)
    s = s[:start] + '        var state = new LegacyResidentCoverage(PredictionCoarseHandoffTest.ready());\n' + s[end:]
    s = s.replace('if (coverage == all) assertEquals(0, primitives', 'if (coverage == all) assertEquals(baseline, primitives')
    s = s.replace('            provider.set(state, previousProvider);\n', '')
    return s
edit('PredictionProductionBatchGpuTest.java', batch)

def scene(s):
    s = s.replace('realSceneExcludesCompleteTerrainAndRestoresTheSameGpuAllocationOnWithdrawal',
                  'realSceneKeepsFallbackCommandsAndAllocationAcrossResidentCoverageChanges')
    s = s.replace('new PredictionRenderer.Scene(Runnable::run,coverage)', 'new PredictionRenderer.Scene(Runnable::run)')
    s = s.replace('assertFalse(hidden.stream().anyMatch(draw -> !draw.seam()));',
                  'assertTrue(hidden.stream().anyMatch(draw -> draw==original),"an unselected Voxy resident must not delete prediction");')
    s = s.replace('assertFalse(scene.residentDraws().stream().anyMatch(draw -> !draw.seam()),"GPU input must share early exclusion");',
                  'assertTrue(scene.residentDraws().stream().anyMatch(draw -> draw==original),"GPU input retains the same fallback");')
    return s
edit('PredictionAuthoritativeCoverageViewGpuTest.java', scene)

def feature(s):
    start = s.index('                                if (preview) {\n                                    var uploads = ')
    end = s.index('                                for (int mask', start)
    s = s[:start] + s[end:]
    s = s.replace('(!preview || !uploaded) && owns', 'owns')
    return s
edit('PredictionFeatureHandoffGpuTest.java', feature)

def legacy(s):
    s = s.replace('(LegacyResidentCoverage) reference("submissionCoverage")', 'new LegacyResidentCoverage()')
    s = s.replace('(LegacyResidentCoverage)rendererField("submissionCoverage")', 'new LegacyResidentCoverage()')
    return s
for name in ('LegacyPassFixture.java', 'CompletionLegacyPreparedPass.java'): edit(name, legacy)

def retirement(s):
    s = s.replace('new PredictionRenderResidency(PredictionCoarseHandoffTest.ready())', 'new PredictionRenderResidency()')
    s = s.replace('assertFalse(state.contains(tile), "fully Voxy-owned retirement must not wait for a prediction parent");',
                  'assertTrue(state.contains(tile), "uploaded Voxy metadata is not a drawable prediction replacement");')
    return s
edit('PredictionSubmissionCoverageTest.java', retirement)
