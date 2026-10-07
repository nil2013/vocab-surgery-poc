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
import evaluate_testdata
import check_tokenizer
try:
    import greek_fragments
except ImportError:
    greek_fragments = None
import claude_transcript_cases
import probe_replies
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


flash_next_acceptance = flash_next("acceptance")
flash_next_bench = flash_next("bench")
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

    def test_stream_preserves_testdata_sampling_and_tool_choice(self):
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

    @unittest.skipUnless(Path("test-data/cases/synthetic/suite.json").is_file(), "test-data submodule required")
    def test_testdata_inputs_and_fixed_scoring_contract(self):
        suite, replay, checks, metrics = evaluate_testdata.load_data(Path("test-data"))
        self.assertEqual((len(suite["tests"]), len(replay)), (26, 35))
        self.assertEqual(set(checks), {"lru", "count_by_year"})
        cases = {case["id"]: case for case in suite["tests"]}
        for identifier in ["verbatim_ja", "echo_greek_math"]:
            expected = cases[identifier]["expect"]
            self.assertTrue(evaluate_testdata.score_synthetic(cases[identifier], {"text": expected}, checks)["passed"])
            self.assertFalse(evaluate_testdata.score_synthetic(cases[identifier], {"text": "wrong"}, checks)["passed"])
        self.assertTrue(evaluate_testdata.score_synthetic(cases["translate_in_ko"], {"text": "会議は3時。資料を共有。"}, checks)["passed"])
        self.assertFalse(evaluate_testdata.score_synthetic(cases["translate_in_ko"], {"text": "会議は3時。"}, checks)["passed"])
        self.assertEqual(metrics("日本語ع", "ع")["leak"], 0)
        self.assertEqual(metrics("日本語ع", "")["leak"], 1)

    def test_testdata_pairs_require_matched_coverage_and_keep_nonconcluded_outputs_separate(self):
        row = {"id": "case", "sample": 0, "group": "replay", "response": {"text": "日本語", "finishReason": "stop"},
               "metrics": {"lang": "ja", "leak": 0}}
        changed = {**row, "response": {"text": "中国語", "finishReason": "tool_calls"}}
        pairs = evaluate_testdata.paired_changes([row], [changed])
        self.assertFalse(pairs[0]["textIdentical"])
        self.assertEqual(pairs[0]["firstScalarDifference"], 0)
        self.assertEqual(evaluate_testdata.summarize_phase([row, changed])["replayConcluded"], 1)
        for after in [[], [{**changed, "sample": 1}]]:
            with self.assertRaises(ValueError):
                evaluate_testdata.paired_changes([row], after)

    def test_testdata_template_parses_argument_strings_without_changing_api_input(self):
        messages = [{"role": "assistant", "content": None, "tool_calls": [
            {"function": {"name": "read_file", "arguments": '{"path":"設定.py"}'}}]}]
        normalized = evaluate_testdata.template_messages(messages)
        self.assertEqual(normalized[0]["tool_calls"][0]["function"]["arguments"], {"path": "設定.py"})
        self.assertIsInstance(messages[0]["tool_calls"][0]["function"]["arguments"], str)
        self.assertEqual(evaluate_testdata.template_messages(normalized), normalized)
        for arguments in ["[]", "not-json"]:
            messages[0]["tool_calls"][0]["function"]["arguments"] = arguments
            with self.assertRaises(ValueError):
                evaluate_testdata.template_messages(messages)

    def test_baseline_reuse_requires_full_coverage_and_matching_provenance(self):
        keys = ["source", "pruned", "manifestSha256", "testDataRevision", "inputHashes", "server", "packageVersions",
                "replaySamples", "benchmarkTrials", "benchmarkWarmups", "concurrency"]
        environment = {key: "same" for key in keys}
        expected = [("synthetic", "case", 0)]
        rows = [{"group": "synthetic", "id": "case", "sample": 0}]
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "environment.json").write_text(json.dumps(environment))
            (directory / "baseline.jsonl").write_text(json.dumps(rows[0]) + "\n")
            (directory / "benchmark-cases.json").write_text("[]")
            self.assertEqual(evaluate_testdata.load_baseline(directory, environment, expected, []), rows)
            with self.assertRaises(ValueError):
                evaluate_testdata.load_baseline(directory, {**environment, "manifestSha256": "different"}, expected, [])
            with self.assertRaises(ValueError):
                evaluate_testdata.load_baseline(directory, environment, expected * 2, [])
            with self.assertRaises(ValueError):
                evaluate_testdata.load_baseline(directory, environment, expected, [{"id": "changed"}])

    @unittest.skipUnless(Path("test-data/cases/synthetic/suite.json").is_file() and SANDBOX, "test-data and a sandbox required")
    def test_testdata_fixed_python_harness_runs_restricted_and_fails_wrong_code(self):
        suite, _, checks, _ = evaluate_testdata.load_data(Path("test-data"))
        case = next(case for case in suite["tests"] if case["id"] == "code_count_by_year")
        code = "import datetime\ndef count_by_year(dates):\n result = {}\n for text in dates:\n  try:\n   year = datetime.date.fromisoformat(text).year\n  except ValueError:\n   continue\n  result[year] = result.get(year, 0) + 1\n return result\n"
        self.assertTrue(evaluate_testdata.score_synthetic(case, {"text": "```python\n" + code + "```"}, checks)["passed"])
        self.assertFalse(evaluate_testdata.score_synthetic(case, {"text": "```python\ndef count_by_year(dates): return {}\n```"}, checks)["passed"])

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

    @unittest.skipIf(greek_fragments is None, "needs the regex module")
    def test_greek_fragments_keep_single_letters_and_unaccented_pairs(self):
        rows = [{"compactId": i, "surface": s, "protection": []} for i, s in enumerate(["α", " μ", "ου", "εί", "τικ", " προ", "abc"])]
        rows += [{"compactId": None, "surface": "ται", "protection": []}, {"compactId": 9, "surface": "ουν", "protection": ["policy_math"]}]
        self.assertEqual(sorted(greek_fragments.fragments(json.dumps(r) for r in rows)), [" προ", "εί", "τικ"])

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

    def test_flash_next_acceptance_follows_sending_order_and_rejects_misalignment(self):
        def line(tokens, rounds, accepted, proposed):
            return (f"[tensorfold] done req-1 prompt=9 cached=0 thinking=False tokens={tokens} sha=0 finish=stop "
                    f"tok/s=50.0 ttft=0.1s prefill=0.1s rounds={rounds} accepted={accepted}/{proposed}")
        usage = lambda n: {"usage": {"completion_tokens": n}}
        synthetic = {"results": [{"id": "tool", "response": usage(4), "followup": usage(2)},
                                 {"id": "leak", "samples": [usage(6), usage(8)]}]}
        bench = {"rows": [{"prompt": "ja", "draft": True, "trial": 0, "completion_tokens": 5},
                          {"prompt": "ja", "draft": False, "trial": 0, "completion_tokens": 5}]}
        probe = [{"case": "ko_fix", "setting": "t0", **usage(3)}]
        sent = (list(flash_next_acceptance.synthetic_requests(synthetic)) + list(flash_next_acceptance.bench_requests(bench))
                + list(flash_next_acceptance.probe_requests(probe)))
        self.assertEqual([name for _, name, _ in sent],
                         ["tool", "tool/followup", "leak#0", "leak#1", "bench:ja/mtp#0", "bench:ja/serial#0", "ko_fix/t0"])
        logged = flash_next_acceptance.log_requests(["noise", line(4, 2, 2, 3), line(2, 1, 1, 1), line(6, 4, 2, 6), line(8, 4, 4, 6),
                                             line(5, 2, 3, 3), line(5, 5, 0, 0), line(3, 1, 2, 2)])
        totals = flash_next_acceptance.totals(flash_next_acceptance.attribute(logged, sent))
        self.assertEqual(totals["leak"]["accepted"], 6)
        self.assertEqual(totals["leak"]["proposed"], 12)
        self.assertEqual(totals["tool"]["tokensPerRound"], 2.0)
        self.assertNotIn("bench:ja/serial", totals)
        with self.assertRaises(ValueError):
            flash_next_acceptance.attribute(logged[:-1], sent)
        with self.assertRaises(ValueError):
            flash_next_acceptance.attribute([dict(logged[0], tokens=9)] + logged[1:], sent)

    def test_flash_next_bench_summary_leaves_out_warmups(self):
        rows = [{"prompt": p, "draft": d, "trial": t, "warmup": t == 0, "ttft": 0.1, "total": 1.1, "decode_tps": 10.0 * (t + 1),
                 "completion_tokens": 4, "chars": 10} for p in flash_next_bench.PROMPTS for d in (True, False) for t in range(flash_next_bench.TRIALS)]
        summary = flash_next_bench.summarize(rows)
        self.assertEqual(summary["ja/mtp"]["decode_tps_median"], 30.0)
        self.assertEqual(len(summary["code/serial"]["completion_tokens"]), flash_next_bench.TRIALS - 1)

    @unittest.skipIf(flash_next_draft_vocab is None, "needs numpy and pyyaml")
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
            profile = Path(directory) / "p.yaml"
            profile.write_text("corpus:\n  - path: x\n")
            loaded = flash_next_draft_vocab.load_profile(profile)
            self.assertEqual((loaded["base"], loaded["select"]["minCount"], loaded["protect"]["addedTokens"]), ("builtin", 2, True))
            profile.write_text("corpus:\n  - path: x\nreplace:\n  order: random\n")
            with self.assertRaises(ValueError):
                flash_next_draft_vocab.load_profile(profile)

    def test_transcript_cases_start_at_typed_requests_and_cut_after_tool_results(self):
        rec = lambda kind, content, **extra: json.dumps({"type": kind, "message": {"role": kind, "content": content, **extra}})
        lines = [rec("user", "<local-command-caveat>x</local-command-caveat>"),
                 rec("user", "ファイルを読んで"),
                 rec("assistant", [{"type": "thinking", "thinking": ""}], id="m1"),
                 rec("assistant", [{"type": "text", "text": "読みます"}], id="m1"),
                 rec("assistant", [{"type": "tool_use", "id": "t1", "name": "Read", "input": {"file_path": "a.py"}}], id="m1"),
                 rec("user", [{"type": "tool_result", "tool_use_id": "t1", "content": "print(1)" * 10}]),
                 rec("assistant", [{"type": "tool_use", "id": "t2", "name": "Bash", "input": {"command": "ls"}}], id="m2"),
                 rec("user", [{"type": "tool_result", "tool_use_id": "t2", "content": [{"type": "text", "text": "a.py"}]}]),
                 rec("user", "[Request interrupted by user]"),
                 rec("user", "ありがとう")]
        evs = claude_transcript_cases.events(lines)
        self.assertEqual(claude_transcript_cases.candidates(evs), [(0, 2)])
        messages = claude_transcript_cases.case_messages(evs, 0, 1, 20)
        self.assertEqual([m["role"] for m in messages], ["system", "user", "assistant", "tool"])
        self.assertEqual(messages[2]["content"], "読みます")
        self.assertEqual(json.loads(messages[2]["tool_calls"][0]["function"]["arguments"]), {"file_path": "a.py"})
        self.assertTrue(messages[3]["content"].endswith("[... 60 more characters]"))
        tools = claude_transcript_cases.tool_definitions(claude_transcript_cases.case_messages(evs, 0, 2, 100))
        self.assertEqual([t["function"]["name"] for t in tools], ["Bash", "Read"])
        self.assertEqual(list(tools[1]["function"]["parameters"]["properties"]), ["file_path"])
        with tempfile.TemporaryDirectory() as directory:
            case_dir = Path(directory) / "cases" / "replay"
            case_dir.mkdir(parents=True)
            (case_dir / "cc_00.json").write_text(json.dumps({"id": "cc_00", "request": {"messages": messages, "tools": tools}}))
            name, shown, sent, sent_tools = next(probe_replies.requests(directory))
            self.assertEqual((name, shown, len(sent), len(sent_tools)), ("cc_00", "ファイルを読んで", 4, 2))
            self.assertIn("a.py", probe_replies.context_text(sent))

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
