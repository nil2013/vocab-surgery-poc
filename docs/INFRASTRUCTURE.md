# 第一工程のインフラ層

漢字データと surgery 対象の metadata を、後続のドメイン操作・処理機構から利用できる型として読み込む。解析と I/O は Scala 3 で実装し、CSV は Apache Commons CSV、JSON は Circe と Jawn を使用する。全 package は `me.nsmr.vocabsurgery` 配下に置く。

## Package と責務

| Package | 主な型と API | 責務 |
| --- | --- | --- |
| `core` | `CodePoint`、`OriginalTokenId`、`ByteOffset`、`ByteCount`、`ByteRange` | 意味の異なる整数を区別し、scalar・非負値・range 加算の制約を検証 |
| `io` | `FileIO`、`CsvIO`、`JsonIO`、`DataException` | bounded I/O、共通形式の解析、入力・出力の診断 |
| `kanji` | `KanjiData`、各 source の case classes、`KanjiLoader` | 合意した 6 種類の漢字データを元字体のまま読み込む |
| `model` | `ModelConfig`、`TokenizerData`、`ShardIndex`、`SafetensorsFile`、`ModelSnapshot` と loaders | surgery に必要な metadata と file ranges を読み込み、参照を検査 |

第二工程で `CompactTokenId` と decoded `TokenSurface` を追加した。`CodePoint.sequence` は補助平面を含む scalar 列を返し、unpaired surrogate を拒否する。lexical vocab の `encoded` は ByteLevel の表現であり、decoded surface として使用しない。

## 共通 I/O

metadata の UTF-8 読み込みは既定で 64 MiB までとし、ファイル長と実際に読んだ byte 数の両方を検査する。UTF-8 は strict decoder を使用し、不正 bytes を replacement character に変換しない。JSON は duplicate keys と trailing input を拒否し、整数を `Double` へ変換しない。

CSV は header による column lookup を使用する。UTF-8 BOM、quoted commas、quoted newlines を扱い、必須 column の欠落、header 重複、record の column 数不一致を拒否する。

`FileIO.writeNewUtf8`、`JsonIO.writeNew`、`FileIO.copyRange` は出力を `CREATE_NEW` で開き、既存ファイルを置換しない。parent directories の作成や staging directory の確定は後続の処理機構が担当する。書き込み途中に I/O が失敗した場合、作成済みの部分ファイルは残り得る。

range コピーは最大 16 MiB の buffer を使用する。file positions と byte 数は `Long` とし、range の末尾と位置加算の overflow を検出する。SHA-256 は 64 KiB の buffer で全ファイルまたは指定 range を計算できる。モデルの通常ロードでは metadata と headers のみ hash を取得し、巨大 payload 全体は読まない。

## 漢字データ

`KanjiLoader.load` は次の files を読む。各 source は path、SHA-256、typed records を保持する。

| ファイル | 表現 |
| --- | --- |
| `JIS漢字.csv` | Unicode scalar、面区点、JIS 水準。符号位置列と文字を照合 |
| `常用漢字.csv` | 掲載順と Unicode scalar |
| `教育漢字.csv` | Unicode scalar と学年 1〜6 |
| `子の名に使える漢字.csv` | Unicode scalar と通用字体／常用外／異体字の区分 |
| `常用漢字表本表.json` | 通用字体、0 個以上の康熙字典体、元の JSON record |
| `漢検漢字辞典漢字.csv` | optional Unicode scalar、字体・級・ID・画像等、元の column values |

康熙字典体の空欄は空の vector とし、`辨）（瓣）（辯` は 3 字体として読む。漢検の空欄・単一 scalar ではない文字テキストは `character=None` とし、record 番号と raw text を `GlyphDiagnostic` に残す。画像から文字を推定しない。

この層では字体の NFC 正規化、保護集合の統合、Han の削除判定を実行しない。元データと解析済みの値を保持し、[第二工程の操作](DOMAIN.md) に渡す。

## モデル metadata

`SnapshotLoader.load` は config、generation config、tokenizer、tokenizer config、shard index、chat template を読み込み、index が参照する shards の header を検査する。snapshot の symlinks は読み取り専用で追跡する。

config の typed fields は model type、vocab size、hidden size、embedding 共有、量子化設定、主要な token ID 参照である。量子化 override の省略項目は基本設定を継承し、`quantization` と `quantization_config` が併記される場合は両者を照合する。typed token ID 参照は BOS／EOS／PAD／decoder start とし、scalar と ID list の形を保持する。

tokenizer は lexical vocabulary、merge 順序、added tokens と属性を保持する。重複 ID、added token と lexical vocab の衝突、vocab にない merge 左右・結果を拒否する。added tokens の `special=false` はそのまま保持し、保護対象の判定は第二工程に委ねる。

config・generation config・tokenizer・index の元 JSON は immutable な Circe `Json` として保持する。未知の fields と数値を落とさず、後続の metadata 更新の入力に利用できる。その他の ID fields、protocol、ByteLevel alphabet の詳細検証は、第二工程以降の操作で扱う。

shard index の file names は snapshot 直下の `.safetensors` に限定する。index と全 shard headers の tensor 対応、payload byte 数、登録 token IDs の範囲、主要設定からの ID 参照、embedding／LM head の companion tensors と行数を照合する。

## safetensors

`SafetensorsIO.read` は 8-byte little-endian の length prefix と、既定で最大 16 MiB の header を読む。payload の bytes はロードせず、file size と dtype・shape・range から整合性を検証する。返り値に open channel や payload 配列を保持しない。

header は `{` から始まる JSON とし、`__metadata__` は string map に限定する。byte offsets は payload 起点として保持する。negative dimensions、byte 数の overflow、shape と range の不一致、payload の穴・重複・未索引の末尾を拒否する。rank-0 scalar と empty tensors を扱う。

第一工程の dtype 対応は BOOL、I8／U8、I16／U16／F16／BF16、I32／U32／F32、I64／U64／F64。未知の dtype は `UnsupportedFormat` とし、誤った byte 幅を仮定しない。

`encodeHeader` は supplied tensor metadata から順序を固定した JSON と space padding、length prefix を生成する。`encodeHeaderJson` は `ShardProjection.header` に保持した未知 fields を捨てずに同じ形式で符号化する。payload の書き込みと tensor row slicing は [第三工程の処理機構](PROCESSING.md) が実行する。

## 検証済みの範囲

合成入力で CSV の quoting、不正 UTF-8、JSON の整数精度、既存出力の保持、2 GiB 超の range コピー、safetensors の不正 offsets・shape・header、snapshot 内の参照不整合を検証する。40 GiB の sparse checkpoint を 512 MiB の test heap で検査し、payload 展開を避けることを確認する。

`kanji-data` の実ファイルは 6 種類を読み込む。任意のローカルモデルへアクセスする integration test は明示的な path 指定を必要とし、この workspace では固定 snapshot の 9 shards・1,891 tensors と記録された件数・byte 数を照合した。これらは payload の byte 同一性、pruning の正しさ、oMLX での推論成功を保証する検証ではない。
