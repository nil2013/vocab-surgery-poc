# 第三工程の処理機構

`me.nsmr.vocabsurgery.processing` は immutable なドメイン計画をファイル操作へ接続する。loader／domain／processing／CLI の境界は保ち、CLI に削除判定や行抽出を重複実装しない。実行コマンドは [README](../README.md)、policy の意味は [DOMAIN.md](DOMAIN.md) を参照。

## 計画と入力の同一性

`PolicyFile` は policy JSON を読む。`kind` で戦略を選び、`parameterCount` で index の parameter 数の扱いを決め、残りの fields は kind ごとの `PolicySettings` が読む。未知の kind・field、型違い、null、未対応値は拒否する。`PolicySettings` は戦略が読む参照入力（`inputs`）を宣言し、`load` で domain の `PruningPolicy` と入力の指紋（`fingerprint`）、kind 固有のレポートを返す。plan は指紋を保存し、再計算時に一致を要求する。出力は参照入力と重なってはならない。`plan --policy` を省略すると既定の `characters` policy を使う。

現行の kind は `characters`（`CharacterPolicySettings` → `CharacterPolicy`）だけで、`japaneseReference` に日本語漢字の参照データ directory（既定 `kanji-data`）を指定する。`protectMath=true` が既定値。例えば現在の全 default を明記した policy は以下。

```json
{
  "kind": "characters",
  "japaneseReference": "kanji-data",
  "unicodeVersion": "17.0.0",
  "forbiddenScripts": ["Arabic", "Cyrillic", "Hangul"],
  "pruneHanOutsideReference": true,
  "protectMath": true,
  "japaneseExtra": [],
  "explicitForbidden": [],
  "protectedIds": [],
  "protectedContents": [],
  "unregisteredRows": "drop",
  "parameterCount": "omit-unverified"
}
```

文字集合は Unicode scalar の整数値、ID は旧整数 ID、content は文字列で指定する。`protectedContents` は added token の literal、既存の encoded key、decoded UTF-8 surface を照合する。存在しない保護指定は停止条件。`parameterCount` の別値 `verified-logical-weights` はドメインの検証済み logical weight 数を採用する。未登録行 policy は現在 `drop` のみ。

`deletedTokens` は token 単位の削除リストのパス（任意）。リストは 1 行に JSON 文字列リテラルを 1 つ書く形式で（`" yang"`、`"什么"`、`"\n\n"`）、先頭の空白や改行を曖昧さなく書ける。空行と `#` で始まる行は読み飛ばし、重複行は停止条件。added token はその内容、lexical token は decoded surface との完全一致で照合し、一致した token を理由 `listed` で削除する。構造的保護や `protectedContents`・Math 保護に当たる token は残し、衝突として報告する。どの token にも一致しない行は停止条件にせず、`summary.json` の `policy.deletionList` と `deletion-list-unmatched.jsonl` に出すので、同じリストを複数のモデルに使える。リストは参照入力として SHA-256 を plan に記録し、編集すると保存済み plan の再検証が止まる。`summary.json` の `rowsAboveMultiple` は、行数を 64／128／256 の倍数に合わせるのにあと何行削ればよいかを示す。

`pruneSimplifiedForms`／`pruneTraditionalForms`（既定 false）は、Unihan の `kTraditionalVariant`／`kSimplifiedVariant` で自分と別の字を持つ Han 漢字（簡体字形／繁体字・旧字体形）を、日本語参照集合の中にあっても禁止する。常用漢字と `子の名に使える漢字`（人名用、常用の異体字）は除く。Unihan は `scripts/vocab-surgery fetch-unihan` で `outputs/reference/unihan/<版>/` に取得する。版の既定は ICU4J の Unicode 版（現在 17.0.0）で、`--unicode-version` で変えられる。取得は版付きの URL から staging に行い、既知の SHA-256（17.0.0）または `--sha256` と照合してから置換なしで公開する。照合できない版は `verified=false` として記録する。既に取得済みの版は再取得しない。policy は `unihan`（省略時は ICU の版の取得先）を読み、Unihan の版が ICU と異なれば停止する。Unihan のファイルの SHA-256 は policy 入力の指紋に入る。判定した字は `han-variant-forms.jsonl` に出る。常用・人名用の外にも日本語で使う字（`痒 洒 涛 疱 佇 儘 嘔 囁` など）が含まれるので、有効にする前にこの一覧を確認する。

`protectMath=true` は ICU の `Math=true` の scalar を Script／Han／明示禁止判定から除外する。Math の文字だけからなる token は `Math` 理由で BPE cleanup からも保護する。混在 token に別の禁止文字があれば削除対象になる。`false` はこの追加保護だけを無効にし、基礎 bytes・protocol・手動保護を解除しない。`α`・`λ`・`א` の数学用途は Math 属性に含まれないため、Script の選択や明示保護で扱う。

policy・plan・manifest は版番号を持たない。形式が変わった後の古い成果物は移行せず、入力から作り直す。日英 coding 用の追加 profile は [PRUNING_JA_EN_CODING_V2.md](PRUNING_JA_EN_CODING_V2.md) を参照。

