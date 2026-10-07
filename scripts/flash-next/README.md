# Flash-Next 専用の評価道具

Qwen3.8-Flash-Next の checkpoint（元モデルと加工モデル）を serve して評価するための道具である。Flash-Next 以外のモデルには使わない。汎用の helper は `scripts/` の直下にある。

Flash-Next は oMLX では serve できない。そこで、upstream の TensorFold を固定した commit で取得し、このディレクトリの patch を当てた clone を検証用に使う。clone は本番の serve 環境とは別物で、production 用途には使わない。

## 前提

- NVIDIA GPU と CUDA 13 の toolkit（`nvcc`）。起動時の見積もりで約 84 GiB の GPU メモリを使う（GB10 で実行確認）。
- `git`、`uv`、ネットワーク接続（初回の取得と torch のインストール）。
- CUDA 拡張は、最初の serve のときに clone 内の `.torch-extensions` に build される。

## ファイル

| ファイル | 役割 |
| --- | --- |
| `setup-tensorfold.sh` | upstream（`https://github.com/ashhart/TensorFold.git`）の `cb2ebf05` を `outputs/tensorfold`（`TF_CLONE` で変更）に取得し、ブランチ `vocab-surgery-ple-token-ids` に patch を `git am` で当て、clone 内に `.venv`（Python 3.13、torch 2.14.1+cu130、editable install）を作る。既存の clone は上書きしない |
| `tensorfold-patches/` | clone に当てる改造。`ple-token-ids.json`（PLE 対応表）を読み、PLE の hash に元の語彙数と元の ID を使う。同梱の draft 語彙リストを加工後の ID に変換する |
| `serve.sh` | checkpoint を `--ple-on-ssd --context 65536` で `127.0.0.1:$TF_PORT`（既定 18080）に serve し、ready になったら任意のコマンドを `TF_BASE` 付きで実行し、終了時に止める |
| `evaluate.sh` | run directory に `test-data` の results 以外をコピーし、`testdata-no-exec.patch` を当てて、合成テスト（`--par 1`、`enable_thinking: false`）と `bench.py` を実行する。submodule は変更しない |
| `testdata-no-exec.patch` | 生成 code の sandbox がない host 向けに、code ケースのコードを記録するだけで実行せず、PASS を `null` にする |
| `bench.py` | 日本語・英語・Python の 3 prompt を、MTP あり／なし（request の `draft`）で各 4 回、temperature 0、400 tokens で送る。最初の 1 回は warm-up として集計から除く |
| `acceptance.py` | server log の `accepted=受理数/提案数` を、送った順の結果ファイルと completion tokens で照合して request に割り当て、ケースごとの MTP 受理率を出す。数が合わなければ停止する |

`setup-tensorfold.sh` で作った clone は、patch を当てた後の commit hash まで手元で作った clone（`912415a`、`8afb41b`）と一致する（実機確認）。

## 使い方

```sh
scripts/flash-next/setup-tensorfold.sh
RUN=outputs/runs/<新しい run>
SRC=models/models--Vontra--Qwen3.8-Flash-Next-MLX-4bit-MTP/snapshots/2b170fa6309d5d1ee380b35636075fac7945f286
scripts/flash-next/evaluate.sh "$RUN" original "$SRC"
scripts/flash-next/evaluate.sh "$RUN" pruned-v2 outputs/models/flash-next-ja-en-coding-v2
python3 scripts/flash-next/acceptance.py "$RUN/pruned-v2-server.log" \
  "$RUN/test-data/results/synthetic/pruned-v2/$(date +%F).json" "$RUN/pruned-v2-bench.json"
```

混在した入力への応答は、汎用の [probe_replies.py](../probe_replies.py) を `serve.sh` の下で実行して記録する。

```sh
scripts/flash-next/serve.sh "$RUN/coding-pruned-v2-server.log" outputs/models/flash-next-ja-en-coding-v2 \
  sh -c 'python3 scripts/probe_replies.py "$TF_BASE" fn "$0/test-data" scripts/prompts/mixed-ja-coding.json "$0/coding-pruned-v2.jsonl" 1000' "$RUN"
python3 scripts/flash-next/acceptance.py "$RUN/coding-pruned-v2-server.log" "$RUN/coding-pruned-v2.jsonl"
```

server は 1 本ずつ起動・停止する。複数の server を同時に立てたり、request を並列に送ったりすると、`acceptance.py` で log を request に割り当てられない。
