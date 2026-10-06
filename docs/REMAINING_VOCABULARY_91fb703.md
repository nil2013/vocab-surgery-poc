# 初期 pruning 後の残存語彙：91fb703

調査日：2026-10-06。調査時の repository HEAD は **`91fb703b55a93c98f75c377fe262e416da5d956f`**。

この文書は、その版の初期 policy で pruning したモデルに何が残るかを記録する。日英入力・コーディング中心の用途を前提に追加削減候補を調べた静的分析であり、候補の削除を決定した仕様ではない。追加 pruning は実行していない。policy の意味は [DOMAIN.md](DOMAIN.md)、加工・入力照合は [PROCESSING.md](PROCESSING.md) を参照する。

## 対象を識別する情報

| 項目 | 値 |
| --- | --- |
| 調査時 HEAD | `91fb703b55a93c98f75c377fe262e416da5d956f` |
| 元 HF snapshot | `mlx-community/Qwen3-Coder-Next-4bit` / `7b9321eabb85ce79625cac3f61ea691e4ea984b5` |
| 漢字 submodule revision | `0be3577f7939ec85d2b4e373a7a94262e7449e13` |
| Unicode provider | ICU4J 78.3 / Unicode 17.0.0 |
| 加工モデル | `outputs/models/qwen3-coder-next-4bit-pruned-stage34` |
| 加工 run | `outputs/runs/stage34-validation` |
| 加工後 `tokenizer.json` SHA-256 | `d50c85f027698c1e79639e2348fc10897d9194b5dcb9d5e13d2918da09aa32a2` |
| 適用した `plan.json` SHA-256 | `67006cd9449402600c621d5afe845a6b64f6874e1ad4ddd09e871b0f300bb2f2` |
| manifest の policy SHA-256 | `5f2fae1aefca7e105321e1d7326920babdc23aea360968aa71dc1ac549692dc9` |

公開済みモデルの計画と、調査時 HEAD で作った `outputs/runs/stage34-inspect-plan/plan.json` は、`domain` と `policy` が一致することを確認した。上記 HEAD は調査・照合時のコード版を指す。文書を追加したコミットや、モデルの作成時刻を表す識別子ではない。

初期 policy は `forbiddenScripts=[Arabic,Cyrillic,Hangul]`、`pruneHanOutsideReference=true`、未登録行は `drop`。追加保護漢字、明示禁止 code points、手動保護 IDs／contents はいずれも空。日本語参照集合は採用した 6 ファイルの字体・NFC の和集合で、10,086 字。Han 判定は `Script=Han && General_Category=Lo`。

元の重み行数は 151,936、残存は 128,803。直接削除 22,866、BPE cleanup による間接削除 0、未登録行削除 267。初期加工の payload 削減は 53,298,432 bytes。

## 調査方法と数え方

加工後の `tokenizer.json` を repository の `TokenizerLoader` で読み、lexical keys を `ByteLevel.decode` で元 bytes と strict UTF-8 surface に戻した。文字判定には ICU4J の Script、Script_Extensions、category、binary properties を使用した。元 run の `tokens.jsonl` と全 lexical key・compact ID を照合し、旧 ID と保護理由を引き継いだ。tensor payload や推論 engine は読み込まない。

| 内訳 | 行数 |
| --- | ---: |
| 全登録行 | 128,803 |
| Lexical vocabulary | 128,777 |
| Added tokens | 26 |
| 完全な UTF-8 surface を持つ lexical tokens | 127,329 |
| 単独では UTF-8 にならない lexical tokens | 1,448 |
| 完全な UTF-8 surface を持ち、保護理由のない lexical tokens | 127,201 |

以下の件数は「該当 scalar を一つ以上含む token 行」の数。同じ字を何度含んでも一行と数える。複数 Script や複数分類を含む行があるため、表の単純合計は行数にならない。使用頻度、生成頻度、自然言語の確定判定ではない。例は実在する decoded surface から選んだ。

Added／protocol tokens と、基礎 bytes・部分 UTF-8 tokens は削除候補から除外する。今回列挙した Script・記号群の該当完全 UTF-8 行には保護理由がないが、それだけで用途上不要とは判断しない。

## 残存する文字体系

