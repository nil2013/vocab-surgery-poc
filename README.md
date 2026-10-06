# vocab surgery

Qwen3-Coder-Next 4bit MLX モデルの不要な vocabulary と対応する embedding／LM head を削減する Scala プロジェクト。ビルドは sbt、package は `me.nsmr.vocabsurgery` を使用する。

第一工程のインフラ層と第二工程のドメイン操作を実装済み。漢字参照データとモデル metadata の読み込み、語彙分類、BPE cleanup、ID projection、6 tensors の行抽出と metadata の更新計画を利用できる。checkpoint の加工 pipeline と CLI は後続工程で実装する。

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

この integration test は指定 snapshot の設定・tokenizer・index と 9 個の shard headers を読み取り、仕様に記録した件数・byte 数とドメインの更新計画を検証する。元ファイルを書き換えず、payload の hash や推論品質は検証しない。

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

モデル入力は `models`、将来の加工モデル・分析結果は `outputs` に配置する。ローカル cache、build artifacts、生成結果は Git から除外し、`outputs/SPEC.md` は追跡する。

仕様と実装手順は [仕様書](outputs/SPEC.md)、第一工程の API と I/O は [インフラ層の設計](docs/INFRASTRUCTURE.md)、第二工程の操作・invariants・利用例は [ドメイン操作の設計](docs/DOMAIN.md) を参照。
