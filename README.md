# vocab surgery

MLX safetensors モデルの不要な vocabulary と対応する embedding／LM head の行を削減する Scala プロジェクト。ビルドは sbt、package は `me.nsmr.vocabsurgery` を使用する。対応するモデル系列は Qwen3-Coder-Next と Qwen3.8-Flash-Next で、系列ごとの差は [モデル系列と拡張境界](docs/MODEL_FAMILIES.md) に記述した `ModelFamily` 等の実装に閉じる。

インフラ層、ドメイン操作、checkpoint の加工・検証機構、CLI と評価 helper を実装している。モデル全体を JVM に展開せず、語彙行を持つ tensors を共通の行順序で加工し、全 tensor の byte 一致と参照 tokenizer を検証してから新しいモデルを公開する。oMLX での before／after 評価は Coder-Next について独立して実行できる。

## ビルドと検証

Scala 3.3.8、sbt 2.0.10 を固定している。JDK 17 以降と sbt を用意し、`kanji-data` submodule を初期化する。[sbt 2 の JDK 要件](https://scala-lang.org/blog/2026/06/29/sbt2.html)

```sh
git submodule update --init kanji-data
sbt --batch testFull
```

テストには同梱の漢字参照データ、合成 metadata、40 GiB の sparse checkpoint を使用する。モデルのダウンロードや重みの展開は行わない。テスト JVM の最大 heap は 512 MiB。sbt の JVM と OS の page cache はこの上限に含まれない。

実モデルの検証は明示的に有効化する。次は、本 workspace の snapshot を使用するコマンドである。`set` から始まる単一のコマンド列で、テスト JVM に path を渡す。対応する系列の snapshot であれば同じコマンドで実行でき、policy は `vocabSurgery.policy`（既定 `policies/ja-en-coding-v2.json`）で変えられる。

```sh
sbt --batch ';set Test / javaOptions += "-DvocabSurgery.snapshot=models/models--mlx-community--Qwen3-Coder-Next-4bit/snapshots/7b9321eabb85ce79625cac3f61ea691e4ea984b5";testOnly me.nsmr.vocabsurgery.model.LocalSnapshotSuite'
```

この integration test は指定 snapshot の設定・tokenizer・index と全 shard headers を読み取り、系列によらない不変条件（未登録行数、基礎 token の保護、added tokens の残存、削減 byte 数と行数の対応、対象 tensor だけの縮小、書き換え後の ID 範囲）で更新計画を検証する。元ファイルを書き換えず、payload の hash や推論品質は検証しない。

`tokenizers` をインストール済みの Python を test JVM の `vocabSurgery.tokenizersPython` property に追加すると、更新済み tokenizer JSON の参照実装でのロード、10,086 字の NFC 一致、日本語・coding・protocol 入力の round trip も検証する。Python process はネットワークにアクセスせず、一時ファイルは終了時に削除する。参照検証用の Python は repository root の `.venv` に用意できる。

```sh
uv venv .venv
uv pip install --python .venv/bin/python tokenizers transformers jinja2
```

Coder-Next の snapshot では次のように実行する。Flash-Next では snapshot path を置換する。

```sh
sbt --batch ';set Test / javaOptions ++= Seq("-DvocabSurgery.snapshot=models/models--mlx-community--Qwen3-Coder-Next-4bit/snapshots/7b9321eabb85ce79625cac3f61ea691e4ea984b5", "-DvocabSurgery.tokenizersPython=.venv/bin/python");testOnly me.nsmr.vocabsurgery.model.LocalSnapshotSuite'
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

launcher は `JAVA_HOME`、なければ `PATH` の `java` を使い、JDK 17 未満なら停止する。参照検証には `tokenizers`、`transformers`、`jinja2` を利用できる interpreter（上記の `.venv` 等）を渡す。CLI は Python packages やモデルをダウンロードしない。

```sh
SOURCE=models/models--mlx-community--Qwen3-Coder-Next-4bit/snapshots/7b9321eabb85ce79625cac3f61ea691e4ea984b5
RUN=outputs/runs/my-first-run
MODEL=outputs/models/qwen3-coder-next-pruned-my-first-run
EVAL_PYTHON=.venv/bin/python

scripts/vocab-surgery inspect --source "$SOURCE" --run-dir "$RUN"
scripts/vocab-surgery plan --source "$SOURCE" --run-dir "$RUN"
scripts/vocab-surgery apply --plan "$RUN/plan.json" --model-out "$MODEL" --python "$EVAL_PYTHON"
scripts/vocab-surgery verify --manifest "$RUN/manifest.json" --python "$EVAL_PYTHON" --report "$RUN/reverification.json"
```

`inspect`／`plan` は重み payload を読まず、分析と更新計画を保存する。`plan` は新しい run、または同じ source の `inspection.json` だけを持つ run を受け付ける。既存の計画・モデル・report は上書きしない。標準出力は結果 JSON、標準エラーは進捗と診断。

初期 policy は Unicode 17.0.0 の Arabic／Cyrillic／Hangul と、日本語参照集合外の Han 漢字を対象とし、`protectMath=true` を既定にする。変更には `plan --policy outputs/config/pruning.json` を使う。[policy と処理設計](docs/PROCESSING.md) に形式、保護集合、容量、staging、失敗時の再実行方法を記載している。未加工 shard は copy-on-write clone（macOS `clonefile`、Linux `FICLONE`）を試し、ファイルシステムが対応しなければ bounded copy に切り替える。`apply --copy-only` で clone を無効にできる。

日英 coding 用の追加削減には `--policy policies/ja-en-coding-v2.json` を指定できる。この profile は Greek／Canadian_Aboriginal を残し、他の追加 Script と `个`・`开`・`关`・`无`・`网` を対象とする。[設定・用例・加工結果](docs/PRUNING_JA_EN_CODING_V2.md)を参照。

上記の一連の操作は次でも実行できる。run は `outputs/runs/<name>`、モデルは `outputs/models/<name>` に作られる。`--python` の既定は `.venv/bin/python`。

```sh
scripts/run-surgery.sh --source "$SOURCE" --name my-first-run --policy policies/ja-en-coding-v2.json
```

MTP の draft 語彙リストなど、元モデルの token ID で書かれたリストは、加工後の ID に変換してから使う。`--translate-ids <file>` を付けると、`translate-ids` で `outputs/runs/<name>/translated-ids.txt` を作る。単独では次のように実行する。

```sh
scripts/vocab-surgery translate-ids --manifest "$RUN/manifest.json" --ids draft_vocab.txt --out "$RUN/draft-ids.txt"
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

新しいモデル系列を追加する手順は [モデル系列と拡張境界](docs/MODEL_FAMILIES.md) を参照。[拡張性評価と設計提案](docs/EXTENSIBILITY.md) は、その境界を導入する前（`f2106a5`）に Ornith snapshot を素材として行った調査の記録である。

追加 pruning を検討する際は、[初期 pruning 後の残存語彙調査（91fb703）](docs/REMAINING_VOCABULARY_91fb703.md) を参照。Flash-Next の `ja-en-coding-v2` 加工後については [REMAINING_VOCABULARY_FLASH_NEXT.md](docs/REMAINING_VOCABULARY_FLASH_NEXT.md)。この版の policy で残る文字体系・語彙・記号の件数と例、数学文字との共用、集計条件を記録している。
