#!/usr/bin/env python3
"""Sequential MLX workers compare surviving logits with fresh caches and fixed tolerances."""
import argparse
import gc
import json
import math
import resource
import subprocess
import sys
import time
import statistics
from pathlib import Path


def compare(left, right, atol=1e-2, rtol=1e-3):
    if not math.isfinite(atol) or not math.isfinite(rtol) or atol < 0 or rtol < 0:
        raise ValueError("tolerances must be finite and nonnegative")
    if len(left) != len(right) or not left:
        raise ValueError("logit vectors must have the same nonzero length")
    errors = [abs(a - b) for a, b in zip(left, right)]
    exceeded = sum(not math.isfinite(a) or not math.isfinite(b) or error > atol + rtol * abs(a)
                   for a, b, error in zip(left, right, errors))
    ordered = sorted(range(len(left)), key=lambda index: left[index], reverse=True)[:2]
    finite_vectors = all(math.isfinite(value) for value in left + right)
    margin = left[ordered[0]] - left[ordered[1]] if len(ordered) > 1 and finite_vectors else None
    finite_errors = [error for error in errors if math.isfinite(error)]
    violations = [{"index": index, "baseline": a, "pruned": b, "error": error, "allowed": atol + rtol * abs(a)}
                  for index, (a, b, error) in enumerate(zip(left, right, errors)) if math.isfinite(a) and math.isfinite(b) and error > atol + rtol * abs(a)][:8]
    return {"passed": exceeded == 0, "maxError": max(finite_errors) if finite_errors else None, "meanError": sum(finite_errors) / len(finite_errors) if finite_errors else None, "exceeded": exceeded,
            "firstViolations": violations,
            "atol": atol, "rtol": rtol, "baselineTopMargin": margin, "baselineArgmax": ordered[0] if finite_vectors else None,
            "prunedArgmax": max(range(len(right)), key=lambda index: right[index]) if finite_vectors else None}


def worker(args):
    import numpy as np
    import mlx.core as mx
    from mlx_lm import load
    from mlx_lm.models.cache import make_prompt_cache

    data = json.loads(args.inputs.read_text())
    model, tokenizer = load(str(args.model), lazy=False)
    mx.reset_peak_memory()
    vectors = []
    repeated = []
    indices = mx.array(data["mapping"], dtype=mx.int32) if args.phase == "baseline" else None
    fixed_benchmarks = []
    for case in data["cases"]:
        ids = case["originalIds"] if args.phase == "baseline" else case["compactIds"]
        def run():
            logits = model(mx.array([ids], dtype=mx.int32), cache=make_prompt_cache(model))[0, -1]
            if indices is not None:
                logits = mx.take(logits, indices)
            mx.eval(logits)
            return np.array(logits.astype(mx.float32))
        vectors.append(run())
        if args.phase == "baseline":
            repeated.append(run())
        timings = []
        for trial in range(6):
            cache = make_prompt_cache(model)
            start = time.perf_counter()
            mx.eval(model(mx.array([ids], dtype=mx.int32), cache=cache))
            prefill = time.perf_counter() - start
            start = time.perf_counter()
            for _ in range(16):
                mx.eval(model(mx.array([[ids[-1]]], dtype=mx.int32), cache=cache))
            decode = time.perf_counter() - start
            if trial:
                timings.append({"prefillSeconds": prefill, "prefillTokensPerSecond": len(ids)/prefill, "decodeTokensPerSecond": 16/decode})
        fixed_benchmarks.append({"text": case["text"], "promptTokens": len(ids), "teacherForcedDecodeTokens": 16, "warmups": 1, "trials": 5,
                                 "metrics": {field: {"median": statistics.median(row[field] for row in timings), "min": min(row[field] for row in timings), "max": max(row[field] for row in timings)}
                                             for field in ["prefillSeconds", "prefillTokensPerSecond", "decodeTokensPerSecond"]}})
    np.savez(args.output, logits=np.stack(vectors), repeated=np.stack(repeated) if repeated else np.empty((0, len(data["mapping"]))))
    rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    args.memory.write_text(json.dumps({"peakMlxAllocatedBytes": mx.get_peak_memory(),
                                       "processPeakRssBytes": rss if sys.platform == "darwin" else rss * 1024,
                                       "sameIdTeacherForcedBenchmarks": fixed_benchmarks}) + "\n")
    del model, tokenizer
    gc.collect()
    mx.clear_cache()


