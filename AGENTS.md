# Repository guidance

Purpose: Qwen3-Coder-Next MLX vocabulary and embedding / LM-head pruning with oMLX compatibility. Use Scala and sbt; keep packages under `me.nsmr.vocabsurgery` and organization `me.nsmr`. Version pins are in `build.sbt` and `project/build.properties`.

## Read documents when relevant

- Use [README.md](README.md) for setup, loader experimentation with `sbt console`, and the exact opt-in local snapshot test command.
- Use [docs/INFRASTRUCTURE.md](docs/INFRASTRUCTURE.md) for loader, data representation, diagnostic, or binary I/O changes.
- Use [docs/DOMAIN.md](docs/DOMAIN.md) for Unicode / kanji policy, BPE cleanup, projection, token-reference schemas, or pure model-update plans.
- Use [docs/PROCESSING.md](docs/PROCESSING.md) for saved plans, input inventories, output boundaries, staging, publication, or full checkpoint verification.
- Use [docs/EVALUATION.md](docs/EVALUATION.md) for oMLX helpers, fixed suites, metrics, code execution, or evaluation conclusions. Keep a failed logits gate and pending human ratings visible; do not infer TASK completion from a successful command.
- Use [docs/PRUNING_JA_EN_CODING_V2.md](docs/PRUNING_JA_EN_CODING_V2.md) when changing `policies/ja-en-coding-v2.json` or its Math / manual-Han decisions.
- Use [outputs/SPEC.md](outputs/SPEC.md) when changing surgery requirements or acceptance criteria. Distinguish agreed requirements from initial proposed thresholds.
- Use [kanji-data/README.md](kanji-data/README.md) when changing which reference files or fields are consumed.

## Input and output boundaries

- `models` links to the Hugging Face cache. Keep snapshots and symlink targets read-only; write generated models and analysis to `outputs`, without hard links to inputs.
- `kanji-data` is an upstream submodule. Loader changes must not incidentally edit its data or advance its revision. Initialize it when needed: `git submodule update --init kanji-data`.
- `outputs/SPEC.md` is tracked documentation. Other generated outputs, `target/` directories, and IDE / Scala caches are ignored artifacts; do not hand-edit or commit them.

## Data and architecture constraints

- Loaders parse and validate structure; normalization and pruning belong in domain operations. Preserve original glyphs and missing-text diagnostics; do not infer characters from images.
- Iterate Unicode scalar values through `CodePoint`, not UTF-16 `Char`. ByteLevel keys are encoded text, not decoded surfaces; protocol protection must not depend solely on `special=true`.
- Preserve unknown fields in immutable Circe JSON, added-token attributes, and quantization overrides. Do not round-trip integer IDs or offsets through `Double`.
- Stream checkpoint data with bounded buffers and checked `Long` arithmetic. Metadata loaders read headers / sizes, not tensor payloads. Safetensors offsets are payload-relative.
- Keep create-new output semantics; staging / publication belongs in the processing layer. Preserve contextual `DataException` diagnostics.
- Recompute saved plans against current inputs before execution; plan-time weight hashes cover headers only, while apply / verify record and check complete file hashes. CLI publication requires the external reference tokenizer check.
- Preserve version 1 policy serialization and semantics for existing plan / manifest hashes. New policies use version 2 with Math protection enabled by default; change policy through a new plan.
- Slice packed weights, scales, and biases in identical projected row order, without re-quantization or Transformer / MoE changes.

## Verification

Run from the repository root with JDK 17+. Compiler warnings are errors. No separate lint or formatter configuration is present.

- Compile / type-check: `sbt --batch compile`.
- For localized Scala changes, use `sbt --batch 'testOnly me.nsmr.vocabsurgery.io.InfrastructureSuite'`, replacing / adding fully qualified names from this table:

| Change | Suites under `me.nsmr.vocabsurgery` |
| --- | --- |
| Core types or common I/O | `io.InfrastructureSuite`, plus downstream suites affected by the contract |
| Kanji parsing / records | `kanji.KanjiLoaderSuite` |
| Unicode / kanji reference operations | `kanji.KanjiOperationsSuite`, plus `tokenizer.VocabularySuite` if classification changes |
| ByteLevel, pruning, BPE, projection | `tokenizer.VocabularySuite`, `tokenizer.BpeGraphSuite` |
| Config, tokenizer, index, snapshot | `model.ModelLoaderSuite` |
| Tokenizer rewriting / metadata ID schemas | `model.MetadataOperationsSuite`, `model.ModelOperationsSuite` |
| Tensor / shard projection or model-update plans | `model.TensorOperationsSuite`, `model.ModelOperationsSuite` |
| Safetensors layout / binary I/O | `model.SafetensorsSuite`, plus `model.ModelLoaderSuite` if snapshot behavior changes |
| Plan persistence, transfer, staging, publication | `processing.ProcessingSuite` |
| CLI dispatch, argument / helper failures | `cli.CliSuite` |

- For build / dependency or cross-package changes, use `sbt --batch testFull`. In sbt 2, `test` is incremental; `clean;test` does not replace `testFull`.
- When real-checkpoint coverage is needed, run `model.LocalSnapshotSuite` using the README's test-JVM property setup. It expects the recorded Qwen snapshot. Without `vocabSurgery.snapshot`, it skips: do not count this as coverage or auto-download weights. Reference tokenizer / NFC comparison additionally requires the explicit `vocabSurgery.tokenizersPython` property; do not count an unset property as that coverage.
- Kanji tests need the submodule. Tests use a 512 MiB forked heap and sparse temporary files; retain these checks for memory-sensitive I/O.
- After CLI or runtime dependency changes, run `sbt --batch stageCli` before using `scripts/vocab-surgery`; it launches staged jars, not current source.
- For Python evaluation helper changes, use `python3 -m unittest discover -s scripts/tests -v`. Scala / TypeScript execution coverage requires `stageCli` and the explicit tool setup in EVALUATION.md; report skips. Shell launcher changes need `sh -n scripts/vocab-surgery scripts/run-surgery.sh`.
- Run `sbt --batch doc` for Scaladoc changes. For prose-only changes, check links and `git diff --check`; Scala tests are unnecessary.

Complete the scoped checks, report skipped integration coverage, and commit implementation changes in coherent, verified units as required by the specification.
