## 1. 作業目標

**Qwen3-Coder-Next 4bit MLX モデルを対象に、自己用途で不要な vocabulary を物理的に削除し、対応する embedding / LM head を縮小したモデルを作成して、M5 Max 64GB 上の oMLX で通常どおり推論可能にする。**

PoC の評価目的は次の3点。

1. vocabulary / embedding を実際に削除する操作がどの程度単純に成立するか確認する。
2. pruning 後も日本語・coding 能力を大きく損なわず生成できるか確認する。
3. 不要言語表記の混入減少、モデルサイズ、tokenization efficiency、推論速度の変化を測る。

MTP、calibration-aware quantization、再学習は **PoC の対象外**。

---

## 2. 作業項目

### A. Vocabulary pruning

既存 Qwen3-Coder-Next tokenizer の lexical vocabulary を解析し、

- Arabic
- Cyrillic
- Hangul
- 用意した簡体字集合
- その他明示的に指定した不要文字

を含む非基礎的 token を削除候補とする。

special / protocol tokens は明示的に保護する。

特に、

- chat tokens
- EOS/BOS
- FIM tokens
- tool-call tokens
- `repo_name`
- `file_sep`

等は削除対象外。

### B. BPE merge 整合化

削除対象 token に関連する BPE merge を DAG として処理し、削除後 tokenizer が存在しない token を生成しないようにする。

### C. Token ID compact 化

surviving vocabulary を旧 ID 順などの deterministic な規則で

\[
0,\ldots,K-1
\]

へ再番号付けし、

\[
\text{oldId}\leftrightarrow\text{newId}
\]

の双方向 mapping を保存する。

### D. MLX checkpoint surgery

既存の **Qwen3-Coder-Next MLX 4bit checkpoint** を直接加工する。

対象は主に、

- `model.embed_tokens.*`
- `lm_head.*`

の

- packed weights
- scales
- biases

であり、keep rows のみを同じ順序で抽出する。

Transformer / MoE 本体は一切変更しない。

### E. Metadata 更新

少なくとも、

- `config.json` の `vocab_size`
- tokenizer vocabulary
- merge rules
- added/special token IDs
- tokenizer config
- generation config

を新 ID 空間に合わせる。

### F. oMLX で検証

加工後ディレクトリを通常の MLX model として oMLX にロードし、before/after 比較を行う。

---

## 3. 具体的な手法案

実装言語は **Scala 3** とし、推論 engine は変更しない。

概略 pipeline は、

```text
mlx-community/Qwen3-Coder-Next-4bit
        │
        ▼
tokenizer.json解析
        │
        ▼
decoded surfaceへ復元
        │
        ▼
forbidden-character predicate
        │
        ▼
BPE merge DAG cleanup
        │
        ▼
surviving old IDs
        │
        ▼
old ID → compact ID
        │
        ├──────────────┐
        ▼              ▼
tokenizer rewrite   MLX tensor row slicing
                       │
                       ▼
              embedding / lm_head縮小
        │              │
        └──────┬───────┘
               ▼
         config rewrite
               │
               ▼
         pruned MLX model
               │
               ▼
              oMLX
```

型として最低限、

```scala
opaque type OriginalTokenId = Int
opaque type CompactTokenId  = Int
opaque type TokenSurface    = String
```

を分離する。

projection は例えば、

```scala
final case class VocabularyProjection(
  originalToCompact: Vector[Option[CompactTokenId]],
  compactToOriginal: Vector[OriginalTokenId]
)
```

とし、

\[
\mathrm{original}(\mathrm{compact}(i))=i
\]

を invariant / property test で保証する。

safetensors は Scala の `FileChannel` / `ByteBuffer` で直接扱い、**モデル全体を RAM にロードしない**。変更対象 tensor を含まない shard はコピーまたは APFS clone、変更対象 shard のみ再構築する。

量子化済み row は再量子化せず、

\[
Q(W)[K,:]
\]

をそのままコピーする。このため pruning 前後で surviving weights の量子化誤差は完全に同一に保てる。

---

## 4. 注意事項

### Tokenizer

Qwen は ByteLevel BPE なので、`tokenizer.json` の vocab key をそのまま文字列判定しない。ByteLevel表現を元の byte sequence に戻し、valid UTF-8 の surface に復元してから pruning predicate を適用する。

UTF-8 の一部しか表さない基礎 byte token 等は原則 **protected** にする。

### BPE

単に vocab entry を削除するだけではなく、その token を生成・利用する merge rule の整合性も処理する。

ただし pruning criterion と merge-graph cleanup は別責務として扱う。

### ID

Coder-Next では Flash-Next の PLE がないため、

\[
\text{token ID}
=
\text{embedding row}
=
\text{LM-head row}
\]

という通常の関係を維持したまま compact 化できる。

したがって **oMLX側の変更は原則不要**。

### Quantized tensors

MLX 4bit の `weight` だけを切らず、対応する

```text
weight
scales
biases
```

を必ず同じ row index で切る。

### Special tokens

`special=true` だけを protected 判定の根拠にしない。Coder 固有の FIM / repository / tool tokens を明示的 protected set にする。

### Correctness test

pruning対象が入力・出力に関与しないケースについては、元モデルと加工後モデルの surviving logits は原理上同一であるべきなので、

\[
z_{\mathrm{pruned}}(f(i))
\approx
z_{\mathrm{original}}(i)
\]

を強い differential oracle として利用する。

併せて、

- tokenizer token数比
- greedy output divergence
- forbidden-character generation rate
- 日本語出力の目視評価
- oMLX の tok/s
- peak unified memory

を before / after で記録する。

**PoC の完成条件は、「oMLXで普通に喋る」「surviving behavior が壊れていない」「不要表記の混入が減る」「サイズ・速度差が測れる」の4点**とするのがよいでしょう。
