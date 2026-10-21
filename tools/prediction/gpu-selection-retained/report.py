from pathlib import Path
import csv
import hashlib
import json
import shutil
import statistics
import struct
import xml.etree.ElementTree as ET
import zipfile

neo = Path(__file__).resolve().parents[3]
repositories = [neo, neo.parent / 'voxyserverside']
shared = ['PredictionGpuCommandSelector.java', 'PredictionGpuSelectionPolicy.java',
          'PredictionGpuSelectionInput.java', 'PredictionIndirectBatch.java',
          'PredictionVisiblePlan.java', 'PredictionOcclusionCuller.java']
java = Path('src/main/java/dev/xantha/vss/client/prediction')
for name in shared:
    assert (repositories[0]/java/name).read_bytes() == (repositories[1]/java/name).read_bytes(), name

validation = {
    'date': '2026-10-14', 'liveClientProfiled': False, 'clientLaunched': False,
    'installedInstanceModified': False, 'githubPushed': False,
    'gpu': 'NVIDIA GeForce RTX 4070 Ti SUPER / OpenGL 4.6 / driver 572.70',
    'retained': 'Flat resident command selection, compaction and indirect count in a bounded heavy rotating Hi-Z case',
    'rolledBackOrExcluded': ['New 64-command coarse-node prefilter', 'New queue traversal',
                            'No-Hi-Z selection', 'Stationary selection', '512/1024 tile selection',
                            '1536/2048 simplified selection', 'Selection during translation',
                            'Selection of seam input', 'Water, Iris and spyglass paths'],
    'guards': {'terrainTiles': 3072, 'residentOpaqueQuads': 1572864, 'visibleOpaqueQuads': 524288,
               'seamTiles': 0, 'requiresCurrentFrameHiz': True, 'requiresRotationAtFixedPosition': True,
               'explicitDisableProperty': 'vss.disableGpuPredictionSelection'},
    'platforms': []
}
table = []
for root in repositories:
    platform = 'neoforge' if root == neo else 'forge'
    build = root / 'build-gpu-selection-retained-20261014'
    result = root / 'tools/prediction/gpu-selection-retained/results' / platform
    result.mkdir(parents=True, exist_ok=True)
    for name in ['retained-gpu-selection.csv', 'retained-gpu-selection-throughput.csv']:
        shutil.copy2(build/'results'/name, result/name)
    suites = {}
    for label in ['loading-benefit', 'loading-benefit-gpu', 'gpu-selection-benefit', 'gpu-selection-fallback']:
        counts = {k: 0 for k in ['tests', 'failures', 'errors', 'skipped']}
        details = []
        for file in sorted((build/'test-results'/label).glob('TEST-*.xml')):
            data = ET.parse(file).getroot().attrib
            for key in counts: counts[key] += int(data.get(key, 0))
            details.append({k:data[k] for k in ['name','tests','timestamp']})
        assert counts['tests'] > 0 and sum(counts[k] for k in ['failures','errors','skipped']) == 0, (platform,label,counts)
        suites[label] = dict(counts, suites=details)
    rows = list(csv.DictReader((result/'retained-gpu-selection-throughput.csv').open()))
    summary = []
    for count in sorted({int(row['tiles']) for row in rows}):
        for scenario in range(3):
            pair = [statistics.median([int(row['elapsed_ns'])/int(row['frames'])/1e6
                    for row in rows if int(row['tiles'])==count and int(row['case'])==scenario and int(row['mode'])==mode])
                    for mode in range(2)]
            selected = {int(row['selected_frames']) for row in rows
                        if int(row['tiles'])==count and int(row['case'])==scenario and int(row['mode'])==1}
            summary.append({'tiles':count, 'case':scenario, 'baselineMs':pair[0], 'candidateMs':pair[1],
                            'savingPercent':(1-pair[1]/pair[0])*100, 'selectedFramesPerBatch':sorted(selected)})
            if scenario == 2 and count >= 3072:
                assert min(selected) > 0 and pair[1] < pair[0] * .95, (platform, count, pair, selected)
                table.append(f'| {platform} | {count} | {pair[0]:.4f} | {pair[1]:.4f} | {(1-pair[1]/pair[0])*100:.1f}% |')
            else:
                assert selected == {0}, (platform,count,scenario,selected)
    jar = next((root/'lib/gpu-selection-retained-20261014').glob('vss-*.jar'))
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        assert not any('TraversalCandidate' in name or 'RetainedBaseline' in name
                       or 'RetainedComparison' in name or 'RetainedGpuSelectionGpuTest' in name
                       or 'RetainedSelectionFallbackGpuTest' in name for name in names)
        for name in ['PredictionGpuCommandSelector','PredictionGpuSelectionPolicy','PredictionGpuSelectionInput']:
            assert f'dev/xantha/vss/client/prediction/{name}.class' in names
        majors = sorted({struct.unpack('>H', archive.read(name)[6:8])[0]
                         for name in names if name.startswith('dev/xantha/') and name.endswith('.class')})
        assert majors == ([65] if platform=='neoforge' else [61])
        assert any(name.startswith('META-INF/vss-natives/') for name in names)
        descriptor = 'META-INF/neoforge.mods.toml' if platform=='neoforge' else 'META-INF/mods.toml'
        assert '0.3.5-' in archive.read(descriptor).decode('utf-8')
        renderer_class = archive.read('dev/xantha/vss/client/prediction/PredictionRenderer.class')
        assert b'prepareGpuSelection' in renderer_class and b'visibleQuads' in renderer_class
    (jar.parent/'SHA256SUMS.txt').write_text(f'{digest}  {jar.name}\n',encoding='utf-8')
    source_hashes = {name:hashlib.sha256((root/java/name).read_bytes()).hexdigest()
                     for name in shared+['PredictionRenderer.java']}
    validation['platforms'].append({'platform':platform,'repository':str(root),'jar':str(jar),
        'jarBytes':jar.stat().st_size,'jarSha256':digest,'javaClassMajor':majors[0],
        'tests':suites,'throughput':summary,'sourceSha256':source_hashes,
        'imagePairs':72,'depthPairs':72,'counterAndGlStateChecks':True})

