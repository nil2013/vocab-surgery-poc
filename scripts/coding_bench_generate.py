#!/usr/bin/env python3
"""Generate coding-bench-lite answers without running them, in the bench's own result format.

The prompts, code extraction and request body come from the bench's tools/run.py (loaded from --bench), with its
default sampling. Requests go one at a time, so a TensorFold server log can be attributed with
scripts/flash-next/acceptance.py (each row also carries `case` and `setting`). Generated code is never executed:
rows keep `ok: null` and `detail: "not executed"`; `run.py rescore --file` scores them where a sandbox exists.

Usage: coding_bench_generate.py --bench DIR --base URL --model ID --label LABEL --out FILE [--samples 3]
       [--lang python,scala,rust] [--only id,...] [--extra JSON]
"""
import argparse
import importlib.util
import json
import sys
import time
from pathlib import Path


def bench_tools(bench):
    spec = importlib.util.spec_from_file_location("coding_bench_run", Path(bench) / "tools" / "run.py")
    module = importlib.util.module_from_spec(spec)
    sys.dont_write_bytecode = True
    spec.loader.exec_module(module)
    return module


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--bench", type=Path, required=True)
    parser.add_argument("--base", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--out", type=Path, required=True, help="new JSONL file")
    parser.add_argument("--samples", type=int, default=3)
    parser.add_argument("--lang", default="python,scala,rust")
    parser.add_argument("--only")
    parser.add_argument("--extra", default='{"chat_template_kwargs": {"enable_thinking": false}}')
    parser.add_argument("--temperature", type=float, default=0.7)
    parser.add_argument("--top-p", type=float, default=0.8)
    parser.add_argument("--max-tokens", type=int, default=6000)
    args = parser.parse_args()
    run = bench_tools(args.bench)
    extra = json.loads(args.extra)
    with args.out.open("x", encoding="utf-8") as stream:
        for pid in run.problems(args.only.split(",") if args.only else None):
            meta = run.load(pid)
            for lang in args.lang.split(","):
                for k in range(args.samples):
                    body = {"model": args.model, "messages": [{"role": "user", "content": run.prompt(meta, lang)}],
                            "temperature": args.temperature, "top_p": args.top_p, "max_tokens": args.max_tokens, "seed": k, **extra}
                    rec = {"problem": pid, "level": meta["level"], "lang": lang, "sample": k, "label": args.label, "model": args.model,
                           "params": {x: body.get(x) for x in ("temperature", "top_p", "max_tokens", "seed", "chat_template_kwargs")},
                           "case": f"{pid}/{lang}", "setting": f"s{k}"}
                    started = time.time()
                    d, secs = run.chat(args.base, "none", body)
                    message = d["choices"][0]["message"]
                    text = message.get("content") or ""
                    rec.update(gen_secs=round(secs, 1), finish=d["choices"][0].get("finish_reason"), usage=d.get("usage"),
                               text=text, reasoning=message.get("reasoning_content"),
                               has_code=run.extract(text, lang) is not None, ok=None, detail="not executed")
                    stream.write(json.dumps(rec, ensure_ascii=False) + "\n")
                    stream.flush()
                    print(f"{pid:20s} {lang:6s} s{k} {rec['usage'].get('completion_tokens')} tokens {time.time() - started:.1f}s", flush=True)


if __name__ == "__main__":
    main()
