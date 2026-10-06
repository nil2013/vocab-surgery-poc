#!/usr/bin/env python3
"""Record oMLX before/after streams, fixed code tests, tokenization and independent quality gates."""
import argparse
import collections
import hashlib
import importlib.metadata
import json
import math
import os
import statistics
import threading
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from code_checks import check
import logits


def write_json(path, value):
    with path.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, allow_nan=False, sort_keys=True, indent=2)
        stream.write("\n")


def load_suite(path):
    if path.stat().st_size > 16 * 1024 * 1024:
        raise ValueError("suite exceeds metadata limit")
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not rows or len({row["id"] for row in rows}) != len(rows):
        raise ValueError("suite is empty or contains duplicate IDs")
    for row in rows:
        if not isinstance(row["id"], str) or not row["id"]:
            raise ValueError("case ID must be nonempty text")
        if row["kind"] not in ["chat", "completion"] or row["category"] not in ["japanese", "coding", "fim", "tool", "contamination"]:
            raise ValueError("unsupported evaluation kind / category")
        if not isinstance(row.get("maxTokens"), int) or not 1 <= row["maxTokens"] <= 8192:
            raise ValueError("maxTokens must be in 1..8192")
        if row["kind"] == "chat" and (not isinstance(row.get("messages"), list) or not row["messages"]):
            raise ValueError("chat messages are required")
        if row["kind"] == "completion" and not isinstance(row.get("prompt"), str):
            raise ValueError("completion prompt is required")
        if "language" in row and (row["language"] not in ["python", "scala", "typescript"] or not row.get("tests")):
            raise ValueError("coding cases require a supported language and fixed tests")
        if "language" in row:
            import re
            if not re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*", row.get("function", "")) or any(not isinstance(test.get("args"), list) or "expected" not in test for test in row["tests"]):
                raise ValueError("invalid function name or test arguments")
    return rows


