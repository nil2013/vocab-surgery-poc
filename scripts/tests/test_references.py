"""Tests of the Python reference implementations (B3): logits, check_tokenizer and tokenization."""
import importlib.util
import json
import math
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import logits
import check_tokenizer

TOKENIZERS = importlib.util.find_spec("tokenizers") is not None


class ReferenceTests(unittest.TestCase):
    def test_logit_gate_preserves_thresholds_and_rejects_bad_vectors(self):
        self.assertTrue(logits.compare([1.0, 2.0], [1.0, 2.001])["passed"])
        result = logits.compare([1.0], [1.1])
        self.assertFalse(result["passed"])
        self.assertEqual(result["atol"], .01)
        self.assertFalse(logits.compare([math.nan], [1.0])["passed"])
        for left, right in [([], []), ([1], [1, 2])]:
            with self.assertRaises(ValueError):
                logits.compare(left, right)

    def test_reference_helper_refuses_disabled_assertions(self):
        process = subprocess.run([sys.executable, "-O", str(SCRIPTS / "check_tokenizer.py"), "--help"], capture_output=True, text=True)
        self.assertNotEqual(process.returncode, 0)
        self.assertIn("assertions enabled", process.stderr)

    def test_reference_files_follow_rows_slow_tokenizer_files_and_ple_token_ids(self):
        with tempfile.TemporaryDirectory() as root:
            source, model = Path(root, "source"), Path(root, "model")
            source.mkdir()
            model.mkdir()
            def write(path, value):
                path.write_text(json.dumps(value) if not isinstance(value, str) else value, encoding="utf-8")
            old = {"model": {"vocab": {"a": 0, "b": 1, "ab": 2, "x": 3}, "merges": ["a b"]}, "added_tokens": [{"id": 4, "content": "<e>"}]}
            new = {"model": {"vocab": {"a": 0, "b": 1, "ab": 2}, "merges": [["a", "b"]]}, "added_tokens": [{"id": 3, "content": "<e>"}]}
            write(source / "tokenizer.json", old)
            write(model / "tokenizer.json", new)
            write(source / "config.json", {"text_config": {"vocab_size": 8}})
            write(source / "vocab.json", old["model"]["vocab"])
            write(model / "vocab.json", new["model"]["vocab"])
            write(source / "merges.txt", "#version: 0.2\na b\n")
            write(model / "merges.txt", "#version: 0.2\na b\n")
            write(model / "ple-token-ids.json", {"originalVocabSize": 8, "originalTokenIds": [0, 1, 2, 4]})
            mapping = [0, 1, 2, 4]
            result = check_tokenizer.check_files(source, model, mapping)
            self.assertEqual(result["companionFiles"], ["vocab.json", "merges.txt", "ple-token-ids.json"])
            write(source / "ple-token-ids.json", {"originalVocabSize": 20, "originalTokenIds": [1, 3, 5, 7, 9]})
            write(model / "ple-token-ids.json", {"originalVocabSize": 20, "originalTokenIds": [1, 3, 5, 9]})
            self.assertEqual(check_tokenizer.check_files(source, model, mapping)["pleTokenIds"], {"rows": 4, "originalVocabSize": 20})
            for broken in ([0, 1, 2, 3], [0, 1, 3, 4]):
                with self.assertRaises(AssertionError):
                    check_tokenizer.check_files(source, model, broken)
            write(model / "ple-token-ids.json", {"originalVocabSize": 20, "originalTokenIds": [0, 1, 2, 4]})
            with self.assertRaises(AssertionError):
                check_tokenizer.check_files(source, model, mapping)
            write(model / "ple-token-ids.json", {"originalVocabSize": 20, "originalTokenIds": [1, 3, 5, 9]})
            write(model / "merges.txt", "a b\nb a\n")
            with self.assertRaises(AssertionError):
                check_tokenizer.check_files(source, model, mapping)
            write(model / "merges.txt", "a b\n")
            (model / "vocab.json").unlink()
            with self.assertRaises(FileNotFoundError):
                check_tokenizer.check_files(source, model, mapping)


    @unittest.skipUnless(TOKENIZERS, "needs tokenizers")
    def test_tokenization_counts_and_encodes_texts_without_special_tokens(self):
        from tokenizers import Tokenizer, models, pre_tokenizers
        with tempfile.TemporaryDirectory() as root:
            tokenizer = Tokenizer(models.WordLevel({"a": 0, "b": 1, "[UNK]": 2}, unk_token="[UNK]"))
            tokenizer.pre_tokenizer = pre_tokenizers.Whitespace()
            path = Path(root, "tokenizer.json")
            tokenizer.save(str(path))
            Path(root, "texts.json").write_text(json.dumps(["a b a", "b c"]))
            def run(command):
                out = Path(root, f"{command}.json")
                subprocess.run([sys.executable, str(SCRIPTS / "tokenization.py"), command, "--tokenizer", str(path),
                                "--texts", str(Path(root, "texts.json")), "--out", str(out)], check=True, capture_output=True)
                return json.loads(out.read_text())
            self.assertEqual(run("encode"), [[0, 1, 0], [1, 2]])
            self.assertEqual(run("count"), [[0, 2], [1, 2], [2, 1]])
            with self.assertRaises(subprocess.CalledProcessError):
                run("count")


if __name__ == "__main__":
    unittest.main()
