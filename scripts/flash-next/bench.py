#!/usr/bin/env python3
"""Measure TTFT and decode speed of a Flash-Next server, with and without MTP drafts (TensorFold's per-request "draft" field).

Every request is recorded in order; the first trial of each prompt and mode is a warm-up and is left out of the summary.
Usage: bench.py <base url> <test-data dir> <new output.json>
"""
import json
import statistics
import sys

PROMPTS = {
    "ja": "日本語で、HTTP/2 と HTTP/3 の違いを、接続確立・多重化・輸送層の観点から600字程度で説明してください。",
    "en": "Explain in about 300 words how a B-tree differs from a binary search tree and why databases prefer B-trees.",
    "code": "Write a Python module implementing a thread-safe LRU cache class with get, put and a TTL option. Reply with code only.",
}
TRIALS = 4


def summarize(rows):
    summary = {}
    for name in PROMPTS:
        for draft in (True, False):
            sel = [r for r in rows if r["prompt"] == name and r["draft"] == draft and not r["warmup"]]
            summary[f"{name}/{'mtp' if draft else 'serial'}"] = {
                "decode_tps_median": statistics.median(r["decode_tps"] for r in sel),
                "ttft_median": statistics.median(r["ttft"] for r in sel),
                "completion_tokens": [r["completion_tokens"] for r in sel],
                "chars_per_second_median": statistics.median(r["chars"] / (r["total"] - r["ttft"]) for r in sel)}
    return summary


def main():
    base, testdata, out = sys.argv[1:4]
    sys.path.insert(0, testdata + "/tools")
    from common import chat_stream
    rows = []
    for name, prompt in PROMPTS.items():
        for draft in (True, False):
            for trial in range(TRIALS):
                body = {"model": "fn", "messages": [{"role": "user", "content": prompt}], "temperature": 0, "max_tokens": 400,
                        "chat_template_kwargs": {"enable_thinking": False}, "draft": draft}
                r = chat_stream(base, "none", body)
                usage = r["usage"] or {}
                rows.append({"prompt": name, "draft": draft, "trial": trial, "warmup": trial == 0, "ttft": r["ttft"], "total": r["total"],
                             "decode_tps": r["decode_tps"], "completion_tokens": usage.get("completion_tokens"),
                             "prompt_tokens": usage.get("prompt_tokens"), "chars": len(r["text"]), "text": r["text"]})
                print(json.dumps({k: v for k, v in rows[-1].items() if k != "text"}, ensure_ascii=False), flush=True)
    summary = summarize(rows)
    with open(out, "x", encoding="utf-8") as stream:
        json.dump({"summary": summary, "rows": rows}, stream, ensure_ascii=False, indent=1)
    print(json.dumps(summary, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
