#!/usr/bin/env python3
"""PRF-013 comparator: fail when a tracked perf result is more than 15% worse than its accepted baseline.

A result is matched to a baseline by scenario, hardware size label and metric. Results are never compared
across hardware sizes. A result with no baseline for its scenario and size is reported as "no baseline":
that is not a failure, and it is not a pass of the gate either. Worse by exactly 15% passes; worse by
more than 15% fails (ADR 0010). Direction: lowerIsBetter true means a higher value is worse (latency,
CPU, memory); false means a lower value is worse (throughput, capacity).

Result file (JSON): {"results": [{"scenario", "hardware", "metric", "value", "lowerIsBetter"}]}, or a
load-soak report (test-infra/load-soak), recognised by its "scenario" and "size" keys.
Baseline sources: the result.baseline blocks of test-infra/perf/perf-requirements.json, plus any --baseline
file in the same {"results": [...]} shape (an accepted baseline entry uses "value").

Usage: perf_regression.py --result FILE [FILE ...] [--baseline FILE ...] [--limit 15]
Exit 0 when nothing regressed (no-baseline rows included), 1 on a regression, 2 on unreadable input.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REQ_PATH = ROOT / "test-infra/perf/perf-requirements.json"
LIMIT_PERCENT = 15.0

# Tracked metric inside a load-soak report: scenario -> [(dotted path, metric name, lowerIsBetter)].
LOAD_SOAK_METRICS = {
    "PRF-003": [("changesPerSecond", "changesPerSecond", False)],
    "PRF-008": [("steady.changesPerSecond", "steadyChangesPerSecond", False)],
}


def worse_percent(baseline: float, measured: float, lower_is_better: bool) -> float:
    """Positive when measured is worse than baseline."""
    if baseline == 0:
        raise ValueError("baseline must be non-zero")
    change = (measured - baseline) / abs(baseline) * 100.0
    return change if lower_is_better else -change


def regression_fails(baseline: float, measured: float, lower_is_better: bool, limit: float = LIMIT_PERCENT) -> bool:
    return round(worse_percent(baseline, measured, lower_is_better), 9) > limit


def _num(v) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def _dig(obj, path: str):
    for part in path.split("."):
        if not isinstance(obj, dict) or part not in obj:
            return None
        obj = obj[part]
    return obj


def _entry(d: dict, where: str) -> dict:
    for key in ("scenario", "hardware", "metric"):
        if not isinstance(d.get(key), str) or not d[key]:
            raise ValueError(f"{where}: '{key}' must be a non-empty string")
    value = d.get("value", d.get("baseline"))
    if not _num(value):
        raise ValueError(f"{where}: 'value' must be a number")
    if not isinstance(d.get("lowerIsBetter"), bool):
        raise ValueError(f"{where}: 'lowerIsBetter' must be true or false")
    return {k: d[k] for k in ("scenario", "hardware", "metric", "lowerIsBetter")} | {"value": float(value)}


def parse_results(doc, where: str) -> list[dict]:
    """Entries of a result or baseline document, or of a load-soak report."""
    if isinstance(doc, dict) and "results" in doc:
        items = doc["results"]
        if not isinstance(items, list):
            raise ValueError(f"{where}: 'results' must be a list")
        return [_entry(x, f"{where} results[{i}]") for i, x in enumerate(items)]
    if isinstance(doc, dict) and "scenario" in doc and "size" in doc:
        specs = LOAD_SOAK_METRICS.get(doc["scenario"], [])
        out = []
        for path, metric, lower in specs:
            value = _dig(doc, path)
            if _num(value):
                out.append({"scenario": doc["scenario"], "hardware": str(doc["size"]), "metric": metric,
                            "lowerIsBetter": lower, "value": float(value)})
        return out
    raise ValueError(f"{where}: not a result file (no 'results' list, not a load-soak report)")


def requirements_baselines(path: Path = REQ_PATH) -> list[dict]:
    """Accepted baselines committed in perf-requirements.json (result.baseline and result.runnerBaselines)."""
    doc = json.loads(path.read_text(encoding="utf-8"))
    out = []
    for sc in doc.get("scenarios", []):
        res = sc.get("result")
        if not isinstance(res, dict) or not _num(res.get("baseline")):
            continue
        base = {"scenario": sc["id"], "metric": res["metric"], "lowerIsBetter": res["lowerIsBetter"]}
        out.append(base | {"hardware": res["hardware"], "value": float(res["baseline"])})
        for extra in res.get("runnerBaselines") or []:
            if _num(extra.get("baseline")) and extra.get("hardware"):
                out.append(base | {"hardware": extra["hardware"], "value": float(extra["baseline"])})
    return out


def compare(results: list[dict], baselines: list[dict], limit: float = LIMIT_PERCENT) -> list[dict]:
    index = {(b["scenario"], b["hardware"], b["metric"]): b for b in baselines}
    rows = []
    for r in results:
        b = index.get((r["scenario"], r["hardware"], r["metric"]))
        row = {"scenario": r["scenario"], "hardware": r["hardware"], "metric": r["metric"],
               "value": r["value"], "baseline": None, "worse": None, "status": "no baseline"}
        if b is not None:
            if b["value"] == 0:
                raise ValueError(f"baseline for {r['scenario']} {r['hardware']} {r['metric']} is zero")
            worse = worse_percent(b["value"], r["value"], b["lowerIsBetter"])
            row.update(baseline=b["value"], worse=worse, status="FAIL" if round(worse, 9) > limit else "ok")
        rows.append(row)
    return rows


def render(rows: list[dict], limit: float = LIMIT_PERCENT) -> str:
    head = ("scenario", "size", "metric", "result", "baseline", "worse %", "status")
    body = [(r["scenario"], r["hardware"], r["metric"], f"{r['value']:g}",
             "-" if r["baseline"] is None else f"{r['baseline']:g}",
             "-" if r["worse"] is None else f"{r['worse']:+.2f}", r["status"]) for r in rows]
    widths = [max(len(str(x[i])) for x in [head, *body]) for i in range(len(head))]
    lines = ["  ".join(str(c).ljust(w) for c, w in zip(row, widths)).rstrip() for row in [head, *body]]
    failed = sum(r["status"] == "FAIL" for r in rows)
    nobase = sum(r["status"] == "no baseline" for r in rows)
    lines.append(f"PRF-013: {failed} regression(s) over {limit:g}% worse, "
                 f"{nobase} with no baseline (not evaluated), {len(rows) - failed - nobase} within the limit")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--result", nargs="+", required=True, type=Path)
    ap.add_argument("--baseline", nargs="*", default=[], type=Path)
    ap.add_argument("--limit", type=float, default=LIMIT_PERCENT)
    args = ap.parse_args(argv)
    try:
        baselines = requirements_baselines()
        for p in args.baseline:
            baselines += parse_results(json.loads(p.read_text(encoding="utf-8")), str(p))
        results = []
        for p in args.result:
            results += parse_results(json.loads(p.read_text(encoding="utf-8")), str(p))
        rows = compare(results, baselines, args.limit)
    except (OSError, ValueError, KeyError) as e:
        print(f"perf_regression: {e}", file=sys.stderr)
        return 2
    print(render(rows, args.limit))
    return 1 if any(r["status"] == "FAIL" for r in rows) else 0


if __name__ == "__main__":
    sys.exit(main())
