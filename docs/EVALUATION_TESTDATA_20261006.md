# 元モデルと v2 加工モデルの出力・速度比較

2026-10-06、同じ Mac の oMLX で元モデルと `ja-en-coding-v2` 加工モデルを比較した。**合成の混入用19サンプルでは leak が9件から0件に減った。書き写し、英語推論、Python、tool、長文脈の固定チェックは維持され、削除対象言語の翻訳5ケースでは性能低下が観測された。oMLX の生成速度に改善は観測されなかった。**

以下は今回の固定入力での結果である。日本語の正式な目視採点、replay 報告の意味的な正しさ、TASK 全体の達成は未判定。実行方法は [EVALUATION.md](EVALUATION.md)、加工 policy は [PRUNING_JA_EN_CODING_V2.md](PRUNING_JA_EN_CODING_V2.md) を参照する。

## 対象と条件

| 項目 | 値 |
| --- | --- |
| 元モデル | `mlx-community/Qwen3-Coder-Next-4bit`、snapshot `7b9321eabb85ce79625cac3f61ea691e4ea984b5` |
| 加工モデル | `outputs/models/qwen3-coder-next-4bit-pruned-ja-en-coding-v2` |
| 加工 run | `outputs/runs/ja-en-coding-v2` |
| 元 / 加工後の語彙行数 | 151,936 / 121,737 |
| データ | `test-data` submodule、revision `ce193e0679a8fd143ca9237a45d8b3ff2df642f6` |
| 測定コード | baseline は `b3eb67d791e4b5408580c01b94e714047a488a60`、ロード待機・baseline 再利用を加えた再開は `e368c93` |
| Hardware / OS | Apple M5 Max、64 GiB unified memory、macOS 27.0.1、arm64 |
| Backend | oMLX 0.7.0rc1、専用 loopback server、同時実行1、prefix / SSD cache 無効 |
| 合成 | 26ケース、指定 seed 展開後38サンプル。温度・top_p・上限は元データの指定を保持 |
| Replay | 35ケース × 2サンプル。temperature .7、top_p .8、seed 0 / 1、max_tokens 4000 |
| 通常生成の速度 | 日本語3・Python / Scala / TypeScript 各1。greedy、warm-up 1回、測定5回。逆順でも同じ測定を追加 |

baseline → unload → pruned の順で測定した。元モデルの全測定後、oMLX が切替直後の古いメモリ状況を参照し、加工モデルのロードに HTTP 507 を返した。解放後の明示 load は成功した。完了済み baseline の全138行と、入力 hashes・モデル・server・package versions・測定条件を照合して新しい出力先へコピーし、加工モデル側を再開した。元の baseline bytes は一致する。memory guard の上限は変更していない。

通常生成の速度は、この順序による測定と pruned → unload → baseline の追加測定を別に保存した。モデルを同時に常駐させず、生成中に hash 検証や compiler tests を並走させていない。

## 1. Surgery から予想される変化と数値検証

入力の元 token IDs がすべて残る場合、射影した IDs は元と同じ embedding rows を参照する。Transformer / MoE と残存 LM-head rows の bytes は保持されるため、数学的には残存語彙の raw logits が保たれる。一方、出力語彙を減らすと softmax の分母が変わる。削除集合を除いた残存集合を `R` とすると、同じ内部状態で `p'(i) = p(i) / sum(p(j), j in R)` となる。

したがって、greedy は削除 token が選択候補になる箇所などで分岐し、sampling は同じ seed でも分布の変化で出力が変わり得る。入力に削除語彙を含む場合は分割も変わり、入力側の意味理解にも影響し得る。

参照 tokenizer で確認した短い例は次のとおり。これは生テキストの分割で、chat の system / tool template は含まない。

| 入力 | 元 / 加工後の tokens | 元 ID 列の射影と一致 |
| --- | --- | --- |
| `Pythonのasyncioを日本語で説明してください。` | 13 / 13 | 一致 |
| `α + β = γ, λx.f(x), ℵ₀` | 14 / 14 | 一致 |
| `冻結` | 2 / 3 | 不一致 |
| `个开关无网` | 4 / 10 | 不一致 |
| `Ошибка: файл не найден` | 6 / 37 | 不一致 |
| `저장하기` | 3 / 8 | 不一致 |
| `مرحبا بالعالم` | 5 / 24 | 不一致 |

