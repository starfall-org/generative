# Stream performance problems

Branch: `fix/stream-performance` (from `main`).

## Symptom

With auto-scroll **off**, scrolling the chat by hand stutters while the AI is
generating text. Auto-follow is not involved, so the cause is main-thread and
GPU contention between streaming updates and user scrolling.

Status: originally identified by source review. On 2026-11-03, normal
foreground debug-only Android gfxinfo captures showed substantial frame jank,
both while streaming and with a static long answer. See the hardware audit.
The items below were the initial suspects.

## Suspects

### 1. Streaming fade is redrawn every frame (most likely)

File: `shared/ui/text/StreamingTextFade.kt`

- Every fading range builds a `Path` with `getPathForRange`.
- Every frame draws `clipPath` + `saveLayer` + a `DstIn` gradient, once per
  active range (the `saveLayer` call is around line 117).
- Active ranges are filtered and merged into a new `Path` on every draw.
- `saveLayer` allocates an offscreen layer. While the user scrolls, the whole
  list is redrawn each frame, so this cost is paid every frame.
- With dense chunks, several ranges overlap and the cost multiplies.

### 2. The streaming tail is re-parsed and re-measured on every update

Files: `features/home/chat/ui/markdown/StreamingMarkdownRenderer.kt`,
`IncrementalMarkdownParser.kt`, `MarkdownRenderer.kt`

- Each text change re-lays out the last markdown block. The fade also needs a
  fresh `TextLayoutResult` for `getPathForRange`.
- `LazyColumn` measures newly visible items on the same thread while the user
  scrolls, so one frame can exceed 16 ms.
- `ChunkedStreamingMarkdown.kt` and `MarkdownFrozenChunks.kt` already exist.
  Checked in fix 4: the live path uses the incremental parser (not the frozen chunks), and the
  live tail is capped at 8,000 characters.
- Content over `MAX_MARKDOWN_RENDER_CHARS` switches to
  `StreamingMarkdownPreview` (80 ms loop), which changes how it renders.

### 3. Position callbacks run even when they are not needed

File: `features/home/chat/ui/ChatMessageList.kt`

- Every message end marker has `onVisibilityChanged` and `onGloballyPositioned`.
- The latest message also has `layoutBounds(state.latestContentBounds)`.
- The latest message changes height constantly, so these fire on every layout.
- With auto-scroll off, `onGloballyPositioned` still runs. The `enabled`
  argument of `contentEndPosition()` only stops the `follow()` call.

### 4. `showJumpToLatest` is recomputed on every scroll frame

Files: `ChatMessageList.kt` (`showJumpToLatest`), `ChatScreen.kt` (reads it for
the FAB)

- The `derivedStateOf` reads `latestContentBounds.boundsInRoot`,
  `listState.layoutInfo` and `listState.scrollIndicatorState`.
- It is re-evaluated on every scroll frame and on every growth of the latest
  message. Probably small, but it adds up with 1-3.

## Proposed fixes (in order)

Status: [x] done, [-] dropped or not needed, [ ] still open.

1. [x] Make the streaming fade cheaper. Pausing it while scrolling was dropped (it only
   hid the cost). Now drawn with a single content pass, see Notes. Not profiled yet.
2. [x] While the list scrolls, apply streamed text at a bounded rate (one application per
   `SCROLL_STREAM_APPLY_INTERVAL_MS` = 120 ms), then catch up from the latest text. Once
   scrolling stops, an in-flight wait ends immediately. Text keeps moving rather than pausing.
   Tests: `StreamScrollThrottleTest`.
3. [-] Skip `onGloballyPositioned` / `layoutBounds` when auto-scroll is off. Dropped: it broke
   following when auto-scroll is switched on during growth (see Notes).
4. [x] Check that the chunked path is active and the size of the live tail. Result: the live
   path is the incremental parser, the tail is already bounded (see Notes). No change needed.
5. [x] Cheaper `showJumpToLatest`. Landed in `a70001b8`.
6. [x] Re-run the three scroll/stream regression cases on the Star 4 with its display
   awake: 3/3 passed (15.875 s) on 2026-11-03. Earlier failures occurred while the
   display was dozing/off, and did not reproduce when it was awake. Screen timeout was
   restored to its previous 60 seconds. See the verification notes below.
7. [ ] Profile on the Star 4 using Perfetto or Profile GPU Rendering while manually
   scrolling a long, actively streaming Markdown reply with auto-scroll off. The initial
   `gfxinfo` query returned zero rendered frames; it is not valid performance data.
