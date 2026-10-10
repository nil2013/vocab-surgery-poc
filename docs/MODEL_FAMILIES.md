# モデル系列と拡張境界

語彙の分類、BPE cleanup、行射影、bounded binary transfer、全 tensor 照合はモデルにも戦略にも依存しない。モデルごとに異なる知識は `ModelFamily` に、tokenizer の key 表現は `TokenizerScheme` に、行の格納形式は `RowEncoding` に、どの token を消すかは `PruningPolicy` に、OS・ファイルシステムの能力は `FileTransfer` に閉じ込める。新しいモデルへの対応は、これらの実装を足すことで行い、共通の処理に分岐を加えない。

## 境界

| 境界 | 型 | 実装 | 何を決めるか |
| --- | --- | --- | --- |
| モデル系列 | `model.ModelFamily` | `CoderNext`、`FlashNext`、`Qwen35` | config の寸法の位置、token ID を持つ object、語彙行を持つ module、必須 protocol token、系列固有の出力ファイル |
| tokenizer の key 表現 | `tokenizer.TokenizerScheme` | `ByteLevelBpe` | key から byte 列への復元、基礎 key、構造の検査、`vocab.json`／`merges.txt` 等の付随ファイル |
| 行の格納形式 | `model.RowEncoding` | `Dense`、`AffineQuantized` | 各 module の tensor 構成と列数の検査。行は格納されたまま複製する |
| pruning 戦略 | `tokenizer.PruningPolicy`、`processing.PolicyKind` | `CharacterPolicy`（kind `characters`） | どの token を削除・保護するか、参照入力とその指紋、policy 固有のレポート |
| ファイルシステム | `processing.FileTransfer` | macOS（`clonefile`、`renamex_np`）、Linux（`FICLONE`、`renameat2`） | copy-on-write clone の可否と方式名、置換しない公開 |

`ModelFamily.of` は `config.json` を受け付ける系列がちょうど一つであることを要求する。該当なし・複数該当は計画前に停止する。

### ModelFamily が記述するもの

- `accepts`：系列の判定。現在は `model_type` で判定する。
- `vocabSize`／`hiddenSize`：寸法を持つ JSON path。語彙数の path が複数あれば全て一致を要求し、全て書き換える。
- `tokenIdScopes`／`tokenIdFields`：token ID を持ちうる object と、Hugging Face 共通の field 以外の単一 ID field。scope 外で `*_token_id` 等の field を見つけた場合は、古い ID を残さないよう停止する。
- `vocabularyModules`：axis 0 が語彙の module の tensor 名 prefix。共有 embedding など未対応の構成はここで拒否する。
- `requiredTokens`：単一 ID として残す必要がある protocol token。tokenizer config と chat template から集める token に追加される。
- `familyFileNames`／`familyFiles`：系列が出力に書く JSON ファイル。source にあれば読み、なければ新規に作る。

### Qwen3.8-27B と Ornith

`Qwen35` は `model_type=qwen3_5`／`qwen3_5_moe` を受け付ける。Qwen3.8-27B は名称とは異なり `qwen3_5`、Ornith-1.5-35B-A3B は `qwen3_5_moe` を config に保持している。モデル名や量子化の有無で共通処理を分岐させない。

寸法は `text_config.vocab_size`／`hidden_size`、語彙 modules は `language_model.model.embed_tokens` と `language_model.lm_head`。config の直下、`text_config`、埋め込みの `generation_config` にある token ID と、image／video／vision start／vision end の ID を保護・再採番する。共有 embedding と MTP 専用 embedding は受け付けない。tokenizer は既存 `ByteLevelBpe`、重みは既存の `Dense`／`AffineQuantized` を使う。Flash-Next の PLE 対応表は出力しない。

2026-10-10 に次の実 snapshot を、既存 `ja-en-coding-v2`／`v3` の両方で検証した。`LocalSnapshotSuite` 6 件は、全 shard headers、対象 tensor の行数、ID 再採番、参照 tokenizer の NFC 10,086 字と round trip を確認して通過した。合成 fixture では 3 構成すべての保存計画・適用・全 tensor byte 照合・再検証・再計画を確認した。実重みの適用・全 payload hash・推論品質・速度は未検証で、v4 は Unihan／JMdict 未取得のため未実施。

