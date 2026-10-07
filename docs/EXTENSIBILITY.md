# Pruning system の拡張性評価と設計提案

## 目的と位置付け

本システムが Coder-Next 専用の pruning 実装に留まるか、モデル固有の差分を追加して継続利用できる pruning system になっているかを評価する。ローカルに存在する Ornith は、設定の構造、tensor の命名、量子化の有無が異なる入力として、基本アーキテクチャの汎用性を調べるストレステストの素材に使う。Ornith 対応それ自体を本システムの目的とするものではない。

評価時点の結論は、**pruning と binary I/O の基盤は再利用できるが、モデル固有の前提を差し替える境界は未整備**、である。実行可能なモデル更新計画は現在 Coder-Next に限定される。単に新しいモデル用クラスを一つ追加するだけでは対応できないが、共通の処理エンジンを作り直す必要もないと判断する。

以下では、確認した事実と未実装の設計提案を分ける。これは拡張検討資料であり、現在の対応範囲や acceptance criteria を変更する仕様ではない。

## 調査の基準と範囲

- 調査日：2026-10-06。
- ソースコードの基準 commit：`f2106a5701585d53be57260df2e2c5d6214cf077`。
- 入力は `models/<repository>/snapshots/<revision>` 配下の既存 snapshot。cache と symlink targets は読み取り専用で扱った。
- config、tokenizer、index、全 shard の safetensors headers を照合した。重み payload の読み込み、モデルのロード、pruning の実行、oMLX での推論検証は行っていない。

| 調査入力 | `models` 配下の repository | Snapshot revision |
| --- | --- | --- |
| 現行の基準モデル | `models--mlx-community--Qwen3-Coder-Next-4bit` | `7b9321eabb85ce79625cac3f61ea691e4ea984b5` |
| 比較素材・非量子化 | `models--ornith-ai--Ornith-1.5-35B-A3B-MLX` | `1713baf2e38984a3c557195caf167759144d1d7e` |
| 比較素材・8bit | `models--ornith-ai--Ornith-1.5-35B-A3B-MLX-8bit` | `02440c39bdf7365c494a7f55f2a8b104ba87562f` |

両モデルは Qwen 系で、比較素材の tokenizer も既存アルゴリズムの前提に近い。この比較は、モデル設定・tensor 命名・重み表現を分離できているかの検査にはなるが、SentencePiece、別形式の checkpoint、任意の量子化方式への汎用性を証明するものではない。

## 入力から確認できた差分

| 項目 | Coder-Next 4bit | 比較素材の Ornith |
| --- | --- | --- |
| config の `model_type` | `qwen3_next` | `qwen3_5_moe` |
| 語彙サイズの場所 | 直下の `vocab_size`：151,936 | `text_config.vocab_size`：248,320。直下にはない |
| hidden size | 直下：2,048 | 直下と `text_config`：ともに 2,048 |
| embedding と LM head | 非共有 | 非共有 |
| embedding prefix | `model.embed_tokens` | `language_model.model.embed_tokens` |
| LM-head prefix | `lm_head` | `language_model.lm_head` |
| tokenizer | NFC、ByteLevel BPE | NFC、ByteLevel BPE |
| lexical vocabulary / added tokens | 151,643 / 26 | 248,044 / 33 |
| BPE merges | 151,387 | 247,587 |

Ornith の登録 ID は lexical と added tokens を合わせて 248,077 行で、語彙サイズとの差は 243 行。現在の未登録行 policy を適用する場合の候補になるが、実際の削除集合・compact vocabulary はまだ計算していない。既存の `CoderNextRequired` に列挙された protocol tokens は、この比較素材にも存在する。

### 重み表現

| Ornith の snapshot | Shards / tensors | Index の `total_size`（payload bytes） | 語彙対象 tensor の表現 |
| --- | --- | --- | --- |
| 非量子化 | 14 / 733 | 69,321,221,376 | 入出力とも BF16 weight `[248320, 2048]` |
| 8bit | 7 / 1,757 | 36,827,986,176 | 入出力とも U32 weight `[248320, 512]`、BF16 scales / biases `[248320, 32]` |