`冻` の直接語彙は削除済みだが、`E5 86` と `BB` の断片から再構成できる。削除対象言語の文字列を表現できることと、その言語の意味理解が保たれることは別に検証する必要がある。

推論前に全1,891 tensors、44,774,481,664 payload bytes、全 source / output file hashes、参照 tokenizer を再検証した。別 worker の同一 ID 列による logits 比較も実施した。

| 入力 | 残存 logits の最大絶対誤差 | Gate |
| --- | --- | --- |
| `a` | 0.0009765625 | 合格 |
| Python の短い関数 | 0 | 合格 |
| `日本語で説明します。` | 0 | 合格 |
| chat protocol を含む短入力 | 0 | 合格 |

固定許容差 `.01 + .001 * abs(baseline)` を変更せず4/4合格。baseline 反復比較も合格し、残存語彙上の argmax は4/4一致した。これは4入力での検証であり、全入力の完全一致を保証しない。既存 v1 の別評価で記録された logits gate 不合格は [EVALUATION.md](EVALUATION.md) に残しており、今回の v2 の結果で解消した扱いにしない。

## 2. test-data の出力比較

合成の採点規則は、当時の素材（`qwen3-coder-next-pruning-testdata`、`ce193e0`）の `docs/metrics.md` に従う。複数 seed のケースは全サンプル合格をケースの PASS とする。

| 群 | 元モデル | v2 |
| --- | --- | --- |
| 日本語・Latin 拡張・数学・絵文字・多言語リテラルの書き写し | 8/8 PASS | 8/8 PASS |
| 入出力の翻訳 | 6/6 PASS | 1/6 PASS（簡体字→日本語） |
| 英語推論 | PASS | PASS |
| Python の LRU / 年別集計 | 2/2 PASS | 2/2 PASS |
| Tool-call と固定結果を受けた日本語要約 | PASS | PASS |
| 長文脈 needle（60,738 input tokens） | PASS | PASS |
| 日本語説明・混入ケース | 3/7 PASS | 7/7 PASS |
| 全26ケース | 22/26 PASS | 21/26 PASS |

総 PASS 数の低下には、削除対象言語の翻訳能力低下と、混入チェックの改善が同時に含まれる。そのため用途別の結果を分けて読む。

韓国語からの翻訳では、元モデルの「本日の会議は午後3時…資料を事前に共有」に対して、v2 は「今日の午後、気温が下がり…風も強く」と無関係な文を返した。ロシア語・アラビア語からの翻訳では暗号・表示不良と判断し、露・韓への翻訳では該当文字体系を使わない文を返した。簡体字からの翻訳は同じ日本語本文で合格した。

書き写し8ケースは削除対象言語も含めて元 / v2 の本文が一致した。Python 2ケースの生成本文も一致し、同じ固定 assertions を sandbox 内で通過した。tool の名前・引数は期待値に一致し、follow-up 本文も一致した。server が発行する call ID の違いを品質差として扱っていない。混入用19サンプルでは、leak を含む出力が **9/19（47.4%）→0/19**、該当 scalars が **75/27,977（0.268%）→0/27,499** となった。

この leak は Python Unicode / JIS X 0213 と文脈内文字の除外による指標で、surgery の ICU predicate ではない。本文だけを採点し、reasoning / tool arguments をこの分母に含めない。v2 の0/19は、この固定サンプルで混入を観測しなかった結果であり、byte 語彙による生成が禁止されたことを意味しない。

### Replay

両モデルとも70出力を取得し、`stop` かつ非空 text は67件、`length` は3件、構造化 `tool_calls` 終了は0件。本文は61/70件で完全一致した。残る9件では、要約の言い換え、説明の詳細、調査先として書くファイル名などが変わった。報告の正しさを自動採点した結果ではない。

元 / v2 とも、67件の concluded 指標の言語内訳は ja 2 / en 65 / zh 0、leak は0件。したがって、replay では混入減少は観測不能。過去の履歴で中国語だったケースも、今回の元モデルでの再生成では2サンプルとも英語であり、過去の出力との比較から今回の効果を推定しない。

