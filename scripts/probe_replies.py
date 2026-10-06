#!/usr/bin/env python3
"""Send a fixed set of prompts, each at temperature 0 and at 0.7 with seeds 1 and 2, and record the replies.

Each reply is stored with its script-leak metrics from test-data: `novel` counts leak-class characters absent from the
prompt, `copied` lists those taken from it. Requests are sent one at a time, so tf_acceptance.py can attribute them.
Usage: probe_replies.py <base url> <test-data dir> <prompts.json> <new output.jsonl> [max_tokens]
"""
import json
import sys

SETTINGS = [("t0", 0.0, None), ("t07s1", 0.7, 1), ("t07s2", 0.7, 2)]


def main():
    base, testdata, prompts_path, out = sys.argv[1:5]
    max_tokens = int(sys.argv[5]) if len(sys.argv) > 5 else 600
    sys.path.insert(0, testdata + "/tools")
    from common import chat_stream, is_leak_char, script_metrics
    with open(prompts_path, encoding="utf-8") as stream:
        prompts = json.load(stream)
    with open(out, "x", encoding="utf-8") as stream:
        for name, prompt in prompts.items():
            for tag, temperature, seed in SETTINGS:
                body = {"model": "fn", "messages": [{"role": "user", "content": prompt}], "temperature": temperature,
                        "max_tokens": max_tokens, "chat_template_kwargs": {"enable_thinking": False}}
                if seed is not None:
                    body["seed"] = seed
                r = chat_stream(base, "none", body)
                text = r["text"]
                row = {"case": name, "setting": tag, "prompt": prompt, "text": text, "finish": r.get("finish"),
                       "usage": r["usage"], "decode_tps": r["decode_tps"], "novel": script_metrics(text, prompt),
                       "copied": "".join(sorted({c for c in text if is_leak_char(c) and c in prompt}))}
                stream.write(json.dumps(row, ensure_ascii=False) + "\n")
                stream.flush()
                print(name, tag, row["novel"]["lang"], "novel", row["novel"]["leak_chars"], "copied", row["copied"],
                      "prompt_tokens", (r["usage"] or {}).get("prompt_tokens"), flush=True)


if __name__ == "__main__":
    main()
