# oMLX の before／after 評価

実行主体はこの Mac の oMLX と MLX。Scala CLI は先に manifest を全面再検証し、ICU の scalar predicate から `forbidden-codepoints.json` を生成してから Python helper に渡す。Python 自身の Unicode 版に削除判定を依存させない。加工手順は [README](../README.md)、判定基準は [仕様書](../outputs/SPEC.md) を参照。

## 前提と固定入力

`--python` には `omlx`、`mlx`、`mlx-lm`、`tokenizers`、`transformers`、`numpy` が利用できる interpreter を指定する。この workspace では `/opt/homebrew/Cellar/omlx/0.7.0rc1/libexec/bin/python3.11` を実行確認した。インストールは評価コマンドでは行わない。

[default-suite.jsonl](../evaluation/default-suite.jsonl) は日本語 20、Python／Scala／TypeScript 各 10、FIM 10、tool-call 10、混入用 30 prompts の固定 100 件。日本語は説明・要約と multi-turn、混入用は日本語・code の自発的な出力を対象とする。期待する関数テストも固定する。`make-suite.py` は同じ定義から新しい JSONL を作る helper であり、評価途中で suite を変更しない。

Scala は `sbt --batch stageCli` で固定版 compiler jars を配置する。TypeScript は次の明示的な setup を一度実行する。Node v24.21.0 と TypeScript 6.0.2 を公式 Node 配布元と npm registry から取得し、SHA-256／SHA-512 integrity を検証して `outputs/tools` に展開する。既存出力は上書きしない。Python 3.11 以降を使用する。

```sh
sbt --batch stageCli
python3 scripts/setup-evaluation-tools.py
python3 -m unittest discover -s scripts/tests -v
```

生成 code は [sandbox.py](../scripts/sandbox.py) の `Sandbox` を通して実行する。どの実装も、ネットワークを遮断し、書き込みを使い捨ての作業 directory（HOME・TMPDIR・XDG の各 directory もそこに置く）に限り、読み取りをシステムと呼び出し側が渡した tree に限る。API key は子の環境に渡さない。Python は固定 asserts、Scala はコンパイル後の asserts、TypeScript は strict compile 後の Node VM tests を使う。timeout と heap 制限を設定する。

| 実装 | host | 方式 |
| --- | --- | --- |
| `sandbox-exec` | macOS | `sandbox-exec` の profile で、ネットワーク・作業 directory 外への書き込み・指定外の読み取りを拒否 |
| `docker` | Linux（docker server が必要） | 空の image（`vocab-surgery-sandbox:empty`、初回に `FROM scratch` から作る）に host の `/usr`・`/etc` と渡された tree を読み取り専用で mount し、作業 directory だけを書き込み可能にする。`--network none`、`--read-only`、全 capability の削除、呼び出し元の uid、メモリと pid の上限 |

`sandbox.platform()` は `VOCAB_SANDBOX`（`auto`、`sandbox-exec`、`docker`）に従い、`auto` では host の OS で使える実装を選ぶ。使える実装がなければ実行を停止し、無制限実行に切り替えない。

## 専用 server と CLI

既存 oMLX server の設定変更は不要。helper は新しい directory に比較用 entry を作り、loopback・同時実行 1・prefix／SSD cache 無効の独立 server を起動する。entry の symlink は比較用登録だけに使い、加工モデル内のファイルは独立した regular files のまま。

```sh
EVAL_PYTHON=/opt/homebrew/Cellar/omlx/0.7.0rc1/libexec/bin/python3.11
RUN=outputs/runs/my-first-run
"$EVAL_PYTHON" scripts/serve-evaluation.py --manifest "$RUN/manifest.json" --server-dir outputs/servers/my-first-run --port 8302
```

起動結果の PID、`server.json`、`server.log` を確認する。server がモデル探索を終えて応答するまで待ち、`curl http://127.0.0.1:8302/v1/models` で `baseline` と `pruned` が見えることを確認してから実行する。