| Script | 行数 | 例・用途上の留意点 |
| --- | ---: | --- |
| Hebrew | 3,184 | `ראש`、`אות`、`ונים`。数学利用の例外も検討する |
| Thai | 2,569 | `การ`、`ความ`、`เป็น`、`ที่` |
| Greek | 239 | `α`、`β`、`μ`、`λ`。数学・科学・コードで使う |
| Ethiopic | 118 | `ት`、`ር`、`ማ` |
| Armenian | 79 | `ա`、`ր`、`ն` |
| Canadian_Aboriginal | 72 | `ᐅ`、`ᑕ`。装飾・顔文字の利用とも区別する |
| Devanagari | 65 | `ा`、`्`、`र` |
| Bengali | 44 | `া`、`্`、`ব` |
| Tibetan | 44 | `་`、`ཕ`、`༺` |
| Myanmar | 37 | `မ`、`န`、`သ` |
| Georgian | 36 | `ღ`、`ი`、`რ` |
| Khmer | 36 | `ា`、`ន`、`ស` |
| Lao | 33 | `າ`、`ວ`、`ນ` |
| Malayalam | 32 | `്`、`മ`、`ല` |
| Nko | 32 | `ߞ`、`ߋ`、`ߕ` |
| Mongolian | 30 | `ᠠ`、`ᠢ`、`ᠵ` |
| Syriac | 30 | `ܐ`、`ܓ`、`ܝ` |
| Tamil | 28 | `்`、`ு`、`த` |

Latin・Han・Hiragana・Katakana・Common・Inherited 以外の Script を含む、非保護行の重複を除いた和集合は **7,091 行**。この値には Greek、点字、古代文字、装飾に使われる文字も含む。Hebrew と Thai だけの和集合は **5,753 行**。

初期の禁止対象である Arabic・Cyrillic・Hangul の `Script` を含む完全 UTF-8 lexical tokens は 0。ただし、後述する Common／Inherited の関連記号や、保護された bytes の組み合わせによる表現は残る。

## ラテン文字の外国語語彙

`Script=Latin` かつ非 ASCII の scalar を含む token は **6,094 行**。`ación`、`ção`、`für`、`não`、`über`、`está`、`información`、`résultat` 等が残る。全角ラテン文字、発音記号、人名、借用語も含むため、一括削除は英語・日本語用途にも影響し得る。

ASCII だけの外国語も残る。surface を `String.trim` して一致を確認した例は `Bonjour`、`gracias`、`Hola`。これらは上記 6,094 行の判定だけでは抽出できない。Latin の短い部分文字列は英語・identifier と共有されるので、文字集合だけで外国語由来の全語彙を識別することはできない。

## Han と中国語寄りの語彙

Han 漢字を含む行は **14,939 行**。構成文字が参照集合にある中国語語彙として、`函数`、`数据`、`字符串`、`初始化`、`生命周期`、`不存在` 等が残る。`文件`、`使用`、`方法`、`保存` のように、日本語用途とも重なる例もある。

次の字はすべて `JIS漢字.csv` に含まれ、現行の日本語参照集合内なので直接禁止判定を受けない。

| 字 | その字を含む残存行数 |
| --- | ---: |
| `个` | 104 |
| `开` | 71 |
| `关` | 45 |
| `无` | 37 |
| `网` | 32 |
| `确` | 17 |

「常用漢字＋人名用漢字」の外の Han 漢字を含む行は **4,792 行**。これは狭い集合との比較結果であり、不要語彙数ではない。例えば `脆` を含む 3 行、`琲` を含む 1 行もこの条件に該当する。日本語の技術文書、人名、地名、製品名を巻き込むので、参照集合を単純に狭める方針には注意が必要。

今後は、参照集合内でも不要と判断する字の明示リスト、または中国語寄りの lexical surface の明示リストを検討できる。ただし現在の JSON policy の `explicitForbidden` は code points の指定であり、語彙文字列の禁止リスト機能ではない。

## 記号・装飾・制御文字

| 分類 | 行数 | 例・留意点 |
| --- | ---: | --- |
| Extended_Pictographic | 1,086 | `🙂`、`😉`、`©`、`™`。絵文字以外の用途も含む |
| 罫線・ブロック（U+2500–259F） | 137 | `─`、`█`、`│`。端末 UI・図にも使う |
| 点字（U+2800–28FF） | 7 | `⠀`、`⡱`、`⠩` |
| Dingbats（U+2700–27BF） | 156 | `✓`、`✔`、`❤`、`✅` |
| 矢印（U+2190–21FF） | 97 | `→`、`⇒`、`↔` |
| 非 ASCII の category=Sm | 246 | `×`、`±`、`≤`。全数学文字の集合ではない |
| category=Cf | 25 | BOM、zero-width space、soft hyphen。BOM＋`using`／`namespace`／`//` 等も残る |
| 全角ラテン文字・数字 | 64 | `１`、`２` 等。日本語 typography と共用 |
| 結合文字（Mn／Mc／Me） | 1,701 | タイ語の母音符号、accent、variation selector 等。広い共有用途がある |

