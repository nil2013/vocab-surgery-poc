#!/usr/bin/env python3
"""Reference tokenization for the before/after comparison; the Scala evaluate command calls it (B3 boundary).

It needs Hugging Face tokenizers and transformers, which have no JVM equivalent: chat templates are rendered by
transformers and token ids come from tokenizers.

  suite    --source DIR --model DIR --suite FILE --mapping FILE --out FILE
           Per case: tokens of the rendered prompt before and after, their ratio, and whether the pruned ids are the
           source ids projected through compactToOriginal (the mapping file is that JSON array).
  locate   --tokenizer DIR --positions FILE --out FILE
           For [{"id", "text", "position"}], the token id whose offsets cover each position (or null).
  versions --out FILE
           Versions of the client packages the comparison depends on.
"""
import argparse
import importlib.metadata
import json
from pathlib import Path


def write_new(path, value):
    with Path(path).open("x", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, allow_nan=False)
        stream.write("\n")


def suite(args):
    from tokenizers import Tokenizer
    from transformers import AutoTokenizer
    cases = [json.loads(line) for line in args.suite.read_text(encoding="utf-8").splitlines() if line.strip()]
    mapping = json.loads(args.mapping.read_text())
    inverse = {old: compact for compact, old in enumerate(mapping)}
    backends = [Tokenizer.from_file(str(path / "tokenizer.json")) for path in [args.source, args.model]]
    hf = [AutoTokenizer.from_pretrained(path, local_files_only=True, trust_remote_code=False) for path in [args.source, args.model]]
    rows = []
    for case in cases:
        texts = [case["prompt"], case["prompt"]] if case["kind"] == "completion" else [
            tokenizer.apply_chat_template(case["messages"], tools=case.get("tools"), tokenize=False, add_generation_prompt=True) for tokenizer in hf]
        if texts[0] != texts[1]:
            raise ValueError(f"{case['id']}: before/after chat rendering changed")
        ids = [tokenizer.encode(text, add_special_tokens=False).ids for tokenizer, text in zip(backends, texts)]
        rows.append({"id": case["id"], "category": case["category"], "baselineTokens": len(ids[0]), "prunedTokens": len(ids[1]),
                     "ratio": len(ids[1]) / max(1, len(ids[0])),
                     "identicalProjectedIds": all(value in inverse for value in ids[0]) and ids[1] == [inverse.get(value) for value in ids[0]]})
    write_new(args.out, rows)


def locate(args):
    from tokenizers import Tokenizer
    tokenizer = Tokenizer.from_file(str(args.tokenizer / "tokenizer.json"))
    located = []
    for item in json.loads(args.positions.read_text(encoding="utf-8")):
        encoding = tokenizer.encode(item["text"], add_special_tokens=False)
        candidate = next((token for token, (start, end) in zip(encoding.ids, encoding.offsets) if start <= item["position"] < end), None)
        located.append({"id": item["id"], "candidate": candidate})
    write_new(args.out, located)


def versions(args):
    found = {}
    for name in ["omlx", "mlx", "mlx-lm", "tokenizers", "transformers", "numpy"]:
        try:
            found[name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            found[name] = None
    write_new(args.out, found)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    one = sub.add_parser("suite")
    for name in ["source", "model", "suite", "mapping", "out"]:
        one.add_argument("--" + name, type=Path, required=True)
    two = sub.add_parser("locate")
    for name in ["tokenizer", "positions", "out"]:
        two.add_argument("--" + name, type=Path, required=True)
    three = sub.add_parser("versions")
    three.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    {"suite": suite, "locate": locate, "versions": versions}[args.command](args)


if __name__ == "__main__":
    main()
