#!/usr/bin/env python3
"""Measure TTFT and decode speed of a Flash-Next server, with and without MTP drafts (TensorFold's per-request "draft" field).

Every request is recorded in order; the first trial of each prompt and mode is a warm-up and is left out of the summary.
Usage: bench.py <base url> <new output.json>
"""
import json
import statistics
import sys
import time
import urllib.request

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


def chat_stream(base, body):
    """One streamed chat completion: text, usage, time to the first text and total time."""
    body = {**body, "stream": True, "stream_options": {"include_usage": True}}
    request = urllib.request.Request(base.rstrip("/") + "/chat/completions", data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"})
    started, ttft, text, usage = time.time(), None, "", None
    with urllib.request.urlopen(request, timeout=1800) as response:
        for raw in response:
            line = raw.decode("utf-8", "replace").strip()
            if not line.startswith("data:") or line[5:].strip() == "[DONE]":
                continue
            event = json.loads(line[5:])
            usage = event.get("usage") or usage
            for choice in event.get("choices", []):
                piece = choice.get("delta", {}).get("content")
                if piece:
                    ttft = ttft if ttft is not None else time.time() - started
                    text += piece
    total = time.time() - started
    tokens = (usage or {}).get("completion_tokens") or 0
    ttft = ttft if ttft is not None else total
    return {"text": text, "usage": usage, "ttft": round(ttft, 3), "total": round(total, 3),
            "decode_tps": round(tokens / (total - ttft), 1) if total > ttft and tokens else None}


def main():
    base, out = sys.argv[1:3]
    rows = []
    for name, prompt in PROMPTS.items():
        for draft in (True, False):
            for trial in range(TRIALS):
                body = {"model": "fn", "messages": [{"role": "user", "content": prompt}], "temperature": 0, "max_tokens": 400,
                        "chat_template_kwargs": {"enable_thinking": False}, "draft": draft}
                r = chat_stream(base, body)
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