8bit 版は affine、group size 64 であり、既存の `QuantizedVocabularyModule.validate` が対応する packing・dtype・shape と整合する。`quantization` と `quantization_config` が併記されている。非量子化版は量子化設定も scales / biases も持たない。

全 header で axis 0 が 248,320 の tensors は、非量子化版では上記の入出力 weight 2 個、8bit 版では入出力の weight / scales / biases 6 個だった。これは対象の確認材料であり、一般のモデルで「先頭 dimension が語彙サイズなら対象」と判定してよい根拠にはしない。

### Token ID 参照

Ornith は直下に `image_token_id`、`video_token_id`、`vision_start_token_id`、`vision_end_token_id` を持ち、`text_config` 内にも BOS / EOS / PAD がある。added tokens には vision・audio 系も含まれる。

調査した index に vision / visual 名や MTP 名の tensors はなく、config に `vision_config` もない。この記録から画像・音声機能の利用可否までは判断しない。少なくとも、存在する ID 参照と protocol tokens は pruning 後も保護・再採番する必要がある。

## 現在の抽象化の評価

| 層・概念 | 再利用できる部分 | 現在の制約・必要な確認 |
| --- | --- | --- |
| Core / Unicode / 漢字参照 | opaque types、scalar 単位の分類、参照集合、Math 保護、明示禁止・保護 | モデル名に依存しない。用途に応じた policy 選択は別に行う |
| Tokenizer domain | ByteLevel 復元、BPE cleanup、ID projection、JSON rewrite | NFC と通常の連結型 ByteLevel BPE が前提。別方式には別の対応が必要 |
| Tensor / shard domain | 行 projection、連続行の copy spans、header / offsets 再構築 | 切り出す対象と layout の確定はモデル層に依存する |
| Binary I/O / processing | bounded transfer、staging、create-new publication、全 tensor 比較、hash | 再ロード時のモデル検査と保存計画の schema は拡張に合わせて確認が必要 |
| Model metadata / planning | immutable JSON、未知 fields の保持、型による設定参照 | 設定パス・tensor 名・model type・量子化必須という前提が固定 |
| CLI / evaluation | `--source` 指定、処理の合成、tokenizer 参照検証、日英 coding の suite | ローカル snapshot test は固定 Coder-Next。新モデルの推論・serve 互換性は未検証 |

`ModelConfig` や `ModelSnapshot` は、metadata を表す case class としては分離されている。しかし、モデル固有の知識を交換可能にする adapter の役割までは担っていない。

### Coder-Next 固有の前提が残る箇所

| 根拠となる実装 | 現在の前提 | 拡張時に分離する知識 |
| --- | --- | --- |
| [ConfigLoader.scala](../src/main/scala/me/nsmr/vocabsurgery/model/ConfigLoader.scala) / [ModelData.scala](../src/main/scala/me/nsmr/vocabsurgery/model/ModelData.scala) | `vocab_size` / `hidden_size` を直下から読み、量子化設定を必須とする | テキスト設定の位置、整合性検査、重み表現 |
| [SnapshotLoader.scala](../src/main/scala/me/nsmr/vocabsurgery/model/SnapshotLoader.scala) | 固定 prefixes の weight / scales / biases を必須とする | 語彙 modules と companion tensors の記述 |
| [ModelOperations.scala](../src/main/scala/me/nsmr/vocabsurgery/model/ModelOperations.scala) | `qwen3_next`・非共有を要求し、固定名の量子化 modules を計画する。更新する語彙サイズは直下のみ | 対応能力の判定、対象 module、設定更新パス、必須 protocol |
| [TokenReferences.scala](../src/main/scala/me/nsmr/vocabsurgery/model/TokenReferences.scala) | 既知の直下 ID fields を扱い、未知の非 null `*_token_id` を拒否する。`text_config` には再帰しない | 明示的な nested schema と modality 関連の必須 ID |
| [TensorOperations.scala](../src/main/scala/me/nsmr/vocabsurgery/model/TensorOperations.scala) | module の表現は `QuantizedVocabularyModule` のみ | 非量子化と量子化を区別できる module 型 |