67件には、`<tool_call>` を文字列として含む30件も双方で含まれる。これらの tool は実行していない。`stop` / 非空 text という指標だけで、実際に最終報告まで完成したとは判断できない。当時の素材の `docs/cases.md` にある入力再構成の近似も残る。

### Tokenization の測定範囲

helper の `tokenization.json` はローカル HF template での参照解析。17/26合成ケース・34/35 replay ケースで射影 IDs が一致したが、これは oMLX が使った raw IDs の取得ではない。oMLX は tool / message の前処理を行い、tool / replay では参照解析と native usage の入力 token 数に差があった。

両モデルには同じ API request を送り、実際の `usage.prompt_tokens` は replay の68/70サンプルで一致した。残る2サンプルは同じ1ケースで、入力語彙の削減による増加がある。参照値と native usage は `prompt-usage-comparison.json` に別々に保存し、生成文の再 tokenize を raw generated IDs として扱わない。

## 3. Token 出力速度

native `generation_tokens_per_second` の5回の中央値。正順は baseline → v2、逆順は v2 → baseline。各 phase / ケースに warm-up 1回を置いた。

| ケース | 正順：元 / v2 tok/s | 差 | 逆順：元 / v2 tok/s | 差 |
| --- | --- | --- | --- | --- |
| 日本語説明・短 | 108.84 / 108.08 | −0.70% | 108.90 / 108.63 | −0.25% |
| 日本語要約・中 | 108.37 / 107.50 | −0.80% | 108.02 / 107.77 | −0.23% |
| 日本語要約・長 | 107.64 / 106.84 | −0.74% | 107.49 / 107.19 | −0.28% |
| Python | 107.39 / 106.94 | −0.42% | 107.41 / 107.17 | −0.22% |
| Scala | 107.05 / 106.28 | −0.72% | 107.02 / 106.36 | −0.62% |
| TypeScript | 106.34 / 105.73 | −0.57% | 106.17 / 106.02 | −0.14% |

正順の全30測定で本文と生成 token 数は元 / v2 で一致した。入力数は30 / 223 / 637 / 60 / 339 / 1,148 tokens。生成数は148 / 89 / 87 / 26 / 26 / 41 tokensであり、特に coding の短い出力から長い生成全体の性能を一般化しない。TTFT・prefill tok/s・scalar/s・p95・範囲は raw metrics に保存した。

同じ ID 列を使う別 MLX worker の teacher-forced 測定（4入力、16 decode tokens、warm-up 1・測定5）では約99.2〜99.6 →99.8〜100.2 tok/s、中央値の差は各入力で+0.53〜+0.56%。これは sampling / streaming を含む oMLX の速度とは別の測定である。

今回の oMLX は v2 の値が0.1〜0.8%低く、独立 MLX worker は約0.5%高い。**大きな速度差は観測されず、oMLX での高速化は確認できない。** この規模の差について、固定順序・少数試行・実行条件の揺れを超えた性能向上を主張しない。語彙行と LM-head 計算は減るが、Transformer / MoE の処理は同じであり、速度への寄与は測定結果で判断する。

## 成果物と検証

生成物の root は `outputs/runs/ja-en-coding-v2/evaluation-testdata-20261006`。

- `static-verification.json`：全 file hashes / tensors と参照 tokenizer の再検証。
- `logits-controlled/`：単独測定の logits・反復・teacher-forced 速度・memory。`logits/` の初回は hash 検証と並走したため速度の結論には使わない。
- `testdata-comparison-complete/`：全 raw JSONL、入力と hashes、環境、scores、本文差分、baseline の出所と hash。baseline の sampled server RSS は最初の失敗前に sidecar が保存されていないため null。
- `benchmark-reverse/`：逆順の速度測定。`prompt-usage-comparison.json`：参照 / native 入力数。`analysis-summary.json`：ケース単位 PASS と分母。`hardware.json`：hardware と baseline コード版。
- 初回の template 検査失敗と切替時 HTTP 507 の log / 部分成果物も保持した。専用 server は `outputs/servers/ja-en-coding-v2-testdata-20261006`。

Python helper の19テストが合格し、Scala / TypeScript 実行用 tools の unit coverage もスキップなく実行した。今回のモデル生成 code の実行採点は test-data の Python 2ケースであり、Scala / TypeScript は速度 prompt に限る。日本語の正式な目視採点と固定100件 suite の全品質評価は未実施である。
