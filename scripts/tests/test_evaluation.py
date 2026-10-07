import argparse
import contextlib
import http.server
import importlib.util
import json
import math
import os
import tempfile
import threading
import unittest
from pathlib import Path
import sys
import subprocess
import urllib.error
from unittest.mock import Mock, patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import evaluate
import logits
import code_checks
import check_tokenizer
import sandbox

spec = importlib.util.spec_from_file_location("make_suite", SCRIPTS / "make-suite.py")
make_suite = importlib.util.module_from_spec(spec)
spec.loader.exec_module(make_suite)


def sandbox_available():
    try:
        sandbox.platform()
        return True
    except sandbox.SandboxUnavailable:
        return False


SANDBOX = sandbox_available()


def flash_next(name):
    spec = importlib.util.spec_from_file_location(f"flash_next_{name}", SCRIPTS / "flash-next" / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


try:
    flash_next_draft_vocab = flash_next("draft_vocab")
except ImportError:
    flash_next_draft_vocab = None


@contextlib.contextmanager
def fake_server(body):
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            self.server.last_request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            self.wfile.write(body)
        def log_message(self, *args):
            pass
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}", server
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


def event(value):
    return b"data: " + json.dumps(value).encode() + b"\n\n"


def response(cached=0):
    return event({"choices": [{"delta": {"content": "日本語"}}]}) + event({"choices": [{"delta": {}, "finish_reason": "stop"}],
        "usage": {"prompt_tokens": 10, "completion_tokens": 3, "prompt_tokens_details": {"cached_tokens": cached}}}) + b"data: [DONE]\n\n"