未知 JSON をそのまま保持できることは、未知の token ID 参照を正しく再採番できることとは異なる。model type の拒否条件を外すだけでは、`text_config` 内に旧語彙サイズ・旧 ID を残す危険がある。

### 現在の CLI による確認

調査時点の staged CLI で以下を実行した。

```sh
scripts/vocab-surgery inspect \
  --source models/models--ornith-ai--Ornith-1.5-35B-A3B-MLX-8bit/snapshots/02440c39bdf7365c494a7f55f2a8b104ba87562f \
  --run-dir outputs/runs/ornith-compatibility-probe
```

終了コードは 1 で、`config.json [config.vocab_size]: required field is missing or null` となった。これは現在の対応境界の確認であり、pruning 成功や Ornith の静的検証通過を意味しない。以降の tensor 名・model type・ID schema に関する制約はソースコードから確認したもの。

## 拡張案：モデル固有の知識を明示的に差し替える

以下の名前・API・保存形式は未実装の提案。モデルごとのニューラルネットワーク実装を本システムに持ち込まず、pruning に必要な metadata と capability の差分を表現する。

### ModelAdapter / architecture profile

`ModelAdapter` または architecture profile に、次の知識を集約する。

1. 対応する model type、設定構造、共有／非共有などの対応条件。
2. 語彙サイズ・hidden size・token ID の読み取り位置と、更新すべき JSON paths。
3. 入力 embedding / 出力 head の roles、tensor prefixes、必要な companions と layout。
4. 必須 protocol tokens、token-reference schema、必要な関連 assets の検証条件。

loader はこの記述を使って構造を parse / validate し、domain は同じ記述を使って純粋な更新計画を作る。書き込みや公開は processing に残す。型表現と元 JSON の照合、文脈付き diagnostics、未対応構造の拒否を維持する。

profile は可能なら配布モデル名ではなく、観測した architecture / checkpoint schema を単位にする。ただし同じ `model_type` という理由だけで対応済みとは扱わず、設定・layout・ID schema の適合を確認する。

### VocabularyModule の表現

`VocabularyModule` を sealed trait / enum 等で表し、非量子化の `Dense` と既存 affine layout の `AffineQuantized` を区別する案が適切。共通の結果には検証済み tensors、語彙行数、logical hidden size、bytes per row を持たせる。

非量子化は weight の dtype / shape、量子化は weight と companions の整合性をそれぞれ検査する。その後は共通の projection と [行切り出し処理](../src/main/scala/me/nsmr/vocabsurgery/model/TensorOperations.scala) に渡す。下位の `projectRows` は既に BF16 を扱える計算構造であり、再量子化や tensor payload の展開は不要。

共有 embedding、別 packing、追加 vocabulary companions は capability を定義して検証するまでは拒否する。名前や shape の類似から自動的に推測して加工しない。

### 保存計画・検証との接続

- adapter の選択と schema 解釈を再現できる識別情報を、新しい保存計画に含めることを検討する。
- [PlanStore](../src/main/scala/me/nsmr/vocabsurgery/processing/PlanStore.scala) の入力からの再計算・内容照合と、[CheckpointProcessor](../src/main/scala/me/nsmr/vocabsurgery/processing/CheckpointProcessor.scala) の出力再ロードでも同じ対応規則を使う。
- 既存 plan / manifest の canonical JSON と hashes を暗黙に変えない。形式変更が必要なら、新規形式と legacy plan の解釈を明示し、互換性を検証する。policy version とは別に管理する。
- tokenizer helper、同梱 parser、評価 helper が追加した model schema と整合するかを確認する。現在のテキスト検証から multimodal 機能の成功を推定しない。

## 想定する拡張手順と規模