document = '''**GPU 命令选择收益保留与回撤（2026-10-14）**

按用户要求，只保留在完整提交对照中有净收益的 GPU 平铺选择。两个平台已经接入正式普通不透明 MDI 路径，维持 0.3.5。新粗节点筛选、队列式层级遍历和无 Hi-Z 选择只保留为实验源码，没有进入正式 JAR。

**启用范围**

这是一个保守的条件路径：至少 3072 个地形瓦片、驻留候选不透明面至少 1572864 个、可见提交面至少 524288 个、没有接缝输入；相机位置不变而朝向每帧转动超过约 0.25°；当前帧 Hi-Z 已成功启用；所有候选都具备有效共享 arena slice；GPU 支持 OpenGL 4.5 与 indirect count。望远镜、Iris、水面不启用。

停止转动、平移、尺寸改变、工作量减小、资源撤销或任一条件不满足时使用原路径。当前可见集合的面数从已经准备的驻留范围统计，不持续沿用转向前的计数。常驻输入只在符合转向条件后更新，相机走动和小集合不进行额外全量整理。过时面朝向和 horizon 在建立候选时重新核对。停止使用后清理 CPU 驻留 pass 和输入，命令缓冲仍按原有版本、近期期限和预算管理。

GPU 检查常驻命令的视锥、方向和当前深度，压缩有效命令并写出绘制数量，indirect count 消费结果，不把可见性数量读回 CPU。CPU 仍管理 Scene、归属、显示 LOD、覆盖撤销、接缝及数据调度。这不是完整的 Voxy GPU 多级 LOD 树。

**完整提交收益**

设备为 RTX 4070 Ti SUPER / OpenGL 4.6 / NVIDIA 572.70，512×512 隐藏窗口。基准使用本轮之前的 IndirectBatch；候选使用正式 IndirectBatch、选择策略与输入维护。两条路径均执行 CPU 可见选择、生产 PreparedPass、OpaqueBatches、arena、编码、terrain program 和当前帧 Hi-Z，候选在满足门槛后以驻留集合准备命令。带显示 LOD 的集合逐帧传入真实投影、朝向和像素尺度。

下表是 21 个交错批次、每批 32 帧的中位数，以批次总墙钟时间除以 32；预热 8 个批次，只在批次边界等待 GPU。包含准备、提交和 GPU 完成等待。它不是 Minecraft 帧时间或 FPS。

| 平台 | 地形瓦片数 | 原路径 ms/次 | 保留路径 ms/次 | 耗时减少 |
| --- | ---: | ---: | ---: | ---: |
''' + '\n'.join(table) + '''

3072／4096 集合使用保留细节的 MODEL_UV 合成地形面，避免把可被现有显示 LOD 大幅合并的平面误当作重几何场景。这不是实际草花树贴图测量。最终测试明确断言收益场景确实启用了生产选择；其他场景没有启动 GPU 选择。每个平台对 24 个场景、每场景 3 帧进行颜色和深度逐元素对照，共 72 对颜色与 72 对深度，全数一致。

单帧 GPU 时间在保留场景中通常增加，净收益来自减少 CPU 反复整理和编码；不能将 CPU 与 GPU 单项直接相加推算帧率。不同测试轮次中净收益幅度有波动，本轮以前的扩大范围实验也已经保存。这里没有测当前 Minecraft 客户端 FPS，不能承诺游戏帧率提升上述百分比。

**回撤依据**

第一轮 512 瓦片接入几乎无净收益，1024 瓦片在正式接入对照中也没有稳定提升；两档退出。1536／2048 的不变显示档位集合最初有收益，但逐帧传入实际视角后，1536 退步约 4.2%，2048 仅约 3.8% 的小幅改善；这两档以及已经充分简化的可见集合退出。接缝输入、静止、无 Hi-Z、平移均保留原路径。之前实验中的 64 命令新粗节点预筛与 GPU 队列遍历没有稳定胜过平铺选择，继续隔离在 gpu-traversal-experiments。

没有修改此前已经保留的生成预算、主线程减负、归属切换、显示 LOD、植被档位、网格合并和压缩功能。Forge 事件与映射接口保持对应平台的实现；六个共用 GPU 选择相关生产文件逐字节一致。

**验证与产物**

NeoForge：233 项 CPU 回归、12 项既有 GPU 回归、1 项新选择对照及 1 项 GPU 异常回退检查。Forge：231 项 CPU 回归、12 项既有 GPU 回归、1 项新选择对照及 1 项 GPU 异常回退检查。均没有失败、错误或跳过。新选择检查了 65 条命令的尾工作组、计数每帧清零、输出唯一性、全遮挡后的空计数、SSBO 范围与偏移、纹理／sampler、program、间接 draw／dispatch／parameter 绑定及 image 绑定保持。额外检查先使策略允许选择，再用不受支持的 clip depth 模式让本帧 Hi-Z 初始化拒绝，确认在提交任何驻留 draw 前取消 GPU 选择；Renderer 同时切回原可见输入。既有回归包含覆盖撤销、morph、上传交接、接缝驻留、水面和渲染目标。

JAR 在各仓库 lib/gpu-selection-retained-20261014/，附 SHA256SUMS.txt；NeoForge 字节码为 Java 21，Forge 为 Java 17，Forge reobfJar 已完成。校验确认包含正式选择类与原生后端资源，不包含实验／基准类。完整测试计数、每场景数据、源码和 JAR SHA256 在同名 validation.json。

没有推送 GitHub、替换客户端模组、启动客户端或结束现有游戏进程。用户自行替换 JAR。可用 JVM 参数 -Dvss.disableGpuPredictionSelection=true 对照原提交路径。

复现：在对应仓库使用 JDK 21／JDK 17，运行 gradlew.bat --offline --no-daemon -I tools/prediction/gpu-selection-retained-tests.gradle turnMainRegressionTest turnMainGpuTest gpuSelectionBenefitTest jar。Forge 根据本机 Java 设置提供 org.gradle.java.home。实验基准不进入主源码或正式 JAR。
'''

for root in repositories:
    (root/'docs/prediction-gpu-selection-retained-20261014.md').write_text(document,encoding='utf-8')
    (root/'docs/prediction-gpu-selection-retained-20261014.validation.json').write_text(
        json.dumps(validation,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('\n'.join(table))
for data in validation['platforms']:
    print(data['platform'],data['jarSha256'],data['jar'])
