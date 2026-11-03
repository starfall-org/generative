# Streaming FPS and Markdown profiling

This document describes repeatable measurements for scalability work. A
normal foreground debug-only Activity has now produced initial dumpsys
gfxinfo data on Star 4 (2026-11-03); see stream-hardware-audit.md.
The Compose instrumentation-frame experiments were invalid. The new device
samples are NOT repeated controlled before/after comparisons and no
Perfetto CPU/GPU/allocation profile exists yet. Cached Gradle and the
Android SDK are available on the ModelScope machine; use offline,
resource-limited Gradle commands rather than downloading new tools.

## Fixed scenarios

Use one physical device and one build variant for both runs; record device model, Android build, refresh rate, app commit, display/font settings, and whether battery saver is on.

1. **Short stream:** one paragraph, about 20 lines; follow-bottom on and off.
2. **Long mixed stream:** 200 top-level blocks, with headings, paragraphs, lists, a table, and fenced code; follow-bottom on and off.
3. **Large single blocks:** a 40k-character paragraph, a 20k-line code fence, a 22k-character raw HTML block, a 180-row list, and a 180-row table. Verify the static paragraph/code/HTML message preview is bounded, expanded rows reach the full source, and an active stream overflow preview can open its captured plain-text source lazily.
4. **History scrolling:** open the same conversation cold, scroll down through at least 100 messages, scroll back to the first visible assistant block, then repeat warm.
5. **Deletion race:** delete a message while an AST store is paused before commit; verify that no final/temp entry survives.

The deterministic Compose list fixture is generated in `MarkdownVirtualizationTest` (180 rows). Unit tests generate longer source snapshots for exact append/range behavior. Extend that fixed fixture rather than using real user conversations in profiler recordings.

## Accessibility and reduced motion

On a provisioned device, repeat a streamed paragraph with Android system animations enabled and with animator animations disabled in Developer Options/accessibility settings. Confirm the second case displays each committed text batch without the reveal mask, while TalkBack still reads the full text and selection/copy behaves identically in both cases. The source has a unit test for the policy and the fade remains a draw-only modifier; this device/accessibility check has not been run.

## Counters to capture

The content-free counters are internal and can be inspected in Android Studio's debugger or from a focused instrumentation test:

- `MarkdownParseCache.metrics()` — memory hits/misses/writes and full-parser count, input characters, and elapsed nanoseconds. `fullParseChars` is the key guard against hidden whole-answer reparses. The same bounded LRU entry retains its precomputed content fingerprint so a static Markdown success callback does not SHA-hash the full document on Main.
- `IncrementalMarkdownMetrics.metrics()` — append count/input characters, estimated unstable-tail characters reparsed, maximum unstable-tail size, and bounded-tail preview fallback count. These counters contain no source text and help verify the active parser's bounded-work contract.
- `PersistentMarkdownAstCache.get(context).metrics()` — disk hits/misses, bytes read/written, evictions, corrupt entries, and serialization/deserialization duration.
- `MarkdownBlockHeightCache.metrics()` — measured-height variant hits/misses/writes.
- `ChatRenderMetrics.snapshot()` — ChatScreen/assistant-card recompositions plus top-level block, list-item, and table-row mount/disposal counts. Compare deltas for the same scroll interval; mount counts should stay near the viewport window, and disposal counts should rise when scrolling away.
- `ActiveChatStreamState.cumulativeMetrics` — committed character counts, maximum queued characters, and measured input-to-publish latency. The display uses a 50 ms minimum render interval and commits all text accumulated in that window together, without a per-flush character limit. This is publication latency, **not** a measured presentation timestamp.
- `SentMessageDetector.idScanCount` — assert it stays unchanged for content-only updates and increases once per structural revision.

Counters contain no message body or IDs. Reset `ChatRenderMetrics.resetForTests()` and `MarkdownBlockHeightCache.resetForTests()` between instrumentation cases. Persistent/parse cache metrics are cumulative for the cache instance; use a fresh test cache or a fresh process for cold/warm comparisons.

## Capture frame and main-thread traces

