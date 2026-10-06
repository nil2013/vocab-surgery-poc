# Qwen3-Coder-Next MLX vocabulary pruning 仕様書案

`TASK.md` に基づき、既存の Qwen3-Coder-Next 4bit MLX checkpoint から不要な lexical tokens と対応する embedding／LM head の行を削除する。Scala 3 と sbt で加工ツールを実装し、この Mac の oMLX で日本語・coding・FIM・tool-call の before／after を評価する。入力は `./models`、加工モデルと分析・評価結果は `./outputs` に配置する。

初期の削除対象は Unicode の Script プロパティで判定する Arabic・Cyrillic・Hangul と、日本語漢字の参照集合に含まれない Han 漢字とする。一覧外の漢字は自己用途の判定として簡体字・繁体字と推定し、削除根拠にしてよい。語彙削減による不要表記の混入減少は評価仮説であり、生成禁止を保証する仕様にはしない。

漢字の判定を具体化する参照データとして、追加された `kanji-data` submodule を利用する。採用した漢字一覧の和集合から日本語漢字の保護集合を作り、Han 漢字の削除判定に使用する。

## 決定事項と提案事項

ユーザー確認済みの事項は、上記の初期対象、一覧外の漢字を簡体字・繁体字と推定してよいこと、日本語の説明・要約、Python／Scala／TypeScript のコード生成、FIM、tool-call を評価すること、この Mac 上の oMLX を使用することである。実装言語、直接の checkpoint 加工、再量子化を避けることは `TASK.md` の要件とする。

本案では、未登録の重み行の削除、BPE 到達不能 token の扱い、CLI、成果物形式、評価件数と閾値を具体化している。これらは実装前の提案値とし、最後の「仕様確定に残る事項」にまとめる。

## 対象モデルと入力条件

対象 snapshot を次に固定する。`refs/main` の追従や自動ダウンロードは行わない。

```text
models/models--mlx-community--Qwen3-Coder-Next-4bit/
  snapshots/7b9321eabb85ce79625cac3f61ea691e4ea984b5/
```

`models` は `/Users/tnishimura/.cache/huggingface/hub/` への symlink であり、snapshot 内のファイルも cache の実体を参照している。入力は読み取り専用として扱い、リンク先を含めて上書きしない。原版の Qwen checkpoint や他の MLX モデルは加工対象に含めない。

2026 年 10 月 6 日に確認した対象データは次のとおり。

| 項目 | 実データ |
| --- | --- |
| model type | `qwen3_next` |
| hidden size | 2,048 |
| config の vocab size | 151,936 |
| lexical vocabulary | 151,643 tokens |
| added tokens | 26 tokens、旧 ID 151643〜151668 |
| tokenizer 全体の登録 ID | 0〜151668、合計 151,669 |
| tokenizer 未登録の重み行 | 151669〜151935、267 行 |
| BPE merges | 151,387、各 merge の左右・結果は vocab 内に存在 |
| 基礎 byte alphabet | 256 bytes がすべて存在 |
| strict UTF-8 に復元できない lexical tokens | 1,448、基礎 byte tokens と重複を含む |
| tokenizer normalizer | NFC |
| quantization | affine、基本 4bit、group size 64、一部 module は 8bit |
| embedding と LM head の共有 | `tie_word_embeddings=false` |
| safetensors | 9 shards、全ファイルが存在 |
| tensor 数 | 1,891 |
| tensor payload 合計 | 44,844,060,160 bytes |
| ローカル oMLX | インストール済み package version `0.7.0rc1` |

ファイル存在と header の確認は、全 payload の整合性や推論成功を保証しない。実行時には index と header、ファイル長を改めて照合する。snapshot に存在しない `vocab.json` と `merges.txt` は必須入力とせず、`tokenizer.json` を正とする。

## 作業範囲

処理は、入力検査、pruning plan 作成、BPE 整合化、ID compact 化、checkpoint 加工、metadata 更新、静的検証、推論評価の順に行う。

Transformer／MoE 本体、量子化条件、chat template、推論 engine を維持する。MTP、再学習、calibration-aware quantization、追加量子化、簡体字・繁体字間の字形変換、生成時の文字フィルタは初期 PoC の対象外とする。

