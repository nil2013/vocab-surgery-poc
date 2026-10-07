# 評価まわりの境界と production code 化の設計（2026-10-07）

`scripts/` に溜まった評価用の Python を整理し、日常的に使うものを Scala の production code にするための設計である。`scripts/` に残すのは sh で書ける程度の近道（CLI の短縮経路）だけにする。`scripts/` の Python は参照実装であり、正規の実装ではない。

境界と棲み分けは 2026-10-07 にユーザーと合意した。進み具合は 8 節以降に記録する。

## 1. 境界

境界をまたいで使ってよいのは、相手が公開しているインターフェースだけである。相手の内部の知識（データ形式の解釈、採点、手順）をこちら側に取り込まず、相手の内部を書き換えもしない。

| 境界 | こちら側 | 相手側 | またいでよいもの |
| --- | --- | --- | --- |
| B1. 外部の評価素材（今は `test-data` submodule、test-data-fn、coding-bench-lite。差し替えうる） | 加工したモデルを serve し、endpoint を用意する | ケースの形式、出題文の組み立て、採点、生成コードの実行とその隔離、指標、runner、集計 | 素材が文書で公開している CLI（`--base`、`--model`、`--label` など）と、素材が書き出す結果ファイル（こちらは置き場所を記録するだけで、中身を解釈しない） |
| B2. 推論環境（oMLX、TensorFold の clone） | 起動、終了、計測の段取り | 推論そのもの | 起動オプション、OpenAI 互換の HTTP API、文書化されたログの行（TF の `done req … accepted=`） |
| B3. 外部の参照実装（Python） | 結果の判定と記録 | HF tokenizers による tokenizer の独立検証（`check_tokenizer.py`）、MLX による logits の比較（`logits.py`） | 引数と JSON の入出力（今の `ExternalTools` と同じ） |
| B4. モデル系列・推論環境に固有の部分 | 共通の操作 | 系列ごと（`ModelFamily`）、推論環境ごとの実装 | trait のメソッド |
| B5. production code と `scripts/` | Scala の型・操作・CLI | sh の近道 | CLI の呼び出しだけ |

外部の素材に足りない機能（Linux での生成コードの隔離実行、生成だけを行うモード、結果の出力先の指定など）は、こちらで patch したり取り込んだりせず、素材の側への変更案として 6 節にまとめる。

test-data の submodule は差し替える可能性が高い。特定の素材のパス、ファイル名、ケースの形式を、production code・テスト・手順の文書に埋め込まない。どの素材を使うかは実行のたびに引数で与える。

## 2. 現状の棚卸しと行き先

