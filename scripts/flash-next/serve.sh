#!/bin/bash
# Serve one checkpoint with the experimental clone in outputs, run a command against it, then stop the server.
# Usage: scripts/flash-next/serve.sh <server log> <checkpoint> <command...>
# The clone comes from setup-tensorfold.sh; the server listens on 127.0.0.1:$TF_PORT. CUDA tools come from $CUDA_HOME.
# TF_SERVE_ARGS adds serve options, such as --draft-vocab outputs/runs/<run>/draft.txt.
set -u
log=$1 model=$2; shift 2
root=$(cd "$(dirname "$0")/../.." && pwd)
clone=${TF_CLONE:-$root/outputs/tensorfold} port=${TF_PORT:-18080}
export TORCH_EXTENSIONS_DIR=$clone/.torch-extensions PATH=${CUDA_HOME:-/usr/local/cuda}/bin:$PATH TF_BASE=http://127.0.0.1:$port/v1
if curl -sf "$TF_BASE/models" >/dev/null; then echo "port $port is already serving"; exit 1; fi
# shellcheck disable=SC2086 # TF_SERVE_ARGS holds extra serve options, split on spaces (e.g. --draft-vocab FILE)
"$clone/.venv/bin/tensorfold" serve "$model" --host 127.0.0.1 --port "$port" --name fn --ple-on-ssd --context 65536 ${TF_SERVE_ARGS:-} > "$log" 2>&1 &
server=$!
trap 'kill $server 2>/dev/null; wait $server 2>/dev/null' EXIT
for i in $(seq 1 180); do
  curl -sf "$TF_BASE/models" >/dev/null && break
  kill -0 $server 2>/dev/null || { echo "server exited"; tail -20 "$log"; exit 1; }
  sleep 5
done
curl -sf "$TF_BASE/models" >/dev/null || { echo "server not ready"; exit 1; }
echo "ready after $((i * 5))s"
"$@"
status=$?
echo "command exit $status"
exit $status