| 入力 | Snapshot revision | v2 行数／削減 bytes | v3 行数／削減 bytes |
| --- | --- | --- | --- |
| `mlx-community/Qwen3.8-27B-8bit` | `815b83c0df8ffd1d1b5244cf75fd6ef14fca9ef9` | 181,103／731,320,960 | 179,955／743,811,200 |
| `ornith-ai/Ornith-1.5-35B-A3B-MLX`（BF16） | `1713baf2e38984a3c557195caf167759144d1d7e` | 181,103／550,641,664 | 179,955／560,046,080 |
| `ornith-ai/Ornith-1.5-35B-A3B-MLX-8bit` | `02440c39bdf7365c494a7f55f2a8b104ba87562f` | 181,103／292,528,384 | 179,955／297,524,480 |

元の行数はいずれも 248,320。削減 bytes は payload の計画値で、ファイルの header サイズや実推論 memory の削減値とは区別する。

### Flash-Next 固有の出力

`ple-token-ids.json` は、加工後の各行が PLE の n-gram hash の学習時にどの token ID だったかを記録する（PLE 対応表）。PLE は token ID の hash で表の行を選ぶため、行番号を詰めた checkpoint では、この表で元の ID に戻してから hash する必要がある。`originalVocabSize` は hash 係数の導出に使われる元の語彙行数で、加工後の `vocab_size` とは別に保持する。

加工済みの checkpoint を再度加工する場合は、source の表を今回の射影で合成する。source に表がなければ、元の checkpoint（行番号 = 学習時の ID）とみなす。PLE の表・係数 tensor・MTP 本体は byte 単位で保持する。この表を読む runtime 側の変更は本リポジトリの対象外で、未改造の TensorFold は語彙数から導出した hash 係数が checkpoint の係数と一致しないため load 時に停止する。

MTP の draft 語彙リストは加工後の ID 空間で指定する必要がある。`translate-ids` は元の ID のリストを加工後の ID に変換し、削除された ID を報告する。

## 新しい系列を追加する手順

例として Gemma 系列を追加する場合。

1. 実 snapshot の `config.json`、tokenizer、safetensors headers を読み、寸法の位置、token ID を持つ object、語彙行の tensor 名と格納形式、embedding 共有の有無を確認する。`scripts/vocab-surgery inspect` は未対応の系列で `no model family accepts model_type ...` と停止する。
2. `ModelFamily` を実装した object を `model` package に追加し、`ModelFamily.all` に登録する。
3. tokenizer が ByteLevel BPE でなければ（SentencePiece 由来の Metaspace と byte fallback 等）、`TokenizerScheme` を実装し、`tokenizer` を override する。`ByteLevelBpe.validate` は該当しない tokenizer を拒否する。
4. embedding と LM head が共有なら `vocabularyModules` が共有 module 一つを返す。行の格納形式が `Dense`／`AffineQuantized` 以外なら `RowEncoding` と `VocabularyModule.validate` に追加する。
5. `DomainFixtures` に系列の構成を表す合成 snapshot を追加し、`ModelOperationsSuite` と `ProcessingSuite` で計画・適用・再検証を確認する。実 snapshot では `LocalSnapshotSuite` が系列によらない不変条件を検査する。
6. `scripts/check_tokenizer.py` が系列固有の出力ファイルを検査できるか確認する。

`ModelOperations`、`VocabularyOperations`、`BpeGraph`、`TensorOperations`、`CheckpointProcessor` に系列名の分岐を追加する必要が出た場合は、境界の切り方を見直す。

## 検証済みの範囲

| 系列 | 合成 fixture | 実 snapshot での計画（`LocalSnapshotSuite`） | 実 snapshot での適用・再検証 |
| --- | --- | --- | --- |
| Coder-Next | `DomainFixtures.snapshot` | この DGX に snapshot がないため未実施 | 未実施 |
| Flash-Next | `DomainFixtures.flashNext` | MLX 4bit MTP 版（`2b170fa6`）で実施 | 下記 |
| Qwen3.5 dense／MoE | `DomainFixtures.qwen35`（8bit／BF16） | 上記 3 snapshots、v2／v3 で実施 | 合成 fixture のみ。実重みは未実施 |