| 現在のファイル | 中身 | 行き先 |
| --- | --- | --- |
| `scripts/sandbox.py` | 生成コードの隔離実行（macOS `sandbox-exec`、Docker） | production（`sandbox` パッケージ）。Python 版は参照実装として、Scala 版と結果が一致するのを確かめてから削除 |
| `scripts/code_checks.py` | 自前の固定 suite の生成コードを、関数テストで採点 | production（`evaluation` の自前 suite） |
| `scripts/evaluate.py` | oMLX での元モデルと加工モデルの比較、固定テスト、tokenization の比率、各ゲートの判定 | production（`evaluation` の自前 suite と比較）。ただし、chat template を適用した tokenization の計測は HF transformers に依存するので、B3 の参照実装として Python に残す |
| `scripts/review-evaluation.py` | 保存した応答と人の評価からの再集計 | production（`evaluation`） |
| `scripts/make-suite.py` | 固定 suite（`evaluation/default-suite.jsonl`）の生成 | suite は固定データとして残し、生成器は削除（作り直しは新しい suite の版として扱う） |
| `scripts/setup-evaluation-tools.py` | Node と TypeScript を checksum 付きで取得 | production（参照データの取得 `ReferenceFetch` と同じ形の取得操作） |
| `scripts/serve-evaluation.py` | 比較用の oMLX server の起動 | production（`runtime` の oMLX 実装） |
| `scripts/logits.py` | MLX での logits の比較 | B3 の参照実装として Python に残す |
| `scripts/check_tokenizer.py` | HF tokenizers での独立検証 | B3 の参照実装として Python に残す |
| `scripts/evaluate_testdata.py` | test-data の runner の内部（`CODE_CHECKS`、`common.py`）を取り込み、自前で採点 | **B1 違反。削除**。test-data の評価は素材の CLI を呼ぶ形にする |
| `scripts/flash-next/testdata-sandboxed-exec.patch` | test-data の runner の複製を書き換える | **B1 違反。削除** |
| `scripts/coding_bench_generate.py`、`scripts/coding_bench_score.py` | coding-bench-lite の `run.py` の内部を流用し、subprocess を差し替える | **B1 違反。削除**。生成だけのモードと隔離実行は素材への変更案にする |
| `scripts/probe_replies.py`、`scripts/prompts/*.json` | 混在した入力への応答を記録。test-data の `common.py` を import | **B1 違反。削除**。依頼文の集合は評価素材なので、素材の側への追加案にする |
| `scripts/claude_transcript_cases.py` | Claude Code の transcript から評価ケースを作る | 評価素材を作る道具なので、このリポジトリから外し、素材の側への追加案にする |
| `scripts/flash-next/bench.py` | MTP あり／なしの速度測定 | production（`evaluation` の速度測定。draft の切り替えは推論環境の capability） |
| `scripts/flash-next/acceptance.py` | TF のログから MTP の受理率を集計 | production（`runtime` の TensorFold 実装の capability）。test-data の結果ファイルとの照合はやめ、こちらが送った request の記録とだけ照合する |
| `scripts/flash-next/draft_vocab.py`、`draft-vocab/*.yaml` | 入れ替えた draft 語彙を作る | production（TensorFold 実装の操作）。入力のコーパスは、こちらが定義する中立の形式（生成文の JSONL）にし、評価素材のケースファイルは読まない |
| `scripts/flash-next/serve.sh`、`evaluate.sh` | TF の clone での serve と評価の段取り | production の CLI に置き換え、必要なら sh の近道として薄く残す |
| `scripts/flash-next/setup-tensorfold.sh`、`tensorfold-patches/` | TF の clone の取得、patch の適用、環境の構築 | 環境の構築なので sh のまま残す（git と uv の呼び出しだけ） |
| `scripts/greek_fragments.py` | Greek の語の断片の削除リストを作った一度きりの分析 | 削除。規則は文書に残し、作ったリスト（`policies/greek-fragments-flash-next.txt`）はデータとして残す |
| `scripts/vocab-surgery`、`scripts/run-surgery.sh` | CLI の起動、surgery の一連の手順 | sh の近道として残す |

## 3. Scala のモデル（型）

新しく 3 つのパッケージを置く。既存の `model`（系列）と同じく、実行環境や推論環境に固有の知識は trait の実装に閉じ込める。

### `sandbox`：生成コードの隔離実行

- `Confinement(workDirectory, readable: Vector[Path], environment: Map[String, String], timeout)`：書き込みは作業ディレクトリだけ、読み取りはシステムと `readable` だけ、ネットワークなし。
- `ConfinedResult`：`Completed(exitCode, stdout, stderr)` または `TimedOut`。どちらも実行した実装の名前を持つ。
- `trait Sandbox { def name; def unavailable: Option[String]; def run(command, confinement): ConfinedResult }`
- `object Sandbox { def platform(choice): Sandbox }`：`auto`、`sandbox-exec`、`docker`。使えるものがなければ例外で止め、隔離しない実行には切り替えない。実装（`MacSandboxExec`、`Docker`）は private にする（`FileTransfer` と同じ形）。

### `runtime`：推論環境（B2）

- `Endpoint(baseUri, servedModel)`
- `trait InferenceRuntime { def name; def serve(checkpoint, options, log): RunningServer }`、`trait RunningServer extends AutoCloseable { def endpoint }`
- 実装：`OMlx`（比較用の 2 entry、prefix cache なし）、`TensorFoldClone`（clone の場所、`--ple-on-ssd` などの起動オプション）
- 推論環境ごとの capability（持たない環境もある）：
  - `DraftSwitch`：request ごとに MTP の draft を切り替える（TF の `draft` field）
  - `DraftStatistics`：ログの行から、request ごとの受理数・提案数を取る（TF）
  - `DraftVocabulary`：draft 語彙のリストを差し替える（TF の `--draft-vocab`）