インデント・改行・ASCII punctuation・数学・矢印・BOM はコーディングでも使う。装飾系の候補と一律に扱わない。

## Arabic 関連の残存記号

`Script_Extensions` に Arabic を含む scalar を持つ行は **19 行**。今回該当した文字の `Script` はすべて Common／Inherited であり、現行の `Script=Arabic` 判定では削除されない。

- Common：`،` U+060C、`؛` U+061B、`؟` U+061F、`ـ` U+0640、`﴾` U+FD3E、`﴿` U+FD3F。
- Inherited：U+064B、U+064E、U+064F、U+0650、U+0651、U+0652 の母音・結合符号。

`"،`、`)،`、` ،`、tatweel の連続を含む token もある。U+0600–06FF の範囲だけで数えた場合は 17 行で、19 行との差は U+FD3E／FD3F。関連記号を追加禁止するときは、Script と Script_Extensions の違いを明記し、共有文字を個別に判断する。

## 数学文字を保護する際の境界

調査と同じ ICU4J で確認した分類は以下。Math は Unicode binary property の値であり、人が数学に使うかどうかの完全な判定ではない。

| 文字 | Code point | Script | Math |
| --- | --- | --- | --- |
| `ℵ` | U+2135 | Common | true |
| `ℶ` | U+2136 | Common | true |
| `ℷ` | U+2137 | Common | true |
| `ℸ` | U+2138 | Common | true |
| `א` | U+05D0 | Hebrew | false |
| `α` | U+03B1 | Greek | false |
| `λ` | U+03BB | Greek | false |

`ℵ` とヘブライ文字の `א` は別の code point。`Script=Hebrew` を追加禁止しても、Common の `ℵ` 系列はその条件では削除されない。加工後 tokenizer では `ℵ` は compact ID 126790 の単一 token。`ℶ`・`ℷ`・`ℸ` はそれぞれ 2 tokens で表現できる。

一方、`א` 自体を数式に使う場合や、Greek の数学・科学用途は Script／Math 属性だけでは保護しきれない。追加 pruning 前に、数学・コードで必要な文字と tokens の明示的な例外集合を検討する。`protectedContents` は一致する token の保護指定であり、その文字を含む全 token を自動的に保護する設定ではない。

## 追加削減の規模と未確定事項

この snapshot の 6 対象 tensors では、直接削除 1 行につき payload **2,304 bytes** 減る。

| 仮の直接削除集合 | 重複を除いた行数 | 対応 payload bytes | MiB |
| --- | ---: | ---: | ---: |
| Hebrew＋Thai | 5,753 | 13,254,912 | 約12.64 |
| 主要な日英 Script 以外 | 7,091 | 16,337,664 | 約15.58 |
| 上記＋非 ASCII Latin | 13,185 | 30,378,240 | 約28.97 |

これは各集合を全部直接削除した場合の行数 × byte 幅の計算。数学等の例外による減少、BPE cleanup の追加削除、metadata／header の差分は含まない。モデル全体は約44.8 GB なのでサイズ効果は小さく、速度向上や混入減少を保証する見積もりではない。

追加対象として Hebrew／Thai と Arabic 関連の残存記号を優先検討する余地はあるが、数学・科学・identifier・日本語 typography との共用を先に整理する。ASCII 外国語と中国語 lexical surface は文字体系の判定から分けて検討する。具体的な追加 policy、例外集合、追加加工、品質比較は未実施。

## ローカルの調査成果物と確認

詳細はローカルの `outputs/analyses/remaining-vocabulary-20261006` に保存した。`summary.json` は分類別件数と例、`details.json` は Arabic 関連文字と漢字の比較、`candidate-rows.jsonl` は旧 ID・compact ID・surface・保護理由、`interpretation.json` は集計の解釈を含む。Scala の調査ソースも同 directory に保存している。これらは生成 artifacts として Git 対象外であり、checkout に同梱される恒久的な調査記録はこの文書である。

Scala 3.3.8 と既存の staged jars で調査コードをコンパイル・実行した。全登録行・lexical／added 内訳・UTF-8 内訳の一致、旧計画との lexical key／compact ID の一致、tokenizer SHA-256、調査時 HEAD の計画との `domain`／`policy` 一致を確認した。Script、Math、数学記号の tokenization も実データで確認した。

この分析は vocabulary の静的な残存状態の記録。推論品質や数値 gate の判定を更新するものではない。加工モデルの検証結果と既知の logits 許容差超過は [EVALUATION.md](EVALUATION.md) を参照する。
