#!/usr/bin/env python3
"""Start an isolated loopback oMLX server with two comparison entries and prefix cache disabled."""
import argparse
import importlib.metadata
import json
import os
import socket
import subprocess
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--server-dir", type=Path, required=True)
    parser.add_argument("--port", type=int, default=8302)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    directory = Path(os.path.abspath(args.server_dir))
    if not directory.is_relative_to(Path("outputs").resolve()) or directory == Path("outputs").resolve() or directory.exists():
        raise ValueError("server directory must be new and beneath outputs")
    if any(path.is_symlink() for path in [directory, *directory.parents]):
        raise ValueError("symlink server path is not allowed")
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", args.port))
    directory.mkdir(parents=True)
    models = directory / "models"
    models.mkdir()
    (models / "baseline").symlink_to(Path(manifest["source"]).resolve(), target_is_directory=True)
    (models / "pruned").symlink_to(Path(manifest["modelOutput"]).resolve(), target_is_directory=True)
    command = [sys.executable, "-m", "omlx.cli", "serve", "--model-dir", str(models), "--base-path", str(directory / "data"),
               "--host", "127.0.0.1", "--port", str(args.port), "--no-cache", "--no-hf-cache", "--max-concurrent-requests", "1"]
    environment = dict(os.environ)
    environment["HF_HUB_OFFLINE"] = "1"
    environment["TRANSFORMERS_OFFLINE"] = "1"
    with (directory / "server.log").open("x") as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, env=environment, start_new_session=True)
    with (directory / "server.json").open("x") as stream:
        json.dump({"pid": process.pid, "url": f"http://127.0.0.1:{args.port}", "omlxVersion": importlib.metadata.version("omlx"),
                   "prefixCache": False, "concurrency": 1, "baselineModel": "baseline", "prunedModel": "pruned", "command": command}, stream, indent=2)
    print(f"Started PID {process.pid}; server metadata: {directory / 'server.json'}; log: {directory / 'server.log'}")


if __name__ == "__main__":
    main()