`PlanStore.prepare` は source と policy の参照入力の実 path を確定し、loaders、policy、`ModelOperations.plan` を合成する。`InputInventory` は全ファイルの相対名、サイズ、mtime、file key を記録する。metadata と assets は全 SHA-256、safetensors は header SHA-256 を記録する。plan 時点では巨大な payload 全体の hash は計算しない。入力の cache file symlink は参照できるが、symlink directory、特殊ファイル、index 外の safetensors は拒否する。

`plan.json` は source、実 path にした policy、policy 入力の指紋（`characters` では漢字入力の commit と hash、Unicode provider）、inventory、モデル系列、projection、shard headers、生成する出力ファイルの SHA-256 を含む。canonical JSON は key 順序を固定し、整数を `Double` に変換しない。`PlanStore.loadVerified` は現在の入力から計画を再計算し、署名と内容を比較する。保存された byte ranges を無検証に実行しない。

`PlanReports` は inspection、effective policy と入力の指紋、双方向 mapping、token と削除・保護理由、BPE cleanup、summary を保存し、policy 固有のレポート（`characters` では漢字 provenance／coverage／候補／テキスト欠落診断）を policy に書かせる。`environment.json` は tool の git commit、JVM、OS、heap、作成時刻の記録。時刻はモデル bytes に混ぜない。`inspect` のみの run を `plan` で拡張する場合は、inspection と現在の source metadata の一致を確認する。

## 書き込みと公開

`ProcessingIO` は出力を指定した `outputs` root 配下に限定し、入力と漢字 directory への重なりを拒否する。root または子孫の symlink、path traversal、既存出力の置換は拒否する。root より上の `/var` → `/private/var` 等の OS alias は実 path で照合する。

`CheckpointProcessor.applyPlan` の順序は以下。

1. 保存計画と入力を再検証し、出力・run の再利用を拒否する。
2. 全ファイルをコピーする最悪条件と 64 MiB reserve で容量を確認する。
3. UUID の新しい `outputs/.staging` directory に書き込む。
4. 計画した出力ファイル（config、tokenizer、index、付随ファイル、系列のファイル）を書く。対象 shard は新 header と copy spans で再構築する。その他のファイルは `FileTransfer` の copy-on-write clone を試し、できなければコピーする。
5. 出力ファイルの内容／ID／BPE／全 tensor の byte 対応と、外部参照 tokenizer を検証する。出力のファイル集合は source のファイルと生成ファイルの和に一致する必要がある。
6. source の変更を再確認し、全 source／output files の SHA-256 とサイズ、コピー方式、検証結果を manifest に保存する。
7. staging を最終 model directory に公開し、`publication.json` に完了時刻を記録する。

公開は `FileTransfer.publishNew` が行い、既存 directory を置換しない（macOS `renamex_np(RENAME_EXCL)`、Linux `renameat2(RENAME_NOREPLACE)`）。排他的な rename を持たない OS では処理を開始しない。出力は独立した regular files とし、hard link と model 内の symlink は許さない。copy-on-write clone は書き込み時に分離されるため、source inode への依存にはならない。manifest の各ファイルの `method` は `generated`、`row-projection`、clone 方式名（`clonefile`／`ficlone`）、`buffered-copy` のいずれか。

`BinaryTransfer` はデフォルト 1 MiB、最大 16 MiB の buffer と checked `Long` 計算で転送する。safetensors offsets は payload-relative。packed weights、scales、biases は同じ projection 順序で切り出す。非対象 tensor も source と全 byte を比較する。再量子化や Transformer／MoE の変更は行わない。

参照検証 callback は `passed=true` を返す必要があり、省略できない。CLI は常に [check_tokenizer.py](../scripts/check_tokenizer.py) を使う。この helper は tokenizer の round trip と chat template に加え、各行が source の同じ token を保持すること、`vocab.json`／`merges.txt` が `tokenizer.json` と一致すること、`ple-token-ids.json` が射影（と source の表）に従うことを確認する。合成テストだけは明示した synthetic contract を使い、Hugging Face coverage と区別する。

## 検証と失敗時の扱い

`verifyManifest` は保存計画の SHA、再計算した計画、全ファイルの SHA と inventory、全 tensor byte 対応、参照 tokenizer を再検証する。output 単独で元入力の同一性を確認することはできないため、source、漢字入力、plan を保持する。

容量不足は staging 作成前に停止する。書き込みや参照検証の失敗は staging と `failure.json` を残す。途中のファイルは完成モデルとして使わない。manifest／verification は公開直前に保存するため、それだけで公開完了とは判断せず、`publication.json` と `verify` の成功を確認する。

再開機能は実装していない。原因を直して新しい run と model path で計画を作り直す。公開が未完了だった staging は内容を確認してから手動で片付ける。既存 run の report を消して使い回す運用は行わない。

`ProcessingSuite` は計画改変、source／漢字変更、容量不足、参照検証失敗、既存出力、置換しない公開、symlink／escape、manifest／payload 改変、Flash-Next 形式の適用と生成ファイルの欠落検出、削除なしのコピー、clone の inode 分離、2 GiB 超の sparse offset を検証する。CLI の引数・help・helper 失敗・timeout・評価 gate は `CliSuite`。JVM heap 512 MiB と sparse files によるチェックを維持する。
