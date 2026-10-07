#!/usr/bin/env python3
"""Cut replay cases out of local Claude Code transcripts: a user's request and the first tool calls that followed.

Session transcripts are found under --root (default ~/.claude/projects, top-level *.jsonl only). A candidate starts
at a request the user typed (not a tool result, command output or continuation summary) that the assistant answered
with at least one tool call. Each chosen case keeps that request, the assistant's text and tool calls and their
results up to a random number of tool results, in OpenAI chat format with a tool definition per tool used, so a
model can continue the work. Long tool results are shortened. Choices are random but reproducible from --seed.
The cases hold the user's own conversations: write them under outputs, never into the repository.

Usage: claude_transcript_cases.py --out outputs/runs/<new dir> [--count 3] [--seed 0] [--root DIR]
"""
import argparse
import glob
import json
import os
import random
from pathlib import Path

SYSTEM = "You are a coding agent working in a terminal on the user's repository. Use the tools when you need them."


def text_of(content):
    if isinstance(content, str):
        return content
    return "\n".join(block.get("text", "") for block in content or [] if isinstance(block, dict) and block.get("type") == "text")


def typed_request(record):
    """The text of a request the user typed, or None for tool results, command output, notices and summaries."""
    message = record.get("message") or {}
    if record.get("type") != "user" or record.get("isMeta") or record.get("isCompactSummary") or message.get("role") != "user":
        return None
    content = message.get("content")
    if isinstance(content, list) and any(isinstance(b, dict) and b.get("type") == "tool_result" for b in content):
        return None
    text = text_of(content).strip()
    if not text or text.startswith("<") or text.startswith("[Request interrupted") or "being continued from a previous conversation" in text:
        return None
    return text


def events(lines):
    """Requests, assistant turns (text and tool calls, blocks of one message merged) and tool results, in order."""
    out = []
    for line in lines:
        try:
            record = json.loads(line)
        except json.JSONDecodeError:
            continue
        request = typed_request(record)
        if request is not None:
            out.append({"kind": "request", "text": request})
            continue
        message = record.get("message") or {}
        content = message.get("content")
        if record.get("type") == "assistant" and isinstance(content, list):
            if not out or out[-1]["kind"] != "assistant" or out[-1]["id"] != message.get("id"):
                out.append({"kind": "assistant", "id": message.get("id"), "text": "", "calls": []})
            turn = out[-1]
            for block in content:
                if block.get("type") == "text":
                    turn["text"] += block.get("text", "")
                elif block.get("type") == "tool_use":
                    turn["calls"].append({"id": block["id"], "name": block["name"], "input": block.get("input") or {}})
        elif record.get("type") == "user" and isinstance(content, list):
            for block in content:
                if isinstance(block, dict) and block.get("type") == "tool_result":
                    out.append({"kind": "result", "id": block.get("tool_use_id"), "text": text_of(block.get("content"))})
    return out


def candidates(evs):
    """(request index, tool results available before the next request) for requests answered with a tool call."""
    found = []
    for i, ev in enumerate(evs):
        if ev["kind"] != "request":
            continue
        results, called = 0, False
        for later in evs[i + 1:]:
            if later["kind"] == "request":
                break
            called |= later["kind"] == "assistant" and bool(later["calls"])
            results += later["kind"] == "result"
        if called and results:
            found.append((i, results))
    return found


def case_messages(evs, start, results, max_result_chars):
    """Chat messages from the request at ``start`` through its ``results``-th tool result."""
    messages, seen = [{"role": "system", "content": SYSTEM}, {"role": "user", "content": evs[start]["text"]}], 0
    for ev in evs[start + 1:]:
        if ev["kind"] == "assistant":
            message = {"role": "assistant", "content": ev["text"]}
            if ev["calls"]:
                message["tool_calls"] = [{"id": c["id"], "type": "function",
                                          "function": {"name": c["name"], "arguments": json.dumps(c["input"], ensure_ascii=False)}}
                                         for c in ev["calls"]]
            messages.append(message)
        elif ev["kind"] == "result":
            text = ev["text"]
            if len(text) > max_result_chars:
                text = text[:max_result_chars] + f"\n[... {len(ev['text']) - max_result_chars} more characters]"
            messages.append({"role": "tool", "tool_call_id": ev["id"], "content": text})
            seen += 1
            if seen == results:
                break
    return messages


def tool_definitions(messages):
    """A permissive function definition per tool the case calls, with the argument names it used."""
    names = {}
    for message in messages:
        for call in message.get("tool_calls", []):
            names.setdefault(call["function"]["name"], set()).update(json.loads(call["function"]["arguments"]))
    return [{"type": "function", "function": {"name": name, "description": f"The {name} tool of the user's coding agent.",
             "parameters": {"type": "object", "properties": {key: {} for key in sorted(keys)}}}}
            for name, keys in sorted(names.items())]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=Path(os.path.expanduser("~/.claude/projects")))
    parser.add_argument("--out", type=Path, required=True, help="new directory; cases go to <out>/cases/replay")
    parser.add_argument("--count", type=int, default=3)
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--max-tool-results", type=int, default=4)
    parser.add_argument("--max-result-chars", type=int, default=6000)
    parser.add_argument("--max-chars", type=int, default=60000, help="skip cuts whose messages exceed this many characters")
    args = parser.parse_args()
    pool = []
    for path in sorted(glob.glob(str(args.root / "*" / "*.jsonl"))):
        with open(path, encoding="utf-8") as stream:
            evs = events(stream)
        pool.extend((path, evs, start, results) for start, results in candidates(evs))
    rng = random.Random(args.seed)
    rng.shuffle(pool)
    (args.out / "cases" / "replay").mkdir(parents=True, exist_ok=False)
    chosen = []
    for path, evs, start, available in pool:
        if len(chosen) == args.count:
            break
        results = rng.randint(1, min(available, args.max_tool_results))
        messages = case_messages(evs, start, results, args.max_result_chars)
        if sum(len(json.dumps(m, ensure_ascii=False)) for m in messages) > args.max_chars:
            continue
        case_id = f"cc_{len(chosen):02d}"
        source = {"transcript": os.path.relpath(path, args.root), "event": start, "toolResults": results}
        case = {"id": case_id, "kind": "transcript", "source": source,
                "request": {"messages": messages, "tools": tool_definitions(messages)}}
        (args.out / "cases" / "replay" / f"{case_id}.json").write_text(json.dumps(case, ensure_ascii=False, indent=1), encoding="utf-8")
        chosen.append({"id": case_id, **source, "messages": len(messages), "request": messages[1]["content"][:120]})
    summary = {"seed": args.seed, "candidates": len(pool), "cases": chosen}
    (args.out / "selection.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
