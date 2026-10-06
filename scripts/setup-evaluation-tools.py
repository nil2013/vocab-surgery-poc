#!/usr/bin/env python3
"""Install pinned coding-evaluation tools beneath outputs, verifying published checksums."""
import argparse
import base64
import hashlib
import json
import os
import platform
import tarfile
import tempfile
import urllib.request
from pathlib import Path

NODE = "v24.21.0"
TYPESCRIPT = "6.0.2"


def download(url, destination):
    with urllib.request.urlopen(url, timeout=60) as response, destination.open("xb") as stream:
        while chunk := response.read(1024 * 1024):
            stream.write(chunk)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-root", type=Path, default=Path("outputs/tools"))
    args = parser.parse_args()
    root = Path(os.path.abspath(args.output_root))
    if not root.is_relative_to(Path("outputs").resolve()) or root == Path("outputs").resolve():
        raise ValueError("tools must be beneath outputs")
    if any(part.is_symlink() for part in [root, *root.parents]):
        raise ValueError("symlink output is not allowed")
    root.mkdir(parents=True, exist_ok=True)
    system = {"Darwin": "darwin", "Linux": "linux"}[platform.system()]
    architecture = {"arm64": "arm64", "aarch64": "arm64", "x86_64": "x64"}[platform.machine()]
    name = f"node-{NODE}-{system}-{architecture}"
    node = root / name
    ts = root / f"typescript-{TYPESCRIPT}"
    if node.exists() or ts.exists() or (root / "tools.json").exists():
        raise ValueError("tool output already exists")
    with tempfile.TemporaryDirectory(prefix="tools-download-", dir=root) as temporary:
        tmp = Path(temporary)
        checksums = tmp / "SHASUMS256.txt"
        download(f"https://nodejs.org/dist/{NODE}/SHASUMS256.txt", checksums)
        archive = tmp / (name + ".tar.gz")
        download(f"https://nodejs.org/dist/{NODE}/{archive.name}", archive)
        expected = dict(line.split()[::-1] for line in checksums.read_text().splitlines())[archive.name]
        actual = hashlib.sha256(archive.read_bytes()).hexdigest()
        if actual != expected:
            raise ValueError("Node checksum mismatch")
        with tarfile.open(archive) as tar:
            tar.extractall(root, filter="data")
        metadata = json.load(urllib.request.urlopen(f"https://registry.npmjs.org/typescript/{TYPESCRIPT}", timeout=30))
        if metadata["version"] != TYPESCRIPT:
            raise ValueError("TypeScript version mismatch")
        package = tmp / "typescript.tgz"
        download(metadata["dist"]["tarball"], package)
        integrity = "sha512-" + base64.b64encode(hashlib.sha512(package.read_bytes()).digest()).decode()
        if integrity != metadata["dist"]["integrity"]:
            raise ValueError("TypeScript checksum mismatch")
        ts.mkdir()
        with tarfile.open(package) as tar:
            tar.extractall(ts, filter="data")
        with (root / "tools.json").open("x", encoding="utf-8") as stream:
            json.dump({"nodeVersion": NODE, "nodeSha256": actual, "node": str(node / "bin/node"),
                       "typescriptVersion": TYPESCRIPT, "typescriptIntegrity": integrity,
                       "tsc": str(ts / "package/lib/tsc.js")}, stream, indent=2)
    print(root / "tools.json")


if __name__ == "__main__":
    main()
