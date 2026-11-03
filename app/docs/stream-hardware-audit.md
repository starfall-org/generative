# Streaming hardware-cost audit — 2026-11-03

Scope: branch `fix/stream-performance` (draft PR #61).
This began as a source audit. A normal foreground debug-only Activity on
Star 4 has now provided preliminary Android gfxinfo frame results; see
PROBLEMS.md. These are single samples, not repeated before/after traces.
No CPU %, GPU utilization, battery saving, or FPS improvement is claimed.

## Current source-review disposition after integrating `main`

The scoped producer-cost investigation is **complete**: no changes to
`StreamCharBatcher`, `StreamResponseTextSplitter`, `ChatStreamDisplay` or
append-only snapshot publication are justified. Rendering architecture
concerns should be tracked separately, rather than made mandatory CPU/FPS
thresholds from a slow test device.

The PR branch now avoids rescanning huge offscreen Markdown tails through a
16,384-character budget for **optional speculative preparation only**.
Visible Markdown parsing, full response text, Stop behavior and selection
remain authoritative. The collector's overflow transition and active-overflow
handling were split into focused helpers without changing update order.

`main` was integrated with its own root TODO preserved, and two stale Compose
imports were corrected. Code-level architecture/extension checks pass. The
initial local Gradle attempts found stale imports and hit resource timeouts.
A later bounded run completed Kotlin/Java compilation and test compilation;
45 focused post-merge JVM cases were verified with zero failures (34 Markdown,
scroll, fade, speculation and batcher tests, plus 11 splitter tests). The
final targeted Gradle invocation returned `BUILD SUCCESSFUL`.

The combined `:app:lintDebug` job **did not finish** within its 720-second
resource cap; analysis tasks started, but no complete Android lint result
was produced. CodeFactor and SonarCloud passed on the reviewed PR head.
Per the user's request, merge assessment emphasizes source code and
correctness checks rather than FPS thresholds from a low-end test phone.
The incomplete full lint run remains documented and is not called passed.

## Isolated chunk-producer hardware benchmark — scoped conclusion (2026-11-03)

**Result: the chunk-processing stage is not a material CPU or allocation
bottleneck; leave production chunk logic unchanged.** This is a separate,
scoped answer from the downstream Markdown/Compose rendering problems below.
Measured the actual Kotlin source classes in a process without Compose,
Markdown, network or Room; the benchmark sources and reproduction procedure are
in [`scripts/benchmarks/`](../../scripts/benchmarks/README.md).

The stress workload used 20,000 fragments of 14 characters each (280,000
characters), plus a fragmented `<think>` reasoning scenario. Each value is
the median of seven samples after three warm-up runs:

| Scenario | Star 4 Android 11 thread CPU | Star 4 allocation | JVM 25 thread CPU | JVM allocation |
| --- | ---: | ---: | ---: | ---: |
| Plain-text splitter (20k fragments) | 27.1 ms | 3.36 MiB | 6.4 ms | 3.66 MiB |
| Reasoning / `</think>` splitter (15,712 fragments) | 108.7 ms | 7.91 MiB | 13.2 ms | 7.91 MiB |
| Character batcher, flush every 5 fragments | 29.6 ms | 1.64 MiB | 4.8 ms | 1.27 MiB |
| Append-only snapshots (20k appends) | 7.3 ms | 1.83 MiB | 2.0 ms | 1.98 MiB |
| Whole stream display (20k fragments, 50 ms pacing setting) | 64.2 ms | 5.28 MiB | 10.0 ms | 4.82 MiB |
| Whole stream display with 1,000 forced flushes | 68.2 ms | 5.15 MiB | 10.1 ms | 4.88 MiB |

The whole-display stress case uses roughly **3.4 microseconds of thread CPU
and 270 bytes of allocation per fragment** on Star 4, including the highly
unrealistic forced-flush load. Even at 20 fragments/s it corresponds to roughly
0.068 ms CPU and ~5.4 KiB allocation *per second of stream*, excluding
network/graphics; this is a simple workload extrapolation, not a battery or
end-to-end power measurement. Reasoning-only parsing is a little more costly,
but also measured in microseconds per fragment. The production code performs
bounded local tag probing, Unicode scanning over newly appended characters,
small immutable state publications, and append-only snapshot links. There is no
full-answer Markdown parse or full-answer copy in the chunk producer on each
publication, and persistence is not scheduled per token.

**Decision:** no production change is justified in
`StreamCharBatcher`, `StreamResponseTextSplitter`, `ChatStreamDisplay`, or the
snapshot append mechanism. Existing 600ms frame stalls must not be attributed
to chunk production: the isolated chunk CPU work is orders of magnitude
smaller, while the UI rendering pipeline performs substantial layout/draw work.
Direct on-device per-process GPU/power consumption was not measured; the
inference is limited to isolated thread CPU and allocation.

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

The branch now includes reduced stream-to-Compose invalidations,
background preparation of large overflow strings, overflow updates deferred
during manual scrolling, no offscreen fades for giant text nodes, 100ms
reasoning-snapshot sampling, nonblocking speculative Markdown/TeX work, and
less transient hashing/prefix allocation. These reduce specific
unnecessary operations, but do not fully solve long-turn virtualization or
unbounded selectable Text layout. Keep PR #61 Draft.

## Preliminary device gfxinfo observations

Star 4, Android 11, 720x1560 at 60Hz, isolated normal debug Activity.
One sample per scenario; native adb swipe events and gfxinfo reset.

| Scenario | Frames | Jank | p50 / p95 / p99 | TOTAL PSS (KB) |
| --- | ---: | ---: | --- | ---: |
| 120 blocks, static | 1118 | 714 (63.86%) | 18 / 42 / 61 ms | 280225 |
| 120 blocks, streaming | 727 | 349 (48.01%) | 15 / 44 / 750 ms | 281770 |
| Long paragraph, static | 541 | 214 (39.56%) | 13 / 23 / 73 ms | 264356 |
| Long paragraph, streaming | 145 | 116 (80.00%) | 18 / 800 / 1600 ms | 279503 |

These measurements preceded the most recent overflow-scroll gate and are
NOT repeated controlled comparisons. The large overflow outliers motivated
that gate; they do not prove the subsequent patch fixes the problem.
Naively dividing the large Text into several selectable Text children would
change how Compose selects all text, so it is not used as a quick workaround.
The debug-only benchmark Activity provides a repeatable starting point.


## Repeated overflow measurement with verified stream overlap

The ordinary debug Activity was extended to log window focus and when
the publisher actually started and emitted chunk numbers. On Star 4, three
static and three stream runs with eight native swipes each yielded:

| Metric | Static: median (range) | Stream: median (range) |
| --- | --- | --- |
| p50 frame time | 17ms (16-17) | 17ms (11-17) |
| p95 frame time | 21ms (18-25) | 20ms (20-21) |
| p99 frame time | 25ms (23-28) | 700ms (700-750) |
| Janky fraction | 53.53% (49.91-66.21) | 54.23% (25.46-66.72) |
| Counted frames | 1068 (1027-1147) | 622 (601-686) |

Each live run logged 101-151 publications during the measurement, while
the Activity held window focus. Thus the p99 spike is not merely a
virtual-clock or offscreen-test artifact. The current 120ms/240ms
throttles and overflow scroll gate do not eliminate the costly full
single-Text layout. This measurement does not compare against the
pre-optimization binary and cannot establish speedup.

Additional optimization: each finalized stable AST block now remembers
its own immutable source snapshot. Future publications to the active
tail must not replace that snapshot or re-slice settled text. The
pre-existing per-turn Column still eagerly composes every stable block.

**Still open:** performant, selection-preserving, measured block
virtualization; bounded display layout for a huge selectable overflow;
CPU/GPU/allocations Perfetto traces with a separate prior-version
baseline; current-revision Android instrumentation and broader lint.

## Latest mitigation awaiting device measurements

The special-case fallback has an 850ms quiet period before a full growing
Text relayout, so short pauses between repeated manual swipes do not cause
an expensive text measure. An additional JVM test checks that the renderer
does not pass a short inter-swipe pause as genuinely idle. This is a
*partial mitigation*, not bounded text layout or proven p99 improvement;
reprofiling is outstanding.


## 850ms quiet-period comparison, real device

The new interval is deliberately isolated to an overflow Text whose
full layout can be expensive; ordinary incremental Markdown continues
to render normally. Three real-Activity streaming runs after the gate:

| Metric | Pre-quiet gate, 3 live runs | New gate, 3 live runs |
| --- | --- | --- |
| p95 median (range) | 20ms (20-21) | 18ms (17-26) |
| p99 median (range) | 700ms (700-750) | 69ms (48-600) |
| Janky fraction median (range) | 54.23% (25.46-66.72) | 21.97% (14.73-70.87) |
| Frame count median (range) | 622 (601-686) | 910 (883-944) |

The new benchmark confirmed foreground focus and at least 101 published
chunks during each measurement. Median latency is lower, but the
600ms p99 sample still violates the 16.67ms 60Hz frame budget badly.
Scheduling/device-thermal variance was not controlled, and no Perfetto
CPU/GPU/allocation trace proves where those stalls occur. Treat this as
evidence of mitigation, not completion of the unbounded Text or long-turn
layout tasks. A legitimate Select All is more important than an
incorrectly chunked fallback.