def run(manifest_path, output, atol=1e-2, rtol=1e-3):
    import numpy as np
    from tokenizers import Tokenizer
    manifest = json.loads(manifest_path.read_text())
    plan = json.loads(Path(manifest["plan"]).read_text())
    mapping = plan["domain"]["compactToOriginal"]
    inverse = {old: compact for compact, old in enumerate(mapping)}
    old = Tokenizer.from_file(str(Path(manifest["source"]) / "tokenizer.json"))
    new = Tokenizer.from_file(str(Path(manifest["modelOutput"]) / "tokenizer.json"))
    cases, skipped = [], []
    for text in ["a", "def f(x):\n    return x + 1", "日本語で説明します。", "<|im_start|>user\nhello<|im_end|>"]:
        original = old.encode(text, add_special_tokens=False).ids
        compact = new.encode(text, add_special_tokens=False).ids
        if not all(value in inverse for value in original) or compact != [inverse[value] for value in original]:
            skipped.append({"text": text, "reason": "tokenization changed or source IDs were deleted"})
            continue
        cases.append({"text": text, "originalIds": original, "compactIds": compact})
    if not cases:
        raise ValueError("no surviving-ID logit cases")
    inputs = output / "logit-inputs.json"
    with inputs.open("x", encoding="utf-8") as stream:
        json.dump({"mapping": mapping, "cases": cases}, stream, ensure_ascii=False)
    arrays, memories = {}, {}
    for phase, model_path in [("baseline", manifest["source"]), ("pruned", manifest["modelOutput"])]:
        print(f"Computing {phase} surviving logits", flush=True)
        result = output / f"{phase}-logits.npz"
        memory = output / f"{phase}-memory.json"
        with (output / f"{phase}-logits.log").open("x") as log:
            subprocess.run([sys.executable, str(Path(__file__).resolve()), "worker", "--phase", phase, "--model", model_path,
                            "--inputs", str(inputs), "--output", str(result), "--memory", str(memory)], stdout=log, stderr=subprocess.STDOUT,
                           check=True, timeout=900)
        arrays[phase] = np.load(result)
        memories[phase] = json.loads(memory.read_text())
    records = []
    for index, case in enumerate(cases):
        comparison = compare(arrays["baseline"]["logits"][index].tolist(), arrays["pruned"]["logits"][index].tolist(), atol, rtol)
        repeat = compare(arrays["baseline"]["logits"][index].tolist(), arrays["baseline"]["repeated"][index].tolist(), atol, rtol)
        records.append({**case, "comparison": comparison, "baselineRepeat": repeat})
    with (output / "logits.jsonl").open("x", encoding="utf-8") as stream:
        for record in records:
            stream.write(json.dumps(record, ensure_ascii=False, allow_nan=False) + "\n")
    for arrays_file in arrays.values():
        arrays_file.close()
    return {"passed": all(row["comparison"]["passed"] and row["baselineRepeat"]["passed"] for row in records), "cases": len(records),
            "skipped": skipped, "atol": atol, "rtol": rtol, "memory": memories}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    child = sub.add_parser("worker")
    for name in ["model", "inputs", "output", "memory"]:
        child.add_argument("--" + name, required=True, type=Path)
    child.add_argument("--phase", required=True, choices=["baseline", "pruned"])
    parent = sub.add_parser("compare")
    parent.add_argument("--manifest", type=Path, required=True)
    parent.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "worker":
        worker(args)
    else:
        args.output.mkdir(parents=True, exist_ok=False)
        result = run(args.manifest, args.output)
        with (args.output / "logits-summary.json").open("x") as stream:
            json.dump(result, stream)
        raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