Scala 3 のツールが tokenizer と checkpoint を加工する。参照 tokenizer による encode／decode と MLX logits の評価には、oMLX 環境の Python／MLX を呼び出す検証用プログラムを利用できる。Scala で推論 engine を実装する必要はない。

## Vocabulary の分類と削除判定

### ByteLevel surface の復元

lexical vocab の key を ByteLevel alphabet の逆写像で bytes に戻し、strict UTF-8 decoder で surface を復元する。decoder の replacement character を介した判定は禁止する。変換できない ByteLevel 文字が現れた場合は入力形式の不一致として停止する。

ByteLevel BPE の基礎 alphabet は 256 byte values である。全 byte tokens を保護し、Unicode 入力を bytes に分解できる性質を維持する。[Hugging Face の tokenizer 解説](https://huggingface.co/docs/transformers/tokenizer_summary)

元 tokenizer の NFC normalizer、pre-tokenizer、post-processor、decoder と各設定を維持する。削除判定の都合で NFKC や翻字を追加しない。

### 初期の forbidden predicate

Unicode scalar value ごとに、固定した Unicode データ版の `Script` が `Arabic`、`Cyrillic`、`Hangul` のいずれかであるか、または日本語保護集合にない Han 漢字であるか判定する。初期案は Unicode 17.0.0 の `Scripts.txt` と `UnicodeData.txt` を使用し、ファイルと SHA-256 を実行記録に残す。JVM ごとの Unicode 対応差に判定結果を依存させない。

surface に対象 code point が一つでも含まれる非保護 token を直接削除対象とする。日本語・英語との混在 token も同じ判定を行い、影響を分析レポートに記録する。

`Common`／`Inherited` の共用記号や結合文字は、自動で削除範囲を広げない。Script と Script_Extensions は意味が異なるため、初期仕様では Script のみを使用する。必要な共用文字は後から明示リストで追加する。[Unicode Script Property](https://unicode.org/reports/tr24/)

Han 漢字は初期案で `Script=Han` かつ `General_Category=Lo` の code points と定義する。漢字の一覧外判定を、日本語の踊り字・数字・部首記号・variation selectors にまで広げない。これらの記号は Han 漢字の削除ルールとは別に扱う。

### 漢字参照データと一覧外の漢字判定

参照元は `kanji-data` submodule の `mimneko/kanji-data` とし、確認時の commit は `0be3577f7939ec85d2b4e373a7a94262e7449e13` である。実行時に submodule commit と利用ファイルの SHA-256 を記録する。収録範囲の説明は [kanji-data README](../kanji-data/README.md)、同梱のライセンス表記は [LICENSE](../kanji-data/LICENSE) の CC0 1.0 とする。

| ファイル | 確認した内容 | 利用方法 |
| --- | --- | --- |
| `JIS漢字.csv` | 10,050 個の異なる code points。Unicode 符号位置列と文字列が全件一致 | 幅広い日本語漢字の保護集合とし、水準別にも集計 |
| `常用漢字.csv` | 2,136 字 | 基本的な日本語の検証集合 |
| `教育漢字.csv` | 1,026 字 | 基本的な日本語の検証集合 |
| `子の名に使える漢字.csv` | 3,000 字体 | 人名・異体字を保護 |
| `常用漢字表本表.json` | 2,136 字種の通用字体・康熙字典体 | 字体 field から保護集合に追加 |
| `漢検漢字辞典漢字.csv` | 6,787 行。単一 code point のテキストは 6,250 行、重複除去後 6,244 字 | `漢字テキスト` の有効値を追加し、空欄は別途記録 |

常用漢字・教育漢字・子の名に使える漢字の各 CSV の文字集合は、確認時には JIS 集合の部分集合である。漢検の有効テキストには JIS 集合外の 36 字が含まれる。README の漢検収録数は 6,786 字体だが、実 CSV は 6,787 行であり、処理は実ファイルから計数する。

初期の日本語保護集合 `J_raw` は、上記ファイルの漢字 field から抽出できる Unicode scalar values の和集合とする。確認時の和集合は 10,086 字である。常用漢字表本表の康熙字典体は、空欄を字体なしとして扱い、`辨）（瓣）（辯` のような複数字体欄は区切りを解析して各字体を取得する。説明文・読み・URL・画像から漢字を拾わず、漢検の空欄や単一 scalar value でないテキストは診断に記録する。部首・干支・付表の語は、必要に応じて別の検証資料として扱う。

JIS 集合は補助平面の文字を 303 字含むため、Scala では UTF-16 の `Char` 単位ではなく code point 単位で処理する。また 75 字は元 tokenizer と同じ NFC 正規化で別の code point になる。各収録文字を個別に NFC 正規化し、保護集合を `J = J_raw ∪ normalizedCodePoints(J_raw) ∪ J_extra` とする。`J_extra` は必要に応じて設定する追加保護 code points で、初期値は空とする。元字体と正規化後の両方を保持し、判定に使う NFC 実装は参照 tokenizer との一致を検証して version を記録する。

ユーザーの方針に従い、`Han 漢字 − J` を簡体字・繁体字と推定して削除候補にする。レポートの理由は `han_outside_japanese_reference` とし、どちらの字体・言語であるかを個別に確定する必要はない。一覧外の日本語漢字も同じ推定で削除され得ることを、この自己用途 policy の挙動として扱う。

初期 predicate は、`forbidden(c) = Script(c) ∈ {Arabic, Cyrillic, Hangul} OR (isHanKanji(c) AND c ∉ J) OR c ∈ explicitForbidden` とする。`explicitForbidden` は初期値を空とし、必要になった時点で UTF-8 JSON の code point 配列で指定できる。`J` は Han の一覧外判定にのみ作用し、任意の明示削除ルールを上書きする token 保護とは区別する。

日本語漢字の保護は code point の削除判定に適用する。token に日本語の字が含まれるという理由で token 全体を保護してしまうと、Arabic 等との混在 token も残るため、そのような token 保護は追加しない。既存の byte／protocol 等の protected set は引き続き優先する。

初期 PoC から一覧外 Han 漢字による pruning を有効にする。分析結果は run directory 内の `kanji-reference.json`、`japanese-kanji-reference.json`、`kanji-coverage.jsonl`、`han-pruning-candidates.jsonl` に出力し、入力 commit、採用 field、元・NFC 後の code points、除外行、重複件数、削除理由を記録する。plan 作成時には一覧外 Han を含む token の件数と代表例を出力する。

### Protected set

削除判定より保護を優先し、次の集合の和を protected set とする。

- 256 個の基礎 byte tokens。
- strict UTF-8 に復元できない lexical tokens。部分 byte sequence を含むため初期案ではすべて保護する。
- 26 個の added tokens 全体。`special` 属性の真偽は変更しない。
- BOS／EOS／PAD と、設定・template・protocol が要求する tokens。
- 追加設定で明示された token surface または旧 ID。

added tokens には `<|endoftext|>`、chat／vision tokens、`<tool_call>`、`</tool_call>`、`<tool_response>`、`</tool_response>`、`<|fim_prefix|>`、`<|fim_middle|>`、`<|fim_suffix|>`、`<|fim_pad|>`、`<|repo_name|>`、`<|file_sep|>`、`<think>`、`</think>` が含まれる。tool／FIM／repository／think の tokens は実データで `special=false` だが保護する。

protocol の必須 token が存在しない場合は停止する。保護と forbidden predicate が衝突した token は保護理由付きで報告し、黙って削除しない。

## BPE merge の整合化

pruning predicate と merge cleanup を別 module にする。各 merge を `left + right → result` の依存関係として管理する。元の merge 順序を維持し、新しい merge は作成しない。

1. 直接削除対象を lexical vocab から除く。
2. 左・右・結果のいずれかが削除済みの merge を除く。
3. 基礎 byte tokens から残存 merges で構築可能な tokens を計算する。added tokens は別経路で認識されるため lexical DAG の根には含めない。
4. 到達不能となった非保護 lexical tokens を間接削除対象に追加し、関連 merges を除く。
5. vocab と merges が固定点に達するまで処理し、各残存 merge の左右・結果が残存 vocab に存在することを検証する。

複数の merge 経路がある場合は、全経路を失った場合だけ到達不能と判定する。対象 snapshot では同じ結果を生成する複数 merge は確認されていないが、実装はそれを前提にしない。

到達不能の protected lexical token は vocab と重み行を残し、理由と失った経路を報告する。その token は通常の BPE encoding では選ばれなくても、モデルの出力 ID と decoder では有効である。基礎 byte の欠落、または必須 protocol token が単一 ID として認識できない場合は停止する。

直接削除と間接削除は別件数で記録する。後者には forbidden characters を含まない tokens が入る場合があり、日本語・coding の tokenization 変化を別途確認する。

## Token ID と projection

残存 lexical tokens と added tokens を旧 ID の昇順で並べ、0〜K−1 に再番号付けする。同じ旧 ID が複数箇所に記載されている場合は、token の同一性を確認して一つとして扱う。

tokenizer 未登録の 267 行は初期案では削除する。これらに架空の tokens を割り当てない。pruning の削除数とは別に `unregisteredRowsRemoved=267` と記録する。この扱いにより、最終的な `config.vocab_size` と登録 ID 数と重み行数をすべて K に揃える。

```scala
opaque type OriginalTokenId = Int
opaque type CompactTokenId = Int
opaque type TokenSurface = String

final case class VocabularyProjection(
  originalToCompact: Vector[Option[CompactTokenId]],
  compactToOriginal: Vector[OriginalTokenId]
)
```

`originalToCompact` は元の重み行数 151,936 を長さとし、削除行と未登録行には `None` を保存する。JSON 表現は `null` とする。`compactToOriginal` は長さ K の整数配列とする。

すべての残存旧 ID i について `compactToOriginal(originalToCompact(i)) == i` を保証する。逆方向、compact IDs の一意性・連続性、保護 ID の生存も property test で検証する。

## MLX checkpoint の加工

### 加工 tensor と byte 数

| Tensor | 元 shape | dtype | 元 shard | 1 行の bytes |
| --- | --- | --- | --- | --- |
| `model.embed_tokens.weight` | `[151936, 256]` | U32 | 1 | 1,024 |
| `model.embed_tokens.scales` | `[151936, 32]` | BF16 | 1 | 64 |
| `model.embed_tokens.biases` | `[151936, 32]` | BF16 | 1 | 64 |
| `lm_head.weight` | `[151936, 256]` | U32 | 9 | 1,024 |
| `lm_head.scales` | `[151936, 32]` | BF16 | 9 | 64 |
| `lm_head.biases` | `[151936, 32]` | BF16 | 9 | 64 |

上記 6 tensors の axis 0 を、同一の `compactToOriginal` 順に抽出する。後続 dimension、dtype、量子化設定を維持し、浮動小数点への展開や再量子化は行わない。未知の layout、欠落した companion tensor、行数の不一致は停止条件とする。

1 行削除あたり embedding と LM head 合計で 2,304 bytes を削減する。10,000 行で約 21.97 MiB であり、6 tensors 全体でも 350,060,544 bytes、全 payload の約 0.78% にとどまる。削減量は `2304 × (151936 − K)` bytes に header 差分を加えた値で予測する。APFS clone の物理使用量とモデルの論理サイズは区別して報告する。

### safetensors の読み書き

Scala の `FileChannel`／`ByteBuffer` で header と必要な byte ranges を処理する。8-byte little-endian の header 長と JSON header を読み、data offsets を payload 起点の位置として解釈する。payload は row-major とし、dtype と shape から計算した byte 数を照合する。[safetensors の形式仕様](https://github.com/safetensors/safetensors#format)

ファイル位置と byte 数は `Long` を使用し、加算・乗算の overflow を検出する。bounded buffer で partial read／write と `transferTo` の部分転送を処理する。巨大 shard 全体の配列化・一括 mmap・モデル全体の RAM 展開は行わない。転送 buffer は初期案で最大 16 MiB とし、モデル byte 数に比例した heap allocation を避ける。

shards 1 と 9 は、対象 6 tensors を縮小して再構築し、それ以外の tensor payload を同じ bytes のままコピーする。shards 2〜8 は APFS clone を優先し、利用できなければ通常コピーする。入力との hard link や、元 cache に依存する出力 symlink は作らない。

新 header の shape と offsets を再計算し、payload 内に重複・穴・範囲外を作らない。`__metadata__` は形式に従って保持する。同じ入力・設定から同じ tensor 順序、ID mapping、model file bytes を生成する。

## Tokenizer と metadata の更新

| ファイル | 更新内容 |
| --- | --- |
| `tokenizer.json` | lexical vocab IDs、merges、added token IDs、存在する padding／post-processor 内の ID 参照 |
| `tokenizer_config.json` | 存在する added token decoder の数値 key、ID 参照。token strings と属性は保持 |
| `config.json` | `vocab_size=K`、BOS／EOS／PAD 等の既知の token ID 参照 |
| `generation_config.json` | BOS／EOS／PAD、存在する forced／suppressed token IDs 等の ID 参照 |
| `model.safetensors.index.json` | 全 tensor の shard 対応、payload 合計 `total_size`、parameter 数 metadata の整合化 |
| `chat_template.jinja` | 内容を保持し、実 tokenizer による展開・encoding を検証 |
| その他の同梱ファイル | README、parser などを独立ファイルとしてコピーし、固定 token ID 参照を点検 |

現在の tokenizer config に `added_tokens_decoder` はないため、不要な形式変更で追加しない。現在の EOS は `[151645, 151643]`、generation config の BOS／PAD は 151643 であり、すべて projection を適用する。EOS の配列構造は維持する。

任意の整数を一括置換せず、ID を含む schema 上の field を列挙して更新する。削除される ID が必須設定で参照されていた場合は停止し、対象を保護して plan を作り直す。任意の suppression list では削除済み ID を除外して変更内容を記録する。新 ID はすべて 0〜K−1 内に収める。

`quantization` と `quantization_config`、module ごとの 8bit override、architecture 設定を維持する。index の `total_parameters` は元の計数方式を確認し、logical parameter 数なら削除行あたり `2 × 2048` を減じる。意味を確認できない派生 metadata は古い値を残さず、省略と理由を記録する。

`vocab.json`／`merges.txt` を後から提供する場合は、新 tokenizer から生成する。原版モデルの古いファイルを流用しない。

## CLI と出力構成

次の CLI は実装時のインターフェース案であり、現時点では未実装である。

```text
vocab-surgery inspect  --source <snapshot> --run-dir outputs/runs/<run-id>
vocab-surgery plan     --source <snapshot> --policy <policy.json> --run-dir <run-dir>
vocab-surgery apply    --plan <run-dir>/plan.json --model-out outputs/models/<model-name>
vocab-surgery verify   --manifest <run-dir>/manifest.json
vocab-surgery evaluate --manifest <run-dir>/manifest.json --suite <suite.jsonl> --omlx-url <url>
```

`inspect` と `plan` は重みを出力しない。`apply` は plan に記録した snapshot、設定、metadata hash を照合し、入力が変わっていれば停止する。policy は Unicode 版、対象 Scripts、Han 漢字の定義、日本語保護集合、追加 code points、protected tokens、未登録行 policy を含む。submodule commit、採用ファイル、各ファイルの hash、NFC 条件、追加保護集合も effective policy に記録する。漢字 policy の入力が欠落・変更していれば、Han pruning を黙って省略せず停止する。

```text
outputs/
  SPEC.md
  config/
    pruning.json
  models/
    qwen3-coder-next-4bit-pruned-<run-id>/
      config.json
      generation_config.json
      tokenizer.json
      tokenizer_config.json
      chat_template.jinja
      model.safetensors.index.json
      model-00001-of-00009.safetensors ... model-00009-of-00009.safetensors
      その他の同梱ファイル
  runs/
    <run-id>/
      inspection.json
      kanji-reference.json
      japanese-kanji-reference.json
      kanji-coverage.jsonl
      han-pruning-candidates.jsonl
      policy.effective.json
      plan.json
      original-to-compact.json
      compact-to-original.json
      tokens.jsonl
      merge-cleanup.json
      manifest.json
      verification.json
      environment.json
      logs/
      evaluation/
        suite.jsonl
        tokenizer.jsonl
        logits.jsonl
        baseline.jsonl
        pruned.jsonl
        metrics.json
        REPORT.md
```

`tokens.jsonl` は旧 ID、新 ID、surface または raw bytes、保護理由、直接／間接削除理由、対象 code points を含む。manifest は source path／revision、入力・出力 hash、設定 hash、tool version、shard copy 方法、行数・byte 数を記録する。run ID と測定時刻は sidecar に保存し、モデル bytes の決定性を崩さない。

既存の出力 directory は上書きしない。加工は `outputs/.staging/<run-id>` に行い、静的検証後に最終 model directory へ rename する。失敗時は staging と診断を残し、完成モデルとして公開しない。コピー fallback を含む必要容量を事前に見積もり、空きが不足すれば開始しない。

## 正しさの検証

### 静的検証

全 shards の header と index、dtype、shape、offset、ファイル長を検証する。6 tensors の全残存行を元の対応行と streaming 比較し、byte 単位で同一であることを必須条件にする。非対象 tensors も payload hash で同一性を確認する。

ID の双方向性、K 行への一致、256 byte alphabet、保護 tokens、merge 整合性、全 metadata ID の範囲を検証する。小さな合成 safetensors による行抽出・offset 再構築・不正入力のテストと、projection／merge graph の property tests を用意する。

参照 tokenizer で加工後ファイルをロードし、日本語・ASCII・coding・emoji・combining marks・削除対象文字を含む入力の encode／decode を確認する。通常テキストは NFC 正規化後の入力へ round-trip することを確認し、protocol は `skip_special_tokens=false` 等の条件を固定して別評価する。

漢字 policy は、`J` 内の漢字が一覧外判定で直接削除されないこと、`J` 外の Han 漢字を含む非保護 token が削除されること、混在 token が同じ predicate に従うことを確認する。補助平面・NFC で変わる字体・踊り字等を含むケースを固定し、直接削除と BPE cleanup による間接削除を別に確認する。

FIM／tool-call 等が期待する単一 ID を返すこと、chat template が同じ protocol 文字列を展開することを確認する。削除対象言語も基礎 bytes に分解して入力できることを確認する。

### Surviving logits の比較

同一の残存旧 ID 列を元モデルに与え、その projection を加工後モデルに与える。cache を初期化し、同じ位置・推論条件で、残存出力 ID の raw logits を比較する。

```text
z_pruned[compact(i)] ≈ z_original[i]
```

元と加工後のモデルを同時に RAM に載せず、逐次ロードして比較に必要な logits のみ保存する。初期の許容差案は `abs(a-b) <= 1e-2 + 1e-3 * abs(a)` とし、最大誤差、平均誤差、超過数、上位候補の margin を記録する。元モデルの反復測定も行い、数値的な再現性を確認する。閾値超過時に自動で許容差を広げない。

同じ文章でも pruning 後に token 分割が変われば hidden states は変わり得る。文章からの比較は `encode_pruned(text) == projection(encode_original(text))` が成立するケースだけ logits の一致試験に使用し、それ以外は tokenization と品質の評価へ回す。

surviving raw logits が一致しても、削除された候補による softmax の分母変更で生成確率は変わる。元モデルの greedy 選択が残存 ID で、一意の最大値が数値誤差を十分上回る場合に次 token の一致を期待する。sampling 出力の完全一致を合格条件にはしない。未登録 267 行の削除による候補集合の変化も同様に記録する。

## oMLX と before／after 評価

同じ Mac、oMLX／MLX／mlx-lm／tokenizer versions、context 条件、sampling parameters、同時実行数 1 で比較する。元モデルと加工モデルを別名で識別し、prefix／KV／SSD cache を混在させない。モデルを逐次ロードして同時常駐を避ける。

oMLX は `--model-dir` の直下からモデルを探索するため、加工モデルの探索 root は `outputs/models` とする。元モデルは既存 snapshot を読み取り専用で参照する比較用 entry として登録する。実行時に専用 server の port と設定を決め、既存 server の設定を無断で書き換えない。稼働 version は評価時にも記録する。

初期 suite は、日本語の説明・要約 20 件、Python／Scala／TypeScript 各 10 件、FIM 10 件、tool-call 10 件の計 70 件とする。これに、不要文字の自発的混入を調べる日本語・coding prompts 30 件を加え、sampling は各 5 回の計 150 出力で比較する。実際の prompts と期待する code tests を評価前に固定する。

| 評価項目 | 測定方法と初期合格案 |
| --- | --- |
| 通常推論 | oMLX ロード成功、日本語 multi-turn、EOS 終了、streaming、所定 API の応答成功 |
| FIM と tool-call | protocol IDs の保持、FIM completion、tool-call の parser 成功、tool response 後の応答。各 10 件で構造成功率 100% を目標 |
| 日本語品質 | before／after を 5 段階で目視評価。平均差 −0.5 点以内、重大な破綻なし |
| coding 品質 | 固定した executable tests の成功率。before より 10 percentage points を超えて低下しない |
| tokenization | 入力ごとの before／after token 数、比率、中央値・p95。日本語／coding は p95 比率 1.10 以下を初期目安 |
| 不要文字混入 | 出力中の対象 code points／Unicode scalar values、1 文字以上を含む出力率、Arabic／Cyrillic／Hangul／一覧外 Han 別の内訳 |
| greedy divergence | 最初の分岐位置と候補が削除対象かを記録。全体一致率だけで正誤を判定しない |
| 速度 | TTFT、prefill tok/s、decode tok/s、生成 Unicode scalar values/s、全体時間。warm-up 後 5 回の中央値と範囲 |
| memory | peak MLX allocated memory と process RSS を区別して記録。取得可能なシステム memory も条件付きで併記 |
| サイズ | 全 model files の論理 bytes、削減率、APFS 物理使用量、事前予測との差 |

速度測定は、日本語とコードで概ね短・中・長の入力を用意し、同じ生テキストの利用時比較と、同じ残存 ID 列・出力 token 数の処理速度比較を分ける。prompt token 数が変わるため、tok/s に加えて文字量と所要時間を報告する。warm-up による compile と prefix cache のヒットを区別し、主要比較では prefix cache を無効化するか各試行で初期化する。

不要文字混入の評価は、対象言語を出すよう要求しない prompts を主系列とする。元の混入率が 0 の場合は「減少を確認できない」と判定する。少数件での変化は件数・分母とともに報告し、生成禁止の達成と解釈しない。コードで必要な非 ASCII 文字は注釈を残して解釈する。

基本機能と静的・logits 検証を必須条件とする。品質維持・混入減少・速度変化は各々独立した結論として記録する。速度向上や混入減少が観測されない場合も数値を報告するが、`TASK.md` の全目的が達成されたとは記載しない。

## 合意した実装手順

実装は、インフラ層、ドメイン操作、処理機構、CLI と利用資料の順に進める。各段階で必要な型・操作・検証を整備し、次の段階で組み合わせる。

実装言語は Scala 3、ビルドツールは sbt とする。実装と関連資料は意味のある単位で検証・コミットする。

Scala package の共通 prefix はユーザーのドメインに合わせて `me.nsmr` とし、本ツールは `me.nsmr.vocabsurgery` 配下に置く。sbt の `organization` も `me.nsmr` とする。

opaque types は各段階で必要性が明確になった箇所に適宜導入する。旧 ID と新 ID、code point、ファイル位置など、基礎型が同じでも意味が異なり、取り違えを防ぐ必要がある値を対象とする。`OriginalTokenId`、`CompactTokenId`、`TokenSurface` の分離は前述の要件を維持し、対応する概念を実装する段階で導入する。型の生成・変換時に必要な制約も検証する。

### インフラ層の実装

漢字データ、tokenizer、config、generation config、safetensors header と index について、case classes、loader、writer、その他のファイル I/O を実装する。ファイル形式を表す型と、意味・制約を表すドメイン型を分け、loader で構造と値を検証する。

checkpoint は metadata、tensor の shape・dtype・ファイル位置などで表現する。巨大な重み配列をモデルの case class に保持せず、payload は後続の処理機構が必要な範囲を読み書きできるようにする。

完了条件は、漢字一覧と対象 snapshot の metadata をロードできること、小さな合成ファイルで I/O を検証できること、不正形式・欠落ファイル・範囲外 offsets 等の診断を返せることである。

第一工程は実装済み。Scala 3.3.8／sbt 2.0.10、Apache Commons CSV、Circe を使用し、6 種類の漢字 loader、モデル metadata と safetensors header の loader、共通の bounded I/O を整備した。ローカル snapshot の 9 shards・1,891 tensors の metadata 検査を含む全 23 テストと Scaladoc 生成を確認した。実行方法は [README](../README.md)、責務と I/O 制約は [インフラ層の設計](../docs/INFRASTRUCTURE.md) に記載する。pruning と checkpoint 加工の処理機構は後続工程で実装する。

### ドメイン概念と操作体系の整備

漢字集合の統合・NFC 正規化・包含判定、token surface の復元・分類・保護判定、BPE merge の整合化、projection の生成、モデルと metadata の更新計画などを、ファイル I/O に依存しない操作として定義する。他のデータについても同じ方針で操作を整備する。

操作は、値自身の意味に属するものをクラスに、生成・復元をコンパニオンオブジェクトに、複数の概念を組み合わせるものを操作オブジェクトに配置する。各概念の invariant を明記し、projection と merge graph 等の property tests をこの段階で整備する。

完了条件は、メモリ上の小さなデータで漢字 policy、token 分類、merge cleanup、ID mapping と更新計画を検証できることである。

### 処理機構の実装

ここまでの型、I/O、ドメイン操作を組み合わせ、残す tokens・行とコピー先を示す plan の生成、およびその plan を実行する機構を実装する。候補分析と checkpoint 書き換えを独立して実行・検証できる構成にする。

bounded buffer、64-bit のファイル位置、部分 read／write、APFS clone とコピー fallback、staging、入力照合、検証後の確定を扱う。モデル全体を RAM に展開せず、処理に必要な metadata と限定した payload 範囲を保持する。

完了条件は、小さな合成 checkpoint の加工・検証が通ること、実モデルの inspect／plan を生成できること、tokenizer と重みの加工を前述のメモリ方針で実行できることである。

### CLI と scripts と利用資料の実装

CLI は引数の解釈、処理機構の呼び出し、進捗・結果・エラーの表示、終了 status を担当する。helper scripts は必要な CLI 操作をまとめ、ドメイン操作や加工ロジックを重複実装しない。

help コマンドの案内、ユーザドキュメント、仕様・設計の詳細資料、Scaladoc を整備する。Scaladoc、invariant、設計上の判断は各実装段階で記録し、この段階で利用手順と案内を仕上げる。資料には入力・出力、前提条件、操作例、失敗時の診断と再実行方法を含める。

完了条件は、利用者が資料と help を参照して inspect、plan、apply、verify、evaluate を実行し、成果物と結果を確認できることである。

### 検証の進め方

検証は、小さな合成 checkpoint、実モデルの inspect／plan、tokenizer 加工、重み加工、surviving logits の比較、oMLX 評価の順に進める。合成 checkpoint では削除なしのケースと行を削除するケースを確認し、I/O・ドメイン操作・処理機構の問題を切り分ける。各段階に必要な検証を組み込み、CLI 完成まで検証を延期しない。

## 加工と評価の完了条件

| 段階 | 完了条件 |
| --- | --- |
| 入力検査と plan | 対象 snapshot の検査結果、保護・直接削除・間接削除の内訳、projection、削減見積もりを出力 |
| Tokenizer 加工 | 実 tokenizer でロード可能、merge／protocol／round-trip 検証を通過 |
| Checkpoint 加工 | 9 shards と metadata を出力し、残存行と非対象 tensors の byte 同一性を検証 |
| 差分検証 | mapping した入力に対する surviving logits の比較を完了 |
| oMLX 評価 | 同じ Mac で before／after を測定し、raw outputs、metrics、REPORT を出力 |

最終 REPORT は加工モデル path、使用 policy、削除数とサイズ、必須検証の結果、日本語・coding・protocol 品質、混入率、速度・memory、目的ごとの達成状態を含む。`kanji-data` からの保護集合抽出と一覧外 Han の候補分析を plan の一部として完了し、Arabic／Cyrillic／Hangul の削除件数と区別して記録する。

## 仕様確定に残る事項

初期削除 Scripts、一覧外漢字の推定による削除方針、評価用途、実行マシンは確定済み。次の値は本案を初期値として実装可能だが、仕様確定時に確認する。

- 未登録 267 行の削除と、到達不能 protected tokens を保持する方針。
- Unicode データ版、評価件数、品質閾値、logits の許容差。
- 実際の評価 corpus、coding tests、FIM prompts、tool schemas。
- 日本語保護集合に採用するファイルと追加保護文字。本案は上記 6 ファイルの和集合、追加保護なしを初期値とする。
- 評価用 oMLX の専用 port／接続先、cache 初期化方法、memory の取得方法。

基礎 bytes と部分 UTF-8 tokens を保護するため、Arabic・Cyrillic・Hangul と一覧外 Han 漢字は複数 token の組み合わせで引き続き表現・生成できる。PoC の主張は不要な lexical tokens の物理削除と、その実測効果に限定する。
