# vocab surgery

Qwen3-Coder-Next 4bit MLX モデルの不要な vocabulary と対応する embedding／LM head を削減する Scala プロジェクト。ビルドは sbt、package は `me.nsmr.vocabsurgery` を使用する。

インフラ層、ドメイン操作、checkpoint の加工・検証機構、CLI と評価 helper を実装している。モデル全体を JVM に展開せず、量子化済みの 6 tensors を共通の行順序で加工し、全 tensor の byte 一致と参照 tokenizer を検証してから新しいモデルを公開する。oMLX での before／after 評価は独立して実行できる。

## ビルドと検証

Scala 3.3.8、sbt 2.0.10 を固定している。JDK 17 以降と sbt を用意し、`kanji-data` submodule を初期化する。[sbt 2 の JDK 要件](https://scala-lang.org/blog/2026/06/29/sbt2.html)

```sh
git submodule update --init kanji-data
sbt --batch testFull
```

テストには同梱の漢字参照データ、合成 metadata、40 GiB の sparse checkpoint を使用する。モデルのダウンロードや重みの展開は行わない。テスト JVM の最大 heap は 512 MiB。sbt の JVM と OS の page cache はこの上限に含まれない。

実モデルの検証は明示的に有効化する。次は、本 workspace の snapshot を使用するコマンドである。`set` から始まる単一のコマンド列で、テスト JVM に path を渡す。

```sh
sbt --batch ';set Test / javaOptions += "-DvocabSurgery.snapshot=models/models--mlx-community--Qwen3-Coder-Next-4bit/snapshots/7b9321eabb85ce79625cac3f61ea691e4ea984b5";testOnly me.nsmr.vocabsurgery.model.LocalSnapshotSuite'
```

この integration test は指定 snapshot の設定・tokenizer・index と 9 個の shard headers を読み取り、version 1 初期 policy（Math 保護なし）で仕様に記録した件数・byte 数とドメインの更新計画を検証する。元ファイルを書き換えず、payload の hash や推論品質は検証しない。

`tokenizers` をインストール済みの Python を test JVM の `vocabSurgery.tokenizersPython` property に追加すると、更新済み tokenizer JSON の参照実装でのロード、10,086 字の NFC 一致、日本語・coding・protocol 入力の round trip も検証する。Python process はネットワークにアクセスせず、一時ファイルは終了時に削除する。この workspace の oMLX で使われる Python では次を実行できる。異なる環境では Python の絶対 path を置換する。

```sh
sbt --batch ';set Test / javaOptions ++= Seq("-DvocabSurgery.snapshot=models/models--mlx-community--Qwen3-Coder-Next-4bit/snapshots/7b9321eabb85ce79625cac3f61ea691e4ea984b5", "-DvocabSurgery.tokenizersPython=/opt/homebrew/Cellar/omlx/0.7.0rc1/libexec/bin/python3.11");testOnly me.nsmr.vocabsurgery.model.LocalSnapshotSuite'
```

snapshot 未指定時は integration test 全体をスキップする。Python 未指定時は metadata／計画の検証まで実行し、参照実装との比較は行わない。モデル・Python packages の自動ダウンロードはしない。

sbt 2 の `test` は変更の影響があるテストを実行する。全テストを cache によらず実行するには `testFull` を使う。[sbt の testing task](https://www.scala-sbt.org/2.x/docs/en/reference/sbt-test.html)

```sh
sbt --batch testFull
sbt --batch doc
```

## Loader の利用例

`sbt console` 等で次の API を利用できる。

```scala
import java.nio.file.Path
import me.nsmr.vocabsurgery.kanji.KanjiLoader
import me.nsmr.vocabsurgery.model.SnapshotLoader

val kanji = KanjiLoader.load(Path.of("kanji-data"))
val model = SnapshotLoader.load(
  Path.of("models/models--mlx-community--Qwen3-Coder-Next-4bit/snapshots/7b9321eabb85ce79625cac3f61ea691e4ea984b5")
)
```

loader は失敗時に入力 path、field／record、原因を持つ `DataException` を返す例外方式を採る。漢検の文字テキスト欠落など、データ形式として許容する値は records と診断を保持する。

モデル入力は `models`、加工モデル・分析結果は `outputs` に配置する。ローカル cache、build artifacts、生成結果は Git から除外し、`outputs/SPEC.md` は追跡する。

## CLI で加工する

repository root から実行する。`stageCli` は runtime jars と、code 評価用の Scala compiler を `target/cli` に配置する。ソースや依存関係を変更したら再実行する。

```sh
sbt --batch stageCli
scripts/vocab-surgery --help
scripts/vocab-surgery help apply
```

この Mac の既存 oMLX Python を利用する例。別の環境では `tokenizers` と `transformers` を利用できる interpreter に置換する。CLI は Python packages やモデルをダウンロードしない。

```sh
SOURCE=models/models--mlx-community--Qwen3-Coder-Next-4bit/snapshots/7b9321eabb85ce79625cac3f61ea691e4ea984b5
RUN=outputs/runs/my-first-run
MODEL=outputs/models/qwen3-coder-next-pruned-my-first-run
EVAL_PYTHON=/opt/homebrew/Cellar/omlx/0.7.0rc1/libexec/bin/python3.11

scripts/vocab-surgery inspect --source "$SOURCE" --run-dir "$RUN"
scripts/vocab-surgery plan --source "$SOURCE" --run-dir "$RUN"
scripts/vocab-surgery apply --plan "$RUN/plan.json" --model-out "$MODEL" --python "$EVAL_PYTHON"
scripts/vocab-surgery verify --manifest "$RUN/manifest.json" --python "$EVAL_PYTHON" --report "$RUN/reverification.json"
```

`inspect`／`plan` は重み payload を読まず、分析と更新計画を保存する。`plan` は新しい run、または同じ source の `inspection.json` だけを持つ run を受け付ける。既存の計画・モデル・report は上書きしない。標準出力は結果 JSON、標準エラーは進捗と診断。

初期 policy は Unicode 17.0.0 の Arabic／Cyrillic／Hangul と、日本語参照集合外の Han 漢字を対象とする。変更には `plan --policy outputs/config/pruning.json` を使う。[policy と処理設計](docs/PROCESSING.md) に形式、保護集合、容量、staging、失敗時の再実行方法を記載している。未加工 shard は APFS clone を試し、失敗時は bounded copy に切り替える。`apply --copy-only` で clone を無効にできる。

現在の新規 policy は version 2 で、`protectMath=true` を既定にする。日英 coding 用の追加削減には `--policy policies/ja-en-coding-v2.json` を指定できる。この profile は Greek／Canadian_Aboriginal を残し、他の追加 Script と `个`・`开`・`关`・`无`・`网` を対象とする。[設定・用例・加工結果](docs/PRUNING_JA_EN_CODING_V2.md)を参照。

上記の一連の操作は次でも実行できる。

```sh
scripts/run-surgery.sh "$SOURCE" "$RUN" "$MODEL" "$EVAL_PYTHON"
```

`apply` と `verify` は全 shard の比較と hash 計算を行うため、モデル全体の I/O が発生する。JVM heap は launcher で 512 MiB に制限する。必要空き容量は clone 成功を前提とせず見積もる。

## 評価する

[評価の手順](docs/EVALUATION.md) を参照して、固定 suite、明示した oMLX 接続先とモデル名、独立した出力 directory を用意する。日本語品質は目視評価を待つ状態で保存される。数値 gate の超過は終了コード 4 となり、許容差は自動で変更しない。

評価 helper の検証には次を使う。Scala／TypeScript の実行テストは `stageCli` と文書に記載した明示的な tool setup が必要で、利用できない場合はスキップと表示される。

```sh
python3 -m unittest discover -s scripts/tests -v
sh -n scripts/vocab-surgery scripts/run-surgery.sh
```

仕様と実装手順は [仕様書](outputs/SPEC.md)、loader の API と I/O は [インフラ層の設計](docs/INFRASTRUCTURE.md)、純粋な操作と invariants は [ドメイン操作の設計](docs/DOMAIN.md)、ファイル加工は [処理機構の設計](docs/PROCESSING.md)、before／after の条件と判定は [評価の手順](docs/EVALUATION.md) を参照。

新しいモデル構造への拡張を検討する際は、[拡張性評価と設計提案](docs/EXTENSIBILITY.md) を参照。既存の Ornith snapshot をアーキテクチャの汎用性のストレステスト素材として、再利用できる基盤・モデル固有の前提・adapter による拡張案を記録している。Ornith 対応はまだ実装していない。

追加 pruning を検討する際は、[初期 pruning 後の残存語彙調査（91fb703）](docs/REMAINING_VOCABULARY_91fb703.md) を参照。この版の policy で残る文字体系・語彙・記号の件数と例、数学文字との共用、集計条件を記録している。