### `evaluation`：自前の評価と段取り

- `ChatClient`：OpenAI 互換の streaming client。text、tool calls、usage、TTFT と decode の時間を返す。
- 自前の固定 suite（`evaluation/default-suite.jsonl`、`outputs/SPEC.md` のゲート）：`SuiteCase`（chat、completion、tool）、`CodeTask`（言語ごとの関数テスト）、`Toolchain`（Python、Scala、TypeScript、Rust の実行手順。実行は必ず `Sandbox` を通す）、比較の集計と各ゲートの判定、人の評価からの再集計。
- 速度測定：固定の prompt を `DraftSwitch` があれば両方で送り、TTFT、decode tok/s、`DraftStatistics` があれば受理率を記録する。
- 外部の評価素材の段取り（B1）：`ExternalSuiteRun(sourceDirectory, command, label)`。素材のディレクトリを run directory に複製し（元の submodule は変えない）、素材が公開する CLI を endpoint を渡して実行し、終了コードと結果ファイルの場所を記録する。採点や集計は素材の CLI に任せ、こちらは結果の中身を読まない。どの CLI を呼ぶかは利用者が引数で与え、素材ごとの使い方をこちらに書き写さない。

### TensorFold に固有の操作

- `DraftVocabularyProfile`（基準のリスト、選ぶ規則、落とす規則、保護する token、明示的な追加・削除）と、`DraftVocabulary.rebuild(profile, corpus, tokenizer, checkpoint)`。コーパスは中立の形式（`{"text": …}` の JSONL）で受け取る。
- profile は今は YAML である。Scala で読むには YAML のライブラリを依存に足すか、既存の Circe で読める JSON に変える（7 節）。

## 4. CLI

既存の `inspect`、`plan`、`apply`、`verify` などと並べて、次を置く案である。名前は仮。

| command | 中身 |
| --- | --- |
| `serve` | `--runtime omlx\|tensorfold` で checkpoint を serve し、ready になったら endpoint を表示する。`--then <command…>` で、ready の後にコマンドを実行してから止める |
| `evaluate` | 自前の固定 suite による元モデルと加工モデルの比較（今の `evaluate` を Scala の実装に置き換える） |
| `measure-speed` | 速度、TTFT、受理率の測定 |
| `run-external` | 外部の評価素材の CLI を、serve した endpoint に対して実行して記録する |
| `draft-vocab` | draft 語彙の入れ替え（TensorFold） |
| `sandbox-check` | この host で使える sandbox と、使えない理由を表示する |
| `fetch-reference node`、`fetch-reference typescript` | 評価に使う Node と TypeScript の取得（今の `setup-evaluation-tools.py`。既存の参照データの取得に、公開された digest で照合する取得元として足す） |

## 5. 進め方

各段階は、型 → 操作 → CLI → 必要なら sh の近道、の順に作る。Python の参照実装と結果が一致するのを確かめてから、Python 版を削除する。

1. 境界違反のファイルを削除する（2 節で「B1 違反」としたもの）。文書から参照している手順も直す。
2. `sandbox`：Scala 版を作り、Python 版のテスト（ネットワークの遮断、作業ディレクトリ外への書き込みの拒否、`.venv` の Python の実行）を移す。
3. `runtime`：oMLX と TensorFold clone の起動、`DraftSwitch`、`DraftStatistics`、`DraftVocabulary`。
4. `evaluation`：`ChatClient`、速度測定、外部の評価素材の段取り。
5. 自前の固定 suite（`evaluate.py`、`code_checks.py`、`review-evaluation.py`）と、ツールの取得。
6. draft 語彙の入れ替え。

## 6. 外部の評価素材への変更案

