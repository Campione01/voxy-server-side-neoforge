from pathlib import Path

root = Path(__file__).resolve().parents[3]
source = (root / 'src/test/java/dev/xantha/vss/client/prediction/PredictionGpuTraversalExperimentTest.java').read_text(encoding='utf-8')
source = source.replace('PredictionGpuTraversalExperimentTest', 'RetainedGpuSelectionGpuTest')
source = source.replace('TraversalGpuCommandSelector', 'PredictionGpuCommandSelector')
source = source.replace('TraversalExperimentVisiblePlan', 'PredictionVisiblePlan')
source = source.replace('TraversalExperimentBatch', 'RetainedComparisonBatch')
source = source.replace('new PredictionVisiblePlan<Integer,PredictionRenderer.Draw>(mode == 0,PredictionRenderer.Draw::bounds)', 'new PredictionVisiblePlan<Integer,PredictionRenderer.Draw>(PredictionRenderer.Draw::bounds)')
source = source.replace('new long[4]', 'new long[2]').replace('mode<4', 'mode<2').replace('order<4', 'order<2').replace('Math.floorMod(round+order,4)', 'Math.floorMod(round+order,2)')
source = source.replace('3-order', '1-order')
source = source.replace('new int[]{128,1024,1025}', 'new int[]{128,512,1024,1025,1536,2048,3072,4096}')
source = source.replace('List<PredictionRenderer.Draw> draws=mode==0?plans.get(mode).selectStable(camera,view,projection.matrix(),frustum):plans.get(mode).residents();', '''List<PredictionRenderer.Draw> visible=plans.get(mode).selectStable(camera,view,projection.matrix(),frustum);
                                    List<PredictionRenderer.Draw> draws=batches.get(mode).select(visible,residentInput(plans.get(mode)),target.getDepthTextureId(),transform,camera,ranges(pass));''')
source = source.replace('pass.counter("workloadRevision"));', 'pass.counter("workloadRevision"),pass.counter("selectedCandidates"));')
source = source.replace('GPU_TRAVERSAL', 'RETAINED_GPU_SELECTION')
source = source.replace('cpu_ns,gpu_ns,visible_tiles,encoded_items,uploaded_bytes', 'cpu_ns,gpu_ns,visible_tiles,encoded_items,uploaded_bytes,selected')
source = source.replace('mode,frames,elapsed_ns', 'mode,frames,elapsed_ns,selected_frames')
source = source.replace('+batch.uploadedBytes());', '+batch.uploadedBytes()+","+batch.selected());')
source = source.replace('glFinish();long start=System.nanoTime();\n                    for(int f=', 'glFinish();long start=System.nanoTime();int selectedFrames=0;\n                    for(int f=')
source = source.replace('                        pass.prepare(draws,1,camera);', '                        if(batch.selected())selectedFrames++;\n                        pass.prepare(draws,1,camera);')
# Only the throughput loop has a local named batch before pass preparation.
source = source.replace('                                    if(batch.selected())selectedFrames++;\n                        pass.prepare(draws,1,camera);', '                                    pass.prepare(draws,1,camera);')
source = source.replace('+frames+","+time);', '+frames+","+time+","+selectedFrames);')
source = source.replace('                    glFinish();long time=System.nanoTime()-start;', '''                    glFinish();long time=System.nanoTime()-start;
                    if(mode==0 || scenario!=2 || count<3072)assertEquals(0,selectedFrames,"disabled cases must not dispatch resident selection");
                    else assertTrue(selectedFrames>0,"heavy rotating Hi-Z case must exercise production selection");''')
source = source.replace('var transform=new Matrix4f(projection.matrix()).mul(view);',
    'var transform=new Matrix4f(projection.matrix()).mul(view);\n                        updateDisplayView(count,camera,view,yaw);')
