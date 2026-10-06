# 日英 coding 用の追加 pruning と Math 保護

2026-10-06 に、[91fb703 の残存語彙調査](REMAINING_VOCABULARY_91fb703.md)をもとに追加 profile を実装・適用した。実装と profile の commit は **`dbab73fbc02bc559e27bf2626eceab9c29c90fbe`**。元 HF snapshot と漢字 submodule は同調査の固定版を使用する。

## 設定の意図

canonical profile は [policies/ja-en-coding-v2.json](../policies/ja-en-coding-v2.json)。指定した 62 Scripts、参照集合外 Han、明示指定の `个`・`开`・`关`・`无`・`网` を対象とする。

Greek と Canadian_Aboriginal は用途があるため Script の禁止対象から外す。Latin・Han・Hiragana・Katakana・Common・Inherited も Script 一括禁止の対象にしない。Han は参照集合と明示禁止文字で判定する。非 ASCII Latin、絵文字・罫線・矢印、Common／Inherited の Arabic 関連記号は、今回の profile の追加禁止項目には含めない。

新しい policy formatVersion は 2。`protectMath` は true が既定で、false に切り替えられる。version 1 の保存済み policy は従来どおり Math 保護なしで読み書きする。JSON 表現と policy hash を維持するため、旧 plan を新しい既定値で解釈し直さない。schema と移行条件は [PROCESSING.md](PROCESSING.md) を参照。

## Math 保護の意味

ICU4J 78.3／Unicode 17.0.0 の `Math=true` を文字単位で保護する。保護は Script・Han・明示禁止 code point の判定より優先し、generated-text metrics にも同じ scalar predicate を使う。

Math の文字だけからなる token は `ProtectionReason.Math` により BPE cleanup からも保護する。別の禁止文字を含む混在 token は、その禁止文字を理由に削除できる。例えば `个ℵ` は `个` が禁止なので削除対象だが、`ℵ` 自体の禁止判定は false となる。Math の文字を含む token 全体を無条件に保護する設計ではない。

Math 属性は「人が数学に使うすべての文字」の判定ではない。`ℵ` 系列は Common／Math=true、`א` は Hebrew／Math=false、`α`・`λ` は Greek／Math=false。この profile は Greek を禁止しないため `α`・`λ` を保つ。その他の数学用途は必要に応じて明示した token 保護や Script 選択で扱う。

false にしても、基礎 bytes・部分 UTF-8・added／protocol tokens・その他の明示保護は有効なまま。

## 指定した5字の用例

前回モデルの完全 UTF-8 surface を調べた結果。件数はその字を含む token 行数であり、出現頻度ではない。複数字を含む行の重複を除いた和集合は **287 行**で、これらはすべて非保護だった。

| 字 | Code point / JSON 整数 | 行数 | 実在する用例 |
| --- | --- | ---: | --- |
| `个` | U+4E2A / 20010 | 104 | `一个`、`个人`、`个月`、`每个人`、`个性` |
| `开` | U+5F00 / 24320 | 71 | `开始`、`公开`、`开放`、`开幕`、`打开` |
| `关` | U+5173 / 20851 | 45 | `相关`、`关注`、`关系`、`关于`、`开关` |
| `无` | U+65E0 / 26080 | 37 | `无法`、`无人`、`无限`、`无效`、`无关` |
| `网` | U+7F51 / 32593 | 32 | `网站`、`网友`、`网上`、`官网`、`网址` |

これらの字は現在の広い漢字参照集合に入っているが、日常的な日英 coding 用途では優先度が低いとして、今回 `explicitForbidden` に指定した。字を含む全非保護 token を候補にするため、上表の語全体も削除される。日本語の異体字・固有名詞等で使う可能性と、入力時の token 分割増加は残る。

基礎 bytes と部分 UTF-8 を残しているので、削除した字を引き続き入力・出力できる。指定字を含む lexical token の物理削除を、生成禁止や混入減少の保証と解釈しない。

## 計画と加工結果

