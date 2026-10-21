"""Summarize synchronized, read-only runtime and whole-GPU observations."""
import csv
import datetime as dt
import json
import statistics
from pathlib import Path

BASE = Path(__file__).resolve().parent


def stats(rows, key):
    values = [row[key] for row in rows]
    return ({"n": len(values), "min": min(values), "median": statistics.median(values),
             "mean": statistics.mean(values), "max": max(values)} if values else None)


def analyze(phase):
    directory = BASE / phase
    runtime = [json.loads(line) for line in (directory / "runtime.jsonl").read_text().splitlines()]
    intervals = []
    for before, after in zip(runtime, runtime[1:]):
        seconds = (after["nanoTime"] - before["nanoTime"]) / 1e9
        frames = after["frameStarts"] - before["frameStarts"]
        intervals.append({
            "start": before["time"], "end": after["time"], "seconds": seconds,
            "active": all(row.get("active") and not row["paused"] and row.get("screen") is None
                          for row in (before, after)),
            "frames": frames, "fps": frames / seconds,
            "renderCpuNanos": after["renderCpuNanos"] - before["renderCpuNanos"],
            "processCpuNanos": after["processCpuNanos"] - before["processCpuNanos"],
            "cpuOneCorePercent": (after["renderCpuNanos"] - before["renderCpuNanos"]) / seconds / 1e7,
            "processLogicalCores": (after["processCpuNanos"] - before["processCpuNanos"]) / seconds / 1e9,
            **{key: after[source] - before[source] for key, source in
               [("prepared", "preparedPlans"), ("reused", "reusedPlans"),
                ("uploads", "meshUploads"), ("drawCalls", "drawCalls"), ("quads", "submittedQuads")]},
            "drawCallsPerFrame": (after["drawCalls"] - before["drawCalls"]) / max(1, frames),
            "quadsPerFrame": (after["submittedQuads"] - before["submittedQuads"]) / max(1, frames),
        })
    gpu = []
    for row in csv.reader((directory / "gpu.csv").read_text(encoding="utf-8-sig").splitlines()):
        row = [item.strip() for item in row]
        time = dt.datetime.strptime(row[0], "%Y/%m/%d %H:%M:%S.%f").replace(
            tzinfo=dt.timezone(dt.timedelta(hours=8))).astimezone(dt.timezone.utc)
        gpu.append({"time": time.isoformat(), "pstate": row[1], "util": int(row[2]),
                    "memUtil": int(row[3]), "vram": int(row[4]), "watts": float(row[6]),
                    "coreMHz": int(row[8]), "tempC": int(row[10])})
    active = [row for row in intervals if row["active"]]
    selected = [row for row in gpu if any(
        dt.datetime.fromisoformat(interval["start"]) <= dt.datetime.fromisoformat(row["time"])
        < dt.datetime.fromisoformat(interval["end"]) for interval in active)]
    seconds = sum(row["seconds"] for row in active)
    frames = sum(row["frames"] for row in active)
    result = {
        "phase": phase, "rows": len(runtime), "pausedRows": sum(row["paused"] for row in runtime),
        "unfocusedRows": sum(not row.get("active") for row in runtime), "intervals": intervals,
        "activeSeconds": seconds,
        "gpuAll": {key: stats(gpu, key) for key in ["util", "vram", "watts", "coreMHz", "tempC"]},
        "gpuActive": {key: stats(selected, key) for key in ["util", "vram", "watts", "coreMHz", "tempC"]},
        "activeStats": {key: stats(active, key) for key in
                        ["fps", "cpuOneCorePercent", "processLogicalCores", "drawCallsPerFrame", "quadsPerFrame"]},
        "activeTotals": {key: sum(row[key] for row in active) for key in
                         ["frames", "renderCpuNanos", "processCpuNanos", "prepared", "reused", "uploads", "drawCalls", "quads"]},
    }
    if seconds and frames:
        result["activeWeighted"] = {
            "fps": frames / seconds,
            "renderCpuOneCorePercent": result["activeTotals"]["renderCpuNanos"] / seconds / 1e7,
            "processLogicalCores": result["activeTotals"]["processCpuNanos"] / seconds / 1e9,
            "drawCallsPerFrame": result["activeTotals"]["drawCalls"] / frames,
            "quadsPerFrame": result["activeTotals"]["quads"] / frames,
        }
    (directory / "runtime-analysis.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    if active:
        (directory / "active-windows.tsv").write_text("".join(
            row["start"] + "\t" + row["end"] + "\n" for row in active), encoding="utf-8")
    print(json.dumps({"phase": phase, "seconds": seconds, "weighted": result.get("activeWeighted"),
                      "totals": result["activeTotals"], "gpu": result["gpuActive"]}, ensure_ascii=False))


if __name__ == "__main__":
    for phase in ("results", "active"):
        analyze(phase)