source = source.replace('vss.gpuTraversalResults', 'vss.retainedGpuSelectionResults')
source = source.replace('            Path output=Path.of(', '            RetainedSelectorStateChecks.run();\n            Path output=Path.of(')
source = source.replace('production-gpu-traversal', 'retained-gpu-selection')
source = source.replace('if(seam)words=Arrays.copyOf(words,64*12);', 'if(seam)words=Arrays.copyOf(words,64*12);')
source = source.replace('int[] words=PredictionDisplayGeometryTest.grid(0);', 'int[] words=PredictionDisplayGeometryTest.grid(count>=3072 ? PredictionPackedMesh.FLAG_MODEL_UV : 0);')
source = source.replace('var payload=PredictionPackedMesh.seamRecords(words,64);payload.prepareGpuStorage();', 'var payload=count>=1536 ? PredictionPackedMesh.terrainRecords(words,64) : PredictionPackedMesh.seamRecords(words,64);payload.prepareGpuStorage();')
source = source.replace('    private static int tex(', '''    private static List<PredictionRenderer.Draw> residentInput(PredictionVisiblePlan<Integer,PredictionRenderer.Draw> plan) {
        return plan.residents();
    }
    private static int tex(''')
source = source.replace('    private static List<PredictionRenderer.Draw> residentInput(', '''    private static final java.lang.reflect.Field DISPLAY_VIEW=displayField("displayWorkView"), DISPLAY_SCALE=displayField("selectionScale");
    private static java.lang.reflect.Field displayField(String name) {
        try { var field=PredictionRenderer.class.getDeclaredField(name); field.setAccessible(true); return field; }
        catch(ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void updateDisplayView(int count,Vec3 camera,Matrix4f view,double yaw) throws Exception {
        if(count<1536)return;
        double scale=512/(2*Math.tan(Math.toRadians(35)));
        DISPLAY_SCALE.setDouble(null,scale);
        var perspective=new Matrix4f().perspective((float)Math.toRadians(70),1,.05f,4096);
        var direction=new Vec3(Math.sin(yaw),-.13,-Math.cos(yaw)).normalize();
        DISPLAY_VIEW.set(null,PredictionWorkView.rendered(perspective,view,camera,direction,scale));
    }
    private static List<PredictionRenderer.Draw> residentInput(''')
assert 'draws=mode==0?' not in source
assert 'new long[4]' not in source
assert 'TraversalExperiment' not in source
output = root / 'tools/prediction/gpu-selection-retained/baseline-src/dev/xantha/vss/client/prediction/RetainedGpuSelectionGpuTest.java'
output.write_text(source, encoding='utf-8')

script = (root / 'tools/prediction/gpu-traversal-tests.gradle').read_text(encoding='utf-8')
script = script.replace('gpu-traversal-20261014', 'gpu-selection-retained-20261014')
script = script.replace('gpu-traversal-experiments/candidate-src', 'gpu-selection-retained/baseline-src')
script = script.replace("java.include 'dev/xantha/vss/client/prediction/**'", "java.include 'dev/xantha/vss/client/prediction/**'\n                java.exclude '**/PredictionGpuTraversalExperimentTest.java'")
script = script.replace("'LoadingBenefit', 'MainReduction'", "'GpuSelectionPolicy', 'GpuSelectionInput', 'LoadingBenefit', 'MainReduction'")
script = script.replace('gpuTraversalExperimentTest', 'gpuSelectionBenefitTest')
script = script.replace('gpu-traversal-experiment', 'gpu-selection-benefit')
script = script.replace('PredictionGpuTraversalExperimentTest.class', 'RetainedGpuSelectionGpuTest.class')
script = script.replace('vss.gpuTraversalResults', 'vss.retainedGpuSelectionResults')
script = script.replace("            tasks.register('gpuSelectionBenefitTest', Test)", """            tasks.register('gpuSelectionFallbackTest', Test) {
                regression(delegate, 'gpu-selection-fallback')
                include '**/RetainedSelectionFallbackGpuTest.class'
                systemProperty 'vss.gpuTests', 'true'
                systemProperty 'vss.indirectArenaMiB', '128'
            }
            tasks.register('gpuSelectionBenefitTest', Test)""")
(root / 'tools/prediction/gpu-selection-retained-tests.gradle').write_text(script, encoding='utf-8')
