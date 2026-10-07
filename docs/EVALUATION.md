# 元モデルと加工モデルの比較評価

`scripts/vocab-surgery evaluate` は、manifest を全面再検証してから、元モデルと加工モデルを推論環境（`--runtime omlx|tensorfold`）で順に serve し、このプロジェクトの固定 suite で比較する。削除判定は Scala の policy（ICU の scalar predicate）から取り、Python 自身の Unicode 版に依存させない。加工手順は [README](../README.md)、判定基準は [仕様書](../outputs/SPEC.md)、境界は [EVALUATION_ARCHITECTURE.md](EVALUATION_ARCHITECTURE.md) を参照。

## 前提と固定入力

`--python` には `tokenizers`、`transformers`（`--logits` では加えて `mlx`、`mlx-lm`、`numpy`）が使える interpreter を指定する。Python は参照実装（chat template を適用した tokenization の比率、出力位置の token、logits、参照 tokenizer の検証）だけに使う。インストールは評価コマンドでは行わない。oMLX で serve するときは `--omlx-python` に `omlx` が入った interpreter を指定する。

[default-suite.jsonl](../evaluation/default-suite.jsonl) は日本語 20、Python／Scala／TypeScript 各 10、FIM 10、tool-call 10、混入用 30 prompts の固定 100 件。日本語は説明・要約と multi-turn、混入用は日本語・code の自発的な出力を対象とする。期待する関数テストも固定する。suite は固定データであり、評価途中で変更しない。

Scala の関数テストは `sbt --batch stageCli` で配置した固定版 compiler jars を使う。TypeScript は Node と TypeScript を参照データと同じ取得で一度だけ用意する。Node は公式配布元の `SHASUMS256.txt`、TypeScript は npm registry の SHA-512 integrity と照合し、`outputs/reference/<name>/<version>` に展開する。既存の出力は上書きしない。

```sh
sbt --batch stageCli
scripts/vocab-surgery fetch-reference node         # 24.21.0
scripts/vocab-surgery fetch-reference typescript   # 6.0.2
```

生成 code は `sandbox` パッケージ（`src/main/scala/me/nsmr/vocabsurgery/sandbox/Sandbox.scala`）の `Sandbox` を通して実行する。`scripts/vocab-surgery sandbox-check` で、この host で使える実装と使えない理由を表示し、`sandbox-run --work-dir DIR [--readable TREE]... [--env NAME=VALUE]... -- COMMAND...` で任意のコマンドを隔離して実行する（実行したコマンドが失敗すると終了コード 5）。どの実装も、ネットワークを遮断し、書き込みを使い捨ての作業 directory（HOME・TMPDIR・XDG の各 directory もそこに置く）に限り、読み取りをシステムと呼び出し側が渡した tree に限る。API key は子の環境に渡さない。Python は固定 asserts、Scala はコンパイル後の asserts、TypeScript は strict compile 後の Node VM tests を使う。timeout と heap 制限を設定する。

| 実装 | host | 方式 |
| --- | --- | --- |
| `sandbox-exec` | macOS | `sandbox-exec` の profile で、ネットワーク・作業 directory 外への書き込み・指定外の読み取りを拒否 |
| `docker` | Linux（docker server が必要） | 空の image（`vocab-surgery-sandbox:empty`、初回に `FROM scratch` から作る）に host の `/usr`・`/etc` と渡された tree を読み取り専用で mount し、作業 directory だけを書き込み可能にする。`--network none`、`--read-only`、全 capability の削除、呼び出し元の uid、メモリと pid の上限 |

`Sandbox.platform()` は `VOCAB_SANDBOX` または `--sandbox`（`auto`、`sandbox-exec`、`docker`）に従い、`auto` では host の OS で使える実装を選ぶ。使える実装がなければ実行を停止し、無制限実行に切り替えない。

## 実行

```sh
RUN=outputs/runs/my-first-run
scripts/vocab-surgery evaluate --manifest "$RUN/manifest.json" --python .venv/bin/python \
  --runtime omlx --omlx-python /opt/homebrew/Cellar/omlx/0.7.0rc1/libexec/bin/python3.11 \
  --evaluation-out "$RUN/evaluation" --trials 5 --warmups 1
```

