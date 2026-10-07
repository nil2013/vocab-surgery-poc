#!/bin/bash
# Check out upstream TensorFold at the pinned commit, apply this project's patches, and build the clone's own environment.
# Usage: scripts/flash-next/setup-tensorfold.sh   (creates $TF_CLONE, default outputs/tensorfold; refuses an existing one)
# Needs git, uv, network access, and an NVIDIA GPU with CUDA 13 (torch 2.14.1+cu130; CUDA extensions build on first serve).
set -eu
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
clone=${TF_CLONE:-$root/outputs/tensorfold}
upstream=https://github.com/ashhart/TensorFold.git
base=cb2ebf0540f42604e2759b2ddef497861e928248
case $clone in "$root"/outputs/*) ;; *) echo "clone must be under outputs"; exit 1;; esac
if [ -e "$clone" ]; then echo "$clone already exists"; exit 1; fi
git clone -q "$upstream" "$clone"
git -C "$clone" checkout -q -b vocab-surgery-ple-token-ids "$base"
git -C "$clone" am -q --committer-date-is-author-date "$here"/tensorfold-patches/*.patch
printf '/.venv/\n/.torch-extensions/\n' >> "$clone/.git/info/exclude"
uv venv -q --python 3.13 "$clone/.venv"
uv pip install -q --python "$clone/.venv/bin/python" --index-url https://download.pytorch.org/whl/cu130 \
  --extra-index-url https://pypi.org/simple "torch==2.14.1"
uv pip install -q --python "$clone/.venv/bin/python" -e "$clone[test]" ninja
"$clone/.venv/bin/python" -c "import torch, tensorfold; print('torch', torch.__version__, 'cuda', torch.version.cuda, torch.cuda.is_available())"
git -C "$clone" log --oneline "$base..HEAD"
