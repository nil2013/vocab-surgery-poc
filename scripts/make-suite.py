#!/usr/bin/env python3
"""Write a fixed 70-case quality suite and 30 contamination prompts as UTF-8 JSONL."""
import argparse
import json
from pathlib import Path


def cases():
    tasks = [
        ("sum_even", "配列中の偶数だけを合計", "xs", "Vector[Int]", "Int", [([[]], 0), ([[1, 2, 3, 4]], 6), ([[-2, 0, 5]], -2)]),
        ("count_vowels", "英語の母音 a/e/i/o/u を大文字小文字を区別せず数える", "s", "String", "Int", [([""], 0), (["Hello WORLD"], 3), (["xyz"], 0)]),
        ("dedupe", "整数配列の重複を除き、最初の出現順を保つ", "xs", "Vector[Int]", "Vector[Int]", [([[]], []), ([[2, 1, 2, 3, 1]], [2, 1, 3])]),
        ("is_palindrome", "ASCII の英数字以外を無視し、小文字化して回文判定", "s", "String", "Boolean", [(["A man, a plan, a canal: Panama!"], True), (["hello"], False), ([""], True)]),
        ("gcd", "二つの整数の非負の最大公約数を返す", "a,b", "Int,Int", "Int", [([18, 24], 6), ([-8, 12], 4), ([0, 0], 0)]),
        ("factorial", "非負整数 n の階乗を返す", "n", "Int", "Int", [([0], 1), ([5], 120), ([7], 5040)]),
        ("fibonacci", "F(0)=0, F(1)=1 の n 番目の値を返す", "n", "Int", "Int", [([0], 0), ([1], 1), ([10], 55)]),
        ("max_or_none", "整数配列の最大値を返し、空配列なら None/null を返す", "xs", "Vector[Int]", "Option[Int]", [([[]], None), ([[-5, -2, -7]], -2), ([[3, 1, 4]], 4)]),
        ("longest_word", "空白区切りで最長の単語を返す。同長は最初、空なら空文字", "s", "String", "String", [([""], ""), (["a bb cc"], "bb"), (["the longest word"], "longest")]),
        ("slug", "ASCII 小文字にし、英数字以外の連続を '-' 一つに置換。両端の '-' を除く", "s", "String", "String", [(["Hello, World!"], "hello-world"), ([" A__B  C "], "a-b-c"), (["!!!"], "")]),
    ]
    rows = []
    topics = ["BPE tokenizer の働き", "immutable data の利点", "UTF-16 と Unicode scalar の違い", "単体テストと統合テスト", "cache の利点と注意点",
              "CSV の引用符", "JSON の整数精度", "bounded buffer でのコピー", "型で ID の取り違えを防ぐ方法", "決定的な処理結果の利点"]
    for index, topic in enumerate(topics):
        rows.append({"id": f"ja-explain-{index+1:02}", "category": "japanese", "kind": "chat", "messages": [{"role": "user", "content": f"{topic}について、日本語で初心者向けに3文で説明してください。"}], "maxTokens": 160})
    for index in range(10):
        paragraph = f"チーム{index+1}は月曜日に資料を整理し、火曜日に試作品を確認した。入力形式の誤りが2件見つかり、担当者が修正した。金曜日には再検証と利用案内の更新を予定している。"
        rows.append({"id": f"ja-summary-{index+1:02}", "category": "japanese", "kind": "chat", "messages": [{"role": "user", "content": "次の記録を日本語で2文に要約してください。\n" + paragraph}], "maxTokens": 160})
    for language in ["python", "scala", "typescript"]:
        for index, (name, description, arguments, types, result, tests) in enumerate(tasks):
            if language == "python":
                signature = f"def {name}({', '.join(arguments.split(','))}):"
            elif language == "scala":
                parameters = ", ".join(f"{key}: {typ}" for key, typ in zip(arguments.split(','), types.split(',')))
                signature = f"def {name}({parameters}): {result}"
            else:
                ts_types = {"Int": "number", "String": "string", "Vector[Int]": "number[]"}
                parameters = ", ".join(f"{key}: {ts_types[typ]}" for key, typ in zip(arguments.split(','), types.split(',')))
                ts_result = {"Int": "number", "String": "string", "Boolean": "boolean", "Vector[Int]": "number[]", "Option[Int]": "number | null"}[result]
                signature = f"function {name}({parameters}): {ts_result}"
            rows.append({"id": f"{language}-{index+1:02}", "category": "coding", "kind": "chat", "language": language,
                         "function": name, "scalaReturnType": result, "tests": [{"args": args, "expected": expected} for args, expected in tests],
                         "messages": [{"role": "user", "content": f"{language} で「{description}」関数を実装してください。署名は {signature}。関数のコードだけを出力し、説明・import・main は不要です。"}], "maxTokens": 256})
    for index, (name, description, arguments, _, _, tests) in enumerate(tasks):
        prefix = f"# {description}\ndef {name}({', '.join(arguments.split(','))}):\n"
        suffix = "\n# end\n"
        rows.append({"id": f"fim-{index+1:02}", "category": "fim", "kind": "completion", "language": "python", "function": name,
                     "prefix": prefix, "suffix": suffix, "tests": [{"args": args, "expected": expected} for args, expected in tests],
                     "prompt": f"<|fim_prefix|>{prefix}<|fim_suffix|>{suffix}<|fim_middle|>", "maxTokens": 256})
    for index in range(10):
        a, b = index + 2, index + 3
        tool = {"type": "function", "function": {"name": "add", "description": "二つの整数を足す", "parameters": {"type": "object", "properties": {"a": {"type": "integer"}, "b": {"type": "integer"}}, "required": ["a", "b"]}}}
        rows.append({"id": f"tool-{index+1:02}", "category": "tool", "kind": "chat", "messages": [{"role": "user", "content": f"add ツールを使って {a}+{b} を計算し、日本語で結果を教えてください。"}],
                     "tools": [tool], "expectedTool": {"name": "add", "arguments": {"a": a, "b": b}, "result": a+b}, "maxTokens": 128})
    for index in range(30):
        topic = topics[index % 10]
        prompt = f"{topic}について日本語で短く説明してください。例は{index+1}個ではなく1個にしてください。"
        if index >= 20:
            prompt = f"Python の短いコード例と日本語の説明で、{topic}を示してください。"
        rows.append({"id": f"contamination-{index+1:02}", "category": "contamination", "kind": "chat", "sampling": True,
                     "messages": [{"role": "user", "content": prompt}], "maxTokens": 128})
    rows[1]["messages"] = [{"role": "user", "content": "mutable と immutable の違いを一文で説明してください。"},
                           {"role": "assistant", "content": "mutable は値を変更でき、immutable は作成後の値を変更できません。"},
                           {"role": "user", "content": "その説明を踏まえ、immutable data の利点を日本語で初心者向けに3文で説明してください。"}]
    for index, label, copies in [(0, "short", 0), (10, "medium", 6), (11, "long", 24), (20, "short", 0), (30, "medium", 30), (40, "long", 120)]:
        rows[index]["benchmark"] = True
        rows[index]["benchmarkLength"] = label
        if copies:
            context = "担当者は入力を確認し、記録を保存し、翌日に再検証した。" if index < 20 else "// context: immutable values and deterministic transformations\n"
            rows[index]["messages"][0]["content"] += "\n参考記録:\n" + context * copies
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("x", encoding="utf-8") as stream:
        for case in cases():
            stream.write(json.dumps(case, ensure_ascii=False, sort_keys=True) + "\n")


if __name__ == "__main__":
    main()
