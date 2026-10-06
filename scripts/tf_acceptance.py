#!/usr/bin/env python3
"""Attribute the experimental server's per-request MTP statistics to the requests that produced them.

The server logs one `done req-… tokens=N … rounds=R accepted=A/P` line per request. Requests must have been sent one at a
time; the result files are given in the order they were sent (synthetic results JSON, tf_bench.py JSON) and
every request's completion-token count must match its log line.
Usage: tf_acceptance.py <server log> <result file>... [--json <new output.json>]
"""
import argparse
import json
import re
from pathlib import Path

DONE = re.compile(r"done req-\S+ prompt=(\d+) .*?tokens=(\d+) .*?tok/s=([\d.]+) .*?rounds=(\d+) accepted=(\d+)/(\d+)")


def log_requests(lines):
    rows = []
    for line in lines:
        match = DONE.search(line)
        if match:
            prompt, tokens, tps, rounds, accepted, proposed = match.groups()
            rows.append({"prompt": int(prompt), "tokens": int(tokens), "tps": float(tps), "rounds": int(rounds),
                         "accepted": int(accepted), "proposed": int(proposed)})
    return rows


def synthetic_requests(result):
    """Requests of a run_synthetic.py result in sending order: response, tool follow-up, then samples."""
    for case in result["results"]:
        if case.get("response"):
            yield case["id"], case["id"], case["response"]
        if case.get("followup"):
            yield case["id"], f"{case['id']}/followup", case["followup"]
        for index, sample in enumerate(case.get("samples", [])):
            yield case["id"], f"{case['id']}#{index}", sample


def bench_requests(result):
    for row in result["rows"]:
        name = f"bench:{row['prompt']}/{'mtp' if row['draft'] else 'serial'}"
        yield name, f"{name}#{row['trial']}", {"usage": {"completion_tokens": row["completion_tokens"]}}


def requests(path):
    result = json.loads(path.read_text(encoding="utf-8"))
    return list(bench_requests(result) if "rows" in result else synthetic_requests(result))


def attribute(logged, sent):
    if len(logged) != len(sent):
        raise ValueError(f"{len(logged)} logged requests but {len(sent)} sent")
    rows = []
    for log, (group, name, response) in zip(logged, sent):
        tokens = ((response.get("usage") or {}).get("completion_tokens"))
        if tokens != log["tokens"]:
            raise ValueError(f"{name}: sent request generated {tokens} tokens, log line says {log['tokens']}")
        rows.append({"group": group, "name": name, **log})
    return rows


def totals(rows):
    out = {}
    for row in rows:
        if row["proposed"] == 0:
            continue  # serial decode: no drafts
        total = out.setdefault(row["group"], {"accepted": 0, "proposed": 0, "tokens": 0, "rounds": 0})
        for key in total:
            total[key] += row[key]
    for total in out.values():
        total["rate"] = total["accepted"] / total["proposed"]
        total["tokensPerRound"] = total["tokens"] / total["rounds"]
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("log", type=Path)
    parser.add_argument("results", type=Path, nargs="+")
    parser.add_argument("--json", type=Path)
    args = parser.parse_args()
    rows = attribute(log_requests(args.log.read_text(encoding="utf-8").splitlines()), [r for path in args.results for r in requests(path)])
    for row in rows:
        if row["proposed"]:
            print(f"{row['name']:32s} tokens={row['tokens']:5d} accepted={row['accepted']}/{row['proposed']} "
                  f"rate={row['accepted'] / row['proposed']:.3f} tokens/round={row['tokens'] / row['rounds']:.2f} tok/s={row['tps']}")
    summary = totals(rows)
    for group, total in summary.items():
        print(f"TOTAL {group:30s} {total['accepted']}/{total['proposed']} rate={total['rate']:.4f} tokens/round={total['tokensPerRound']:.3f}")
    if args.json:
        with args.json.open("x", encoding="utf-8") as stream:
            json.dump({"requests": rows, "totals": summary}, stream, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