8. [ ] Compare frame timing and jank against the previous behavior; only mark PR #61
   ready for review after the stutter is verified resolved.

## How to confirm

Use Profile GPU Rendering or Perfetto while the jank happens.

- Slow **draw** frames point to item 1.
- Slow **measure/layout** frames point to items 2 and 3.
- Compare with the fade turned off (fix 1) to confirm.

## Notes

- Fix 1 changed twice. Pausing the fade while scrolling only hid the cost, so it was
  dropped. The fade is now drawn with a single content pass: the text is rendered once
  into one layer and the fresh ranges are masked in place (DstIn), instead of being
  drawn once outside the fading region and once more inside it. Per-range path and
  bounds are built once per text update; nothing is allocated per frame except the
  mask gradients. Trade-off: while fading, the layer is the size of the text node.
  Not profiled yet.
- Fix 3 (skip the follow-position callback when auto-scroll is off) was dropped. It broke
  following when auto-scroll is switched on while the reply is growing: the first callback
  after attaching only records a baseline, so that growth was never followed. Caught by
  `ChatStreamingScrollPerformanceRegressionTest`. The callback is cheap, so it stays.
- Fix 5 (cheaper `showJumpToLatest`) landed in `a70001b8`.
- Unit tests: no new failures. 9 tests in 6 classes fail the same way on `c40ca041`
  (`IncrementalMarkdownParserTest`, `ChatTextSelectionDismissalTest`, `ContentApiMediaTest`,
  `ConversationDrawerGeneratingIndicatorTest`, `ConversationMinimapSideBarTest`,
  `CrashReportDialogUiTest`), so they are not caused by this branch.
- `STREAM_FADE_DURATION_MS` is currently 120 ms in the code, which differs from
  the 480 ms mentioned earlier. Check which value is intended.
- `plans/streaming-markdown-chunking.md` was not found in this checkout.
- Fix 2 landed as a rate limit, not a pause: `LocalChatListScrolling` (a `State<Boolean>` provided
  by `ChatMessageList`, so scroll start/stop does not recompose rows) is read inside
  `collectIncrementalMarkdownUpdates`. While scrolling, each application waits 120 ms and then
  re-reads the latest snapshot, so everything published meanwhile is applied in one step.
  Without scrolling there is no wait. The wait also ends as soon as the list stops scrolling,
  so the final catch-up is not delayed. Freeze (Stop) is re-checked afterwards. Not profiled yet.
- Fix 4 result: with a stream snapshot (the live path from `AssistantMessageCard`),
  `StreamingMarkdownRenderer` uses `IncrementalStreamingMarkdown` and `IncrementalMarkdownParser`.
  Stable nodes are frozen and only the unstable tail is laid out again. That tail is capped at
  `MAX_INCREMENTAL_UNSTABLE_TAIL_CHARS` = 8,000 and each append at
  `MAX_INCREMENTAL_PARSER_APPEND_CHARS` = 4,096. Beyond the cap the rest is shown as plain text
  (`preserveUnstableTailAsPlainText`). `ChunkedStreamingMarkdown` is only the fallback when no
  snapshot is given (`streamSnapshot == null`). Past `MAX_MARKDOWN_RENDER_CHARS` = 128,000 the
  response switches to the overflow/preview path.
- `STREAM_FADE_DURATION_MS` = 120 ms is intended: `StreamingFadeTrackerTest` asserts it is at
  most 160 ms. The 480 ms mentioned earlier is outdated.
- Verification on 2026-11-03 (offline Gradle, no daemon, at most 2 workers):
  the first targeted run of `StreamScrollThrottleTest`,
  `ChatStreamingScrollPerformanceRegressionTest`, `StreamingFadeTrackerTest`, and
  `ChatJumpButtonVisibilityTest` passed 18/19 tests. The new scroll-stop test initially
  failed because a raw Compose state write in its standalone coroutine test did not dispatch
  snapshot apply notifications. After using `Snapshot.withMutableSnapshot` to simulate an
  applied UI update, all 4 throttle tests passed in the second run. The other 15 tests had
  passed in the first run. Lint, the full suite, and a real-device performance profile have
  not been run. `git diff --check` passed.

- Device verification attempt on 2026-11-03: connected Star 4 (Android API 30) through
  Tailscale using an ADB tunnel on local port 42349. The installed
  `org.starfall.generative.debug` version `1.2.788-fix/launcher` was preserved.
  For isolation, built an instrumentation-only package with a temporary
  `.streamperf` application ID; the Gradle configuration change was reverted after
  creating the APKs. Offline Gradle `connectedDebugAndroidTest` could not run because
  `com.android.tools.utp:android-test-plugin-host-additional-test-output:32.3.0`
  is not in the local cache. APK compilation itself succeeded, and both isolated
  packages installed using `adb install --no-streaming`.
