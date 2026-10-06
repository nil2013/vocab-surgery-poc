#!/bin/bash
# Run the test-data synthetic cases and the speed bench on one checkpoint served by the experimental clone.
# Usage: scripts/tf-evaluate.sh <new or existing run directory under outputs> <label> <checkpoint>
# The run directory gets a copy of test-data without results, patched so code cases are kept but not executed
# (no sandbox is available on this host). Results: <label>-server.log, test-data/results/synthetic/<label>/, <label>-bench.json.
set -u
run=$1 label=$2 model=$3
scripts=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$run"
run=$(cd "$run" && pwd)
case $run in "$(dirname "$scripts")"/outputs/*) ;; *) echo "run directory must be under outputs"; exit 1;; esac
if [ ! -d "$run/test-data" ]; then
  mkdir "$run/test-data"
  (cd "$scripts/../test-data" && tar cf - --exclude=./results --exclude=./.git .) | (cd "$run/test-data" && tar xf -)
  patch -s -d "$run/test-data" -p1 < "$scripts/testdata-no-exec.patch" || exit 1
fi
"$scripts/tf-serve.sh" "$run/$label-server.log" "$model" sh -c '
  python3 "$1/test-data/tools/run_synthetic.py" --base "$TF_BASE" --model fn --label "$2" --par 1 \
    --extra "{\"chat_template_kwargs\":{\"enable_thinking\":false}}" > "$1/$2-synthetic.log" 2>&1 || exit 1
  python3 "$3/tf_bench.py" "$TF_BASE" "$1/test-data" "$1/$2-bench.json" > "$1/$2-bench.log" 2>&1' sh "$run" "$label" "$scripts"
