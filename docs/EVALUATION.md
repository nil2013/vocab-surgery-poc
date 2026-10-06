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

生成 code は macOS `sandbox-exec` 内で実行する。ネットワークと一時 directory 外への書き込みを拒否し、API key を子の環境に渡さない。Python は固定 asserts、Scala はコンパイル後の asserts、TypeScript は strict compile 後の Node VM tests を使う。timeout と heap 制限を設定する。macOS の制限機構がない環境では実行を停止し、無制限実行に切り替えない。

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

## test-data の同条件比較

`test-data` は独立した upstream submodule。ケースの定義と近似は [cases.md](../test-data/docs/cases.md)、採点の意味は [metrics.md](../test-data/docs/metrics.md) を参照する。既存の履歴出力や別ホストの測定を baseline に使わず、元モデルと加工モデルに同じ固定入力・sampling パラメータを送り直す。

[evaluate_testdata.py](../scripts/evaluate_testdata.py) は、合成26ケース（seed 展開後38リクエスト）、replay35ケース、既存 suite の速度測定6ケースを比較する。replay は既定で各2 samples、temperature .7、top_p .8、seed 0 / 1、max_tokens 4000。合成テストは入力に指定されたパラメータを保持し、top_p 未指定なら request でも省略する。速度は greedy、warm-up 1回・測定5回、同時実行1、prefix cache 無効で測定する。

先に `verify` を実行し、成功 report を渡す。専用 server の起動は前節と同じ。以下の出力先は例で、再実行時は新しい directory にする。

```sh
RUN=outputs/runs/ja-en-coding-v2
EVAL_PYTHON=/opt/homebrew/Cellar/omlx/0.7.0rc1/libexec/bin/python3.11
scripts/vocab-surgery verify --manifest "$RUN/manifest.json" --python "$EVAL_PYTHON" \
  --report "$RUN/testdata-verification.json"
"$EVAL_PYTHON" scripts/logits.py compare --manifest "$RUN/manifest.json" --output "$RUN/testdata-logits"
"$EVAL_PYTHON" scripts/serve-evaluation.py --manifest "$RUN/manifest.json" \
  --server-dir outputs/servers/testdata-comparison --port 8302
"$EVAL_PYTHON" scripts/evaluate_testdata.py --manifest "$RUN/manifest.json" \
  --verification-report "$RUN/testdata-verification.json" \
  --server-environment outputs/servers/testdata-comparison/server.json \
  --test-data test-data --output "$RUN/testdata-comparison"
```

`logits.py` の比較と teacher-forced 速度測定中は、server にモデルをロードせず、hash 検証や別の benchmark を同時に実行しない。oMLX の比較も baseline → unload → pruned → unload の順で行う。native decode tok/s と TTFT、scalar/s を保存し、入力長・生成内容の異なる replay の速度を、そのまま一つの性能値にまとめない。

helper はケースと hash、submodule revision、server・package情報、全応答、tokenization、同じ case / seed の変化、集計を `outputs` に保存する。upstream の runner は submodule 内の `results` を更新するため、この比較では使わない。合成テストの固定 Python harness は upstream から読み、[既存の sandbox](../scripts/code_checks.py) 内で実行する。replay の tool calls は記録するだけで、モデルが指示した操作を実行しない。合成 tool test のみ固定の結果を返す。

ローカルの tokenization 検査では、履歴の tool arguments の JSON strings を object にしたコピーを template に渡す。oMLX 側も request を template に渡す前にこの変換を行う。元ケースと API に送る履歴は変更しない。

この tokenization は HF 参照 template の解析であり、oMLX の追加の前処理によって native usage の入力数と異なる場合がある。実際の入力数・速度には各応答の native usage を使い、参照解析で一致した IDs を API が返した raw IDs として扱わない。

`test-data` の leak は JIS X 0213 / Python Unicode と文脈内文字の除外による指標であり、surgery の ICU policy predicate とは別である。replay は `stop` かつ text がある応答だけを concluded として言語・混入を集計し、`tool_calls` / `length` を別に数える。合成の PASS は指定の文字一致・keywords・code harness 等の判定で、日本語品質の人による評価を代替しない。失敗時は部分 JSONL を保持して停止し、新しい出力先で再実行する。