| 条件 | 残存行数 | 元 snapshot からの payload 削減 |
| --- | ---: | ---: |
| 記録済み v1 初期 policy | 128,803 | 53,298,432 bytes |
| v2 Math 保護＋指定5字（追加 Script なし、比較用 plan） | 128,517 | 53,957,376 bytes |
| 今回の追加 Script＋指定5字＋Math 保護 | **121,737** | **69,578,496 bytes** |

今回の plan は直接削除 29,932、間接削除 0、未登録行削除 267。前回の残存行から 7,067 行を削除し、Math 保護により元 snapshot から 1 行を復元したため、差し引き **7,066 行**減った。前回に対する追加 payload 削減は **16,280,064 bytes（約15.53 MiB）**。

復元した行は `؈`（U+0608、元 ID 150382、新 ID 121211）。Script=Arabic だが Math=true のため保護対象になる。既に削減したモデルからの再加工では復元できないので、今回も元 HF snapshot から加工した。

Math のみからなる **873 行**は全残存。指定5字を含む完全 UTF-8 token は **0 行**。Greek の 239 行と Canadian_Aboriginal の 72 行も保持した。

| 成果物・識別情報 | 値 |
| --- | --- |
| Profile | `policies/ja-en-coding-v2.json` |
| Run | `outputs/runs/ja-en-coding-v2` |
| 加工モデル | `outputs/models/qwen3-coder-next-4bit-pruned-ja-en-coding-v2` |
| Model files の論理サイズ | 44,778,921,439 bytes |
| Payload | 44,774,481,664 bytes |
| `tokenizer.json` SHA-256 | `4f00ea53adf66809989b56b5316807c9115cbabaff75b224912aebc264a3a423` |
| `plan.json` SHA-256 | `8861c2deb3a97d2c6b1ec17789dbd4c5dc2b869eab48aebf6bee833fd473ad78` |
| Manifest の policy SHA-256 | `51c9daf4846b5384cf751ef26a23091cded397dcd9e17994dd2a0913c7a85722` |

## 実行と検証

同じ条件で新しい run と model directory を作る場合は次を使う。作成済み path を上書きしない。

```sh
sbt --batch stageCli
scripts/vocab-surgery plan \
  --source models/models--mlx-community--Qwen3-Coder-Next-4bit/snapshots/7b9321eabb85ce79625cac3f61ea691e4ea984b5 \
  --policy policies/ja-en-coding-v2.json --run-dir outputs/runs/ja-en-coding-v2-new
scripts/vocab-surgery apply --plan outputs/runs/ja-en-coding-v2-new/plan.json \
  --model-out outputs/models/qwen3-coder-next-4bit-pruned-ja-en-coding-v2-new \
  --python /opt/homebrew/Cellar/omlx/0.7.0rc1/libexec/bin/python3.11
scripts/vocab-surgery verify --manifest outputs/runs/ja-en-coding-v2-new/manifest.json \
  --python /opt/homebrew/Cellar/omlx/0.7.0rc1/libexec/bin/python3.11
```

72 Scala tests、Scaladoc、CLI staging を確認した。テストには Math の on／off、禁止文字との混在、数学用途でも Math=false の文字、v1 plan／manifest の互換性、不正な version／型／null を含む。固定 snapshot と参照 tokenizer の opt-in coverage も有効にして実行した。

実モデルでは JVM heap 512 MiB で加工し、全 1,891 tensors の射影・非対象 byte 対応、全 source／output hashes、metadata、参照 tokenizer の検証を通してから公開した。独立した `verify` も成功。Math／指定5字／日本語／English／Python／Scala／TypeScript の round trip と、上記の policy invariants を別途確認した。旧 v1 モデルの policy hash も新 loader で一致する。

確認結果は run 内の `verification.json`、`reverification.json`、`policy-verification.json`、`math-and-manual-han-roundtrip.json` に保存した。これらとモデルは生成 artifacts として Git 対象外。

ユーザー指定により今回は **静的検証まで**。新モデルの推論、surviving logits、日本語・coding 品質、混入率、速度は未測定。v1 モデルの既知の logits 許容差超過は別の評価結果であり、新モデルの合否として流用しない。