class Omlx:
    def __init__(self, url, api_key=None, timeout=180):
        parsed = urllib.parse.urlparse(url)
        if parsed.scheme not in ["http", "https"] or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("oMLX URL must be an HTTP(S) endpoint without embedded credentials")
        root = url.rstrip("/")
        self.base = root if root.endswith("/v1") else root + "/v1"
        self.headers = {"Content-Type": "application/json"}
        if api_key:
            self.headers["Authorization"] = "Bearer " + api_key
        self.timeout = timeout

    def json(self, path, body=None):
        request = urllib.request.Request(self.base + path, data=None if body is None else json.dumps(body).encode(), headers=self.headers,
                                         method="GET" if body is None else "POST")
        with urllib.request.urlopen(request, timeout=self.timeout) as response:
            data = response.read(16 * 1024 * 1024 + 1)
            if len(data) > 16 * 1024 * 1024:
                raise ValueError("response exceeds metadata limit")
            return json.loads(data)

    def unload(self, model):
        try:
            self.json("/models/" + urllib.parse.quote(model, safe="") + "/unload", {})
        except urllib.error.HTTPError as error:
            if error.code != 400:
                raise

    def stream(self, model, case, temperature=0.0, seed=2026, messages=None):
        payload = {"model": model, "max_tokens": case["maxTokens"], "temperature": temperature, "top_p": 1.0,
                   "seed": seed, "stream": True, "stream_options": {"include_usage": True}}
        if case["kind"] == "completion":
            payload["prompt"] = case["prompt"]
            endpoint = "/completions"
        else:
            payload["messages"] = messages if messages is not None else case["messages"]
            if "tools" in case:
                payload["tools"] = case["tools"]
            endpoint = "/chat/completions"
        request = urllib.request.Request(self.base + endpoint, data=json.dumps(payload).encode(), headers=self.headers, method="POST")
        started, first = time.perf_counter(), None
        text, reasoning, calls, usage, finish = "", "", {}, {}, None
        done, events, bytes_read = False, 0, 0
        with urllib.request.urlopen(request, timeout=self.timeout) as response:
            while True:
                line = response.readline(65537)
                if not line:
                    break
                if len(line) > 65536:
                    raise ValueError("oversized SSE line")
                bytes_read += len(line)
                if bytes_read > 16 * 1024 * 1024:
                    raise ValueError("SSE response exceeds limit")
                if not line.startswith(b"data:"):
                    continue
                data = line[5:].strip()
                if data == b"[DONE]":
                    done = True
                    break
                row = json.loads(data)
                events += 1
                if row.get("usage"):
                    usage = row["usage"]
                for choice in row.get("choices", []):
                    delta = choice.get("delta", {})
                    content = choice.get("text", delta.get("content", "")) or ""
                    reasoning_piece = delta.get("reasoning_content", "") or ""
                    tool_parts = delta.get("tool_calls", [])
                    if (content or reasoning_piece or tool_parts) and first is None:
                        first = time.perf_counter()
                    text += content
                    reasoning += reasoning_piece
                    for part in tool_parts:
                        index = part["index"]
                        call = calls.setdefault(index, {"id": "", "type": "function", "function": {"name": "", "arguments": ""}})
                        if part.get("id"):
                            call["id"] = part["id"]
                        function = part.get("function", {})
                        call["function"]["name"] += function.get("name", "")
                        call["function"]["arguments"] += function.get("arguments", "")
                    if choice.get("finish_reason"):
                        finish = choice["finish_reason"]
        elapsed = time.perf_counter() - started
        if not done or finish is None or first is None or not usage:
            raise ValueError("incomplete SSE response, finish reason, or usage")
        text.encode("utf-8", errors="strict")
        cached = usage.get("prompt_tokens_details", {}).get("cached_tokens")
        if cached is None:
            raise ValueError("server did not report cache usage; controlled comparison cannot be verified")
        if cached:
            raise ValueError("prefix cache hit invalidates the controlled benchmark; use the dedicated --no-cache server")
        for field in ["prompt_tokens", "completion_tokens"]:
            if type(usage.get(field)) is not int or usage[field] < 0:
                raise ValueError("invalid usage token count")
        ttft = first - started
        decode_seconds = max(0.0, elapsed - ttft)
        return {"text": text, "reasoning": reasoning, "toolCalls": [calls[key] for key in sorted(calls)], "finishReason": finish,
                "usage": usage, "seconds": elapsed, "ttftSeconds": ttft, "sseEvents": events,
                "prefillTokensPerSecond": usage.get("prompt_tokens_per_second"),
                "decodeTokensPerSecond": usage.get("generation_tokens_per_second"),
                "clientPrefillEstimate": usage["prompt_tokens"] / ttft if ttft > 0 else None,
                "clientDecodeEstimate": max(0, usage["completion_tokens"] - 1) / decode_seconds if decode_seconds > 0 else None,
                "scalarsPerSecond": len(text) / elapsed if elapsed > 0 else None}


def quantiles(values):
    if not values:
        return None
    ordered = sorted(values)
    return {"median": statistics.median(ordered), "p95": ordered[max(0, math.ceil(len(ordered) * .95) - 1)], "min": ordered[0], "max": ordered[-1]}


class RssSampler:
    def __init__(self, pid):
        self.pid, self.peak = pid, None
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.sample, daemon=True)
    def sample(self):
        while not self.stop.is_set():
            if self.pid:
                result = subprocess.run(["ps", "-o", "rss=", "-p", str(self.pid)], capture_output=True, text=True)
                if result.stdout.strip().isdigit():
                    self.peak = max(self.peak or 0, int(result.stdout.strip()) * 1024)
            self.stop.wait(.25)
    def start(self):
        self.thread.start()
    def finish(self):
        self.stop.set()
        self.thread.join(timeout=1)