モデル切替直後に oMLX の dynamic memory ceiling が旧状況を参照して HTTP 507 を返す場合、明示 load を最大20秒、1秒間隔で再試行する。他の HTTP errors は再試行せず、memory guard の上限は変更しない。完了した baseline の後に失敗した場合は、新しい出力先で `--baseline-results <旧 comparison directory>` を指定できる。モデル・manifest hash・データ revision / hashes・server 情報・package versions・samples / trials / warmups が一致し、全 baseline 行の case / seed / group が揃っている場合だけコピーして再利用し、そのファイル hash と出所を記録する。部分 baseline は再利用しない。

## 実験用 TensorFold clone での評価（Flash-Next）

Flash-Next は oMLX ではなく、`outputs/tensorfold` に clone して改造した TensorFold で serve する（[ロード検査](MODEL_FAMILIES.md#tensorfold-でのロード検査2026-10-07)）。本番の serve 環境とその venv は使わない。clone 内の `.venv` と `.torch-extensions` を使い、server は `127.0.0.1:18080`（`TF_PORT` で変更）で 1 本ずつ起動・停止する。

```sh
RUN=outputs/runs/fn-serve-eval
scripts/tf-evaluate.sh "$RUN" original models/models--Vontra--Qwen3.8-Flash-Next-MLX-4bit-MTP/snapshots/2b170fa6309d5d1ee380b35636075fac7945f286
scripts/tf-evaluate.sh "$RUN" pruned-v2 outputs/models/flash-next-ja-en-coding-v2
python3 scripts/tf_acceptance.py "$RUN/pruned-v2-server.log" \
  "$RUN/test-data/results/synthetic/pruned-v2/$(date +%F).json" "$RUN/pruned-v2-bench.json"
```

- [tf-serve.sh](../scripts/tf-serve.sh) は checkpoint を serve し、ready になってから任意のコマンドを `TF_BASE` 付きで実行して、終了時に server を止める。
- [tf-evaluate.sh](../scripts/tf-evaluate.sh) は run directory に `test-data` の results 以外をコピーし、[testdata-no-exec.patch](../scripts/testdata-no-exec.patch) を当ててから、upstream の合成テスト（`--par 1`、`enable_thinking: false`）と [tf_bench.py](../scripts/tf_bench.py) を実行する。submodule は変更しない。
- この host（DGX、AppArmor が unprivileged user namespace を禁止）には生成 code の sandbox がない。patch は code ケースのコードを記録するだけで実行せず、PASS を `null` にする。無制限実行には切り替えない。
- `tf_bench.py` は日本語・英語・Python の 3 prompt を、MTP あり／なし（request の `draft`）で各 4 回、temperature 0、400 tokens で送る。最初の 1 回は warm-up として集計から除く。
- [tf_acceptance.py](../scripts/tf_acceptance.py) は server log の `accepted=A/P` を、送った順の結果ファイルと completion tokens で照合して request に割り当て、ケースごとの MTP 受理率と 1 round あたりの token 数を出す。数が合わなければ停止する。

## 2026-10-06 の実行確認

固定 Qwen snapshot を 512 MiB JVM heap で加工し、K=128,803、全 1,891 tensors の byte 対応、payload 44,790,761,728 bytes、参照 tokenizer の検証に成功した。未対象の 7 shards は APFS clone。論理 model bytes は 44,855,965,826 → 44,795,576,221、payload 削減は 53,298,432 bytes。

専用 oMLX 0.7.0rc1 で 7 件の小 suite を before／after 実行し、日本語 multi-turn、Python／Scala／TypeScript 各 1、FIM 1、tool-call 1 と EOS／streaming の動作を確認した。別の短入力 2 件で warm-up 1＋測定 5 回、同一 ID 列 4 件でも測定した。これらは helper の動作確認であり、固定 100 件の品質評価と日本語目視評価は未実施。

logits は 4 件中 3 件が完全一致。入力 `a` の 128,803 要素中 1 要素で誤差 .015625、許容値 .012390625 となり gate は不合格。source 反復は一致し、surviving argmax は 4 件とも一致した。原因は未確定で、閾値を広げて合格扱いにしていない。TASK の全目的達成は未確認である。

同日、最新 v2 加工モデルで全静的検証、固定4入力の logits、`test-data` 全26合成・35 replay ケース、速度の正順 / 逆順比較を追加実施した。[測定結果と限界](EVALUATION_TESTDATA_20261006.md)を参照。v2 の logits は合格したが、上記 v1 の不合格とは別の結果である。