1. **既存対応を adapter に移す。** Coder-Next の制約を緩めず、設定・対象 tensor・protocol の知識を取り出す。既存 projection、metadata、tensor bytes と保存計画の解釈を保持する。
2. **第二の schema で境界を検証する。** 比較素材の 8bit 版は既存 affine validator と整合するため、nested config、tensor namespace、ID schema の差分を試す最初の入力に適している。モデル層の変更と fixtures / tests が中心になる。
3. **重み表現を拡張する。** 非量子化 module を追加し、同じ比較素材の BF16 版で量子化必須の前提を除去できているか確認する。共通の binary engine は再利用する。
4. **処理・評価へ接続する。** 新規 plan の保存・再計算、出力の再ロード、全 tensor 比較、参照 tokenizer を検証する。推論・oMLX 互換性は metadata 検査と別に評価する。

これはモデル層を中心とした中規模の拡張という見積もりであり、第一工程や binary I/O の作り直しを想定していない。一方、loader 一箇所の条件変更や新規クラス一個だけで完結する変更でもない。所要時間・変更行数は未見積もりで、未検証の schema や helper 側の差分によって変わる。

## 汎用性を判定するための検証案

| 観点 | 確認すること |
| --- | --- |
| 既存モデルの回帰 | 固定 Coder-Next の解析結果・projection・metadata・全 tensor 対応を維持し、旧 plan / manifest を意図した方法で検証できる |
| Adapter 境界 | 第二の schema を追加しても Unicode / BPE / 行 projection / bounded transfer にモデル名の分岐が増えない |
| 設定と ID の完全性 | nested vocab size と BOS / EOS / PAD、image / video / vision 等の必須参照が正しく更新され、未知の token-reference schema は拒否される |
| 重み表現 | 小さな Dense / AffineQuantized fixtures で入出力の共通行順序と、非対象 tensor の byte 同一性を検証する |
| 処理契約 | 保存計画の改変・入力変更・未対応 layout を拒否し、参照 tokenizer 検証前にモデルを公開しない |
| メモリ | より大きい tokenizer / merge graph の実入力でも metadata と計画を所定 heap で処理できるか測定する。bounded payload I/O だけで metadata のメモリ適合を保証しない |
| 推論・評価 | 加工前モデルのロード確認から始め、surviving logits と oMLX の日英 coding 評価を独立して行う |

この比較で同じ処理エンジンを保ったまま差分を adapter と重み表現に閉じ込められれば、拡張可能な pruning system への一つの実証になる。別の tokenizer family や checkpoint format まで同じ方式で対応できるかは、追加の検証課題として残る。

既存モデルの failed logits gate や pending human ratings は、この設計検討によって解消した扱いにしない。現行の結果・判定は [EVALUATION.md](EVALUATION.md) を参照し、閾値や TASK の達成条件をこの文書で変更しない。

## 将来の拡張余地：入力と出力で異なる pruning（2026-10-07 時点、未実装）

現在の tool は、`embed_tokens`（入力）と `lm_head`（出力）を同じ射影で切る。入力側は保守的に、出力側は積極的に削る、といった非対称の policy は、将来の拡張余地として残す。v4 には入れていない。

### 成り立つ条件

- **重み**：`tie_word_embeddings: false` の系列（Flash-Next、実機確認）なら、2 つの tensor から別々の行を削れる。tied の系列では、行列を複製しない限りできない。
- **ID の対応**：自己回帰の生成では、出力した token を次の入力として読み戻す。そのため、runtime は「`lm_head` の j 行目 = token ID j = `embed_tokens` の j 行目」を前提にしている。
  - TF：前提にしている（実機確認）。ただし draft head には、一部の行だけを採点して `id_map` で ID に戻す仕組みがある。
  - llama.cpp：前提にしている（公式のソースで確認、`c479922`）。Qwen3 は `token_embd` と `output` を同じ `n_vocab` の形状で作り、形状が違うと load を拒否する。logits の buffer と sampling の候補も `n_vocab` 個で、添字がそのまま token ID になる。
  - oMLX・mlx-lm：未確認。