- The first direct ADB run of `ChatStreamingScrollPerformanceDeviceRegressionTest`
  encountered two failures while the device was dozing with its display OFF:
  `No compose hierarchies found in the app` and `ComposeTimeoutException`.
  After waking the display, dismissing keyguard and temporarily extending
  the screen timeout, all **3 of 3 tests passed** in **15.875 seconds** using
  direct ADB instrumentation. The screen timeout was restored to its original
  **60000 ms**. These regression cases use synthetic layouts and stream events;
  passing them does not establish that long Markdown streams scroll smoothly.
- No valid Perfetto trace, comparable frame times, or full lint/full test suite
  result is available yet. Leave device performance profiling and actual smoothness
  verification unchecked; do not merge PR #61 based only on passing tests.


## Real-device profiling attempts (2026-11-03, Star 4, ADB 42911)

- Connectivity: the previous ADB port 42349 went offline; a fresh Tailscale/socat
  tunnel on localhost:42911 connected successfully. The production debug install
  (`1.2.788-fix/launcher`) remained untouched. An isolated
  `org.starfall.generative.streamperf` test APK was used.
- All 3 existing Android scroll/stream regression tests had already passed on
  Star 4 with the display awake. The four throttle unit tests passed as well.
- Attempted automated per-frame profiling of an incrementally growing Markdown
  answer via `Window.OnFrameMetricsAvailableListener` and an Android test APK.
  The attempt did **not** produce valid comparative frame timings:
  1. Compose Test `performTouchInput` waits for test idleness and caused the
     injected gestures to occur after the stream publisher had finished; its
     apparent slow frames cannot be attributed to simultaneous user scrolling.
  2. Global `UiAutomation.injectInputEvent` delivered events, but did not
     scroll the target LazyColumn (`scrollChanges=1`); the benchmark's window
     rendered no counted frames.
  3. A `LazyListState.scroll` attempt from the Android test with the activity
     brought forward via temporary shell permission did obtain window focus,
     but the Compose test's virtual frame scheduling still yielded
     `baseline frames=0` and `streaming frames=0` with no recorded scroll
     progress. No meaningful frame-rate inference is possible from these runs.
  4. The experimental benchmark source was removed rather than leaving a
     known-failing Android test in the branch. The isolated device install was
     never used to modify the user's existing app or persistent chat data.
- **Still required:** capture actual frames in an ordinary foreground Activity
  running outside Compose Test's virtual clock, with a deterministic streaming
  publisher and native scroll motion. Use Android FrameMetrics/Perfetto and
  compare like-for-like baseline vs streaming p95 and missed-frame counts.
  No conclusion that the scroll stutter is gone has been established.


## Regular foreground Activity measurements — 2026-11-03

Unlike the failed Compose Test experiments, a normal debug-only Activity
successfully rendered a long chat answer under real Android frame scheduling.
Model: Star 4, Android 11, 720x1560, refresh rate 60Hz, isolated package
org.starfall.generative.streamperf; native adb input swipe gestures and
Android dumpsys gfxinfo reset before each measurement.

| Scenario | Frames | Janky frames | p50 | p95 | p99 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 120 blocks, static | 1118 | 714 (63.86%) | 18ms | 42ms | 61ms |
| 120 blocks, streaming | 727 | 349 (48.01%) | 15ms | 44ms | 750ms |
| Long unfinished paragraph, static | 541 | 214 (39.56%) | 13ms | 23ms | 73ms |
| Long unfinished paragraph, streaming | 145 | 116 (80%) | 18ms | 800ms | 1600ms |

These observations were captured BEFORE the final manual-scroll overflow gate.
They are only one run per scenario with differing frame totals and no
like-for-like old-version baseline; no FPS improvement claim is justified.
The overflow spikes confirm that the growing single Text fallback needs
more than a normal Markdown stream throttle.

In follow-up local source changes the growing source is decoupled from
per-publication Compose invalidation; giant overflow copies are prepared on
Dispatchers.Default, idle overflow updates are coalesced to 240ms, and
overflow presentation waits for a manual scroll to stop. Very large
streamed Text nodes disable offscreen fade layers, and active reasoning
materialization is sampled at 100ms. Speculative Markdown/TeX parsing now
uses a nonblocking parser permit. The remaining selectable single-Text
fallback and per-turn non-lazy Markdown layout are not solved.