- **test-data、test-data-fn**
  - runner が生成コードを隔離せずに実行している。Linux でも使える隔離の手段（たとえば外から与える実行コマンドの前置き）を runner に持たせる。
  - 結果の出力先を指定できるようにする（今はパッケージの中の `results/` に固定）。
  - 生成だけを行い、コードの実行を後回しにするモード。
  - 混在した入力への依頼文（今の `scripts/prompts/mixed-ja*.json`）と、Claude Code の transcript からケースを作る道具（今の `claude_transcript_cases.py`）を、素材の側のケースと道具として持つ。
  - draft 語彙のコーパスとして、replay の生成部分を中立の形式で書き出す機能。
- **coding-bench-lite**
  - 生成だけを行うモードと、保存した出力の採点（`rescore`）を、隔離して実行する手段。
  - Linux での隔離実行。

## 7. 決めたこと（2026-10-07）

- draft 語彙の profile は JSON にする（既存の Circe で読める。YAML のライブラリは足さない）。profile は規則だけを持ち、コーパスは引数で渡す。
- 自前の固定 suite（`evaluation/default-suite.jsonl` と `outputs/SPEC.md` のゲート）は、このプロジェクトの受け入れ基準としてこちら側に置く。
- chat template を適用した tokenization の計測は、B3 の参照実装（Python）に残す。
- Python が不可欠な箇所（HF tokenizers、transformers、MLX）は Python のまま残す。

## 8. 段階 1 の結果

2 節で「B1 違反」としたファイル（`evaluate_testdata.py`、test-data の runner への patch、`coding_bench_generate.py`、`coding_bench_score.py`、`probe_replies.py` と依頼文、`claude_transcript_cases.py`、`flash-next/evaluate.sh`）と、一度きりの `greek_fragments.py` を削除した。最後に含まれるコミットは `f163c9b`。

- `flash-next/bench.py` は、test-data の通信部分の import をやめ、自前の最小の client を持つ。
- `flash-next/acceptance.py` は、test-data の結果の形式を読まず、このプロジェクトが送った request の記録とだけ照合する。
- `flash-next/draft_vocab.py` は、評価素材のケースファイルを読まず、中立のコーパス（`{"text": …}` の JSONL）と JSON の profile を読む。
- AGENTS.md と評価の手順の文書から、特定の素材のパスとケースの形式を外した。過去のレポートは記録として残し、当時のスクリプトの在りかを書き添えた。

## 9. 段階 2 の結果（sandbox）

- `sandbox` パッケージ：`Confinement`、`ConfinedResult`（`Completed`／`TimedOut`）、`trait Sandbox`、`SandboxChoice`、`Sandbox.platform()`。実装 `MacSandboxExec` と `Docker` は外から見えない（`FileTransfer` と同じ形）。使える実装がなければ `SandboxUnavailable` で止め、隔離しない実行はない。
- CLI：`sandbox-check`（使える実装と理由）、`sandbox-run`（任意のコマンドを隔離して実行し、失敗なら終了コード 5）。
- テスト：`sandbox.SandboxSuite`（選択の規則、Docker の起動引数、symlink を通る interpreter の tree、環境、実際の隔離とタイムアウト）と `cli.CliSuite`。
- 参照実装との一致（実機確認、Docker）：偽の `docker` で記録した `docker run` の引数は、フラグ・mount・コマンドが一致し、環境変数は既定の `PATH` を揃えた後に一致した。同じ確認用スクリプトの結果（ネットワークなし、外への書き込みの拒否、作業ディレクトリへの書き込み、HOME、渡した環境変数）も一致した。
- Python の `scripts/sandbox.py` は、まだ `code_checks.py` が使うので、段階 5 で一緒に削除する。

## 10. 段階 3・4 の結果（推論環境、評価の段取り、速度）

