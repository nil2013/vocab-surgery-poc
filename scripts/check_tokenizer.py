#!/usr/bin/env python3
"""Offline reference validation; only metadata is loaded, never tensor payloads."""
import argparse
import ast
import json
from pathlib import Path


def read_json(path):
    with Path(path).open(encoding="utf-8") as stream:
        return json.load(stream)


def validate(source, model, mapping, nfc):
    import tokenizers
    import transformers
    from tokenizers import Tokenizer

    old = Tokenizer.from_file(str(source / "tokenizer.json"))
    new = Tokenizer.from_file(str(model / "tokenizer.json"))
    inverse = {original: compact for compact, original in enumerate(mapping)}
    assert len(inverse) == len(mapping) and mapping == sorted(mapping), "invalid projection"
    assert new.get_vocab_size() == len(mapping), "tokenizer row count mismatch"
    assert set(new.get_vocab().values()) == set(range(len(mapping))), "noncontiguous IDs"
    original_json = read_json(source / "tokenizer.json")
    added = original_json["added_tokens"]
    for token in added:
        encoded = new.encode(token["content"], add_special_tokens=False).ids
        assert encoded == [inverse[token["id"]]], ("protocol is not a single projected ID", token["content"], encoded)
    for raw, expected in nfc:
        assert old.normalizer.normalize_str(raw) == expected, ("source NFC mismatch", raw)
        assert new.normalizer.normalize_str(raw) == expected, ("output NFC mismatch", raw)
    samples = ["日本語の説明を要約します。欄𠀋", "ASCII and emoji 🐈 e\u0301", "line\n\tindent\r\n",
               "def f(x):\n    return x + 1\n", "val xs: List[Int] = List(1, 2)", "const n: number = 42;",
               "<|fim_prefix|>x<|fim_suffix|>y<|fim_middle|>", '<tool_call>{"name":"f"}</tool_call>',
               "<|im_start|>user\nhello<|im_end|>", "ع Ж 한 汉", "\x00"]
    rows = []
    for text in samples:
        original_ids = old.encode(text, add_special_tokens=False).ids
        ids = new.encode(text, add_special_tokens=False).ids
        assert all(0 <= value < len(mapping) for value in ids)
        assert new.decode(ids, skip_special_tokens=False) == old.normalizer.normalize_str(text), ("round-trip failed", text)
        rows.append({"text": text, "originalTokens": len(original_ids), "compactTokens": len(ids),
                     "projectedEncoding": all(value in inverse for value in original_ids) and ids == [inverse.get(value) for value in original_ids]})
    old_hf = transformers.AutoTokenizer.from_pretrained(source, local_files_only=True, trust_remote_code=False)
    new_hf = transformers.AutoTokenizer.from_pretrained(model, local_files_only=True, trust_remote_code=False)
    chats = [[{"role": "system", "content": "日本語で答えてください。"}, {"role": "user", "content": "こんにちは。"}],
             [{"role": "user", "content": "1+1は？"}, {"role": "assistant", "content": "2です。"}, {"role": "user", "content": "説明して。"}],
             [{"role": "user", "content": "時刻を調べて。"}, {"role": "assistant", "content": "", "tool_calls": [{"id": "call_1", "type": "function", "function": {"name": "clock", "arguments": {}}}]},
              {"role": "tool", "tool_call_id": "call_1", "content": "12:00"}]]
    for messages in chats:
        kwargs = dict(tokenize=False, add_generation_prompt=True)
        before = old_hf.apply_chat_template(messages, **kwargs)
        after = new_hf.apply_chat_template(messages, **kwargs)
        assert before == after, "chat protocol rendering changed"
        ids = new.encode(after, add_special_tokens=False).ids
        assert new.decode(ids, skip_special_tokens=False) == new.normalizer.normalize_str(after)
    fixed = []
    for script in sorted(source.rglob("*.py")):
        for node in ast.walk(ast.parse(script.read_text(encoding="utf-8"))):
            if isinstance(node, ast.Assign) and isinstance(node.value, ast.Constant) and type(node.value.value) is int:
                names = [target.id for target in node.targets if isinstance(target, ast.Name)]
                for name in names:
                    if name.lower().endswith("token_id"):
                        original = node.value.value
                        fixed.append({"file": str(script.relative_to(source)), "name": name, "id": original})
                        assert inverse.get(original) == original, ("unrewritten fixed token ID in copied parser", name, original)
    return {"passed": True, "tokenizersVersion": tokenizers.__version__, "transformersVersion": transformers.__version__,
            "nfcCharacters": len(nfc), "protectedAddedTokens": len(added), "samples": rows, "chatTemplates": len(chats), "fixedParserIds": fixed}


def main():
    if not __debug__:
        raise RuntimeError("reference validation requires Python assertions enabled")
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ["source", "model", "mapping", "nfc", "report"]:
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    report = validate(args.source, args.model, read_json(args.mapping), read_json(args.nfc))
    with args.report.open("x", encoding="utf-8") as stream:
        json.dump(report, stream, ensure_ascii=False, sort_keys=True)
        stream.write("\n")


if __name__ == "__main__":
    main()
