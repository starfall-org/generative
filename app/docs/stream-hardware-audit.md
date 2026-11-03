# Streaming hardware-cost audit — 2026-11-03

Scope: branch `fix/stream-performance` (draft PR #61).
This is a **source/complexity audit**, not an Android CPU/GPU allocation trace. A prior
synthetic Compose test produced no valid overlapping scroll/stream frame measurements;
see `../../PROBLEMS.md`. In particular, no CPU %, memory MB, or FPS improvement is claimed.

## What already avoids unnecessary work

- `logic/ChatStreamDisplay.kt` batches visible publications with
  `STREAM_COMMIT_INTERVAL_MS = 50` ms (`StreamCharBatcher.kt`), rather than
  invalidating UI for every network token. Each publication holds an append-only
  `StreamTextSnapshot` whose existing text segments are shared. No full response
  copy is needed for the normal answer-text publication.
- `ui/markdown/StreamingMarkdownRenderer.kt` uses an incremental parser on
  `MarkdownRenderWork`/`Dispatchers.Default`, with bounded parser append
  (`4,096` characters) and unstable Markdown tail (`8,000` characters).
  A `snapshotFlow().conflate()` collector can catch up from the latest source.
  This branch's scroll throttle applies at most about once per 120 ms while
  `LazyListState.isScrollInProgress` is true, without stopping the stream.
- `ui/AssistantMessageCard.kt` collects answer updates inside the text subtree,
  not by re-creating the assistant-card shell. `logic/ChatGeneration.kt`
  persists completed blocks and the final response, not every network fragment.
- Streaming fade now renders text in one content pass, rather than two.

## Avoidable / scaling costs ranked by severity

| Priority | Path | Code evidence and resource mechanism | Confidence |
| --- | --- | --- | --- |
| P0 | Long answer body | `ui/ChatMessageList.kt` has one `LazyColumn` item **per turn**, while `ui/markdown/MarkdownIncrementalDocument.kt` renders **every stable and unstable AST top-level node** in a `Column`. The large active turn is not virtualized by block. Memory, composition/measurement and selection registrars can scale with all blocks in the turn even when many blocks are off screen. | Confirmed architectural property; actual frame cost unmeasured |
| P0 | Oversized unstable tail and overflow | Once the incremental parser hits its 8k unstable-tail or 128k parser/document budget, `MarkdownIncrementalDocument.kt` takes `source.slice(overflowStart, sourceLength)` as a newly growing whole `String` and shows it through a single `OverflowPlainText` / Compose `Text`. On subsequent commits the parser stops but full overflow copy and text layout **do not stop or become bounded**. A long unfinished code fence or continuous paragraph may therefore incur growing allocation/layout costs. | Confirmed by control flow; no allocation trace |
| P1 | Active fade GPU work | `shared/ui/text/StreamingTextFade.kt` still uses `saveLayer(nodeBounds)` plus `DstIn` masks for every animating text node. At 50ms visible commits and 120ms fade duration the fade may run almost continuously; the layer's cost grows with text-node area. Cached range paths and one draw pass helped but do not remove the offscreen rendering cost. While manually scrolling the local 120ms throttle reduces text updates, not every fade frame. | Confirmed drawing operations; GPU expense unmeasured |
| P1 | Reasoning/thinking | `ui/LiveStreamProcessing.kt:rememberLiveStreamReasoning` materializes the **entire reasoning snapshot** on each reasoning-length change. `ui/AssistantMessageCard.kt` rebuilds the reasoning/action presentation, and `ui/LiveThinkingBlock.kt` measures/displays the full plain-text reasoning string even though the collapsed preview is only about 3.5 lines. Copying and text layout repeatedly are potentially quadratic in a growing long reasoning answer. | Confirmed code; per-device magnitude unmeasured |
| P1 | Precomputation contention | `ui/markdown/StreamingMarkdownPreparation.kt` prepares Markdown/LaTeX for **unmounted** streaming rows. If the tail lacks stable boundaries, it repeatedly scans the growing remaining tail. Full Markdown parses share `MarkdownRenderWork`'s single global semaphore with visible parsing, so off-screen speculative work may delay rendering a visible response. Background work saves open latency but need not run at full frequency when another screen is foreground. | Confirmed flow and shared semaphore; contention unmeasured |
| P2 | Per-update rescanning and allocations | Every changing tail block (`ui/markdown/MarkdownIncrementalDocument.kt`) re-slices source, performs `MathSplitter.hasUnclosedMathDelimiter` when enabled, and hashes the tail using `markdownContentFingerprint` (SHA-256) for cache identity. Inline annotators and `StreamingFadeTracker.update` also rescan growing strings; `String.commonPrefixWith` constructs a prefix string. These are bounded by the tail *on the normal path*, but repeated scans allocate and add main-thread work. | Confirmed loops; cost likely dependent on tail size |
| P2 | Duplicate live data | The generation keeps a `StringBuilder` of the final answer plus append-only stream segments until completion. This is an intentional trade-off to preserve final persistence and stable append-only UI snapshots; avoid changing it without allocation and correctness measurements. | Confirmed state duplication; not automatically wasteful |

## Important distinctions

- **Parser bounded does not mean layout bounded.** The 8k tail guard limits incremental parsing, not the single fallback Text or the number of already stable blocks.
- **Chunk throttling is not frame throttling.** 50ms publication and 120ms scroll application do not prevent per-frame fade layers or a full-length Markdown block from being measured.
- The previous `3/3` Android regression suite verifies functional behavior only; it does not quantify jank. No controlled valid baseline/after trace exists.
- An offscreen pre-render that improves resume/open latency should be budgeted as low priority, not simply deleted. A flat per-message lazy list would risk selection/copy, content order and scroll continuity; any block-level virtualization must preserve those contracts and belong to the renderer rather than hardcoding Markdown inside chat core.

## Ordered next changes and validation

1. Instrument **real foreground** frame timing, CPU main-thread sections, allocation rate and GPU layers without the Compose Test virtual clock. Collect short answer, 200 Markdown blocks, long open code fence, 40k-character paragraph and long reasoning with auto-scroll off. Include output-length sweeps and idle-vs-scroll comparison.
2. Bound *rendering* and memory of oversized growing overflow content while retaining the full response for copy/selection, then test open fence/paragraph and stop/complete transitions.
3. Reduce large-turn per-frame measurement by renderer-owned block-level virtualization or another verified approach compatible with continuous selection, clipboard, links and chat positioning. Use existing `MarkdownVirtualizationTest`/height-cache metrics as a starting point; do not add guessed heights in chat core.
4. Isolate active fade GPU cost: compare animation on/off under the same workload. Prefer clipped/new-range-only rendering rather than a full text-node `saveLayer`; preserve ordinary visibility at every stream update.
5. Limit repeated full-reasoning materialization/layout and reduce offscreen speculative parser priority. Avoid DB writes and per-token UI updates. Re-run live-thinking tests and cross-tool-block tests.
6. Compare p50/p95 frame time, jank ratio, layout/draw duration, allocation rate, `IncrementalMarkdownMetrics` and `ChatRenderMetrics` before/after each change, not only end-state FPS.

No new Gradle build or device performance test was run for this audit. None of the P0/P1
findings are marked as fixed. Keep draft PR #61 open until the measurements validate the
changes. The implementation, tests, and audit are tracked in draft PR #61.
