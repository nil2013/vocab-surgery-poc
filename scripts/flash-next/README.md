# Flash-Next 専用の評価道具

Qwen3.8-Flash-Next の checkpoint（元モデルと加工モデル）を serve して評価するための道具である。Flash-Next 以外のモデルには使わない。serve と速度・受理率の測定は production の CLI（`vocab-surgery serve`、`measure-speed`、`run-external` の `--runtime tensorfold`）にあり、ここにはその近道と、clone の環境構築だけを置く。

Flash-Next は oMLX では serve できない。そこで、upstream の TensorFold を固定した commit で取得し、このディレクトリの patch を当てた clone を検証用に使う。clone は本番の serve 環境とは別物で、production 用途には使わない。

## 前提

- NVIDIA GPU と CUDA 13 の toolkit（`nvcc`）。起動時の見積もりで約 84 GiB の GPU メモリを使う（GB10 で実行確認）。
- `git`、`uv`、ネットワーク接続（初回の取得と torch のインストール）。
- CUDA 拡張は、最初の serve のときに clone 内の `.torch-extensions` に build される。

## ファイル

| ファイル | 役割 |
| --- | --- |
| `setup-tensorfold.sh` | upstream（`https://github.com/ashhart/TensorFold.git`）の `cb2ebf05` を `outputs/tensorfold`（`TF_CLONE` で変更）に取得し、ブランチ `vocab-surgery-ple-token-ids` に patch を `git am` で当て、clone 内に `.venv`（Python 3.13、torch 2.14.1+cu130、editable install）を作る。既存の clone は上書きしない |
| `tensorfold-patches/` | clone に当てる改造。`ple-token-ids.json`（PLE 対応表）を読み、PLE の hash に元の語彙数と元の ID を使う。draft 語彙のリスト（TF の `draft_vocab.txt`、または `--draft-vocab` で渡すファイル）を加工後の ID に変換する |
| `serve.sh` | `vocab-surgery serve --runtime tensorfold --model fn=<checkpoint>` の近道。新しい server ディレクトリ（`server.log`、`server.json`）を作って serve し、`--` の後のコマンドを `VOCAB_ENDPOINT`、`VOCAB_MODELS` 付きで実行してから止める。`--draft-vocab FILE`、`--port N` などの serve の option をそのまま渡せる |
| `draft_vocab.py` | JSON の profile と中立のコーパス（1 行に `{"text": …}`）から、MTP の draft 語彙のリストを作り直す。行数は基準のリスト（TF の `draft_vocab.txt`）と同じに保ち、コーパスで頻出する token を頻度順に入れ、コーパスで一度も生成されない行を ID の大きい方から落とす。added tokens（`<|im_end|>` など）は落とさない。repository の `.venv`（tokenizers、numpy）で動かす。コーパスは評価素材の側が書き出す |
| `draft-vocab/` | `draft_vocab.py` の profile（規則だけを持つ）。`corpus-frequent.json` は、コーパスで 2 回以上生成された token を、言語を問わず頻度順に入れる |

`setup-tensorfold.sh` で作った clone は、patch を当てた後の commit hash まで手元で作った clone（`912415a`、`8afb41b`、`0b77801`）と一致する（実機確認）。

## 使い方

```sh
scripts/flash-next/setup-tensorfold.sh
SRC=models/models--Vontra--Qwen3.8-Flash-Next-MLX-4bit-MTP/snapshots/2b170fa6309d5d1ee380b35636075fac7945f286
scripts/vocab-surgery measure-speed --runtime tensorfold --model "fn=$SRC" --server-dir outputs/servers/<新しい名前> \
  --out outputs/runs/<run>/original-speed.json
```

`measure-speed` は `evaluation/speed-prompts.json` の 3 prompt を、MTP あり／なし（TF の request の `draft`）で各 4 回送り、速度、TTFT、server のログから割り当てた MTP の受理数を記録する。

外部の評価素材（test-data など）は、その素材が文書で公開している CLI を、`vocab-surgery run-external`（または `serve.sh` の `--` の後）で `VOCAB_ENDPOINT` を endpoint として渡して実行する。素材の runner や結果の形式はこちらで扱わない（[境界](../../docs/EVALUATION_ARCHITECTURE.md)）。

draft 語彙を作り直して serve するには、次のようにする。コーパスは評価素材の側が中立の形式で書き出したものを使う。

```sh
.venv/bin/python scripts/flash-next/draft_vocab.py --profile scripts/flash-next/draft-vocab/corpus-frequent.json \
  --corpus <corpus.jsonl> --tokenizer "$SRC/tokenizer.json" \
  --builtin outputs/tensorfold/src/tensorfold/families/qwen4_exp/cuda/draft_vocab.txt \
  --model outputs/models/flash-next-ja-en-coding-v4 --out "$RUN/draft.txt"
scripts/vocab-surgery measure-speed --runtime tensorfold --model fn=outputs/models/flash-next-ja-en-coding-v4 --draft-vocab "$RUN/draft.txt" \
  --server-dir outputs/servers/<新しい名前> --out "$RUN/pruned-v4-draft-speed.json"
```

server は 1 本ずつ起動・停止する。複数の server を同時に立てたり、request を並列に送ったりすると、`measure-speed` で log を request に割り当てられない。