### Flash-Next の適用記録（2026-10-07、DGX Spark、ext4）

tool は `a4dfe26`（policy を trait 化する前）、policy は `ja-en-coding-v2`、source は MLX 4bit MTP 版 `2b170fa6`。`scripts/run-surgery.sh` で inspect／plan／apply／verify／translate-ids を実行した。

| 項目 | 結果 |
| --- | --- |
| 行数 | 248,320 → 181,103（直接削除 66,928、間接削除 46、未登録行 243） |
| payload | 112,994,060,728 bytes（削減 215,094,400 bytes） |
| apply の検証 | 全 3,747 tensors の byte 照合、参照 tokenizer（tokenizers 0.23.2、transformers 5.18.0）、行の token 一致 181,103 行、`vocab.json`／`merges.txt`／`ple-token-ids.json` の検査がすべて通過 |
| verify（再検証） | 同じ検査を再実行して通過 |
| ファイル | 生成 8、row-projection 2、buffered-copy 26（ext4 は reflink 非対応のため clone なし） |
| draft 語彙リストの変換 | TensorFold 同梱の 79,591 件中 78,837 件を加工後の ID に変換、754 件は削除済み |

成果物は `outputs/runs/flash-next-ja-en-coding-v2`、`outputs/models/flash-next-ja-en-coding-v2`（git 管理外）。

同日、tool `cae6726` と policy `ja-en-coding-v4` で同じ手順を実行した。行数は 248,320 → 172,416（直接削除 75,617、間接削除 44、未登録行 243）、payload の削減は 242,892,800 bytes。全 3,747 tensors の byte 照合、参照 tokenizer、行の token 一致 172,416 行、`vocab.json`／`merges.txt`／`ple-token-ids.json` の検査と、再検証がすべて通過した。間接削除の 44 行は、元の tokenizer の merge 規則でも作られない token（`ワクチン`、`マラソン` など。元の語彙に 201 個あり、残りは直接削除）だった。成果物は `outputs/runs/flash-next-ja-en-coding-v4`、`outputs/models/flash-next-ja-en-coding-v4`。

### TensorFold でのロード検査（2026-10-07）

本番の serve 環境とは別に、TensorFold `cb2ebf05` を `outputs/tensorfold` に clone し、ブランチ `vocab-surgery-ple-token-ids`（`912415a`）で `ple-token-ids.json` を読む改造を加えた。改造は 4 ファイルで、PLE の hash に元の語彙数と元の ID（履歴と PLE EOS も元の ID）を使う。続く `8afb41b` で、組み込みの draft 語彙リストを PLE 対応表で加工後の ID に変換して使うようにした（削除済みの token はリストから落ちる）。それ以前は加工済み checkpoint では拒否していた。専用の venv と CUDA 拡張の build directory を clone 内に置いた。検査 script と結果は `outputs/runs/flash-next-ja-en-coding-v2/tensorfold-load/`。

| 検査 | 結果 |
| --- | --- |
| 加工済み checkpoint のロード | 成功。hash 係数の照合（`NGram.check`）が元の語彙数 248,320 で通過し、PLE EOS は 248,044 |
| greedy 生成（日本語 2、英語・Python 2 の指示、各最大 96 token、MTP なし） | 4 件とも、加工後の出力 token を PLE 対応表で元の ID に戻すと元の checkpoint の出力と完全一致 |
| MTP（draft 最大 3、`translate-ids` で変換した 78,837 件の draft 語彙リスト） | ロード成功。4 件とも MTP なしの出力と完全一致 |

1 GPU、`--ple-on-ssd` 相当、4,096 token の window での検査であり、長文・sampling・複数 request・prefix cache の再開は検査していない。

`FileTransfer` の Linux clone（`FICLONE`）は reflink 非対応の ext4 でのみ実行し、clone できない場合にコピーへ戻ることを確認した。reflink 対応ファイルシステム（XFS、Btrfs）での clone 成功は未検証。