- 元モデルを `baseline`、加工モデルを `pruned` という名前で、それぞれ専用の server（`$RUN/evaluation/servers/baseline`、`…/pruned`）に serve する。loopback、同時実行 1。元モデルの測定を終えて server を止めてから加工モデルを起動するので、2 つのモデルが同時に常駐しない。
- prefix cache は使わない。oMLX は `--no-cache`、TensorFold clone は `--prompt-cache-gib 0`（patch 0004）で起動する。cache hit が報告された応答、欠けた SSE 終端・usage・finish reason、過大な stream は失敗条件。
- `--extra` は全 request に足す JSON object（例：`{"chat_template_kwargs":{"enable_thinking":false}}`）。`--suite` で別の固定 suite を指定できる。`--logits` で `scripts/logits.py`（MLX）による logits の比較も行う。
- 失敗したときもログと部分成果物を残す。再実行には新しい evaluation directory を使う。

## 測定と判定
## 測定と判定

- **静的／参照検証**: 全 1,891 tensors の byte 対応、全 file hashes、連続 ID、BPE、全 added tokens の単一 ID、10,086 字の NFC、round trip、multi-turn／tool の chat template。同梱 Python の固定 token ID assignment も点検する。
- **logits**: source と pruned を別 worker で逐次ロードし、fresh cache と同じ残存 ID 列で raw logits を比較する。4 固定入力のうち tokenizer projection が一致するものだけを採用し、非一致入力は skip 理由を残す。`abs(a-b) <= .01 + .001*abs(a)` を固定し、source 反復比較、誤差、超過数、最初の超過、上位 margin を記録する。引き上げは自動で行わない。
- **速度**: 生テキストを送る推論環境の stream と、`--logits` での同じ ID 列による MLX teacher-forced 測定を区別する。suite 内の日本語／code の短・中・長 6 ケースは warm-up 後 5 回。native usage の prefill／decode tok/s、TTFT、全時間、scalar/s、入力 category／長さ別の中央値・p95・範囲を保存する。teacher-forced は 4 固定入力で 16 decode tokens、warm-up 1 回と測定 5 回。
- **memory／サイズ**: `--logits` の MLX worker の peak allocated と peak RSS、各段階の server PID の sampled RSS を別々に記録する。OS 全体の unified memory や APFS clone の固有物理 bytes は測定できておらず null。論理 file bytes と payload 削減見積もりは記録する。RSS はサンプリング間隔 .25 秒なので短い peak を見逃し得る。
- **品質**: code の実行テスト、FIM の prefix／completion／suffix、tool parser と tool response 後の応答を検査する。日本語の 5 段階目視評価は未入力なら `pending-human-review`。自動採点で埋めない。
- **混入／分岐**: sampling は混入 prompts 各 5 回。reasoning と tool arguments も scalar の分母に含め、禁止 scalar 比率、1 文字以上を含む出力率、理由別内訳を残す。基準側の混入が 0 なら減少は観測不能。greedy 分岐の候補判定は API が raw generated IDs を返さないため decoded text の再 tokenize による推定と明記する。

CLI の終了コードは 0=処理成功、1=実行失敗、2=引数エラー、3=`verify` 失敗、4=評価完了したが logits gate 不合格。0 だけで TASK の全目的達成とは判断しない。各品質 gate、全件 coverage、目視品質、混入減少を `metrics.json` の `taskGoalsFullyAchieved` で別に判定する。

## 保存結果と目視評価

evaluation directory に suite、tokenization、baseline／pruned の応答（`baseline.jsonl`、`pruned.jsonl`）、各 server の `server.log` と `server.json`、environment、metrics、ratings template、`REPORT.md` を保存する。全件 suite は benchmark と sampling の繰り返しを含むため、各段階の出力は 100 行より多くなる。

`ratings-template.json` を別ファイルへコピーし、全日本語 ID に baseline／pruned の 1〜5 点と `majorBreakdown` を記入する。未記入、重複、欠落 ID は拒否する。元の測定値を上書きせず、推論なしで新しい metrics に集計し直す。

```sh
scripts/vocab-surgery review --evaluation "$RUN/evaluation" \
  --ratings outputs/reviews/my-first-run-ratings.json --out "$RUN/reviewed-metrics.json"
```

## 推論環境と速度の測定

`runtime` パッケージの `InferenceRuntime` が checkpoint を serve する。実装は `omlx`（macOS、比較用の entry を symlink で並べ、prefix／HF cache なし、同時実行 1、offline）と `tensorfold`（`scripts/flash-next/setup-tensorfold.sh` で作った clone、CUDA）。どちらも新しい server ディレクトリに `server.log` と `server.json`（起動したコマンド、版、option）を残し、`/v1/models` が答えるまで待ち、止めるときは子プロセスまで止める。推論環境ごとの capability（request ごとの draft の切り替え、ログからの draft の受理数、モデルのロードとアンロード）は trait で表し、持たない環境では使わない。