- 非対称に削るには、出力行と token ID の対応表を runtime が使えるようにする改造が必要になる。重みの加工だけでは足りない。`lm_head` に bias がないので、行を残したまま出力を禁止することもできない。

### 方式

| 方式 | 内容 | 必要な変更 |
| --- | --- | --- |
| 入力は元のまま、出力だけ削る | tokenizer・埋め込み・PLE は元の ID のまま、`lm_head` だけ残す行の集合 K に制限し、`id_map` で元の ID に戻す。2026-10-06 夜に一度検討した案 | tool：K と `id_map` の出力、`lm_head` の行の切り出し。TF：draft head の仕組みを target に広げる patch |
| 入出力を別々に削る（一般形） | 入力の語彙 I と出力の語彙 O（O ⊆ I）を別々に決める | tool：policy の判定を 3 値（両方に残す・入力のみ・削除）にする。入出力の 2 つの射影、出力側の構造保護（byte の基礎 token、protocol、EOS）、plan・検証・CLI のすべてを 2 系統にする。TF：上と同じ patch |

### 採らなかった理由と、適用できる範囲

- **言語の混在が防げない**：入力側を残すと、モデルはその言語を読めてしまう。出力側には byte の token が残るので、その言語に引きずられて byte で書く経路が開いたままになる。
  - 元のモデルは、ハングルが混じった依頼に韓国語で答えた（[serve 比較](EVALUATION_FLASH_NEXT_20261007.md)）。
  - 入力も削った pruned-v2 でさえ、ハングルを byte で書いた例があった。
  - 混在の防止を優先する限り、外国の文字体系は入出力の両方から削る。
- **非対称が成り立つ範囲**：読めても出力の引っ張られる先が日本語のままである文字に限られる。例は日本語の旧字体・異体字で、入力では読めるようにし、出力では削る、という使い方になる。
- **入力側を削る代償**：削った文字体系で書かれた指示は読めなくなる。読めない語に作り話の意味を付けたり、指示を落としたりする例がある（同レポート）。
- **検討中の具体案（ユーザー提案、2026-10-07）**：入力側は、削除対象の Script を含む 2 字以上の token だけを削り、1 字の token は残す、やや保守的な規則にする。出力側は、日本語の字を含む token でも削る積極的な規則にする（旧字体を出力できなくても、coding 用途なら痛手は小さい）。
  - 字数の閾値の効き方は、Script によって大きく違う。元の語彙の token を、前後の空白を除いた字数で数えた（実機確認）。

    | Script | 1 字 | 2 字 | 3 字以上 |
    | --- | ---: | ---: | ---: |
    | Hangul | 1,703 | 3,719 | 1,385 |
    | Cyrillic | 180 | 1,231 | 17,169 |

  - 「2 字以上を削る」規則では、Hangul は 1 音節の token（25%）、Cyrillic は 1 字の token（1%）だけが入力に残る。語の token は消え、字単位でだけ読める状態になる。
  - 1 字の token が読解の足場になることは、pruned-v2 が日本語と共有する 1 字の漢字から「会议→会議」を読めた例で観察している。字単位で読めることで、出力が引きずられる強さがどの程度戻るかは未確認。
- **byte の経路を確実に塞ぐ方法**：語彙の pruning とは別に、decode のときに UTF-8 の状態を見て、禁止 Script の文字を作る byte 列を mask する方法がある。TF には grammar 用の mask を差し込む箇所がある。

## 関連文書

- Loader / 表現 / I/O の現行契約：[INFRASTRUCTURE.md](INFRASTRUCTURE.md)。
- Pruning / projection / ID schemas の現行対応範囲：[DOMAIN.md](DOMAIN.md)。
- 保存計画 / publication / 全 checkpoint 検証：[PROCESSING.md](PROCESSING.md)。
- 推論・品質・数値評価：[EVALUATION.md](EVALUATION.md)。
- 合意した要件と acceptance criteria：[SPEC.md](../outputs/SPEC.md)。
