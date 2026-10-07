#!/bin/sh
# Inspect, plan, apply and re-verify one surgery under outputs/runs/NAME and outputs/models/NAME,
# optionally translating a token-ID list (e.g. an MTP draft vocabulary) into the published IDs.
set -eu
usage() {
  echo 'Usage: scripts/run-surgery.sh --source SNAPSHOT --name NAME [--policy POLICY.json] [--python PYTHON] [--translate-ids IDS.txt] [--copy-only]' >&2
  exit 2
}
source='' name='' policy='' python='' ids='' copy=''
while [ "$#" -gt 0 ]; do
  case "$1" in
    --source) [ "$#" -ge 2 ] || usage; source=$2; shift 2 ;;
    --name) [ "$#" -ge 2 ] || usage; name=$2; shift 2 ;;
    --policy) [ "$#" -ge 2 ] || usage; policy=$2; shift 2 ;;
    --python) [ "$#" -ge 2 ] || usage; python=$2; shift 2 ;;
    --translate-ids) [ "$#" -ge 2 ] || usage; ids=$2; shift 2 ;;
    --copy-only) copy=--copy-only; shift ;;
    *) usage ;;
  esac
done
[ -n "$source" ] && [ -n "$name" ] || usage
case "$name" in */*|.*|'') echo 'NAME must be a single directory name' >&2; exit 2 ;; esac
REPO_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
# Resolve user paths before changing directory, so relative arguments keep their meaning.
absolute() { case "$1" in /*) printf '%s\n' "$1" ;; *) printf '%s/%s\n' "$(pwd -P)" "$1" ;; esac; }
source=$(absolute "$source")
[ -z "$policy" ] || policy=$(absolute "$policy")
[ -z "$ids" ] || ids=$(absolute "$ids")
[ -z "$python" ] || python=$(absolute "$python")
cd "$REPO_DIR"
[ -n "$python" ] || python="$REPO_DIR/.venv/bin/python"
[ -x "$python" ] || { echo "Python with tokenizers and transformers not found: $python (see README)" >&2; exit 2; }
run="outputs/runs/$name"
cli=scripts/vocab-surgery
set -x
"$cli" inspect --source "$source" --run-dir "$run"
if [ -n "$policy" ]; then "$cli" plan --source "$source" --run-dir "$run" --policy "$policy"; else "$cli" plan --source "$source" --run-dir "$run"; fi
"$cli" apply --plan "$run/plan.json" --model-out "outputs/models/$name" --python "$python" $copy
"$cli" verify --manifest "$run/manifest.json" --python "$python" --report "$run/reverification.json"
if [ -n "$ids" ]; then "$cli" translate-ids --manifest "$run/manifest.json" --ids "$ids" --out "$run/translated-ids.txt"; fi
