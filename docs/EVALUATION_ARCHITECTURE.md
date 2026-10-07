# 評価まわりの境界と production code 化の設計（2026-10-07）

`scripts/` に溜まった評価用の Python を整理し、日常的に使うものを Scala の production code にするための設計である。`scripts/` に残すのは sh で書ける程度の近道（CLI の短縮経路）だけにする。`scripts/` の Python は参照実装であり、正規の実装ではない。

境界と棲み分けは 2026-10-07 にユーザーと合意した。段階 1（境界違反の削除）は済み、段階 2 以降は未実装。

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
| `fetch-tools` | 評価に使う Node と TypeScript の取得（今の `setup-evaluation-tools.py`） |

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
