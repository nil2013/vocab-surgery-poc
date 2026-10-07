#!/usr/bin/env python3
"""Score coding-bench-lite answers with the bench's own tests, running every command in the host's sandbox.

The bench's tools/run.py compiles and runs answers with subprocess; this adapter loads it (from --bench) and gives
its run_tests a subprocess whose run() goes through sandbox.platform() instead. The toolchains the bench calls
(the Python running this script, rustc with its rustup home, scala-cli with its JDK and Coursier cache) are mounted
read-only; scala-cli compiles in-process because its Bloop build server does not start under the sandbox.
The scored rows go to a new file and record which sandbox ran them.

Usage: coding_bench_score.py --bench DIR --results FILE --out FILE [--lang python,scala,rust] [--only id,...]
"""
import argparse
import importlib.util
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from sandbox import platform, python_trees  # noqa: E402


def toolchains():
    """(read-only trees, environment) for the bench's python3, rustc and scala-cli on this host."""
    readable = python_trees()
    env = {}
    path = [str(Path(sys.executable).resolve().parent), "/usr/local/bin", "/usr/bin", "/bin"]
    rustup = Path(os.environ.get("RUSTUP_HOME", Path.home() / ".rustup"))
    if rustup.is_dir():
        readable.append(rustup)
        env["RUSTUP_HOME"] = str(rustup)
    scala = shutil.which("scala-cli")
    if scala:
        readable.append(Path(scala).resolve().parent)
        path.insert(0, str(Path(scala).resolve().parent))
        coursier = Path(os.environ.get("COURSIER_CACHE", Path.home() / ".cache" / "coursier" / "v1"))
        if coursier.is_dir():
            readable.append(coursier)
            env["COURSIER_CACHE"] = str(coursier)
    java_home = os.environ.get("JAVA_HOME") or (str(Path(shutil.which("java")).resolve().parent.parent) if shutil.which("java") else None)
    if java_home:
        readable.append(Path(java_home).resolve())
        env["JAVA_HOME"] = str(Path(java_home).resolve())
        path.insert(0, str(Path(java_home).resolve() / "bin"))
    env["PATH"] = os.pathsep.join(path)
    return readable, env


class ConfinedSubprocess:
    """The part of the subprocess module run_tests uses, with run() confined by a sandbox."""

    TimeoutExpired = subprocess.TimeoutExpired

    def __init__(self, sandbox, readable, env):
        self.sandbox, self.readable, self.env = sandbox, readable, env

    def run(self, command, cwd=None, timeout=None, **_):
        command = [str(part) for part in command]
        if Path(command[0]).name == "scala-cli" and "--server=false" not in command:
            command.insert(2, "--server=false")
        return self.sandbox.run(command, Path(cwd), self.readable, self.env, timeout=timeout)


def bench_tools(bench, confined):
    spec = importlib.util.spec_from_file_location("coding_bench_run", Path(bench) / "tools" / "run.py")
    module = importlib.util.module_from_spec(spec)
    sys.dont_write_bytecode = True
    spec.loader.exec_module(module)
    module.subprocess = confined
    return module


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--bench", type=Path, required=True)
    parser.add_argument("--results", type=Path, required=True, help="generated rows (coding_bench_generate.py or run.py)")
    parser.add_argument("--out", type=Path, required=True, help="new JSONL file for the scored rows")
    parser.add_argument("--lang", default="python,scala,rust")
    parser.add_argument("--only")
    args = parser.parse_args()
    box = platform()
    readable, env = toolchains()
    run = bench_tools(args.bench, ConfinedSubprocess(box, readable, env))
    langs, only = args.lang.split(","), set(args.only.split(",")) if args.only else None
    rows = [json.loads(line) for line in args.results.read_text(encoding="utf-8").splitlines() if line]
    with args.out.open("x", encoding="utf-8") as stream:
        for row in rows:
            if row["lang"] in langs and (only is None or row["problem"] in only) and "text" in row:
                code = run.extract(row["text"], row["lang"])
                if code is None:
                    row.update(ok=False, detail="NO_CODE")
                else:
                    ok, detail, seconds = run.run_tests(row["problem"], row["lang"], code)
                    row.update(ok=ok, detail=detail, test_secs=round(seconds, 1))
                row["sandbox"] = box.name
                print(f"{row['problem']:20s} {row['lang']:6s} s{row['sample']} {'PASS' if row['ok'] else 'FAIL'} {row.get('detail', '')[:100]}", flush=True)
            stream.write(json.dumps(row, ensure_ascii=False) + "\n")
    scored = [r for r in rows if r.get("sandbox")]
    print(json.dumps({"sandbox": box.name, "scored": len(scored), "pass": sum(1 for r in scored if r["ok"])}))


if __name__ == "__main__":
    main()
