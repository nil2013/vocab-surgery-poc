#!/usr/bin/env python3
"""Compare fixed test-data cases on one isolated oMLX server; keep the submodule read-only."""
import argparse
import ast
import hashlib
import importlib.metadata
import importlib.util
import json
import os
import re
import subprocess
import sys
import tempfile
import unicodedata
from pathlib import Path

from code_checks import restricted
from evaluate import Omlx, RssSampler, quantiles, write_json


def load_data(root):
    suite = json.loads((root / "cases/synthetic/suite.json").read_text())
    replay = [json.loads(path.read_text()) for path in sorted(root.glob("cases/replay/*.json"))]
    if not suite["tests"] or not replay:
        raise ValueError("synthetic and replay cases are required")
    for rows in [suite["tests"], replay]:
        if len({row["id"] for row in rows}) != len(rows):
            raise ValueError("duplicate case IDs")
    # Read fixed upstream harness strings without executing its argparse / runner module.
    tree = ast.parse((root / "tools/run_synthetic.py").read_text())
    checks = next(ast.literal_eval(node.value) for node in tree.body if isinstance(node, ast.Assign)
                  and any(isinstance(target, ast.Name) and target.id == "CODE_CHECKS" for target in node.targets))
    spec = importlib.util.spec_from_file_location("testdata_common", root / "tools/common.py")
    common = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(common)
    return suite, replay, checks, common.script_metrics


def stream_case(case):
    return {"kind": "chat", "messages": case["messages"], "maxTokens": case.get("max_tokens", 1000),
            **({"tools": case["tools"]} if case.get("tools") else {})}


def template_messages(messages):
    # oMLX parses OpenAI argument strings before applying the Qwen template.
    # Normalize only this local rendering copy; the API request stays unchanged.
    result = json.loads(json.dumps(messages))
    for message in result:
        for call in message.get("tool_calls", []):
            function = call.get("function", call)
            arguments = function.get("arguments", {})
            if isinstance(arguments, str):
                arguments = json.loads(arguments)
            if not isinstance(arguments, dict):
                raise ValueError("historical tool arguments must be a JSON object")
            function["arguments"] = arguments
    return result


def score_synthetic(case, response, checks):
    text, kind = response["text"], case["kind"]
    if kind == "exact":
        return {"passed": text.strip() == case["expect"]}
    if kind == "exact_codeblock":
        match = re.search(r"```text\n(.*?)```", text, re.S)
        return {"passed": (match.group(1) if match else text).strip() == case["expect"]}
    if kind == "keywords_all_of_any":
        return {"passed": all(any(word in text for word in group) for group in case["expect"])}
    if kind == "script_present":
        return {"passed": any(unicodedata.name(char, "").startswith(case["expect"]) for char in text)}
    if kind == "code_exec":
        match = re.search(r"```python\n(.*?)```", text, re.S)
        if not match:
            return {"passed": False, "detail": "no python block"}
        with tempfile.TemporaryDirectory(prefix="vocab-testdata-code-") as temporary:
            directory = Path(temporary).resolve()
            script = directory / "case.py"
            script.write_text(match.group(1) + "\n" + checks[case["check"]])
            result = restricted([sys.executable, "-I", script], directory,
                                [Path(sys.executable).resolve().parent.parent], timeout=60)
        return {"passed": result.returncode == 0 and "PASS" in result.stdout,
                "detail": (result.stdout + result.stderr)[-2000:]}
    if kind in ["leak", "tool"]:
        return None
    raise ValueError("unsupported synthetic kind: " + kind)


def paired_changes(before, after):
    if len(before) != len(after):
        raise ValueError("before/after coverage differs")
    result = []
    for left, right in zip(before, after):
        if (left["id"], left["sample"]) != (right["id"], right["sample"]):
            raise ValueError("before/after case or seed differs")
        a, b = left["response"]["text"], right["response"]["text"]
        first = next((index for index, pair in enumerate(zip(a, b)) if pair[0] != pair[1]), None)
        if first is None and len(a) != len(b):
            first = min(len(a), len(b))
        result.append({"id": left["id"], "sample": left["sample"], "textIdentical": a == b,
                       "firstScalarDifference": first, "baselineFinish": left["response"]["finishReason"],
                       "prunedFinish": right["response"]["finishReason"],
                       "baselineScore": left.get("score"), "prunedScore": right.get("score")})
    return result