1. Run the app on the fixed device and enable Android Studio **System Trace**/Perfetto with Frame Timeline, main-thread scheduling, Compose, and memory tracks.
2. Record each fixed scenario for at least 15 seconds after warm-up. Keep the user at the same scroll position and use the same character-chunk configuration.
3. Record frame duration distribution, janky frames, longest main-thread sections, allocation rate, time-to-first-visible-content, parser characters, and visible/prefetched mount/disposal deltas.
4. Repeat each case three times; report median and range, not the best run. Keep trace files outside Git and redact any personally identifiable data.
5. Separate the cost of active-stream publication from the cost of the animation and auto-follow frame loop. Repeat with follow-bottom enabled and disabled.
6. For warm-open, retain app-private `noBackupFilesDir/render-cache` entries between process restarts. For cold-open, clear only the render cache in the test fixture (never a real user's app data) and confirm the parse fallback is asynchronous.

Suggested checks in an Android-connected checkout (not run in this sandbox):

```text
./gradlew :app:testDebugUnitTest --tests org.starfall.generative.ChatGenerationTest
./gradlew :app:testDebugUnitTest --tests org.starfall.generative.features.home.chat.ui.markdown.IncrementalMarkdownParserTest
./gradlew :app:testDebugUnitTest --tests org.starfall.generative.features.home.chat.ui.markdown.LargeMarkdownParagraphTest
./gradlew :app:testDebugUnitTest --tests org.starfall.generative.features.home.chat.ui.markdown.MarkdownBlockHeightIndexTest
./gradlew :app:testDebugUnitTest --tests org.starfall.generative.StreamingFadeTrackerTest
./gradlew :app:testDebugUnitTest --tests org.starfall.generative.MarkdownParserTest
./gradlew :app:testDebugUnitTest --tests org.starfall.generative.PersistentMarkdownAstCacheTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.starfall.generative.MarkdownVirtualizationTest
./gradlew :app:lintDebug
```

Use the repository's normal project-wide verification command from `AGENTS.md` after the focused cases pass. Do not download tooling or dependencies in an isolated/offline environment.

## Result log

| Run | Commit | Device/build | Scenario | Median frame ms / janky % | Full-parse chars | Window mounts/disposals | Notes |
| --- | --- | --- | --- | --- | ---: | --- | --- |
| 120 blocks static | Debug streamperf | Star 4 Android 11 60Hz | Manual scroll, no new chunks | p50 18ms / p95 42ms, 63.86% jank | Not captured | Not captured | One sample, not an old-version baseline. |
| 120 blocks streaming | Debug streamperf | Star 4 Android 11 60Hz | Manual scroll with new chunks | p50 15ms / p95 44ms, 48.01% jank | Not captured | Not captured | One sample; p99 750ms. |

A missing baseline or profile is an explicit verification gap, not a passing performance check. Keep this table updated with actual data; do not fill it with estimates.

For the long unfinished paragraph, the original debug capture showed
p95 23ms and 39.56% jank without new chunks versus p95 800ms and 80% jank
while streaming. This one-shot result predates the overflow scroll gate.
All four scenarios must be repeated against the latest code.


## Three-run focused overflow results (2026-11-03)

The updated normal debug Activity waited 15 seconds before beginning
the 500-chunk stream (one chunk every 55ms), logged window focus,
and logged publication counts. Repeated 8-swipe static vs live workloads:

- Static: p95 25/21/18 ms, p99 28/25/23 ms; median p95 21ms, p99 25ms.
- Live: p95 20/21/20 ms, p99 750/700/700 ms; median p95 20ms,
  p99 700ms. All three live runs confirmed more than 100
  publications during the swipe measurement.
- Frame counts differed across runs and static vs live. The large
  differences in p99 are reproducible, but do not isolate whether
  selection registration, Text layout, GC or GPU compositing dominates.
- These are not an old binary vs new binary measurement. A real
  trace and previous-version baseline are still required to quantify
  benefits before PR #61 is ready.


## Focused quiet-period rerun, 2026-11-03

The same Star 4 normal-Activity benchmark was repeated three times
after adding an 850ms uninterrupted scroll-free gate for giant
selectable Text overflow. It logged focus and actual overlapping
published chunk counts. p95 was 26/17/18ms, p99 69/48/600ms.
The three previous same-device live runs were p95 20/21/20ms and
p99 750/700/700ms. Median p99 fell from 700ms to 69ms, but a 600ms
outlier remains. Full results appear in the audit and PROBLEMS.md.

Current branch Android regression results: 3/3 passed in 13.954s,
using direct instrumentation of the isolated streamperf test package.
These results test correctness rather than sustained GPU/CPU efficiency.