def summarize(baseline, pruned, tokenization, logit_result, ratings=None):
    phases = {}
    for label, records in [("baseline", baseline), ("pruned", pruned)]:
        sampled = [row for row in records if row["category"] == "contamination"]
        coding = [row for row in records if row["category"] == "coding" and row["trial"] == 0]
        fim = [row for row in records if row["category"] == "fim" and row["trial"] == 0]
        tool = [row for row in records if row["category"] == "tool" and row["trial"] == 0]
        phases[label] = {"outputs": len(records), "codingPassed": sum(row["codeCheck"]["passed"] for row in coding), "codingCases": len(coding),
                         "fimPassed": sum(row["codeCheck"]["passed"] for row in fim), "fimCases": len(fim),
                         "toolPassed": sum(row.get("toolPassed", False) for row in tool), "toolCases": len(tool),
                         "contaminationOutputs": len(sampled), "contaminatedOutputs": sum(row["forbiddenScalars"] > 0 for row in sampled),
                         "forbiddenScalars": sum(row["forbiddenScalars"] for row in sampled), "outputScalars": sum(row["outputScalars"] for row in sampled),
                         "eosObserved": any(row["finishReason"] == "stop" for row in records),
                         "speed": {field: quantiles([row[field] for row in records if row.get("benchmark") and row.get(field) is not None])
                                   for field in ["seconds", "ttftSeconds", "prefillTokensPerSecond", "decodeTokensPerSecond", "scalarsPerSecond"]}}
        phases[label]["contaminatedOutputRate"] = sum(row["forbiddenScalars"] > 0 for row in sampled) / len(sampled) if sampled else None
        scalars = sum(row["outputScalars"] for row in sampled)
        phases[label]["forbiddenScalarRate"] = sum(row["forbiddenScalars"] for row in sampled) / scalars if scalars else None
        reasons = collections.Counter()
        for row in sampled:
            reasons.update(row.get("forbiddenByReason", {}))
        phases[label]["forbiddenByReason"] = dict(reasons)
        groups = sorted({(row["category"], row.get("benchmarkLength", "unspecified")) for row in records if row.get("benchmark")})
        phases[label]["speedByCategoryAndLength"] = [{"category": category, "length": length,
            "metrics": {field: quantiles([row[field] for row in records if row.get("benchmark") and row["category"] == category
                                         and row.get("benchmarkLength", "unspecified") == length and row.get(field) is not None])
                        for field in ["seconds", "ttftSeconds", "prefillTokensPerSecond", "decodeTokensPerSecond", "scalarsPerSecond"]}}
            for category, length in groups]
    ratios = [row["ratio"] for row in tokenization if row["category"] in ["japanese", "coding"]]
    by_id = {row["id"]: row for row in baseline if row["trial"] == 0}
    divergence = []
    for row in pruned:
        if row["trial"] != 0 or row["category"] == "contamination":
            continue
        before, after = by_id[row["id"]]["text"], row["text"]
        first = next((i for i, pair in enumerate(zip(before, after)) if pair[0] != pair[1]), None)
        if first is None and len(before) != len(after):
            first = min(len(before), len(after))
        divergence.append({"id": row["id"], "firstScalarDifference": first})
    quality = {"state": "pending-human-review"}
    if ratings is not None:
        expected = {row["id"] for row in baseline if row["category"] == "japanese"}
        if len(ratings) != len(expected) or {row.get("id") for row in ratings} != expected:
            raise ValueError("ratings must cover every Japanese case exactly once")
        if not ratings or any(type(row.get("baseline")) not in [int, float] or type(row.get("pruned")) not in [int, float]
                              or not 1 <= row["baseline"] <= 5 or not 1 <= row["pruned"] <= 5 for row in ratings):
            raise ValueError("ratings require 1..5 numeric before/after scores")
        delta = statistics.mean(row["pruned"] - row["baseline"] for row in ratings)
        quality = {"state": "reviewed", "meanDelta": delta, "passed": delta >= -0.5 and not any(row.get("majorBreakdown") for row in ratings)}
    b, p = phases["baseline"], phases["pruned"]
    coding_gate = None if not b["codingCases"] else p["codingPassed"] / p["codingCases"] >= b["codingPassed"] / b["codingCases"] - 0.1
    contamination = "reduction_not_observable" if b["contaminatedOutputs"] == 0 else (
        "reduced" if p["contaminatedOutputs"] / max(1, p["contaminationOutputs"]) < b["contaminatedOutputs"] / max(1, b["contaminationOutputs"]) else "not_reduced")
    result = {"phases": phases, "tokenization": quantiles(ratios), "tokenizationGate": None if not ratios else quantiles(ratios)["p95"] <= 1.10,
            "codingGate": coding_gate, "fimGate": None if not p["fimCases"] else p["fimPassed"] == p["fimCases"],
            "toolGate": None if not p["toolCases"] else p["toolPassed"] == p["toolCases"], "logits": logit_result,
            "japaneseQuality": quality, "contaminationConclusion": contamination, "greedyDivergence": divergence,
            "taskGoalsFullyAchieved": False}
    result["taskGoalsFullyAchieved"] = all(value is True for value in [logit_result["passed"], result["tokenizationGate"], coding_gate,
        result["fimGate"], result["toolGate"], quality.get("passed"), b["eosObserved"], p["eosObserved"]]) and contamination == "reduced"
    coverage = {"japaneseCases": len({row["id"] for row in baseline if row["category"] == "japanese"}),
                "codingByLanguage": {language: len({row["id"] for row in baseline if row.get("language") == language and row["category"] == "coding"}) for language in ["python", "scala", "typescript"]}}
    coverage["completeInitialSuite"] = coverage["japaneseCases"] >= 20 and all(count >= 10 for count in coverage["codingByLanguage"].values()) and b["fimCases"] >= 10 and b["toolCases"] >= 10 and b["contaminationOutputs"] >= 150 and p["contaminationOutputs"] >= 150
    result["coverage"] = coverage
    result["taskGoalsFullyAchieved"] = result["taskGoalsFullyAchieved"] and coverage["completeInitialSuite"]
    return result


