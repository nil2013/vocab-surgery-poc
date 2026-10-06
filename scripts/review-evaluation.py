#!/usr/bin/env python3
"""Recompute conclusions from saved responses and complete human ratings without repeating inference."""
import argparse
import json
from pathlib import Path
from evaluate import summarize, write_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evaluation", type=Path, required=True)
    parser.add_argument("--ratings", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    def lines(name):
        return [json.loads(line) for line in (args.evaluation / name).read_text(encoding="utf-8").splitlines() if line.strip()]
    ratings = json.loads(args.ratings.read_text(encoding="utf-8"))
    expected = {row["id"] for row in lines("suite.jsonl") if row["category"] == "japanese"}
    if len(ratings) != len(expected) or {row.get("id") for row in ratings} != expected:
        raise ValueError("ratings must cover every Japanese case exactly once")
    previous = json.loads((args.evaluation / "metrics.json").read_text())
    result = summarize(lines("baseline.jsonl"), lines("pruned.jsonl"), lines("tokenizer.jsonl"), previous["logits"], ratings)
    result["size"] = previous["size"]
    result["serverMemory"] = previous.get("serverMemory")
    write_json(args.out, result)


if __name__ == "__main__":
    main()
