"""Audit the delivered fixed-record scene artifacts and their scoped regression evidence."""
from pathlib import Path
import csv
import hashlib
import json
import statistics
import struct
import xml.etree.ElementTree as ET
import zipfile

REPO = Path(__file__).resolve().parents[2]
BUILD = "build-persistent-scene-20261015"
DELIVERY = "lib/persistent-scene-20261015"
PRODUCTION = [
    "PredictionGpuScene", "PredictionSubmissionEncoding", "PredictionSubmissionChanges",
    "PredictionIndirectBatch", "PredictionRenderer",
]
COMMON = [
    *(f"src/main/java/dev/xantha/vss/client/prediction/{name}.java" for name in PRODUCTION[:-1]),
    *(f"src/test/java/dev/xantha/vss/client/prediction/{name}.java" for name in (
        "PredictionGpuSceneTest", "PredictionSubmissionChangesTest", "PredictionGpuSceneGpuTest")),
    "tools/prediction/persistent-scene-tests.gradle",
]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def test_results(directory):
    suites = [ET.parse(file).getroot() for file in sorted(directory.glob("TEST-*.xml"))]
    assert suites, directory
    counts = {key: sum(int(suite.get(key, 0)) for suite in suites)
              for key in ("tests", "failures", "errors", "skipped")}
    assert counts["tests"] > 0 and all(counts[key] == 0 for key in ("failures", "errors", "skipped")), counts
    return dict(counts, suites=[{"name": suite.get("name"), "tests": int(suite.get("tests")),
                                "timestamp": suite.get("timestamp")} for suite in suites])


def throughput(path):
    with path.open(newline="", encoding="utf-8") as stream:
        rows = list(csv.DictReader(stream))
    results = []
    for case, label in enumerate(("stationary_camera_rotation", "rotation_one_mesh_metadata_update_per_frame")):
        medians = []
        for mode in (0, 1):
            subset = [row for row in rows if int(row["case"]) == case and int(row["mode"]) == mode]
            assert len(subset) == 21
            assert {int(row["round"]) for row in subset} == set(range(21))
            assert all(int(row["frames"]) == 32 and int(row["selected_frames"]) >= 31 for row in subset)
            medians.append(statistics.median(int(row["elapsed_ns"]) / int(row["frames"]) / 1e6
                                             for row in subset))
            if mode == 1:
                assert all(int(row["encoded_items"]) == (32 if case else 0) for row in subset)
                assert all(int(row["uploaded_bytes"]) == (3712 if case else 0) for row in subset)
        assert medians[1] < medians[0], (label, medians)
        results.append({"case": label, "orderedMsPerPass": medians[0], "fixedSceneMsPerPass": medians[1],
                        "reductionPercent": 100 * (1 - medians[1] / medians[0])})
    assert len(rows) == 84
    return {"csv": str(path), "sha256": sha(path.read_bytes()), "roundsPerMode": 21,
            "framesPerRound": 32, "warmupRounds": 12, "gpuCompletionIncluded": True,
            "gameFpsBenchmark": False, "fullScenePrepareIncluded": False, "cases": results}


def verify(repo, version, major):
    jar = repo / DELIVERY / f"vss-{version}.jar"
    log = (repo / "build-persistent-scene-validation.log").read_text(errors="replace")
    assert "BUILD SUCCESSFUL" in log and "FAILED" not in log
    cpu = test_results(repo / BUILD / "test-results/persistent-scene-cpu")
    gpu = test_results(repo / BUILD / "test-results/persistent-scene-gpu")
    sources = {path: sha((repo / path).read_bytes()) for path in COMMON}
    renderer = "src/main/java/dev/xantha/vss/client/prediction/PredictionRenderer.java"
    sources[renderer] = sha((repo / renderer).read_bytes())
    native = {}
    with zipfile.ZipFile(jar) as package:
        assert package.testzip() is None
        names = package.namelist()
        assert len(names) == len(set(names)), "duplicate ZIP entries"
        assert not any(name.endswith("Test.class") or "ExperimentTest" in name for name in names)
        assert f"Implementation-Version: {version}" in package.read("META-INF/MANIFEST.MF").decode()
        assert "dev/xantha/vss/client/prediction/PredictionSubmissionCoverage.class" not in names
        for name in (*PRODUCTION, "PredictionAuthoritativeCoverageView"):
            entry = f"dev/xantha/vss/client/prediction/{name}.class"
            data = package.read(entry)
            assert struct.unpack(">H", data[6:8])[0] == major, entry
            if major == 65:
                assert data == (repo / BUILD / "classes/java/main" / entry).read_bytes(), entry
        for entry in names:
            if entry.startswith("META-INF/vss-natives/") and not entry.endswith("/"):
                data = package.read(entry)
                assert data == (repo / "src/main/resources" / entry).read_bytes()
                native[entry] = sha(data)
        assert len(native) == 5
        mixins = json.loads(package.read("vss.compat.mixins.json"))
        assert "client.LevelRendererPredictionMaskMixin" in mixins["client"]
        if major == 61:
            assert "> Task :reobfJar" in log
            refmap = json.loads(package.read("vss.refmap.json"))
            hooks = refmap["mappings"]["dev/xantha/vss/mixin/client/LevelRendererPredictionMaskMixin"]
            assert hooks["setSectionDirty(IIIZ)V"].endswith("m_109501_(IIIZ)V")
            assert hooks["allChanged"].endswith("m_109818_()V")
            assert package.read("META-INF/jarjar/lz4-java-1.8.0.jar") == (repo / "src/main/jarjar/lz4-java-1.8.0.jar").read_bytes()
    checksum = sha(jar.read_bytes())
    (jar.parent / "SHA256SUMS.txt").write_text(f"{checksum}  {jar.name}\n", encoding="ascii")
    return {"repository": str(repo), "version": version, "jar": str(jar), "jarBytes": jar.stat().st_size,
            "jarSha256": checksum, "javaClassMajor": major, "cpu": cpu, "gpu": gpu,
            "sourcesSha256": sources, "nativeSha256": native,
            "throughput": throughput(repo / BUILD / "results/persistent-scene-throughput.csv")}


def main():
    neo_repo = REPO if "neoforge" in REPO.name else REPO.parent / "voxyserverside-neoforge-1.21.1"
    forge_repo = REPO if REPO.name == "voxyserverside" else REPO.parent / "voxyserverside"
    neo = verify(neo_repo, "0.3.5-neoforge-1.21.1", 65)
    forge = verify(forge_repo, "0.3.5-forge-1.20.1", 61)
    for path in COMMON:
        assert neo["sourcesSha256"][path] == forge["sourcesSha256"][path], path
    evidence = {"date": "2026-10-15", "liveClientProfiledThisChange": False, "clientLaunched": False,
                "installedInstanceModified": False, "githubPushed": False, "commonSourcesIdentical": COMMON,
                "productionScope": "qualified resident opaque GPU selection; whole ranges; existing policy gates unchanged",
                "platforms": [neo, forge]}
    for platform in (neo, forge):
        root = Path(platform["repository"])
        (root / DELIVERY / "validation.json").write_text(
            json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({key: platform[key] for key in ("version", "jarBytes", "jarSha256", "throughput")},
                         ensure_ascii=False))


if __name__ == "__main__":
    main()