- `runtime` パッケージ：`InferenceRuntime`、`RunningServer`、`ServeRequest`、`ServedModel`、`Endpoint`、capability の `DraftSwitch`・`DraftStatistics`・`ModelResidency`。実装は `OMlx` と `TensorFoldClone`。起動、ready の待機、`server.json` の記録、子プロセスまでの停止は共通（`ServerProcess`）。
- `evaluation` パッケージ：`ChatClient`（OpenAI 互換の streaming、tool call の組み立て、usage、TTFT）、`SpeedProbes`・`SpeedMeasurement`・`DraftAttribution`、`ExternalRun`。速度の prompt は固定データ `evaluation/speed-prompts.json` に移した。
- CLI：`serve`、`measure-speed`、`run-external`（serve の option は 3 つで共有）。`scripts/flash-next/serve.sh` は `vocab-surgery serve --runtime tensorfold` の近道になった。
- 参照実装との一致（実機確認、DGX、v4、TF の clone）：
  - Scala の `serve` で起動した server に対する速度測定は、以前 `serve.sh` で起動したときと出力 18 本がすべて一致し、受理数も一致した。
  - Scala の `measure-speed` は、Python の `bench.py` と `acceptance.py` と、出力 24 本・生成 token 数・受理数（ja 516/1468、en 956/1448、code 1264/1492）がすべて一致し、速度の差は 1% 前後だった。
- Python の `scripts/flash-next/bench.py` と `acceptance.py` を削除した。

## 11. 段階 5 の結果（自前の固定 suite、比較、ツールの取得）

- `evaluation` パッケージ：`QualitySuite`（`SuiteCase`、`CaseCategory`、`CodeTask`、`FunctionTest`、`ExpectedTool`）、`CodeChecks` と `Toolchains`（Python、Scala、TypeScript。実行は必ず `Sandbox` を通す。Rust はまだない）、`Comparison`（段階ごとに推論環境で serve し、warm-up、繰り返し、禁止 scalar の数え上げ、コードの採点、tool の追質問、server の RSS の標本、集計、`review` での再集計）、`ComparisonReferences`（B3 の参照実装の結果）。
- `runtime`：`ResourceSampler`（server の PID の RSS）。`TensorFoldClone` に `promptCache`（`false` で `--prompt-cache-gib 0`）を足した。clone の patch 0004 で、qwen4_exp の CUDA の serve が `--prompt-cache-gib 0` で prefix を再利用しなくなる。比較では毎回 prompt 全体を prefill させ、cache hit の応答は失敗とする。
- 参照データの取得：`fetch-reference node`（公式の `SHASUMS256.txt`）と `fetch-reference typescript`（npm registry の SHA-512 integrity）。公開された digest での照合（`PublishedDigest`）と、展開したディレクトリの tree digest を足した。
- B3 の参照実装：`scripts/tokenization.py`（`suite`：chat template を適用した前後の token 数と ID の対応、`locate`：出力位置の token、`versions`）。logits は従来どおり `scripts/logits.py`（`--logits` のときだけ）。
- CLI：`evaluate`（`--runtime omlx|tensorfold` を含む推論環境の option を共有）、`review`。
- 参照実装との一致（実機確認、DGX、Flash-Next の元モデルと v4、TF の clone）：
  - Scala の `evaluate` の出力（各段階 244 行）に Python の `evaluate.summarize` を当てた結論と値が、`metrics.json` と一致した（Scala だけが足す、出力位置の token の注記を除く）。
  - 実際の生成コード 104 行の採点は、Python の `code_checks.check` と合否がすべて一致した。baseline の 40 ケースの応答から作った 160 通り（元の応答、構文を壊したもの、値を変えたもの、空）でも一致し、不合格の判定を含む。
  - Node と TypeScript は、`setup-evaluation-tools.py` と同じ binary と digest を取得した。
- このときの結果：coding 30/30、FIM 10/10、tool 10/10（両方）。混入用 150 出力のうち禁止 scalar を含む出力は 3 → 0。日本語の目視評価は未入力。TF の usage には prefill／decode の速度がないので、suite 内の速度は TTFT、全時間、scalar/s だけが値を持つ。
- テスト：`evaluation.OwnSuiteSuite`、`processing.ProcessingSuite`（ツールの取得）、`cli.CliSuite`（偽の helper と推論環境での `evaluate`・`review`）。Python 側は `scripts/tests/test_references.py`（`check_tokenizer.py`、`logits.py`、`tokenization.py`）。
- Python の `sandbox.py`、`code_checks.py`、`evaluate.py`、`review-evaluation.py`、`make-suite.py`、`serve-evaluation.py`、`setup-evaluation-tools.py` と、そのテストを削除した。