def run(args, logit_runner=logits.run):
    from tokenizers import Tokenizer
    from transformers import AutoTokenizer
    suite = load_suite(args.suite)
    if args.trials < 1 or args.warmups < 0 or args.baseline_model == args.pruned_model:
        raise ValueError("invalid trials / warmups / model identities")
    manifest = json.loads(args.manifest.read_text())
    plan = json.loads(Path(manifest["plan"]).read_text())
    source, model = Path(manifest["source"]), Path(manifest["modelOutput"])
    mapping = plan["domain"]["compactToOriginal"]
    inverse = {old: compact for compact, old in enumerate(mapping)}
    forbidden = {code_point: reasons for code_point, reasons in json.loads((args.output / "forbidden-codepoints.json").read_text())}
    tools_path = args.tools if args.tools else Path("outputs/tools/tools.json")
    tools = json.loads(tools_path.read_text()) if any(row.get("language") == "typescript" for row in suite) else {}
    if any(row.get("language") == "scala" for row in suite) and not args.compiler.is_dir():
        raise ValueError("Scala compiler jars are missing; run sbt stageCli")
    client = Omlx(args.omlx_url, os.environ.get(args.api_key_env))
    discovered = {row["id"] for row in client.json("/models")["data"]}
    if not {args.baseline_model, args.pruned_model}.issubset(discovered):
        raise ValueError("explicit model identities are not discovered by oMLX")
    client.unload(args.baseline_model)
    client.unload(args.pruned_model)
    logit_result = logit_runner(args.manifest, args.output)
    tokenization = []
    backends = [Tokenizer.from_file(str(path / "tokenizer.json")) for path in [source, model]]
    hf = [AutoTokenizer.from_pretrained(path, local_files_only=True, trust_remote_code=False) for path in [source, model]]
    for case in suite:
        texts = [case["prompt"], case["prompt"]] if case["kind"] == "completion" else [tokenizer.apply_chat_template(case["messages"], tools=case.get("tools"), tokenize=False, add_generation_prompt=True) for tokenizer in hf]
        if texts[0] != texts[1]:
            raise ValueError("before/after chat rendering changed")
        ids = [tokenizer.encode(text, add_special_tokens=False).ids for tokenizer, text in zip(backends, texts)]
        tokenization.append({"id": case["id"], "category": case["category"], "baselineTokens": len(ids[0]), "prunedTokens": len(ids[1]),
                             "ratio": len(ids[1]) / max(1, len(ids[0])), "identicalProjectedIds": all(value in inverse for value in ids[0]) and ids[1] == [inverse.get(value) for value in ids[0]]})
    with (args.output / "tokenizer.jsonl").open("x", encoding="utf-8") as stream:
        for row in tokenization:
            stream.write(json.dumps(row, ensure_ascii=False) + "\n")
    server = json.loads(args.server_environment.read_text()) if getattr(args, "server_environment", None) else {}
    if server and server.get("url") != args.omlx_url:
        raise ValueError("server metadata URL differs from requested endpoint")
    versions = {}
    for name in ["omlx", "mlx", "mlx-lm", "tokenizers", "transformers", "numpy"]:
        try:
            versions[name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            versions[name] = None
    write_json(args.output / "environment.json", {"clientPackageVersions": versions, "omlxUrl": args.omlx_url, "concurrency": 1,
                                                "omlxServerVersion": server.get("omlxVersion"), "serverPid": server.get("pid"), "serverPrefixCache": server.get("prefixCache"),
                                                "prefixCache": "cached prompt tokens must be zero", "suiteSha256": hashlib.sha256(args.suite.read_bytes()).hexdigest(),
                                                "trials": args.trials, "warmups": args.warmups, "seedBase": 2026})
    all_records = []
    server_memory = {}
    for phase, name in [("baseline", args.baseline_model), ("pruned", args.pruned_model)]:
        records = []
        sampler = RssSampler(server.get("pid"))
        sampler.start()
        phase_path = args.output / f"{phase}.jsonl"
        with phase_path.open("x", encoding="utf-8"):
            pass
        try:
            for case in suite:
                if case.get("benchmark"):
                    for _ in range(args.warmups):
                        client.stream(name, case)
                repeats = args.trials if case.get("sampling") or case.get("benchmark") else 1
                for trial in range(repeats):
                    print(f"{phase}: {case['id']} trial {trial+1}/{repeats}", flush=True)
                    response = client.stream(name, case, temperature=.7 if case.get("sampling") else 0.0, seed=2026 + trial)
                    generated = response["text"] + response["reasoning"] + "".join(call["function"]["arguments"] for call in response["toolCalls"])
                    generated.encode("utf-8", errors="strict")
                    reasons = collections.Counter(reason for char in generated for reason in forbidden.get(ord(char), []))
                    row = {**response, "id": case["id"], "category": case["category"], "language": case.get("language"), "trial": trial, "benchmark": case.get("benchmark", False),
                           "benchmarkLength": case.get("benchmarkLength", "unspecified"), "temperature": .7 if case.get("sampling") else 0.0, "seed": 2026+trial,
                           "forbiddenScalars": sum(ord(char) in forbidden for char in generated), "outputScalars": len(generated), "forbiddenByReason": dict(reasons)}
                    if case.get("language"):
                        row["codeCheck"] = check(case, response["text"], tools, args.compiler)
                    if case["category"] == "tool":
                        expected = case["expectedTool"]
                        calls = response["toolCalls"]
                        try:
                            valid = len(calls) == 1 and bool(calls[0]["id"]) and calls[0]["function"]["name"] == expected["name"] and json.loads(calls[0]["function"]["arguments"]) == expected["arguments"]
                        except (KeyError, ValueError):
                            valid = False
                        if valid:
                            messages = case["messages"] + [{"role": "assistant", "content": response["text"] or None, "tool_calls": calls},
                                                            {"role": "tool", "tool_call_id": calls[0]["id"], "content": json.dumps(expected["result"])}]
                            followup = client.stream(name, case, messages=messages)
                            row["toolResponse"] = followup
                            valid = bool(followup["text"].strip()) and str(expected["result"]) in followup["text"]
                        row["toolPassed"] = valid
                    records.append(row)
                    with (args.output / f"{phase}.jsonl").open("a", encoding="utf-8") as stream:
                        stream.write(json.dumps(row, ensure_ascii=False, allow_nan=False) + "\n")
        finally:
            sampler.finish()
            server_memory[phase] = {"peakSampledServerRssBytes": sampler.peak, "sampleIntervalSeconds": .25}
            client.unload(name)
        all_records.append(records)
    ratings = json.loads(args.ratings.read_text()) if args.ratings else None
    metrics = summarize(*all_records, tokenization, logit_result, ratings)
    baseline_by_id = {row["id"]: row for row in all_records[0] if row["trial"] == 0}
    for divergence in metrics["greedyDivergence"]:
        position = divergence["firstScalarDifference"]
        if position is not None:
            encoding = backends[0].encode(baseline_by_id[divergence["id"]]["text"], add_special_tokens=False)
            candidate = next((token for token, (start, end) in zip(encoding.ids, encoding.offsets) if start <= position < end), None)
            divergence["retokenizedBaselineCandidate"] = candidate
            divergence["candidateDeleted"] = candidate is not None and candidate not in inverse
            divergence["candidateEvidence"] = "retokenized decoded output; raw generated IDs are not returned by this API"
    metrics["serverMemory"] = server_memory
    metrics["size"] = {"sourceLogicalBytes": sum(path.stat().st_size for path in source.rglob("*") if path.is_file()),
                       "prunedLogicalBytes": sum(path.stat().st_size for path in model.rglob("*") if path.is_file()),
                       "predictedPayloadReductionBytes": plan["domain"]["savedPayloadBytes"], "apfsPhysicalBytes": None}
    write_json(args.output / "metrics.json", metrics)
    write_json(args.output / "ratings-template.json", [{"id": row["id"], "baseline": None, "pruned": None, "majorBreakdown": False} for row in suite if row["category"] == "japanese"])
    lines = ["# Before / after evaluation", "", f"Model: `{model}`", "", f"Static verification: passed before evaluation. Surviving logits gate: {logit_result['passed']}.", "",
             f"Coding gate: {metrics['codingGate']}; FIM gate: {metrics['fimGate']}; tool-call gate: {metrics['toolGate']}.", "",
             f"Japanese quality: {metrics['japaneseQuality']['state']}. Fill ratings-template.json in a separate ratings file for a human assessment.", "",
             f"Contamination: {metrics['contaminationConclusion']}. This measures lexical pruning effects and does not establish generation prohibition.", "",
             "Detailed measured values and denominators are in metrics.json; raw outputs are in baseline.jsonl and pruned.jsonl.", "",
             "All TASK.md goals are not claimed achieved. Quality, contamination and speed conclusions remain independent.", ""]
    with (args.output / "REPORT.md").open("x", encoding="utf-8") as stream:
        stream.write("\n".join(lines))
    return metrics


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ["manifest", "suite", "output"]:
        parser.add_argument("--" + name, type=Path, required=True)
    for name in ["omlx-url", "baseline-model", "pruned-model"]:
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--api-key-env", default="OMLX_API_KEY")
    parser.add_argument("--trials", type=int, default=5)
    parser.add_argument("--warmups", type=int, default=1)
    parser.add_argument("--tools", type=Path)
    parser.add_argument("--compiler", type=Path, default=Path("target/cli/compiler"))
    parser.add_argument("--ratings", type=Path)
    parser.add_argument("--server-environment", type=Path)
    run(parser.parse_args())


if __name__ == "__main__":
    main()
