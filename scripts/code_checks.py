"""Run fixed function tests in disposable directories with macOS file / network restrictions."""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def extract_code(text):
    blocks = re.findall(r"```[^\n]*\n(.*?)```", text, re.S)
    return blocks[0].strip() if blocks else text.strip()


def java_executable():
    candidates = [Path(os.environ.get("JAVA_HOME", "/nonexistent")) / "bin/java", Path("/opt/homebrew/opt/openjdk/bin/java"), Path("/usr/local/opt/openjdk/bin/java")]
    return str(next((path for path in candidates if path.is_file()), Path(shutil.which("java") or "/nonexistent")))


def restricted(command, directory, readable, timeout=20):
    sandbox = Path("/usr/bin/sandbox-exec")
    if not sandbox.is_file():
        raise RuntimeError("coding execution needs macOS sandbox-exec; no unrestricted fallback")
    roots = ["/System", "/usr", "/bin", "/sbin", "/dev", "/Library", "/opt/homebrew", "/private/preboot", "/private/var/db/dyld", str(directory), *map(str, readable)]
    parents = {str(parent) for root in roots for parent in Path(root).parents} | {"/var", "/etc", "/tmp", "/etc/localtime", "/private/etc/localtime"}
    allowed = " ".join(f"(subpath {json.dumps(root)})" for root in roots) + " " + " ".join(f"(literal {json.dumps(parent)})" for parent in sorted(parents))
    profile = f'(version 1)(allow default)(deny network*)(deny file-read-data (require-not (require-any {allowed})))(deny file-write* (require-not (subpath {json.dumps(str(directory))})))'
    environment = {"PATH": os.defpath, "TMPDIR": str(directory), "LANG": "en_US.UTF-8"}
    return subprocess.run([str(sandbox), "-p", profile, *map(str, command)], cwd=directory, env=environment,
                          capture_output=True, text=True, timeout=timeout)


def scala_value(value):
    if value is None:
        return "None"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, list):
        return "Vector(" + ",".join(scala_value(item) for item in value) + ")"
    return json.dumps(value, ensure_ascii=True)


def check(case, text, tools, compiler):
    code = extract_code(text)
    if case["kind"] == "completion":
        code = case["prefix"] + text + case["suffix"]
    try:
        with tempfile.TemporaryDirectory(prefix="vocab-code-") as temporary:
            directory = Path(temporary).resolve()
            language = case["language"]
            if language == "python":
                script = directory / "case.py"
                harness = "\n".join(f"assert {case['function']}(*{test['args']!r}) == {test['expected']!r}" for test in case["tests"])
                script.write_text(code + "\n" + harness + '\nprint("VOCAB_TEST_PASS")\n', encoding="utf-8")
                result = restricted([sys.executable, "-I", script], directory, [Path(sys.executable).resolve().parent.parent])
            elif language == "scala":
                asserts = []
                for test in case["tests"]:
                    arguments = ",".join(scala_value(value) for value in test["args"])
                    expected = scala_value(test["expected"])
                    if case.get("scalaReturnType", "").startswith("Option[") and test["expected"] is not None:
                        expected = f"Some({expected})"
                    asserts.append(f"assert({case['function']}({arguments}) == {expected})")
                script = directory / "Runner.scala"
                script.write_text("object Runner {\n" + code + '\ndef main(args: Array[String]): Unit = {\n' + "\n".join(asserts) + '\nprintln("VOCAB_TEST_PASS")\n}\n}\n', encoding="utf-8")
                classpath = str(compiler.resolve() / "*")
                compile_result = restricted([java_executable(), "-Xmx384m", "-Djava.io.tmpdir=" + str(directory), "-cp", classpath,
                                             "dotty.tools.dotc.Main", "-classpath", classpath, "-d", directory, script], directory, [compiler.resolve()], timeout=30)
                if compile_result.returncode:
                    return {"passed": False, "phase": "compile", "detail": compile_result.stderr[-4000:]}
                result = restricted([java_executable(), "-Xmx128m", "-cp", str(directory) + os.pathsep + classpath, "Runner"], directory, [compiler.resolve()])
            elif language == "typescript":
                node, tsc = Path(tools["node"]), Path(tools["tsc"])
                script = directory / "case.ts"
                script.write_text(code, encoding="utf-8")
                compile_result = restricted([node, tsc, "--strict", "--target", "es2022", "--module", "nodenext", "--moduleResolution", "nodenext", "--outDir", directory, script],
                                            directory, [node.parent.parent, tsc.parent.parent], timeout=30)
                if compile_result.returncode:
                    return {"passed": False, "phase": "compile", "detail": (compile_result.stdout + compile_result.stderr)[-4000:]}
                runner = directory / "runner.cjs"
                runner.write_text("const vm=require('node:vm'), fs=require('node:fs');\nconst context=vm.createContext({exports:{}},{codeGeneration:{strings:false,wasm:false}});\n"
                                  "vm.runInContext(fs.readFileSync('case.js','utf8'),context,{timeout:1000});\n" +
                                  "\n".join("if(vm.runInContext(" + json.dumps(f"JSON.stringify({case['function']}(...{json.dumps(test['args'])}))") +
                                             ",context,{timeout:1000})!==" + json.dumps(json.dumps(test["expected"], separators=(",", ":"))) + ")throw new Error('test failed');" for test in case["tests"]) +
                                  '\nconsole.log("VOCAB_TEST_PASS");\n', encoding="utf-8")
                result = restricted([node, "--max-old-space-size=128", "--permission", "--allow-fs-read=" + str(directory), runner], directory, [node.parent.parent])
            else:
                raise ValueError("unsupported coding language")
            return {"passed": result.returncode == 0 and "VOCAB_TEST_PASS" in result.stdout, "phase": "execute", "detail": (result.stdout + result.stderr)[-4000:]}
    except (subprocess.TimeoutExpired, OSError, ValueError) as error:
        return {"passed": False, "phase": "execute", "detail": str(error)}
