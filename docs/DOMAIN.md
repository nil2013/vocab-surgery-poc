# 第二工程のドメイン操作

この層は、読み込んだ metadata から語彙の削減方針、ID mapping、tensor と metadata の更新計画を作る。操作はファイル I/O を行わず、immutable な入力と結果を扱う。対象 snapshot の読み込みには [インフラ層](INFRASTRUCTURE.md) の loaders を使う。checkpoint のコピー・行抽出の実行・staging と CLI は後続工程である。

## 操作と型

| 概念 | API | 主な結果 |
| --- | --- | --- |
| Unicode properties／NFC | `UnicodeData`、`UnicodeScript` | Script、Han 漢字判定、正規化、provider provenance |
| 日本語漢字の参照集合 | `JapaneseKanjiReference.from` | 元字体・NFC・追加文字の和集合、収録元、hash、欠落診断 |
| ByteLevel 復元 | `ByteLevel.encode`／`decode` | byte 列、strict UTF-8 surface または `None` |
| 禁止文字・token 保護 | `PruningPolicy`、`VocabularyOperations.analyze` | token ごとの判定理由、削除・保護の衝突、未登録行 |
| BPE cleanup | `BpeGraph.from`／`cleanup` | 残存 edges、直接／間接削除、到達不能な保護 token |
| ID 射影 | `VocabularyProjection.fromSurvivors` | 旧 ID と compact ID の双方向 mapping |
| tokenizer 更新 | `TokenizerRewrite` | vocab、merges、added token IDs を更新した JSON |
| schema 上の ID 参照 | `TokenReferences` | 必須 ID の収集、各 field の構造を保った更新 |
| 量子化 layout | `QuantizedVocabularyModule.validate` | packed weight と scales／biases の検査結果 |
| 行抽出・shard 再構築 | `TensorOperations.projectRows`、`ShardProjection.plan` | 新 metadata、payload-relative コピー範囲、新 header JSON |
| モデル更新計画 | `ModelOperations.plan` | 上記を合成した `ModelUpdatePlan` |

`OriginalTokenId` と `CompactTokenId`、ByteLevel key と `TokenSurface`、byte offset と count を区別する。純粋な操作の invariant 違反は `DomainException(code, location, detail)` として返す例外方式を採る。checked byte arithmetic の overflow は `ArithmeticException`、opaque types の負値等は constructor の `IllegalArgumentException` となる。入力 path を伴う loader の `DataException` と区別する。

## 漢字・Unicode

ICU4J 78.3 の bundled Unicode 17.0.0 を使用し、Script、General_Category、NFC を host JDK の Unicode 版から独立させる。実行時に Unicode 版を照合し、provider／library version／Unicode version を保持する。外部 UCD files の hash を取得したと扱わない。

参照集合は採用した 6 source の字体 fields の和集合 `raw` と、各 scalar を NFC にした `normalized`、指定した `additional` の和である。元字体を捨てず、正規化後の字体にも収録元を紐づける。source hashes と missing-text diagnostics を保持し、画像や説明文から字を補わない。`fromCharacters` は合成テストや明示した小集合の実験用 factory である。

初期の禁止判定は Arabic／Cyrillic／Hangul、参照集合外の `Script=Han && General_Category=Lo`、明示した code points の和を使う。Han の数詞・踊り字・部首・variation selectors を Han 漢字扱いしない。追加の Script／文字／保護 ID／保護 content は `PruningPolicy` の typed fields で指定する。policy のファイル形式と CLI はまだ実装していない。

日本語漢字の収録は Han 判定にだけ作用する。日本語と禁止文字を含む混在 token も禁止文字を理由に直接削除できる。明示した token 保護は削除判定より優先する。

## Tokenizer と BPE

ByteLevel は 256 byte values と encoded scalar values の全単射を用いる。decode は元の bytes を保持し、不正な UTF-8 を replacement character に変換しない。256 個の基礎 byte、strict UTF-8 に戻せない lexical tokens、すべての added tokens、必須 protocol、設定からの必須 ID／content、明示した token 保護を優先する。`special=false` の tool／FIM tokens も保護する。

`BpeGraph` は lexical keys と ID の一意性、基礎 alphabet、merge の左右と連結結果の存在を検査する。merge の連結結果を得る経路が複数あれば、どれか一つの到達可能な経路を残せる。cleanup は直接削除した token に関係する edges を除き、基礎 bytes からの到達可能性を work queue で求め、到達不能な非保護 token を間接削除する。保護 token の orphan は残して診断する。残存 edges は元の rank 順であり、rank を再計算しない。

projection は残存旧 ID を昇順に並べ、`0..K-1` に写す。削除・未登録の重み行は `None`／JSON `null` とし、架空の token を追加しない。コンストラクタでも双方向性、順序、一意性を検査する。

モデル更新計画の対応範囲は、NFC と ByteLevel を使う通常の連結型 BPE、`qwen3_next`、embedding と LM head が非共有のモデルである。affixed merges、byte fallback、ignore-merges、未知の decoder／normalizer は拒否する。モデルの `unk_token`、tokenizer config の既知の token strings、template 中の固定 `<|...|>` 参照も収集する。Jinja の実行や動的に組み立てた token の解決、同梱 parser の点検は後続工程で検証する。

