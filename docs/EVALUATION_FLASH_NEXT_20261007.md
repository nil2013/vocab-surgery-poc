# Flash-Next の元モデルと v2 加工モデルの serve 比較

2026-10-07、DGX の実験用 TensorFold clone で、Qwen3.8-Flash-Next の元モデルと `ja-en-coding-v2` 加工モデル（以下 pruned-v2）を同じ条件で serve して比べた。

**結果の要約**

- 混入テストで leak があったサンプルは 2/19 から 0/19 に減った。
- 削除対象の文字体系への翻訳・からの翻訳は 6 件すべて失敗した。日本語・英語・コードの固定チェックは維持された。
- 生成速度は 1〜3% 上がった。
- MTP の受理率は変わらなかった。temperature 0 の速度測定では、受理数まで完全に一致した。
- 日本語の一部を簡体字・ハングルに置き換えた依頼に対して、元モデルは返答全体を韓国語・中国語に切り替えることがあった。pruned-v2 は日本語で答え続けたが、ハングルの部分を読めず推測で補った。推測を外すと別の内容を返し、意味の説明を求められると作り話をした。

数値は、下記の run directory に保存した応答と server log から集計した（実機確認）。v1 レポートからの引用は（文書記載）と書く。応答の評価は筆者が読んで判定したもので、人による正式な採点ではない。生成 code は実行していない。実行方法は [scripts/flash-next](../scripts/flash-next/README.md)、加工の記録は [MODEL_FAMILIES.md](MODEL_FAMILIES.md#flash-next-の適用記録2026-10-07dgx-sparkext4) を参照する。

## 対象と条件

| 項目 | 値 |
| --- | --- |
| 元モデル | `Vontra/Qwen3.8-Flash-Next-MLX-4bit-MTP`、snapshot `2b170fa6309d5d1ee380b35636075fac7945f286` |
| 加工モデル | `outputs/models/flash-next-ja-en-coding-v2`（tool `a4dfe26`、policy `ja-en-coding-v2`） |
| 語彙行数 | 248,320 → 181,103 |
| Serve | TensorFold `cb2ebf05` の clone、ブランチ `vocab-surgery-ple-token-ids` の `8afb41b`（`scripts/flash-next/tensorfold-patches` を当てたもの）。`--ple-on-ssd --context 65536`、loopback、1 本ずつ送信 |
| MTP の draft 語彙 | 同梱リスト 79,591 件。pruned-v2 では PLE 対応表で変換した 78,837 件（754 件は削除済み） |
| Hardware | NVIDIA GB10（DGX）、Linux 7.0.0-1019-nvidia、torch 2.14.1+cu130 |
| データ | `test-data` revision `ce193e0679a8fd143ca9237a45d8b3ff2df642f6`、合成 26 ケース（38 サンプル＋tool の追加の応答 1） |
| 生成設定 | `enable_thinking: false`。合成テストは元データの temperature・seed を保持 |
| 測定コード | `8261fbd` の内容（その後 `scripts/flash-next/` の `evaluate.sh`、`bench.py`、`acceptance.py` と、汎用の `scripts/probe_replies.py` に配置を変えた） |
| 保存先 | `outputs/runs/fn-serve-eval/`（git 管理外） |

元モデルのすべての測定を終えて server を止めてから、pruned-v2 を起動した。code ケース（2 件）は、この host に sandbox がないため実行せず、PASS を `null` とした。

測定コードの扱いについて：

- 本文の測定は、測定コードを `scripts/` に移す前の同じ内容の script で行った。
- 移した後に、`acceptance.py` で保存済みの log を集計し直し、同じ数値になることを確かめた。test-data の複製と patch の結果も、測定時の複製と一致することを確かめた。
- `setup-tensorfold.sh` で clone を作り直すと、測定に使った clone と commit hash まで一致した。その clone と移した後の `serve.sh`・`probe_replies.py` で、1 prompt の起動・応答・集計を確かめた。
- 移した後の script で、測定の全体を通して実行し直してはいない。
- 移す前の `bench.py` は warm-up の行を保存していなかった。集計し直すときは、temperature 0 で出力が決定的なことを使い、測定 1 回目と同じ token 数で warm-up の行を補った。

## 1. 合成テスト

| 項目 | 元モデル | pruned-v2 |
| --- | --- | --- |
| 日本語、Latin 拡張・Greek・絵文字・罫線の書き写し | PASS | PASS |
| 削除した文字体系の書き写し（コード内の文字列） | PASS | PASS |
| 韓・露・簡・阿 → 日の翻訳 | PASS | **4 件すべて FAIL** |
| 英 → 露・韓の翻訳 | PASS | **2 件とも FAIL** |
| 日本語の説明、英語の推論、60k needle | PASS | PASS |
| tool call | FAIL | FAIL |
| code 2 問 | 実行せず | 実行せず |
| 混入テストで leak があったサンプル | **2/19** | **0/19** |

- **元モデルの混入**：leak_ja_0#1（1 字）と leak_ja_1#2（18 字）の 2 サンプル。
- **pruned-v2 の翻訳の失敗のしかた**
  - 韓・露・阿の原文は「意味をなさない」「特殊な Unicode 文字」と判断し、翻訳しなかった。
  - 簡体字の原文は、日本語に訳さず中国語のまま言い換えた（入力の字を写しているので、leak には数えられない）。
  - 英 → 露・韓は英語を返した。
  - Coder-Next の v1 と同じ傾向である（[test-data のレポート](../test-data/reports/2026-10-06_pruned-v1-stage34.md)、文書記載）。
- **tool call**：両モデルとも同じ理由で失敗した。1 回目の応答で 2 つの tool を同時に呼び、結果を受け取った後の追加の応答でも再び `grep` を呼んだ。出力は両モデルで一致しており、加工による変化ではない。v1 レポートの本番環境の測定では PASS だった（文書記載）。差の原因は調べていない。

## 2. 生成速度

日本語・英語・Python の 3 prompt を、temperature 0、最大 400 tokens、MTP あり／なしで各 4 回送った。最初の 1 回を除いた 3 回の中央値である。

| prompt | 元 MTP あり | pruned MTP あり | 元 MTP なし | pruned MTP なし |
| --- | ---: | ---: | ---: | ---: |
| ja | 40.8 tok/s | 41.2 tok/s | 34.5 tok/s | 35.0 tok/s |
| en | 60.1 tok/s | 61.2 tok/s | 34.4 tok/s | 35.3 tok/s |
| code | 91.2 tok/s | 92.3 tok/s | 34.4 tok/s | 35.2 tok/s |

- **出力**：18 本すべてで両モデルの出力 text が一致した。
- **TTFT**：MTP ありで約 0.06〜0.07 秒、なしで約 0.16〜0.17 秒。両モデルで差はない。
- **速度差**：pruned-v2 は 1〜3% 速い。LM head の行が減った分と考えられるが、内訳は測っていない。
- **MTP の効果**：MTP なしに対する速度は、日本語で 1.18 倍、英語で 1.75 倍、code で 2.65 倍だった。日本語では MTP がほとんど効いていない。

## 3. MTP の受理率

server は request ごとに draft の受理数を log に残す（`accepted=受理数/提案数`）。`scripts/flash-next/acceptance.py` でこれを各応答に割り当てた。

| 対象 | 元モデル | pruned-v2 |
| --- | ---: | ---: |
| 速度測定 ja（temperature 0） | 129/367 = 0.351 | 129/367 = 0.351 |
| 速度測定 en | 239/362 = 0.660 | 239/362 = 0.660 |
| 速度測定 code | 316/373 = 0.847 | 316/373 = 0.847 |
| 合成 leak_ja 18 サンプル（temperature 0.7） | 7,777/16,607 = 0.468 | 8,178/17,974 = 0.455 |
| 合成の英語推論・code 2 問・tool・Latin 拡張／Greek／絵文字の書き写し | — | 元モデルと受理数まで一致 |
| 合成の verbatim_ja、60k needle | 43/86、17/46 | 43/84、17/48（出力と受理数は同じで、提案数だけ異なる） |

**仮説**：加工前に、「MTP の draft の外の表現が出る確率が下がる分、投機的デコーディングの却下が減る」という仮説（ユーザーの見立て）があった。今回の結果はこれを支持しない。

- **temperature 0**：受理と却下は、本体と draft の argmax が一致するかで決まる。加工で受理率が変わりうるのは、どちらかの argmax が削除した token になった場合だけである。速度測定の 3 prompt では、そうした場合が一度もなかった。
- **sampling（leak_ja）**：サンプルごとのばらつき（0.27〜0.63）が、モデル間の差よりずっと大きい。どちらの方向の効果とも言えない。
- **元モデルで混入が起きた 2 サンプル**：受理率は同じケースの他のサンプルと変わらない。
- **混入の頻度との関係**：v1 レポートの履歴集計では、元の Flash-Next の出力のうち leak 文字は 55/550,747 字（0.010%）、leak を含む text パーツは 19/786 だった（文書記載）。パーツ単位では頻繁でも、token 単位ではまれである。受理率を平均で動かす大きさではない。
- **draft 側の経路**：draft が削除した token を予測し、本体はそれを予測しない、という場合も受理率に効きうる。ただし draft はもともと同梱リストに制限されており、今回の加工でそこから外れたのは 754 件だけだった。
- **日本語の受理率が低い理由**：0.35 という低さは、混入とは関係がない。MTP head が日本語の次の token を当てにくいことによる。

## 4. 日本語の一部を簡体字・ハングルに置き換えた依頼

[mixed-ja.json](../scripts/prompts/mixed-ja.json) の 8 文を、temperature 0 と 0.7（seed 1、2）で送った（各モデル 24 応答、最大 600 tokens）。依頼は 3 種類である。

- 普通の依頼：会議資料、案内メール、食べ方、施策
- 自然な日本語への修正
- 日本語でない部分の指摘と、その意味の説明

| 観察 | 元モデル | pruned-v2 |
| --- | --- | --- |
| 返答全体が韓国語に切り替わった | **3/24**（ko_food の temperature 0 と seed 2、ko_request の seed 2） | 0/24 |
| 置き換えた語の扱い | 黙って日本語に戻して答えた | 「意味が通じない文字列」「文字化け」と前置きし、推測で補った |
| 修正の依頼 | 正しく直した（昨天→昨日、图书馆→図書館、一整天→一日中。친구・영화・재미있었→友達・映画・面白かった） | 昨天→昨日は直した。图书馆は 3 本とも読めず、「家で」「部屋で」と推測した。친구は落とした |
| 意味の説明の依頼 | 正しく説明した（회의＝会議、预算＝予算） | ハングルの字母分解をでたらめに書き、「회의は意味のある語ではない」と述べた後、同じ文を繰り返して上限に達した（temperature 0）。「预は風が吹く様子」という存在しない意味を作った（seed 1） |
| prompt にないハングルの出力 | 韓国語で返答した 3 本 | 1 本（ko_food の seed 2）。推測の候補として「깻깻」「신흥」などを byte の token で書いた |
| 入力の token 数 | 30〜40 | 36〜47（削除した字が byte の token に分かれる） |

**簡体字の部分の読み取り**

- 日本語と共有する字（会・議・資…）や文脈で、多くを読み取れた。たとえば zh_request では「会议→会議」「项目→プロジェクト」と 1 語ずつ直してから答えた。
- 簡体字形の字そのもの（图・书）は読めなかった。

**ハングルの部分の読み取り**

- 文脈から推測できた語：会議、案内、キムチ。
- 推測できなかった語：友達。

**MTP の受理率**

- 簡体字の文：0.509 → 0.493。ハングルの文：0.404 → 0.431。両方を混ぜた文：0.431 → 0.441。
- 出力の内容がモデル間で大きく違う（元モデルは韓国語で答えたものもある）ので、加工の効果としては比べられない。

## 5. 同じ置き換えをした簡単な Python の課題

[mixed-ja-coding.json](../scripts/prompts/mixed-ja-coding.json) の 6 課題を、同じ 3 設定で送った（最大 1000 tokens）。code は実行せず、構文解析と目視で判定した。組み込みの `max`／`min` を呼んでいないかは、関数本体の構文木で確かめた。

| 課題（置き換えた語） | 元モデル | pruned-v2 |
| --- | --- | --- |
| バブルソート（冒泡排序・昇顺・并べ・写して） | 3/3 正しい | 3/3 正しい |
| 二分探索（查找・数组・目标・见つかったら） | 3/3 正しい。**3/3 とも docstring が中国語** | 3/3 正しい。説明・コメントは日本語 |
| 回文判定（회문・판정） | 3/3 正しい | 2/3 は「回文の誤入力」と推測して正しく書いた。**1/3 は `s == '회문'` を判定する関数を書いた** |
| フィボナッチ（피보나치・재귀ではなく반복で） | 3/3 反復で正しい。**2/3 は返答全体が韓国語**、1/3 は英語 | 3/3 フィボナッチと推測した。**「再帰ではなく反復」の制約は読めず**、再帰＋メモ化を最初に示す、三角数・階乗も書く（上限到達）、「その数列は存在しない」と前置きする、などが出た |
| 最大値・最小値（최대값・최소값、組み込み禁止） | 3/3 正しい、組み込みは不使用 | 3/3 正しい、組み込みは不使用 |
| 文字の出現回数（字符串・문자・出现） | 3/3 正しい | 3/3 正しい |

- **構文エラー**：pruned-v2 の回文判定の 1 本だけだった。使用例の `print(` の閉じ括弧が欠けていた。関数本体は正しい。
- **出力の長さ**：pruned-v2 は前置きや候補の列挙で長くなった。回文判定とフィボナッチでは、3 本合計で元モデルの 2.7 倍と 2.0 倍の token 数だった。
- **MTP の受理率**：pruned-v2 は回文判定（0.71 → 0.55）とフィボナッチ（0.76 → 0.60）で下がった。ただし出力の内容が違うので、加工の効果としては比べられない。

## 6. v4 の追加評価

同日、`ja-en-coding-v4`（172,416 行、`outputs/models/flash-next-ja-en-coding-v4`）を、移した後の `scripts/flash-next/` の道具で同じ条件のまま評価した（保存先は `outputs/runs/fn-serve-eval-v4/`）。v4 は v3 に拡張 Latin 文字、`hanForms`（`priority`）、端数削り 86 件を加えたもの。

| 項目 | 元モデル | pruned-v2 | pruned-v4 |
| --- | --- | --- | --- |
| 合成テストの FAIL | tool call、leak_ja_0、leak_ja_1 | 翻訳 6 件、tool call | 翻訳 6 件、tool call |
| 混入テストで leak があったサンプル | 2/19 | 0/19 | 0/19 |
| 速度 MTP あり ja／en／code（tok/s） | 40.8／60.1／91.2 | 41.2／61.2／92.3 | 41.7／61.8／93.5 |
| 速度 MTP なし ja／en／code（tok/s） | 34.5／34.4／34.4 | 35.0／35.3／35.2 | 35.8／35.6／35.9 |
| 速度測定の受理数 | ja 129/367、en 239/362、code 316/373 | 同じ | 同じ |
| 合成 leak_ja の受理率 | 0.468 | 0.455 | 0.462（7,894/17,071） |

- **速度測定の出力**：v4 の 18 本は、元モデル・pruned-v2 と完全に一致した。速度の向上は元モデル比で 2〜4% になった。
- **混合文（会話・コーディング）、temperature 0**：v4 の出力は 14 本中 13 本が pruned-v2 と同じだった。違ったのはバブルソートの 1 本で、コメントが 1 行増えただけで、コードは同じだった。
- **混合文、temperature 0.7**：v4 と pruned-v2 で違う出力になったが、加工の効果としては比べられない。TF の sampling の乱数は token ID から作られ、v2 と v4 では compact ID が違うからである。
  - 傾向は pruned-v2 と同じで、返答全体が他の言語に切り替わったものはない。
  - ハングルの課題では、回文判定の 1 本が再び `'회문'` との一致判定を書いた。フィボナッチの「反復で」という制約は読めなかった。
  - 意味の説明の依頼（temperature 0）では、pruned-v2 と同じ縮退が出た。
- `hanForms` と拡張 Latin の追加削除で、日本語・英語・コードの固定チェックと、日本語の混合文への応答に劣化は見られなかった。

## 解釈

- **出力言語の安定**：加工で、入力の文字体系に引きずられて返答の言語が切り替わることが抑えられた。
  - 元モデルの失敗は、code では正しいアルゴリズムに中国語・韓国語の docstring や説明が付く形で出た。会話では、返答全体が韓国語になる形で出た。
  - 今回の 42 応答（会話 24、code 18）では、元モデルで返答全体または docstring が中国語・韓国語になったものが 8 本、pruned-v2 では 0 本だった。
- **代償**：削除した文字体系で書かれた部分の意味は、ほぼ失われる。
  - 日本語と字を共有する簡体字は、かなり読める。
  - ハングルと簡体字形の字そのものは、文脈からの推測に頼る。
- **推測の振る舞い**：推測は、課題名のように定番の内容では当たりやすい。一方で付帯条件は落ちる。
  - pruned-v2 は「分からない」と止まるより、推測で進めて答えることが多かった。
  - 推測を外したときに、別の関数をもっともらしく書いた（`s == '회문'`）。意味を問われたときに、作り話をした。
- **用途との関係**：日本語・英語・code だけの入力が前提なら、この代償はほぼ表に出ない。削除した文字体系が入力に混じる用途では、上の失敗を考慮する必要がある。
- **速度**：加工による速度の改善は 1〜3% にとどまった。MTP の受理率は変わらなかった。

## 限界

- 各 prompt は 3 応答（temperature 0 と seed 2 つ）だけである。割合は傾向を示すもので、統計的な比較ではない。
- 応答の判定は筆者の目視であり、人による日本語の採点は行っていない。
- 合成テストの code 2 問と、混合文の code は実行していない。
- 比較は同じ clone・同じ設定の 1 回ずつである。本番の serve 環境での値（v1 レポートの 3/19 など）とは、server の版と設定が異なる。
- 混合文の prompt は筆者が作った少数の例で、置き換え方の偏りがある。

## 再現

[scripts/flash-next](../scripts/flash-next/README.md) の前提（CUDA 13 の NVIDIA GPU、約 84 GiB の GPU メモリ）を満たす環境で実行できる。

```sh
scripts/flash-next/setup-tensorfold.sh
RUN=outputs/runs/<新しい run>
SRC=models/models--Vontra--Qwen3.8-Flash-Next-MLX-4bit-MTP/snapshots/2b170fa6309d5d1ee380b35636075fac7945f286
scripts/flash-next/evaluate.sh "$RUN" original "$SRC"
scripts/flash-next/evaluate.sh "$RUN" pruned-v2 outputs/models/flash-next-ja-en-coding-v2
for label in original pruned-v2; do
  model=$([ $label = original ] && echo "$SRC" || echo outputs/models/flash-next-ja-en-coding-v2)
  scripts/flash-next/serve.sh "$RUN/mixed-$label-server.log" "$model" \
    sh -c 'python3 scripts/probe_replies.py "$TF_BASE" fn "$0/test-data" scripts/prompts/mixed-ja.json "$0/mixed-$1.jsonl"' "$RUN" $label
  scripts/flash-next/serve.sh "$RUN/coding-$label-server.log" "$model" \
    sh -c 'python3 scripts/probe_replies.py "$TF_BASE" fn "$0/test-data" scripts/prompts/mixed-ja-coding.json "$0/coding-$1.jsonl" 1000' "$RUN" $label
done
```