```sh
scripts/vocab-surgery evaluate --manifest "$RUN/manifest.json" --python "$EVAL_PYTHON" \
  --suite evaluation/default-suite.jsonl --omlx-url http://127.0.0.1:8302 \
  --baseline-model baseline --pruned-model pruned \
  --evaluation-out "$RUN/evaluation" --server-environment outputs/servers/my-first-run/server.json \
  --trials 5 --warmups 1 --timeout-seconds 3600
```

`--api-key-env` は credential の値ではなく環境変数名を受け付ける。default は `OMLX_API_KEY`。URL に credential、query、fragment を埋め込まない。`--server-environment` を省略すると server version と RSS は取得できない。client の package version を server version と同一視しない。

評価は明示した 2 モデルを unload し、baseline → pruned の順にロードして各段階の最後に unload する。cache hit が報告された応答、欠けた SSE 終端・usage・finish reason、過大な stream は失敗条件。helper 失敗はログと部分成果物を保持する。再実行は新しい evaluation directory を使う。

専用 server は評価後も起動状態を保つ。終了時は `server.json` の PID、`ps -p <PID> -o command=` の oMLX process、`lsof -p <PID>` に現れるその directory の `server.log` を確認し、`kill -TERM <PID>` を実行する。oMLX は process title を変更する場合があるため、command 表示だけで起動 directory を判断しない。他の oMLX process は対象にしない。

## 測定と判定

- **静的／参照検証**: 全 1,891 tensors の byte 対応、全 file hashes、連続 ID、BPE、全 added tokens の単一 ID、10,086 字の NFC、round trip、multi-turn／tool の chat template。同梱 Python の固定 token ID assignment も点検する。
- **logits**: source と pruned を別 worker で逐次ロードし、fresh cache と同じ残存 ID 列で raw logits を比較する。4 固定入力のうち tokenizer projection が一致するものだけを採用し、非一致入力は skip 理由を残す。`abs(a-b) <= .01 + .001*abs(a)` を固定し、source 反復比較、誤差、超過数、最初の超過、上位 margin を記録する。引き上げは自動で行わない。
- **速度**: 生テキストの oMLX stream と、同じ ID 列による MLX teacher-forced 測定を区別する。suite 内の日本語／code の短・中・長 6 ケースは warm-up 後 5 回。native usage の prefill／decode tok/s、TTFT、全時間、scalar/s、入力 category／長さ別の中央値・p95・範囲を保存する。teacher-forced は 4 固定入力で 16 decode tokens、warm-up 1 回と測定 5 回。
- **memory／サイズ**: MLX worker の peak allocated と peak RSS、指定 server PID の sampled RSS を別々に記録する。OS 全体の unified memory や APFS clone の固有物理 bytes は測定できておらず null。論理 file bytes と payload 削減見積もりは記録する。RSS はサンプリング間隔 .25 秒なので短い peak を見逃し得る。
- **品質**: code の実行テスト、FIM の prefix／completion／suffix、tool parser と tool response 後の応答を検査する。日本語の 5 段階目視評価は未入力なら `pending-human-review`。自動採点で埋めない。
- **混入／分岐**: sampling は混入 prompts 各 5 回。reasoning と tool arguments も scalar の分母に含め、禁止 scalar 比率、1 文字以上を含む出力率、理由別内訳を残す。基準側の混入が 0 なら減少は観測不能。greedy 分岐の候補判定は API が raw generated IDs を返さないため decoded text の再 tokenize による推定と明記する。

CLI の終了コードは 0=処理成功、1=実行失敗、2=引数エラー、3=`verify` 失敗、4=評価完了したが logits gate 不合格。0 だけで TASK の全目的達成とは判断しない。各品質 gate、全件 coverage、目視品質、混入減少を `metrics.json` の `taskGoalsFullyAchieved` で別に判定する。

## 保存結果と目視評価