class EvaluationTests(unittest.TestCase):
    def test_default_suite_counts_and_determinism(self):
        rows = make_suite.cases()
        self.assertEqual(rows, make_suite.cases())
        self.assertEqual(len(rows), 100)
        self.assertEqual(len({row["id"] for row in rows}), 100)
        for category, count in [("japanese", 20), ("coding", 30), ("fim", 10), ("tool", 10), ("contamination", 30)]:
            self.assertEqual(sum(row["category"] == category for row in rows), count)
        for language in ["python", "scala", "typescript"]:
            self.assertEqual(sum(row["category"] == "coding" and row["language"] == language for row in rows), 10)
        self.assertEqual(len([row for row in rows if row.get("benchmark")]), 6)
        recorded = [json.loads(line) for line in (SCRIPTS.parent / "evaluation/default-suite.jsonl").read_text().splitlines()]
        self.assertEqual(recorded, rows)
        self.assertTrue(any(len(row.get("messages", [])) >= 3 for row in rows))

    def test_suite_validation_rejects_bad_inputs_and_duplicate_ids(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "suite.jsonl"
            for rows in [[], [make_suite.cases()[0]] * 2, [{"id": "x", "kind": "bogus", "category": "coding", "maxTokens": 12}],
                         [{**make_suite.cases()[20], "function": "unsafe; exit()"}], [{**make_suite.cases()[0], "maxTokens": 0}]]:
                path.write_text("\n".join(json.dumps(row) for row in rows))
                with self.assertRaises(ValueError):
                    evaluate.load_suite(path)

    def test_streaming_measures_usage_and_keeps_unicode_without_credentials_in_url(self):
        with fake_server(response()) as (url, server):
            result = evaluate.Omlx(url).stream("model", make_suite.cases()[0])
            self.assertEqual(result["text"], "日本語")
            self.assertEqual(result["finishReason"], "stop")
            self.assertEqual(result["usage"]["completion_tokens"], 3)
            self.assertTrue(server.last_request["stream"])
            self.assertEqual(server.last_request["seed"], 2026)
            self.assertGreaterEqual(result["ttftSeconds"], 0)
        for url in ["file:///tmp/model", "http://user:password@localhost", "http://localhost/?key=value"]:
            with self.assertRaises(ValueError):
                evaluate.Omlx(url)

    def test_streaming_rejects_missing_done_usage_cache_hits_and_invalid_json(self):
        bodies = [response().replace(b"data: [DONE]\n\n", b""), response(cached=10), b"data: {invalid}\n\n",
                  event({"choices": [{"delta": {"content": "x"}, "finish_reason": "stop"}]}) + b"data: [DONE]\n\n",
                  b"data: " + b"x" * 65536 + b"\n"]
        for body in bodies:
            with fake_server(body) as (url, _):
                with self.assertRaises(ValueError):
                    evaluate.Omlx(url).stream("model", make_suite.cases()[0])

    def test_tool_call_deltas_are_reassembled(self):
        body = event({"choices": [{"delta": {"tool_calls": [{"index": 0, "id": "call_1", "function": {"name": "add", "arguments": '{"a":'}}]}}]})
        body += event({"choices": [{"delta": {"tool_calls": [{"index": 0, "function": {"arguments": '2,"b":3}'}}]}, "finish_reason": "tool_calls"}],
                       "usage": {"prompt_tokens": 5, "completion_tokens": 4, "prompt_tokens_details": {"cached_tokens": 0}}}) + b"data: [DONE]\n\n"
        with fake_server(body) as (url, _):
            result = evaluate.Omlx(url).stream("model", next(row for row in make_suite.cases() if row["category"] == "tool"))
            self.assertEqual(json.loads(result["toolCalls"][0]["function"]["arguments"]), {"a": 2, "b": 3})

    def test_stream_preserves_explicit_sampling_and_tool_choice(self):
        case = next(row for row in make_suite.cases() if row["category"] == "tool")
        with fake_server(response()) as (url, server):
            evaluate.Omlx(url).stream("model", case, temperature=.7, seed=1, top_p=.8, tool_choice="none")
            request = server.last_request
            self.assertEqual((request["temperature"], request["seed"], request["top_p"], request["tool_choice"]), (.7, 1, .8, "none"))
            self.assertEqual(request["tools"], case["tools"])
            evaluate.Omlx(url).stream("model", case, top_p=None)
            self.assertNotIn("top_p", server.last_request)
            self.assertNotIn("tool_choice", server.last_request)
        for top_p in [0, 1.1, math.nan]:
            with self.assertRaises(ValueError):
                evaluate.Omlx("http://localhost").stream("model", case, top_p=top_p)

    def test_model_loading_retries_only_memory_admission_failures(self):
        client = evaluate.Omlx("http://localhost")
        memory_error = urllib.error.HTTPError("http://localhost", 507, "memory", {}, None)
        client.json = Mock(side_effect=[memory_error, {"status": "ok"}])
        with patch("evaluate.time.sleep") as sleep:
            self.assertEqual(client.load("pruned"), {"status": "ok"})
            sleep.assert_called_once_with(1)
        for code, settle in [(401, 20), (507, 0)]:
            error = urllib.error.HTTPError("http://localhost", code, "error", {}, None)
            client.json = Mock(side_effect=error)
            with patch("evaluate.time.sleep") as sleep, self.assertRaises(urllib.error.HTTPError):
                client.load("pruned", settle_seconds=settle)
            sleep.assert_not_called()
            error.close()

    def test_logit_gate_preserves_thresholds_and_rejects_bad_vectors(self):
        self.assertTrue(logits.compare([1.0, 2.0], [1.0, 2.001])["passed"])
        result = logits.compare([1.0], [1.1])
        self.assertFalse(result["passed"])
        self.assertEqual(result["atol"], .01)
        self.assertFalse(logits.compare([math.nan], [1.0])["passed"])
        for left, right in [([], []), ([1], [1, 2])]:
            with self.assertRaises(ValueError):
                logits.compare(left, right)

    def test_zero_baseline_contamination_and_pending_ratings_are_not_success_claims(self):
        record = {"id": "x", "category": "contamination", "trial": 0, "text": "abc", "forbiddenScalars": 0, "outputScalars": 3, "finishReason": "stop"}
        result = evaluate.summarize([record], [record], [], {"passed": True})
        self.assertEqual(result["contaminationConclusion"], "reduction_not_observable")
        self.assertEqual(result["japaneseQuality"]["state"], "pending-human-review")
        self.assertFalse(result["taskGoalsFullyAchieved"])
        with self.assertRaises(ValueError):
            evaluate.summarize([record], [record], [], {"passed": False}, [{"baseline": None, "pruned": 5}])

    def test_metrics_keep_denominators_speed_groups_and_require_complete_ratings(self):
        sampled = {"id": "sample", "category": "contamination", "trial": 0, "text": "aع", "forbiddenScalars": 1,
                   "outputScalars": 2, "forbiddenByReason": {"script_arabic": 1}, "finishReason": "stop"}
        japanese = {"id": "ja", "category": "japanese", "trial": 0, "text": "日本語", "finishReason": "stop",
                    "benchmark": True, "benchmarkLength": "short", "seconds": 2.0}
        records = [sampled, japanese]
        ratings = [{"id": "ja", "baseline": 5, "pruned": 5}]
        result = evaluate.summarize(records, records, [], {"passed": True}, ratings)
        phase = result["phases"]["baseline"]
        self.assertEqual(phase["contaminatedOutputRate"], 1.0)
        self.assertEqual(phase["forbiddenScalarRate"], .5)
        self.assertEqual(phase["forbiddenByReason"], {"script_arabic": 1})
        self.assertEqual(phase["speedByCategoryAndLength"][0]["length"], "short")
        for invalid in [[], ratings * 2, [{"id": "wrong", "baseline": 5, "pruned": 5}], [{"id": "ja", "baseline": math.nan, "pruned": 5}]]:
            with self.assertRaises(ValueError):
                evaluate.summarize(records, records, [], {"passed": True}, invalid)

    def test_setup_helpers_reject_output_path_traversal_before_mutating(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "manifest.json"
            manifest.write_text("{}")
            for script, arguments in [("setup-evaluation-tools.py", ["--output-root", "outputs/../must-not-be-created"]),
                                      ("serve-evaluation.py", ["--manifest", str(manifest), "--server-dir", "outputs/../must-not-be-created"])]:
                process = subprocess.run([sys.executable, str(SCRIPTS / script), *arguments], capture_output=True, text=True)
                self.assertNotEqual(process.returncode, 0)
                self.assertIn("beneath outputs", process.stderr)
                self.assertFalse(Path("must-not-be-created").exists())

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

    @unittest.skipIf(flash_next_draft_vocab is None, "needs numpy")
    def test_flash_next_draft_list_keeps_its_rows_and_protected_tokens(self):
        np = flash_next_draft_vocab.np
        counts = np.array([5, 0, 0, 0, 9, 3, 0, 1, 7, 0])
        kept = np.ones(10, bool)
        kept[6] = False
        protected = np.zeros(10, bool)
        protected[3] = True
        ids, added, dropped = flash_next_draft_vocab.swap(counts, [0, 1, 2, 3, 6], kept, protected, 2)
        # 6 is not kept, so four rows; 3 is protected, so only 1 and 2 make room, for the two most frequent (4, 8)
        self.assertEqual(ids.tolist(), [0, 3, 4, 8])
        self.assertEqual(added.tolist(), [4, 8])
        self.assertEqual(dropped.tolist(), [1, 2])
        ids, added, _ = flash_next_draft_vocab.swap(counts, [0, 1, 2, 3], kept, protected, 2, add=[9], remove=[0])
        self.assertIn(9, ids.tolist())
        self.assertEqual(len(ids), 4)
        self.assertNotIn(0, ids.tolist())
        with self.assertRaises(ValueError):
            flash_next_draft_vocab.swap(counts, [0, 1, 2, 3], kept, protected, 2, remove=[3])
        with tempfile.TemporaryDirectory() as directory:
            profile = Path(directory) / "p.json"
            profile.write_text("{}")
            loaded = flash_next_draft_vocab.load_profile(profile)
            self.assertEqual((loaded["base"], loaded["select"]["minCount"], loaded["protect"]["addedTokens"]), ("builtin", 2, True))
            profile.write_text('{"replace": {"order": "random"}}')
            with self.assertRaises(ValueError):
                flash_next_draft_vocab.load_profile(profile)
            corpus = Path(directory) / "c.jsonl"
            corpus.write_text('{"text": "a"}\n\n{"text": "a"}\n{"text": "b"}\n')
            self.assertEqual(flash_next_draft_vocab.corpus_texts([corpus]), ["a", "b"])
            corpus.write_text('{"body": "a"}\n')
            with self.assertRaises(ValueError):
                flash_next_draft_vocab.corpus_texts([corpus])

    def test_sandbox_platform_picks_an_available_implementation_and_never_runs_unconfined(self):
        class Fake(sandbox.Sandbox):
            def __init__(self, name, reason):
                self.name, self.reason = name, reason
            def unavailable(self):
                return self.reason
            def run(self, command, workdir, readable=(), env=None, timeout=20):
                raise AssertionError("not run")
        both = {"docker": Fake("docker", None), "sandbox-exec": Fake("sandbox-exec", "missing")}
        self.assertEqual(sandbox.platform("docker", both).name, "docker")
        with self.assertRaises(sandbox.SandboxUnavailable):
            sandbox.platform("sandbox-exec", both)
        with self.assertRaises(sandbox.SandboxUnavailable):
            sandbox.platform("none", both)
        with self.assertRaises(sandbox.SandboxUnavailable):
            sandbox.platform("docker", {"docker": Fake("docker", "no server")})

    def test_docker_sandbox_mounts_only_the_work_directory_writable_and_has_no_network(self):
        with tempfile.TemporaryDirectory() as directory:
            argv = sandbox.Docker().command("n", ["python3", "-c", "1"], directory, [Path(directory) / "missing", "/usr"], {"A": "b"})
            joined = " ".join(argv)
            self.assertIn("--network none", joined)
            self.assertIn("--read-only", argv)
            self.assertIn("--cap-drop ALL", joined)
            writable = [a for a in argv if a.startswith("type=bind") and not a.endswith(",readonly")]
            self.assertEqual(writable, [f"type=bind,src={os.path.realpath(directory)},dst={os.path.abspath(directory)}"])
            self.assertNotIn("missing", joined)
            self.assertIn(f"HOME={os.path.abspath(directory)}", argv)
            self.assertIn("A=b", argv)
            self.assertEqual(argv[-3:], ["python3", "-c", "1"])

    @unittest.skipUnless(SANDBOX, "no sandbox on this host")
    def test_sandbox_blocks_network_and_writes_outside_the_work_directory(self):
        with tempfile.TemporaryDirectory(dir=Path("outputs").resolve() if Path("outputs").is_dir() else None) as directory:
            script = Path(directory) / "probe.py"
            outside = Path(directory).parent / f"must-not-be-created-{os.getpid()}"
            script.write_text("import socket, pathlib\n"
                              "try:\n    socket.create_connection(('1.1.1.1', 80), timeout=3); print('NETWORK')\nexcept OSError:\n    print('no network')\n"
                              f"try:\n    pathlib.Path({str(outside)!r}).write_text('x'); print('WROTE')\nexcept OSError:\n    print('no write')\n"
                              "pathlib.Path('inside.txt').write_text('ok'); print('inside')\n")
            result = sandbox.platform().run(["python3", "-I", str(script)], Path(directory), timeout=60)
            self.assertEqual(result.stdout.split(), ["no", "network", "no", "write", "inside"], result.stderr)
            self.assertFalse(outside.exists())

    @unittest.skipUnless(SANDBOX, "no sandbox on this host")
    def test_python_fixed_tests_fail_wrong_code_and_deny_writes_outside_temporary_directory(self):
        case = {"kind": "chat", "language": "python", "function": "add", "tests": [{"args": [2, 3], "expected": 5}]}
        self.assertTrue(code_checks.check(case, "def add(a,b):\n return a+b", {}, Path("."))["passed"])
        self.assertFalse(code_checks.check(case, "def add(a,b):\n return a-b", {}, Path("."))["passed"])
        with tempfile.TemporaryDirectory() as directory:
            outside = Path(directory) / "must-not-be-created"
            code = f"def add(a,b):\n open({str(outside)!r},'w').write('bad')\n return a+b"
            self.assertFalse(code_checks.check(case, code, {}, Path("."))["passed"])
            self.assertFalse(outside.exists())

    @unittest.skipUnless(Path("outputs/tools/tools.json").is_file() and Path("target/cli/compiler").is_dir(), "stageCli and explicit tool setup are required")
    def test_scala_and_typescript_are_compiled_and_executable(self):
        tools = json.loads(Path("outputs/tools/tools.json").read_text())
        for language, source in [("scala", "def add(a: Int,b: Int): Int = a+b"), ("typescript", "function add(a: number,b: number): number { return a+b; }")]:
            case = {"kind": "chat", "language": language, "function": "add", "tests": [{"args": [2, 3], "expected": 5}]}
            self.assertTrue(code_checks.check(case, source, tools, Path("target/cli/compiler"))["passed"])
            self.assertFalse(code_checks.check(case, source.replace("a+b", "a-b"), tools, Path("target/cli/compiler"))["passed"])


if __name__ == "__main__":
    unittest.main()
