# DFlash2 と語彙改造モデルの対応検討

`incoai/Qwen3.8-27B-DFlash2` は、Qwen3.8-27B に組み合わせる外付け draft checkpoint である。mlx-dspark が利用できるが、mlx-dspark 専用の形式ではない。**改造後の target と同じ語彙射影を selector に適用し、mask／EOS／PAD を再採番することで対応可能と判断する。** ただし、このリポジトリの `ModelFamily` を追加するだけでは、target への依存と draft の入力形式を表現できない。今回は通常モデル 3 件への対応を導入し、DFlash の加工・推論エンジンの実装は次の作業として分ける。[配布元の説明](https://huggingface.co/incoai/Qwen3.8-27B-DFlash2/blob/015e795645c74b1a0eeef3b570031fb62e769bc5/README.md)、[DFlash の公式実装](https://github.com/z-lab/dflash/tree/07ebd93db9f472af339b644bb70221ad8428328a)

専用エンジンを自作する場合も、最初は MLX と既存のモデル層を利用し、語彙の対応、hidden state の取得、検証と状態復元に範囲を絞る案が適している。Qwen3.8 用のこの draft を Coder-Next に流用することはできない。Coder-Next には別の公式 DFlash があり、その mask embedding は現在の surgery で削除される未登録行にあるため、別途保持する必要がある。

## 調査対象と証拠

調査日は 2026-10-10。ローカル snapshot は `015e795645c74b1a0eeef3b570031fb62e769bc5`。config と safetensors header を読み、81 tensors とファイル長の整合性を確認した。重み payload のロードと実モデルによる speculative decoding は行っていない。

| 項目 | ローカルの DFlash2 |
| --- | --- |
| architecture／model_type | `DFlash2DraftModel`／`qwen3` |
| 想定 target | Qwen3.8-27B、64 層、hidden 5,120 |
| draft | 5 層、hidden 5,120、intermediate 17,408 |
| target から取得する layer IDs | `[5, 19, 33, 47, 61]` |
| block size | 8。通常は anchor 1 行と draft 7 行 |
| selector | rank 256、各位置の候補数 16 |
| dynamic convolution | kernel size 2、group size 16 |
| draft attention | sliding window 2,048、config の `is_causal=false` |
| vocabulary | 248,320 行 |
| mask ID | 248,070 |
| EOS／PAD | 248,044 |
| checkpoint | 単一 `model.safetensors`、payload 3,848,808,960 bytes |
| 語彙を持つ tensors | `candidate_selector.predecessor_codebook`／`successor_codebook`、各 BF16 `[248320, 256]` |
| 同梱されないもの | tokenizer、generation config、shard index、embedding、LM head |

公開ソースは `outputs/diagnostics/qwen35-support-20261010/` に取得した。mlx-dspark は commit `d6042f38f0aa7dd3e03a2a406111d725056d45a3`、z-lab/dflash は `07ebd93db9f472af339b644bb70221ad8428328a`。ローカルにインストールされた mlx-dspark 0.20.1 の `load.py`、`dflash_model.py`、`target.py`、`generate.py` は、取得した前者のファイルと byte 一致した。以後のコードに関する判断は、この固定した内容についてのもの。

## Draft と target の結合

DFlash は target の複数層から得た hidden state を連結して draft に渡し、target 自身の embedding と LM head を借用する。DFlash2 はそこへ、隣り合う候補の相性を評価する codebook と dynamic convolution を加える。selector は各位置の上位候補を評価して、最後に検証済み token を起点に候補列をたどる。この checkpoint に独立した embedding／LM head がないのは欠損ではなく、この構造による。[mlx-dspark の `bind` と selector](https://github.com/ARahim3/mlx-dspark/blob/d6042f38f0aa7dd3e03a2a406111d725056d45a3/src/mlx_dspark/dflash_model.py#L269)、[DFlash2 の説明](https://inco.ai/blog/dflash2/)

本プロジェクトの現在の入力は、tokenizer と shard index を持つ単独 checkpoint である。また `VocabularyModule` は `prefix.weight` と config の hidden 幅を使って語彙行を検証する。DFlash2 の codebook はその命名ではなく、列数も target の hidden 幅ではない。既存の `TensorOperations.projectRows` と bounded binary transfer は再利用できるが、入力の結合と対象 tensor の記述を追加する必要がある。[モデル境界](MODEL_FAMILIES.md)、[処理境界](PROCESSING.md)

## Qwen3.8 の改造語彙への対応

target の保存済み射影を `R[j] = compactToOriginal[j]`、生存行数を `K` とする。target の embedding `E`／LM head `W` と同じ順序で、前駆 codebook `A` と後続 codebook `B` を扱う。

```text
E'[j] = E[R[j]]       W'[j] = W[R[j]]
A'[j] = A[R[j]]       B'[j] = B[R[j]]
draft.vocab_size = K
mask / EOS / PAD = target の originalToCompact[旧 ID]
```

codebook の 2 tensors だけを行射影し、`hidden_projection`、`fc`、norm、attention、MLP、convolution は byte を保つ。未知の config fields も保つ。target layer IDs、rank、hidden 幅、RoPE は語彙の再採番では変えない。draft 自身の tokenizer から独立した policy を計算するのではなく、target の検証済み計画・manifest に結び付けた一つの射影を使う。

ローカル target の tokenizer では ID 248,070 は `<|audio_start|>` で、既存 added token として保護されている。この文字列の用途から別の mask に置換せず、学習時に使った同じ embedding 行を再採番する。正式な `Qwen35` で作った保存計画の値は次のとおり。

| 既存 policy | `K` | mask の新 ID | 2 codebooks の削減 bytes |
| --- | --- | --- | --- |
| `ja-en-coding-v2` | 181,103 | 181,096 | 68,830,208 |
| `ja-en-coding-v3` | 179,955 | 179,948 | 70,005,760 |

v2 の draft payload 削減は約 1.79%。draft backbone 自体は縮まらないため、物理加工だけの容量効果は限定される。target の LM head の縮小は draft の投影にも使われるが、速度・受理率の改善は実測が必要である。

### 物理加工と runtime の ID 対応

初期 PoC には、draft のファイルを加工せず、runtime で `A[R[compactId]]`／`B[R[compactId]]` を参照する方法もある。target の embedding／LM head は既に compact ID を使うため、そちらをもう一度旧 ID に戻してはならない。runtime の有効語彙数、mask／EOS／PAD、sampling 用 `q` の幅も `K` にそろえる。元 draft の config／codebook の格納行数と、runtime の有効語彙数は区別する。

現在の mlx-dspark は selector で ID を codebook の行番号として直接使い、sampling の `q` を draft config の語彙幅で作る。無加工 draft をそのまま compact target に結合すると、mask が範囲外になり、再採番だけしても codebook の行対応が誤る。runtime の対応付けまたは物理射影のいずれかを実装する必要がある。[selector の直接参照](https://github.com/ARahim3/mlx-dspark/blob/d6042f38f0aa7dd3e03a2a406111d725056d45a3/src/mlx_dspark/dflash_model.py#L288)、[`q` の構築](https://github.com/ARahim3/mlx-dspark/blob/d6042f38f0aa7dd3e03a2a406111d725056d45a3/src/mlx_dspark/generate.py#L863)

固定した生存候補と hidden state を使う小さな CPU 実験では、公開 `CandidateSelector` の codebook を同じ射影で切り出すと、候補間 score、greedy の選択、同じ uniforms による sampled の選択と `q` が一致した。score の最大差は 0。未射影 codebook に compact ID を直接渡すと最大差は約 19.38 だった。これは行対応の確認であり、学習済み draft の受理率や実推論の証拠ではない。script と結果は `outputs/diagnostics/qwen35-support-20261010/selector_projection_probe.py` と `selector-projection-result.json`。

### 再学習と生成分布

生存 token の既存行を保てば、ID 対応の修復のために draft を再学習する必要はないと考えられる。ただし、語彙削除は上位候補・softmax の分母・tokenization を変える。元の全語彙モデルと同じ候補や受理率を保つことは主張しない。

greedy では target が候補列の一致する prefix だけを採用する。sampling では、実際に draft が選択した条件付き分布 `q` を保存し、target 分布 `p` に対して `min(1, p(x)/q(x))` で受理し、最初の棄却位置で正の `p-q` を正規化して再抽選する。候補数を絞った selector の `q` と、target に指定した temperature／top-p／top-k を混同しない。[参照実装の rejection sampling](https://github.com/z-lab/dflash/blob/07ebd93db9f472af339b644bb70221ad8428328a/dflash/model_mlx.py#L64)

この保存対象は**改造後 target の分布**である。元モデルとの同値性を意味せず、数値計算の違いについても別に検証する。本プロジェクトの既存 logits gate の不合格記録と pending human ratings は、この対応追加で合格・完了扱いにしない。[評価の状態](EVALUATION.md)

## Runtime で確認が必要な attention の差

ローカル draft config は `is_causal=false`。固定した z-lab の MLX 参照実装はその値を読み、sliding context に対して draft block 内の未来位置を含める。比較した mlx-dspark の config 型と loader はこの field を読まず、sliding draft layer に causal mask を使う。少なくともこの条件では、二つの実装の可視位置が一致しない。[z-lab の mask](https://github.com/z-lab/dflash/blob/07ebd93db9f472af339b644bb70221ad8428328a/dflash/model_mlx.py#L153)、[mlx-dspark の mask](https://github.com/ARahim3/mlx-dspark/blob/d6042f38f0aa7dd3e03a2a406111d725056d45a3/src/mlx_dspark/dflash_model.py#L200)

これは source と config の比較で確認した差で、実 checkpoint の受理率への影響は未測定である。専用エンジンでは checkpoint の attention semantics を明示し、参照実装との layer 出力比較を先に行う。target の検証が正しくても、誤った draft attention は受理率を下げうるため、速度だけで正しさを判定しない。

## Coder-Next には別の draft と mask embedding が必要

incoai の draft は hidden 5,120／target 64 層を前提とする。Coder-Next は hidden 2,048／48 層で、借用する embedding／head と hidden の連結幅が異なる。token ID の変換だけでこの draft を使うことはできない。

公式の `z-lab/Qwen3-Coder-Next-DFlash` は Coder-Next と組み合わせる別 checkpoint である。取得した config revision は `6d741db11b89d7ea80a423b109f0424817ce8f1b`。hidden 2,048、target 48 層、tap `[3, 11, 23, 35, 43]`、draft 8 層、block size 16 で、DFlash2 selector は宣言していない。この draft の重みは取得していない。[モデル説明](https://huggingface.co/z-lab/Qwen3-Coder-Next-DFlash)、[固定 config](https://huggingface.co/z-lab/Qwen3-Coder-Next-DFlash/blob/6d741db11b89d7ea80a423b109f0424817ce8f1b/config.json)

その mask ID 151,669 は、ローカル Coder-Next tokenizer の登録 ID 0〜151,668 の直後にある未登録行である。v2 はこれを未登録 267 行の一つとして削除する。`translate-ids` では生存 ID に変換できず、現在の `protectedIds` も未登録 ID を受け付けない。mask を別の登録 token に変更すると学習時と異なる入力 embedding になるため、同等の代替とは扱わない。

専用エンジンでは、元 checkpoint のこの embedding 行を補助の mask embedding として保持し、draft の mask 位置だけに直接入力する設計が候補になる。通常 token は compact target の embedding を使い、mask は出力語彙には追加しない。packed weight／scales／biases の元行と出所を保存して、必要なときだけ元の計算と同じ復元を行う。削除済み checkpoint だけからこの行を復元することはできないので、元入力と manifest の指紋を保持する。これは未実装の runtime 入力境界の提案で、通常 surgery の未登録行 policy は今回変更しない。

## 用途を限定した専用エンジンの構成案

モデル層・量子化 kernel を最初から書き直すより、MLX の上に次の境界を持つ小さな推論実装を置く案を推奨する。この runtime は独立したプロジェクトに置き、vocab-surgery は checkpoint／mapping／manifest を渡し、文書化した CLI と HTTP API で利用する。[既存の推論境界](EVALUATION_ARCHITECTURE.md)

| 境界 | 担当する内容 |
| --- | --- |
| target adapter | Coder-Next／Qwen3.8-27B のモデル層を使い、prefill、decode、hidden tap、block verify、状態復元を提供 |
| vocabulary binding | target と draft の出所、`K`、射影、token の行対応、mask／stop IDs、補助 mask embedding を検証 |
| draft proposer | 最初は n-gram lookup、次に対応する DFlash／DFlash2。block の候補 IDs と実際の `q` を返す |
| verifier | target の採用 prefix、棄却時の置換、EOS、最大生成長、sampling を処理 |
| request state | KV cache、Gated DeltaNet の再帰状態と conv window、draft context、生成履歴を request ごとに保持 |
| 外部インターフェース | 当初は単一 request の CLI、後から OpenAI 互換 streaming と停止・tool call を追加 |

両 target は full attention と線形 attention の hybrid である。KV の末尾を切るだけでは、棄却した token が Gated DeltaNet の状態や conv window に残る。検証前の状態を保持し、採用 prefix までの再帰を再実行するか、必要な prefix の状態を記録する。draft context と RoPE の位置も同じ採用点に戻す。mlx-dspark はこの capture／再実行の実装を持つので、設計の参考になる。module 全体の関数を書き換える hook は concurrency の境界を持つため、専用エンジンでは request ごとの明示的な状態取得を優先する。[状態復元の実装](https://github.com/ARahim3/mlx-dspark/blob/d6042f38f0aa7dd3e03a2a406111d725056d45a3/src/mlx_dspark/target.py#L417)

次に着手するなら、以下の順序で判断できる。

1. compact target の autoregressive baseline と、hidden tap を加えた forward の一致を確認する。モデルのロード・metadata 検証と推論の検証を分ける。
2. greedy の lookup proposer で検証ループと Gated DeltaNet の復元を確認する。全採用、先頭／中間／末尾で棄却、複数 round、EOS、長文、cancel 後の別 request を検査する。初期実装は採用 prefix の再実行方式でよい。
3. Qwen3.8 用 DFlash2 に runtime の codebook mapping と再採番を導入する。未改造 target＋元 draft、改造 target の通常生成、改造 target＋対応した draft を区別して比較する。
4. Coder-Next 用の別 draft と補助 mask embedding を接続する。hidden／tap／mask の確認が済むまで incoai draft と共通のモデルだと扱わない。
5. sampling を追加し、小さな分布の厳密な計算と反復試験で rejection sampling を確認する。その後に、実モデルの分布・数値差・速度を測る。

最適化は correctness の後に行う。語彙削減と draft 推論を組み合わせても、draft 自体の実行・復元・同期のコストが増えるため、自動的に高速化するわけではない。比較には採用 token 数だけでなく、生成文字数／秒、TTFT、draft／verify／rollback 時間、peak memory と実際の受理長を使う。anchor と追加 token を含む受理長の定義も固定する。検証幅 1／2／4／8 などを同じ checkpoint と入力で比べ、元の block size をそのまま最速設定だとみなさない。

mlx-dspark と z-lab のコードを取り込む場合は、各 upstream の LICENSE と NOTICE も保持する。mlx-dspark 自体は MIT と表示されるが、その NOTICE は DFlash2 部分と配布 checkpoint の Apache-2.0 由来も記載する。[mlx-dspark NOTICE](https://github.com/ARahim3/mlx-dspark/blob/d6042f38f0aa7dd3e03a2a406111d725056d45a3/NOTICE)、[z-lab LICENSE](https://github.com/z-lab/dflash/blob/07ebd93db9f472af339b644bb70221ad8428328a/LICENSE)

## 今回の完了範囲

通常の Qwen3.8-27B 8bit と Ornith BF16／8bit は `Qwen35` として対応を導入した。合成 checkpoint で計画・適用・全 tensor byte 照合・再検証・再計画を通過し、実 snapshot では既存 v2／v3 の 6 計画と参照 tokenizer 検証、CLI inspect／plan が成功した。[対応の記録](MODEL_FAMILIES.md#qwen38-27b-と-ornith)

DFlash2 は、target と組み合わせる専用処理または runtime の ID 対応で扱う余地がある。容量だけを目的に約 1.8% の削減のために加工機構を広げる優先度は低い。一方、改造モデル専用エンジンの候補としては、target の embedding／head を共有できる点と候補 selector が参考になる。実推論の性能・受理率、attention の差の影響、Coder-Next の補助 mask embedding の PoC は次の検証事項である。Gemma 対応と DFlash の production 実装は今回の変更に含めていない。
