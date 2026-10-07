#!/usr/bin/env python3
"""Rebuild TensorFold's Flash-Next MTP draft list from a JSON profile and a corpus, keeping its number of rows.

The profile holds the rules only: the base list, which tokens may enter (generated at least `minCount` times, most
frequent first, in any language), which base rows they replace (rows the corpus never generates, highest token id
first: BPE merges them last), what never leaves (added tokens such as <|im_end|>), and explicit additions and
removals. The corpus is neutral JSONL, one generated text per line as {"text": ...}; whoever owns the evaluation
material exports it. With --model, only the tokens that checkpoint keeps count, so the list scores as many of its
rows as the base does. The output holds sorted upstream token ids, one per line, as `tensorfold serve --draft-vocab`
expects.

Usage: draft_vocab.py --profile P.json --corpus C.jsonl [...] --tokenizer tokenizer.json --builtin draft_vocab.txt
       [--model DIR] --out FILE
Needs tokenizers and numpy (the repository's .venv).
"""
import argparse
import json
from pathlib import Path

import numpy as np

REPLACE_ORDERS = ("highest-id",)


def load_profile(path):
    """The profile with defaults, rejecting unknown keys and values."""
    profile = json.loads(Path(path).read_text(encoding="utf-8"))
    known = {"base", "select", "replace", "protect", "force"}
    unknown = set(profile) - known
    if unknown:
        raise ValueError(f"{path}: unknown keys {sorted(unknown)}")
    select = {"minCount": 2, **(profile.get("select") or {})}
    replace = {"from": "unused", "order": "highest-id", **(profile.get("replace") or {})}
    if replace["from"] != "unused" or replace["order"] not in REPLACE_ORDERS:
        raise ValueError(f"{path}: replace supports from: unused and order: {REPLACE_ORDERS}")
    protect = {"addedTokens": True, "tokens": [], **(profile.get("protect") or {})}
    force = {"add": [], "remove": [], **(profile.get("force") or {})}
    return {"base": profile.get("base", "builtin"), "select": select, "replace": replace, "protect": protect, "force": force}


def corpus_texts(paths):
    """Distinct texts of neutral corpus files ({"text": ...} per line), in file order."""
    seen, texts = set(), []
    for path in paths:
        for number, line in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            text = json.loads(line).get("text")
            if not isinstance(text, str):
                raise ValueError(f"{path}:{number}: expected {{\"text\": string}}")
            if text.strip() and text not in seen:
                seen.add(text)
                texts.append(text)
    return texts


def swap(counts, base, kept, protected, min_count, add=(), remove=()):
    """The swapped list over the kept ids and the ids swapped in and out; the row count stays the base's.

    Candidates enter most frequent first (forced additions before them); they take the rows of forced removals,
    then base rows the corpus never generates, highest id first. Protected rows never leave.
    """
    def mask(ids):
        out = np.zeros(len(counts), bool)
        out[list(ids)] = True
        return out

    listed = mask(base) & kept
    budget = int(listed.sum())
    forced_in, forced_out = mask(add), mask(remove)
    if (forced_in & forced_out).any() or (forced_out & protected).any() or (forced_in & ~kept).any():
        raise ValueError("force.add and force.remove overlap, remove a protected token, or add a token the model drops")
    listed &= ~forced_out
    wanted = np.where(((counts >= min_count) | forced_in) & ~listed & kept & ~forced_out)[0]
    wanted = wanted[np.lexsort((wanted, -counts[wanted], ~forced_in[wanted]))]
    droppable = np.where(listed & (counts == 0) & ~protected & ~forced_in)[0][::-1]
    room = budget - int(listed.sum())
    n = min(len(wanted), len(droppable) + room)
    if forced_in[wanted[n:]].any():
        raise ValueError("not enough unused rows to make room for force.add")
    added = np.sort(wanted[:n])
    dropped = np.sort(droppable[:max(0, n - room)])
    listed[added] = True
    listed[dropped] = False
    return np.where(listed)[0], added, dropped


def coverage(counts, ids):
    inside = np.zeros(len(counts), bool)
    inside[ids] = True
    return float(counts[inside].sum() / max(counts.sum(), 1))


def token_ids(tokenizer, values, what):
    """Ids for profile entries: an int is an id, a string a token that must encode to exactly one id."""
    ids = []
    for value in values:
        if isinstance(value, int):
            ids.append(value)
            continue
        found = tokenizer.token_to_id(value)
        if found is None:
            encoded = tokenizer.encode(value, add_special_tokens=False).ids
            if len(encoded) != 1:
                raise ValueError(f"{what}: {value!r} is not one token")
            found = encoded[0]
        ids.append(found)
    return ids


def main():
    from tokenizers import Tokenizer

    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--profile", type=Path, required=True, help="JSON draft-list profile")
    parser.add_argument("--corpus", type=Path, nargs="+", required=True, help="neutral corpus JSONL ({\"text\": ...} per line)")
    parser.add_argument("--tokenizer", type=Path, required=True, help="the upstream checkpoint's tokenizer.json")
    parser.add_argument("--builtin", type=Path, required=True, help="TensorFold's built-in draft_vocab.txt (base: builtin)")
    parser.add_argument("--model", type=Path, help="a vocabulary-pruned checkpoint whose kept tokens bound the list")
    parser.add_argument("--out", type=Path, required=True, help="new draft list (one upstream id per line)")
    args = parser.parse_args()
    profile = load_profile(args.profile)
    tokenizer = Tokenizer.from_file(str(args.tokenizer))
    size = tokenizer.get_vocab_size(with_added_tokens=True)
    texts = corpus_texts(args.corpus)
    counts = np.zeros(size, np.int64)
    for encoding in tokenizer.encode_batch(texts, add_special_tokens=False):
        np.add.at(counts, np.array(encoding.ids, dtype=np.int64), 1)
    base_path = args.builtin if profile["base"] == "builtin" else Path(profile["base"])
    base = np.unique(np.loadtxt(base_path, dtype=np.int64).reshape(-1))
    kept = np.ones(size, bool)
    if args.model is not None:
        table = json.loads((args.model / "ple-token-ids.json").read_text())
        kept[:] = False
        kept[np.array(table["originalTokenIds"], dtype=np.int64)] = True
    protected = np.zeros(size, bool)
    if profile["protect"]["addedTokens"]:
        protected[list(tokenizer.get_added_tokens_decoder())] = True
    protected[token_ids(tokenizer, profile["protect"]["tokens"], "protect.tokens")] = True
    ids, added, dropped = swap(counts, base, kept, protected, int(profile["select"]["minCount"]),
                               token_ids(tokenizer, profile["force"]["add"], "force.add"),
                               token_ids(tokenizer, profile["force"]["remove"], "force.remove"))
    with args.out.open("x", encoding="utf-8") as stream:
        stream.writelines(f"{i}\n" for i in ids)
    examples = lambda values: [tokenizer.decode([int(i)]) for i in values[np.argsort(-counts[values], kind="stable")][:40]]
    report = {"profile": str(args.profile), "corpus": [str(p) for p in args.corpus], "texts": len(texts), "tokens": int(counts.sum()), "base": int(len(base)),
              "baseKept": int(kept[base].sum()), "rows": int(len(ids)), "swapped": int(len(added)),
              "protectedInBase": int((protected & kept)[base].sum()), "added": examples(added),
              "droppedHighestIds": [int(i) for i in dropped[-10:]],
              "coverage": {"base": coverage(counts, base[kept[base]]), "swapped": coverage(counts, ids)}}
    print(json.dumps(report, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