evaluation directory に suite、forbidden scalars、tokenization、logits、baseline／pruned の raw JSONL、helper log、environment、metrics、ratings template、`REPORT.md` を保存する。サブプロセスは model を同時常駐させない。全件 suite は benchmark の繰り返しを含むため、各段階の出力は 100 行より多くなる。

`ratings-template.json` を別ファイルへコピーし、全日本語 ID に baseline／pruned の 1〜5 点と `majorBreakdown` を記入する。未記入、重複、欠落 ID は拒否する。元の測定値を上書きせず、新 report に集計できる。

```sh
"$EVAL_PYTHON" scripts/review-evaluation.py --evaluation "$RUN/evaluation" \
  --ratings outputs/reviews/my-first-run-ratings.json --out "$RUN/reviewed-metrics.json"
```

## 外部の評価素材

`test-data` submodule などの外部の評価素材は、ケースの形式、出題文の組み立て、採点、生成コードの実行、指標、runner を自分で持つ。このプロジェクトは、加工したモデルを serve して endpoint を用意し、素材が文書で公開している CLI にその endpoint を渡して実行するだけにする。素材の内部（runner、`tools/` のモジュール、ケースの形式）を import・patch・再実装しない。どの素材を使うかは実行のたびに与え、特定の素材のパスやケースの形式を手順やコードに埋め込まない（素材は差し替えうる）。境界の詳細は [EVALUATION_ARCHITECTURE.md](EVALUATION_ARCHITECTURE.md) を参照する。

- 素材の runner が素材の中に結果を書く場合は、素材を run directory に複製してから実行し、元の submodule と revision を変えない。
- 素材に足りない機能（Linux での生成コードの隔離実行など）は、素材の側への変更案にする。

2026-10-06 の test-data の比較（[測定結果と限界](EVALUATION_TESTDATA_20261006.md)）と、2026-10-07 の Flash-Next の評価の一部は、素材の内部を取り込んでいた helper で行った。その helper は境界違反として削除した（最後に含まれるのは `f163c9b`）。

## Flash-Next

Flash-Next は oMLX ではなく、実験用に改造した TensorFold で serve する。その取得・serve・評価の道具は Flash-Next 専用として [scripts/flash-next](../scripts/flash-next/README.md) にまとめてある。2026-10-07 の比較結果は [Flash-Next の serve 比較](EVALUATION_FLASH_NEXT_20261007.md)。

## 2026-10-06 の実行確認

固定 Qwen snapshot を 512 MiB JVM heap で加工し、K=128,803、全 1,891 tensors の byte 対応、payload 44,790,761,728 bytes、参照 tokenizer の検証に成功した。未対象の 7 shards は APFS clone。論理 model bytes は 44,855,965,826 → 44,795,576,221、payload 削減は 53,298,432 bytes。

専用 oMLX 0.7.0rc1 で 7 件の小 suite を before／after 実行し、日本語 multi-turn、Python／Scala／TypeScript 各 1、FIM 1、tool-call 1 と EOS／streaming の動作を確認した。別の短入力 2 件で warm-up 1＋測定 5 回、同一 ID 列 4 件でも測定した。これらは helper の動作確認であり、固定 100 件の品質評価と日本語目視評価は未実施。

logits は 4 件中 3 件が完全一致。入力 `a` の 128,803 要素中 1 要素で誤差 .015625、許容値 .012390625 となり gate は不合格。source 反復は一致し、surviving argmax は 4 件とも一致した。原因は未確定で、閾値を広げて合格扱いにしていない。TASK の全目的達成は未確認である。

同日、最新 v2 加工モデルで全静的検証、固定4入力の logits、`test-data` 全26合成・35 replay ケース、速度の正順 / 逆順比較を追加実施した。[測定結果と限界](EVALUATION_TESTDATA_20261006.md)を参照。v2 の logits は合格したが、上記 v1 の不合格とは別の結果である。
