# 第三工程の処理機構

`me.nsmr.vocabsurgery.processing` は immutable なドメイン計画をファイル操作へ接続する。loader／domain／processing／CLI の境界は保ち、CLI に削除判定や行抽出を重複実装しない。実行コマンドは [README](../README.md)、policy の意味は [DOMAIN.md](DOMAIN.md) を参照。

## 計画と入力の同一性

`PolicySettings` は version 1／2 の JSON を読み、未知の field、型違い、null、未対応値を拒否する。新規設定は version 2 で、`protectMath=true` が既定値。version 1 は従来どおり Math 保護なしで読み書きし、保存済み plan と manifest の policy hash・意味を維持する。例えば現在の全 default を明記した policy は以下。

```json
{
  "formatVersion": 2,
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

`protectMath=true` は ICU の `Math=true` の scalar を Script／Han／明示禁止判定から除外する。Math の文字だけからなる token は `Math` 理由で BPE cleanup からも保護する。混在 token に別の禁止文字があれば削除対象になる。`false` はこの追加保護だけを無効にし、基礎 bytes・protocol・手動保護を解除しない。`α`・`λ`・`א` の数学用途は Math 属性に含まれないため、Script の選択や明示保護で扱う。

version 1 の JSON に `protectMath` を追加することはできない。既存設定を変更する場合は新しい version 2 policy で新規 plan を作る。plan／manifest 自体の container formatVersion は引き続き 1 で、policy の version と別に管理する。日英 coding 用の追加 profile は [PRUNING_JA_EN_CODING_V2.md](PRUNING_JA_EN_CODING_V2.md) を参照。

`PlanStore.prepare` は source／漢字 directory の実 path を確定し、loaders、policy、`ModelOperations.plan` を合成する。`InputInventory` は全ファイルの相対名、サイズ、mtime、file key を記録する。metadata と assets は全 SHA-256、safetensors は header SHA-256 を記録する。plan 時点では巨大な payload 全体の hash は計算しない。入力の cache file symlink は参照できるが、symlink directory、特殊ファイル、index 外の safetensors は拒否する。

`plan.json` は tool／format version、source、漢字入力 commit と hash、settings、inventory、projection、shard headers、metadata 更新を含む。canonical JSON は key 順序を固定し、整数を `Double` に変換しない。`PlanStore.loadVerified` は現在の入力から計画を再計算し、署名と内容を比較する。保存された byte ranges を無検証に実行しない。

`PlanReports` は inspection、effective policy、双方向 mapping、token と削除理由、BPE cleanup、漢字 provenance／coverage／候補／テキスト欠落診断、summary を保存する。`environment.json` は JVM、OS、heap、作成時刻の sidecar。時刻はモデル bytes に混ぜない。`inspect` のみの run を `plan` で拡張する場合は、inspection と現在の source metadata の一致を確認する。

## 書き込みと公開

`ProcessingIO` は出力を指定した `outputs` root 配下に限定し、入力と漢字 directory への重なりを拒否する。root または子孫の symlink、path traversal、既存出力の置換は拒否する。root より上の `/var` → `/private/var` 等の OS alias は実 path で照合する。

`CheckpointProcessor.applyPlan` の順序は以下。

1. 保存計画と入力を再検証し、出力・run の再利用を拒否する。
2. 全ファイルをコピーする最悪条件と 64 MiB reserve で容量を確認する。
3. UUID の新しい `outputs/.staging` directory に書き込む。
4. 対象 shard は新 header と copy spans で再構築する。未対象ファイルは macOS `clonefile` を試し、失敗時はコピーする。
5. metadata／ID／BPE／全 tensor の byte 対応と、外部参照 tokenizer を検証する。
6. source の変更を再確認し、全 source／output files の SHA-256 とサイズ、コピー方式、検証結果を manifest に保存する。
7. staging を最終 model directory に公開し、`publication.json` に完了時刻を記録する。

macOS の公開は `renamex_np(RENAME_EXCL)` で既存 directory を置換しない。出力は独立した regular files とし、hard link と model 内の symlink は許さない。APFS clone は書き込み時に分離されるため、source inode への依存にはならない。

`BinaryTransfer` はデフォルト 1 MiB、最大 16 MiB の buffer と checked `Long` 計算で転送する。safetensors offsets は payload-relative。packed weights、scales、biases は同じ projection 順序で切り出す。非対象 tensor も source と全 byte を比較する。再量子化や Transformer／MoE の変更は行わない。

参照検証 callback は `passed=true` を返す必要があり、省略できない。CLI は常に [check_tokenizer.py](../scripts/check_tokenizer.py) を使う。合成テストだけは明示した synthetic contract を使い、Hugging Face coverage と区別する。

## 検証と失敗時の扱い

`verifyManifest` は保存計画の SHA、再計算した計画、全ファイルの SHA と inventory、全 tensor byte 対応、参照 tokenizer を再検証する。output 単独で元入力の同一性を確認することはできないため、source、漢字入力、plan を保持する。

容量不足は staging 作成前に停止する。書き込みや参照検証の失敗は staging と `failure.json` を残す。途中のファイルは完成モデルとして使わない。manifest／verification は公開直前に保存するため、それだけで公開完了とは判断せず、`publication.json` と `verify` の成功を確認する。

再開機能は実装していない。原因を直して新しい run と model path で計画を作り直す。公開が未完了だった staging は内容を確認してから手動で片付ける。既存 run の report を消して使い回す運用は行わない。

`ProcessingSuite` は計画改変、source／漢字変更、容量不足、参照検証失敗、既存出力、symlink／escape、manifest／payload 改変、削除なしのコピー、clone の inode 分離、2 GiB 超の sparse offset を検証する。CLI の引数・help・helper 失敗・timeout・評価 gate は `CliSuite`。JVM heap 512 MiB と sparse files によるチェックを維持する。
