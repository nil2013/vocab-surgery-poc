#!/bin/sh
set -eu
if [ "$#" -ne 4 ]; then
  echo 'Usage: scripts/run-surgery.sh SOURCE RUN_DIR MODEL_OUT PYTHON' >&2
  exit 2
fi
REPO_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
cd "$REPO_DIR"
scripts/vocab-surgery inspect --source "$1" --run-dir "$2"
scripts/vocab-surgery plan --source "$1" --run-dir "$2"
scripts/vocab-surgery apply --plan "$2/plan.json" --model-out "$3" --python "$4"
scripts/vocab-surgery verify --manifest "$2/manifest.json" --python "$4" --report "$2/reverification.json"