```sh
scripts/vocab-surgery serve --runtime tensorfold --model fn=<checkpoint> --server-dir outputs/servers/<new> -- <command…>
scripts/vocab-surgery measure-speed --runtime tensorfold --model fn=<checkpoint> --server-dir outputs/servers/<new> --out outputs/runs/<run>/speed.json
```

- `serve` は `--` の後のコマンドを、ready の後に `VOCAB_ENDPOINT`（`http://127.0.0.1:<port>/v1`）と `VOCAB_MODELS` 付きで実行し、終わったら止める（失敗は終了コード 5）。コマンドがなければ中断されるまで serve する。
- `measure-speed` は `evaluation/speed-prompts.json` の prompt を、draft を切り替えられる推論環境では両方のモードで、各 trial 送り、最初の warm-up を除いて decode tok/s、TTFT、生成 token 数の中央値を出す。ログから draft の受理数が取れる推論環境では、送った request と生成 token 数で照合して割り当て、prompt とモードごとの受理率と 1 round あたりの token 数を記録する。

## 外部の評価素材

`test-data` submodule などの外部の評価素材は、ケースの形式、出題文の組み立て、採点、生成コードの実行、指標、runner を自分で持つ。このプロジェクトは、加工したモデルを serve して endpoint を用意し、素材が文書で公開している CLI にその endpoint を渡して実行するだけにする。素材の内部（runner、`tools/` のモジュール、ケースの形式）を import・patch・再実装しない。どの素材を使うかは実行のたびに与え、特定の素材のパスやケースの形式を手順やコードに埋め込まない（素材は差し替えうる）。境界の詳細は [EVALUATION_ARCHITECTURE.md](EVALUATION_ARCHITECTURE.md) を参照する。

- `run-external` は serve した endpoint に対して素材の CLI を実行し、素材の場所・git revision・作業ディレクトリ・コマンド・終了コードを server ディレクトリの `external-run.json` に記録する。素材の結果の中身は読まない。

  ```sh
  scripts/vocab-surgery run-external --runtime tensorfold --model fn=<checkpoint> --server-dir outputs/servers/<new> \\
    --material <素材> --copy-to outputs/runs/<run>/material -- <素材が公開しているコマンド…>
  ```

- 素材の runner が素材の中に結果を書く場合は、`--copy-to` で run directory に複製してから実行し（`.git` は複製しない）、元の submodule と revision を変えない。
- 素材に足りない機能（Linux での生成コードの隔離実行など）は、素材の側への変更案にする。

2026-10-06 の test-data の比較（[測定結果と限界](EVALUATION_TESTDATA_20261006.md)）と、2026-10-07 の Flash-Next の評価の一部は、素材の内部を取り込んでいた helper で行った。その helper は境界違反として削除した（最後に含まれるのは `f163c9b`）。

## Flash-Next

Flash-Next は oMLX ではなく、実験用に改造した TensorFold で serve する。その取得・serve・評価の道具は Flash-Next 専用として [scripts/flash-next](../scripts/flash-next/README.md) にまとめてある。2026-10-07 の比較結果は [Flash-Next の serve 比較](EVALUATION_FLASH_NEXT_20261007.md)。

## 2026-10-06 の実行確認

固定 Qwen snapshot を 512 MiB JVM heap で加工し、K=128,803、全 1,891 tensors の byte 対応、payload 44,790,761,728 bytes、参照 tokenizer の検証に成功した。未対象の 7 shards は APFS clone。論理 model bytes は 44,855,965,826 → 44,795,576,221、payload 削減は 53,298,432 bytes。

専用 oMLX 0.7.0rc1 で 7 件の小 suite を before／after 実行し、日本語 multi-turn、Python／Scala／TypeScript 各 1、FIM 1、tool-call 1 と EOS／streaming の動作を確認した。別の短入力 2 件で warm-up 1＋測定 5 回、同一 ID 列 4 件でも測定した。これらは helper の動作確認であり、固定 100 件の品質評価と日本語目視評価は未実施。

logits は 4 件中 3 件が完全一致。入力 `a` の 128,803 要素中 1 要素で誤差 .015625、許容値 .012390625 となり gate は不合格。source 反復は一致し、surviving argmax は 4 件とも一致した。原因は未確定で、閾値を広げて合格扱いにしていない。TASK の全目的達成は未確認である。

同日、最新 v2 加工モデルで全静的検証、固定4入力の logits、`test-data` 全26合成・35 replay ケース、速度の正順 / 逆順比較を追加実施した。[測定結果と限界](EVALUATION_TESTDATA_20261006.md)を参照。v2 の logits は合格したが、上記 v1 の不合格とは別の結果である。