def summarize_phase(records):
    synthetic = [row for row in records if row["group"] == "synthetic"]
    replay = [row for row in records if row["group"] == "replay"]
    concluded = [row for row in replay if row["response"]["finishReason"] == "stop" and row["response"]["text"]]
    leak = [row for row in synthetic if row["kind"] == "leak"]
    scored = [row for row in synthetic if row.get("score") is not None]
    benchmarks = [row for row in records if row["group"] == "benchmark"]
    return {"syntheticSamples": len(synthetic), "syntheticChecksPassed": sum(row["score"]["passed"] for row in scored),
            "syntheticChecks": len(scored), "leakSamples": len(leak),
            "syntheticLeakingSamples": sum(row["metrics"]["leak"] > 0 for row in leak),
            "replaySamples": len(replay), "replayConcluded": len(concluded),
            "replayLeakingConcluded": sum(row["metrics"]["leak"] > 0 for row in concluded),
            "replayLanguagesConcluded": {language: sum(row["metrics"]["lang"] == language for row in concluded)
                                         for language in ["ja", "en", "zh"]},
            "replayFinishCounts": {finish: sum(row["response"]["finishReason"] == finish for row in replay)
                                   for finish in sorted({row["response"]["finishReason"] for row in replay})},
            "benchmarks": [{"id": identifier, "metrics": {
                field: quantiles([row["response"][field] for row in benchmarks if row["id"] == identifier
                                  and row["response"].get(field) is not None])
                for field in ["decodeTokensPerSecond", "prefillTokensPerSecond", "ttftSeconds", "seconds", "scalarsPerSecond"]}}
                for identifier in sorted({row["id"] for row in benchmarks})]}


