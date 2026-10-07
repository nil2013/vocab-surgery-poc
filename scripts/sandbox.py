"""Confined execution of generated code, whose availability depends on the OS and its container runtime.

A `Sandbox` runs a command with no network, writes confined to one work directory (also its HOME, TMPDIR and XDG
directories), and reads limited to the system, the work directory and the trees the caller lists. Each
implementation names itself so results can record which one ran. `platform()` picks one for the running host, or
the one named by VOCAB_SANDBOX; when none is available it raises instead of running anything unconfined.
"""
import abc
import json
import os
import platform as host
import shutil
import subprocess
import sys
import uuid
from pathlib import Path

DOCKER_IMAGE = "vocab-surgery-sandbox:empty"


class SandboxUnavailable(RuntimeError):
    pass


class Sandbox(abc.ABC):
    """Run commands confined as described in the module docstring. Implementations never fall back to running unconfined."""

    name = ""

    @abc.abstractmethod
    def unavailable(self):
        """Why this implementation cannot run on this host, or None."""

    @abc.abstractmethod
    def run(self, command, workdir, readable=(), env=None, timeout=20):
        """Run ``command`` in ``workdir`` and return the completed process with text output."""


def confined_environment(workdir, env=None):
    """The environment every implementation gives a command: home and scratch inside the work directory."""
    workdir = str(workdir)
    base = {"PATH": os.defpath, "LANG": "en_US.UTF-8", "HOME": workdir, "TMPDIR": workdir,
            "XDG_CACHE_HOME": f"{workdir}/.cache", "XDG_CONFIG_HOME": f"{workdir}/.config",
            "XDG_DATA_HOME": f"{workdir}/.local/share", "XDG_STATE_HOME": f"{workdir}/.local/state",
            "XDG_RUNTIME_DIR": f"{workdir}/.run"}
    return {**base, **(env or {})}


class MacSandboxExec(Sandbox):
    """macOS sandbox-exec with a profile that denies the network, writes outside the work directory and reads
    outside the system and listed trees."""

    name = "sandbox-exec"
    executable = Path("/usr/bin/sandbox-exec")
    system = ["/System", "/usr", "/bin", "/sbin", "/dev", "/Library", "/opt/homebrew", "/private/preboot", "/private/var/db/dyld"]

    def unavailable(self):
        return None if self.executable.is_file() else f"{self.executable} is missing (macOS only)"

    def profile(self, workdir, readable):
        roots = [*self.system, str(workdir), *map(str, readable)]
        parents = {str(parent) for root in roots for parent in Path(root).parents} | {"/var", "/etc", "/tmp", "/etc/localtime", "/private/etc/localtime"}
        allowed = " ".join(f"(subpath {json.dumps(root)})" for root in roots) + " " + " ".join(f"(literal {json.dumps(parent)})" for parent in sorted(parents))
        return (f'(version 1)(allow default)(deny network*)(deny file-read-data (require-not (require-any {allowed})))'
                f'(deny file-write* (require-not (subpath {json.dumps(str(workdir))})))')

    def run(self, command, workdir, readable=(), env=None, timeout=20):
        return subprocess.run([str(self.executable), "-p", self.profile(workdir, readable), *map(str, command)], cwd=workdir,
                              env=confined_environment(workdir, env), capture_output=True, text=True, timeout=timeout)


