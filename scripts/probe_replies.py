#!/usr/bin/env python3
"""Send a fixed set of requests, each at temperature 0 and at 0.7 with seeds 1 and 2, and record the replies.

Requests come from a prompts file (a JSON object name -> user text) or a directory of replay cases
(<dir>/cases/replay/*.json with request.messages and request.tools, as test-data and claude_transcript_cases.py
write them). Each reply is stored with its script-leak metrics from test-data: `novel` counts leak-class characters
absent from the request, `copied` lists those taken from it. Requests are sent one at a time to any
OpenAI-compatible server.
Usage: probe_replies.py <base url> <model> <test-data dir> <prompts.json | cases dir> <new output.jsonl> [max_tokens]
"""
import glob
import json
import sys
from pathlib import Path

SETTINGS = [("t0", 0.0, None), ("t07s1", 0.7, 1), ("t07s2", 0.7, 2)]


def requests(source):
    """(name, request text shown in the output, messages, tools) for a prompts file or a replay-case directory."""
    path = Path(source)
    if path.is_dir():
        for case_path in sorted(glob.glob(str(path / "cases" / "replay" / "*.json"))):
            case = json.loads(Path(case_path).read_text(encoding="utf-8"))
            messages = case["request"]["messages"]
            shown = next((m["content"] for m in messages if m["role"] == "user" and isinstance(m.get("content"), str)), "")
            yield case["id"], shown, messages, case["request"].get("tools") or []
        return
    for name, prompt in json.loads(path.read_text(encoding="utf-8")).items():
        yield name, prompt, [{"role": "user", "content": prompt}], []


def context_text(messages):
    """Every string a request carries, so characters copied from it are not counted as leaks."""
    parts = []
    for message in messages:
        content = message.get("content")
        parts.append(content if isinstance(content, str) else json.dumps(content, ensure_ascii=False))
        parts.extend(call["function"]["arguments"] for call in message.get("tool_calls", []))
    return "\n".join(parts)


def main():
    base, model, testdata, source, out = sys.argv[1:6]
    max_tokens = int(sys.argv[6]) if len(sys.argv) > 6 else 600
    sys.dont_write_bytecode = True  # test-data may be the read-only submodule
    sys.path.insert(0, testdata + "/tools")
    from common import chat_stream, is_leak_char, script_metrics
    with open(out, "x", encoding="utf-8") as stream:
        for name, shown, messages, tools in requests(source):
            context = context_text(messages)
            for tag, temperature, seed in SETTINGS:
                body = {"model": model, "messages": messages, "temperature": temperature,
                        "max_tokens": max_tokens, "chat_template_kwargs": {"enable_thinking": False}}
                if tools:
                    body["tools"] = tools
                if seed is not None:
                    body["seed"] = seed
                r = chat_stream(base, "none", body)
                text = r["text"]
                row = {"case": name, "setting": tag, "prompt": shown, "text": text, "tool_calls": r.get("tool_calls"),
                       "finish": r.get("finish"), "usage": r["usage"], "decode_tps": r["decode_tps"],
                       "novel": script_metrics(text, context),
                       "copied": "".join(sorted({c for c in text if is_leak_char(c) and c in context}))}
                stream.write(json.dumps(row, ensure_ascii=False) + "\n")
                stream.flush()
                print(name, tag, row["novel"]["lang"], "novel", row["novel"]["leak_chars"], "copied", row["copied"],
                      "prompt_tokens", (r["usage"] or {}).get("prompt_tokens"), flush=True)


if __name__ == "__main__":
    main()