def run(args):
    from tokenizers import Tokenizer
    from transformers import AutoTokenizer
    root = args.test_data.resolve()
    suite, replay, checks, script_metrics = load_data(root)
    manifest = json.loads(args.manifest.read_text())
    verification = json.loads(args.verification_report.read_text())
    if verification.get("passed") is not True:
        raise ValueError("static verification must pass before evaluation")
    server = json.loads(args.server_environment.read_text())
    client = Omlx(server["url"], os.environ.get(args.api_key_env), timeout=1800)
    output = Path(os.path.abspath(args.output))
    if not output.is_relative_to(Path("outputs").resolve()) or output == Path("outputs").resolve() or output.exists():
        raise ValueError("output must be new and beneath outputs")
    if any(path.is_symlink() for path in [output, *output.parents]):
        raise ValueError("symlink output is not allowed")
    if args.samples < 1 or args.trials < 1 or args.warmups < 1:
        raise ValueError("samples, trials, and warmups must be positive")
    models = [args.baseline_model, args.pruned_model]
    if len(set(models)) != 2 or not set(models).issubset(row["id"] for row in client.json("/models")["data"]):
        raise ValueError("two distinct discovered model identities are required")
    output.mkdir(parents=True)
    source_paths = [root / "cases/synthetic/suite.json", *sorted(root.glob("cases/replay/*.json")),
                    root / "tools/common.py", root / "tools/run_synthetic.py"]
    inventory = {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in source_paths}
    revision = subprocess.run(["git", "-C", str(root), "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    versions = {name: importlib.metadata.version(name) for name in ["omlx", "mlx", "mlx-lm", "tokenizers", "transformers"]}
    write_json(output / "environment.json", {"source": manifest["source"], "pruned": manifest["modelOutput"],
               "manifestSha256": hashlib.sha256(args.manifest.read_bytes()).hexdigest(),
               "verificationReport": str(args.verification_report.resolve()), "testDataRevision": revision,
               "inputHashes": inventory, "server": server, "packageVersions": versions,
               "replaySamples": args.samples, "benchmarkTrials": args.trials, "benchmarkWarmups": args.warmups,
               "concurrency": 1, "qualityState": "pending-human-review",
               "metricsNote": "test-data JIS/context leak metric, not the surgery ICU policy predicate"})
    write_json(output / "synthetic-suite.json", suite)
    write_json(output / "replay-cases.json", replay)
    benchmark_cases = [json.loads(line) for line in args.benchmark_suite.read_text().splitlines() if line.strip()]
    benchmark_cases = [case for case in benchmark_cases if case.get("benchmark")]
    if not benchmark_cases:
        raise ValueError("benchmark suite has no benchmark cases")
    write_json(output / "benchmark-cases.json", benchmark_cases)
    jobs = []
    for case in suite["tests"]:
        for seed in case.get("seeds", [0]):
            jobs.append(("synthetic", case, seed))
    for case in replay:
        for seed in range(args.samples):
            jobs.append(("replay", case, seed))
    tokenizers = [Tokenizer.from_file(str(Path(manifest[key]) / "tokenizer.json")) for key in ["source", "modelOutput"]]
    hf = [AutoTokenizer.from_pretrained(manifest[key], local_files_only=True, trust_remote_code=False) for key in ["source", "modelOutput"]]
    plan = json.loads(Path(manifest["plan"]).read_text())
    inverse = {old: compact for compact, old in enumerate(plan["domain"]["compactToOriginal"])}
    tokenization = []
    for group, case, seed in jobs:
        if seed != (case.get("seeds", [0])[0] if group == "synthetic" else 0):
            continue
        request = case if group == "synthetic" else case["request"]
        rendered = [t.apply_chat_template(template_messages(request["messages"]), tools=request.get("tools") or None,
                                         tokenize=False, add_generation_prompt=True) for t in hf]
        if rendered[0] != rendered[1]:
            raise ValueError("chat template rendering changed")
        ids = [t.encode(text, add_special_tokens=False).ids for t, text in zip(tokenizers, rendered)]
        tokenization.append({"id": case["id"], "group": group, "baselineTokens": len(ids[0]), "prunedTokens": len(ids[1]),
                             "ratio": len(ids[1]) / max(1, len(ids[0])), "identicalProjectedIds": ids[1] == [inverse.get(value) for value in ids[0]]})
    write_json(output / "tokenization.json", tokenization)
    for model in models:
        client.unload(model)
    phases, memories = [], {}
    for phase, model in zip(["baseline", "pruned"], models):
        records = []
        sampler = RssSampler(server["pid"])
        sampler.start()
        try:
            with (output / f"{phase}.jsonl").open("x", encoding="utf-8") as stream:
                def record(row):
                    records.append(row)
                    stream.write(json.dumps(row, ensure_ascii=False, allow_nan=False) + "\n")
                    stream.flush()
                # Measure after warm-up, before the large replay contexts; never run concurrent requests.
                for case in benchmark_cases:
                    for _ in range(args.warmups):
                        client.stream(model, case)
                    for trial in range(args.trials):
                        print(f"{phase}: benchmark {case['id']} {trial + 1}/{args.trials}", flush=True)
                        record({"id": case["id"], "sample": trial, "group": "benchmark", "response": client.stream(model, case)})
                for group, case, seed in jobs:
                    request = case if group == "synthetic" else case["request"]
                    temperature = case.get("temperature", 0) if group == "synthetic" else .7
                    top_p = case.get("top_p") if group == "synthetic" else .8
                    limit = case.get("max_tokens", 1000) if group == "synthetic" else 4000
                    streaming = stream_case({**request, "max_tokens": limit})
                    choice = "none" if group == "replay" and request.get("tools") else None
                    print(f"{phase}: {group} {case['id']} seed {seed}", flush=True)
                    response = client.stream(model, streaming, temperature=temperature, seed=seed, top_p=top_p, tool_choice=choice)
                    context = "".join(m.get("content") or "" for m in request["messages"]) if group == "synthetic" else case["context_chars"]
                    row = {"id": case["id"], "sample": seed, "group": group, "kind": case.get("kind"),
                           "params": {"temperature": temperature, "top_p": top_p, "max_tokens": limit, "seed": seed, "tool_choice": choice},
                           "response": response, "metrics": script_metrics(response["text"], context)}
                    if group == "synthetic":
                        row["score"] = score_synthetic(case, response, checks)
                        if case["kind"] == "leak":
                            row["score"] = {"passed": row["metrics"]["leak"] == 0}
                        elif case["kind"] == "tool":
                            calls, expected = response["toolCalls"], case["expect"]
                            first = calls[0] if calls else None
                            valid = bool(first) and first["function"]["name"] == expected["name"] and json.loads(first["function"]["arguments"] or "{}").get("path") == expected["path"]
                            followup_ok = False
                            if first:
                                messages = request["messages"] + [{"role": "assistant", "content": response["text"] or None, "tool_calls": calls},
                                              {"role": "tool", "tool_call_id": first["id"], "content": expected["tool_result"]}]
                                followup = client.stream(model, streaming, temperature=temperature, seed=seed, top_p=top_p, messages=messages)
                                row["followup"] = followup
                                followup_ok = all(any(word in followup["text"] for word in group) for group in expected["followup_keywords"])
                            row["score"] = {"passed": valid and followup_ok, "callOk": valid, "followupOk": followup_ok}
                    record(row)
        finally:
            sampler.finish()
            memories[phase] = {"peakSampledRssBytes": sampler.peak, "sampleIntervalSeconds": .25}
            client.unload(model)
        phases.append(records)
    metrics = {"phases": {phase: summarize_phase(records) for phase, records in zip(["baseline", "pruned"], phases)},
               "pairedChanges": paired_changes(*phases), "serverMemory": memories,
               "tokenizationRatio": quantiles([row["ratio"] for row in tokenization]), "qualityState": "pending-human-review"}
    write_json(output / "metrics.json", metrics)
    print(json.dumps({"output": str(output), "phases": metrics["phases"]}, ensure_ascii=False), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ["manifest", "output", "verification-report", "server-environment"]:
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--test-data", type=Path, default=Path("test-data"))
    parser.add_argument("--benchmark-suite", type=Path, default=Path("evaluation/default-suite.jsonl"))
    parser.add_argument("--baseline-model", default="baseline")
    parser.add_argument("--pruned-model", default="pruned")
    parser.add_argument("--api-key-env", default="OMLX_API_KEY")
    parser.add_argument("--samples", type=int, default=2)
    parser.add_argument("--trials", type=int, default=5)
    parser.add_argument("--warmups", type=int, default=1)
    run(parser.parse_args())


if __name__ == "__main__":
    main()