`TokenizerRewrite` は型表現と元 JSON の整合性も確認する。元 merges が string か pair array かを保持し、normalizer／pre-tokenizer／decoder と未知 fields、added token の属性を維持する。

## ID 参照と metadata

必須参照を削減前に収集して保護する。射影後に必須参照が消失していれば停止する。整数全体の置換は行わず、次の schema のみ扱う。

- BOS／EOS／PAD／decoder-start／forced-BOS／forced-EOS／UNK／SEP／CLS／MASK の scalar または flat ID list。`null` と EOS list の構造を維持する。
- `forced_decoder_ids` の `[position, id-or-null]`。position は変更しない。
- `force_words_ids` の token sequences／alternative sequences。すべて必須参照として扱う。
- `suppress_tokens`／`begin_suppress_tokens`。削除済み ID を除外する。
- `bad_words_ids`。削除 ID が含まれる word sequence を全体として除外する。
- tokenizer の `padding.pad_id`、ByteLevel／TemplateProcessing／BertProcessing／RobertaProcessing／Sequence post-processors の参照。
- tokenizer config の `added_tokens_decoder` 数値 keys と存在する `id`。key と `id` が食い違う場合は停止する。

未知の非 null `*_token_id`／`*_token_ids`、`sequence_bias`、未知 post-processor は、古い ID を残すことを避けるため未対応として停止する。それ以外の未知 fields と token strings、templates、hyperparameters は保持する。将来 schema を追加するときは、必須／任意参照の扱いを先に定義する。

index の `weight_map` を保持し、`total_size` を計画後の payload 合計に更新する。`total_parameters` は既定の `OmitUnverified` では省略し、元値と理由を `MetadataNotice` に残す。元の値が logical dense weight elements であると確認した呼び出し側だけが `VerifiedLogicalWeights` を指定でき、その場合は削除行数 × hidden size × 2 を減じる。`Long` の範囲と整数精度を維持する。任意の suppression fields の変更も notices に残す。

## Tensor と shard の計画

対象 modules の effective quantization は override を優先し、affine 4／8-bit、U32 packed weights、同じ浮動 dtype の scales／biases を検査する。hidden size は packing と group size で割り切れる必要がある。未知の layout、追加の vocabulary companion、欠落 tensor、不一致行数を拒否する。対象 snapshot では 4bit・group size 64・BF16 companions の 6 tensors になる。

各対象 tensor の axis 0 を共通の `compactToOriginal` に従って射影する。連続した旧行を一つの `CopySpan` にまとめ、元と先の payload-relative byte ranges を作る。再量子化しない。後続 dimensions と dtype を保持する。未加工 tensor は元の byte range 全体をコピーする記述とし、前の tensor が縮小した場合にだけ先の offset が移動する。

`ShardProjection` は元 payload の連続性を検査し、新 shape と offsets、payload size を計算する。元 header の tensor ごとの未知 fields と `__metadata__` を保持する。payload を配列化せず、copy spans と JSON を返す。`ModelUpdatePlan.savedPayloadBytes` は payload の削減見積もりであり、header 差分や APFS の物理使用量を含まない。実行・header の出版・ファイル hash の照合は第三工程が担当する。

## 利用例

`sbt console` で、README の loader 例に続けて実行できる。

```scala
import me.nsmr.vocabsurgery.kanji.JapaneseKanjiReference
import me.nsmr.vocabsurgery.tokenizer.PruningPolicy
import me.nsmr.vocabsurgery.model.ModelOperations

val japanese = JapaneseKanjiReference.from(kanji)
val plan = ModelOperations.plan(model, PruningPolicy(japanese))
val mapping = plan.projection.originalToCompactJson
val compactRows = plan.projection.compactSize
val savedBytes = plan.savedPayloadBytes.longValue
```

この例はモデルを出力しない。`plan.policy`、分析理由、mapping、metadata notices、shard plans を後続の処理機構に渡す。

## 検証と工程境界

`KanjiOperationsSuite`、`VocabularySuite`、`BpeGraphSuite`、`MetadataOperationsSuite`、`TensorOperationsSuite`、`ModelOperationsSuite` が正常系と不正入力を検証する。property tests は ByteLevel の全単射、projection の双方向性、BPE cleanup の固定点、NFC closure、コピー範囲と実際の小行列抽出の一致を検査する。第一工程の I/O と loader tests も保持する。

ローカル snapshot では 512 MiB の test heap で 9 shards／1,891 tensors の metadata と更新計画を検証した。初期 policy の結果は K=128,803、直接削除 22,866、間接削除 0、未登録行削除 267、payload 削減 53,298,432 bytes。変わる shard は 2 個で、残る 7 個の payload と header は同じである。この結果は固定 snapshot・初期 policy に対する記録であり、他の snapshot に強制する定数ではない。

追加の参照 tokenizer 検証では、oMLX 環境の `tokenizers 0.23.2` が更新済み JSON をロードでき、参照漢字 10,086 字の NFC が ICU と一致すること、日本語・Python／Scala／TypeScript・FIM・tool-call 等の 8 入力が round trip することを確認した。再実行の opt-in property は [README](../README.md) を参照する。Python process の memory は JVM の 512 MiB 上限に含まれない。

第一・第二工程の型、loader、純粋操作、更新計画とテストを整備済み。重みの実コピー・加工モデルの生成、surviving logits、oMLX 推論品質・速度・memory の評価は第三工程以降で検証する。
