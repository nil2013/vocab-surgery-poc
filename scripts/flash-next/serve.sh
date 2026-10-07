#!/bin/sh
# Serve one Flash-Next checkpoint with the experimental clone in outputs, run a command against it, then stop.
# Usage: scripts/flash-next/serve.sh <new server dir under outputs> <checkpoint> [serve options...] [-- command...]
# A shortcut for `vocab-surgery serve --runtime tensorfold --model fn=<checkpoint>`; the command sees VOCAB_ENDPOINT
# and VOCAB_MODELS. Serve options include --draft-vocab FILE, --port N and --tensorfold-clone DIR.
set -eu
[ "$#" -ge 2 ] || { sed -n '3,5p' "$0" >&2; exit 2; }
directory=$1 model=$2; shift 2
exec "$(dirname "$0")/../vocab-surgery" serve --runtime tensorfold --model "fn=$model" --server-dir "$directory" "$@"