class Docker(Sandbox):
    """A Linux container from an empty image: the host's system trees and listed trees mounted read-only, the work
    directory read-write at the same path, no network, no capabilities, the caller's uid, bounded memory and pids."""

    name = "docker"
    system = ["/usr", "/etc", "/bin", "/sbin", "/lib", "/lib32", "/lib64", "/libx32"]

    def __init__(self, docker="docker", image=DOCKER_IMAGE, memory="4g", pids=1024):
        self.docker, self.image, self.memory, self.pids = docker, image, memory, pids

    def unavailable(self):
        if host.system() != "Linux":
            return "mounts the host's system trees, so it needs a Linux host"
        if shutil.which(self.docker) is None:
            return f"{self.docker} is not on PATH"
        probe = subprocess.run([self.docker, "version", "--format", "{{.Server.Version}}"], capture_output=True, text=True)
        return None if probe.returncode == 0 else f"docker server unreachable: {probe.stderr.strip()[:200]}"

    def ensure_image(self):
        if subprocess.run([self.docker, "image", "inspect", self.image], capture_output=True).returncode != 0:
            built = subprocess.run([self.docker, "build", "-q", "-t", self.image, "-"], input="FROM scratch\n", capture_output=True, text=True)
            if built.returncode != 0:
                raise SandboxUnavailable(f"cannot build {self.image}: {built.stderr.strip()[:300]}")

    def mounts(self, workdir, readable):
        """(source, target, read-only) bind mounts: system trees and listed trees read-only, the work directory writable."""
        seen, out = set(), []
        for path in [*self.system, *map(str, readable)]:
            if os.path.exists(path) and path not in seen:
                seen.add(path)
                out.append((os.path.realpath(path), os.path.abspath(path), True))
        out.append((os.path.realpath(workdir), os.path.abspath(workdir), False))
        return out

    def command(self, name, command, workdir, readable=(), env=None):
        argv = [self.docker, "run", "--rm", "--name", name, "--network", "none", "--read-only", "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges", "--pids-limit", str(self.pids), "--memory", str(self.memory),
                "--user", f"{os.getuid()}:{os.getgid()}", "--tmpfs", "/tmp:rw,exec,mode=1777,size=512m",
                "--workdir", os.path.abspath(workdir)]
        for source, target, read_only in self.mounts(workdir, readable):
            argv += ["--mount", f"type=bind,src={source},dst={target}" + (",readonly" if read_only else "")]
        for key, value in confined_environment(os.path.abspath(workdir), env).items():
            argv += ["--env", f"{key}={value}"]
        return [*argv, self.image, *map(str, command)]

    def run(self, command, workdir, readable=(), env=None, timeout=20):
        self.ensure_image()
        name = f"vocab-sandbox-{uuid.uuid4().hex[:12]}"
        try:
            return subprocess.run(self.command(name, command, workdir, readable, env), capture_output=True, text=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            subprocess.run([self.docker, "rm", "-f", name], capture_output=True)
            raise


def python_trees(executable=None):
    """The trees an interpreter needs inside a sandbox: a virtual environment, its base installation and every
    directory its binary's symlinks pass through, each kept at the path the interpreter uses (uv aliases them)."""
    executable = Path(os.path.abspath(executable or sys.executable))
    trees = [Path(sys.prefix), Path(sys.base_prefix)] if executable == Path(os.path.abspath(sys.executable)) else []
    hop = executable
    for _ in range(40):
        trees.append(hop.parent.parent)
        if not hop.is_symlink():
            break
        hop = Path(os.path.abspath(hop.parent / os.readlink(hop)))
    trees.append(executable.resolve().parent.parent)
    return list(dict.fromkeys(Path(os.path.abspath(tree)) for tree in trees))


IMPLEMENTATIONS = {"sandbox-exec": MacSandboxExec, "docker": Docker}
PREFERENCE = {"Darwin": ["sandbox-exec"], "Linux": ["docker"]}


def platform(choice=None, implementations=None):
    """The sandbox named by ``choice`` (default VOCAB_SANDBOX, then "auto": the first available for this OS)."""
    implementations = implementations or {name: cls() for name, cls in IMPLEMENTATIONS.items()}
    choice = choice or os.environ.get("VOCAB_SANDBOX", "auto")
    names = PREFERENCE.get(host.system(), []) if choice == "auto" else [choice]
    reasons = []
    for name in names:
        if name not in implementations:
            raise SandboxUnavailable(f"unknown sandbox {name!r}; choose from {sorted(implementations)}")
        reason = implementations[name].unavailable()
        if reason is None:
            return implementations[name]
        reasons.append(f"{name}: {reason}")
    raise SandboxUnavailable("no sandbox for generated code on this host; not running it unconfined (" + "; ".join(reasons or [host.system()]) + ")")
