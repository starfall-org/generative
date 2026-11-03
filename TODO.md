# Stream-performance follow-up

Branch: `fix/stream-performance` — [draft PR #61](https://github.com/starfall-org/generative/pull/61).

## Implemented and checked on this branch

- [x] Rework streaming-text fade to one content pass and cached paths.
- [x] Defer expensive Jump-to-latest visibility recomputation during manual scrolling.
- [x] Use a scroll-aware 120 ms streaming Markdown application interval; coalesce stream
      snapshots rather than pausing network input or persisting chunks.
- [x] Flush a pending update immediately when scrolling stops.
- [x] Inspect the incremental Markdown path: bounded unstable parser tail (8,000 chars),
      bounded parser append (4,096 chars), and 120 ms fade as intended.
- [x] Add four `StreamScrollThrottleTest` unit tests and run them successfully.
- [x] Run three chat stream/scroll Android regression cases on Star 4: 3/3 passed.
- [x] Restore the temporary test-only `build.gradle.kts` application ID and screen
      timeout to their original values.

## Implemented hardware-cost reductions

Detailed findings and first device data: app/docs/stream-hardware-audit.md.

- [x] Decouple network stream publication from the source presented to the measured
      Markdown tree. Re-render accepted parser snapshots, not every network chunk.
- [x] Prepare growing plain-text overflow in Dispatchers.Default rather than
      slicing a large source on the Compose main thread. Coalesce to 240ms
      while idle; defer relayout while a user manually scrolls and catch up
      on release. Preserve the full source and single-node Select All.
- [x] Skip decorative saveLayer/DstIn fade for Text nodes over 2048 chars,
      while keeping the original reveal for normal paragraphs.
- [x] Limit full reasoning-snapshot materialization to ten UI refreshes/s.
- [x] Use a nonblocking parser permit for speculative offscreen Markdown/TeX
      instead of queueing behind and competing with visible work.
- [x] Skip re-scanning tiny offscreen unfinished-tail appends unless the
      appended source contains paragraph/math boundary candidates.
- [x] Eliminate per-publication SHA-256 for transient tail content and
      eliminate growing substring allocation for fade prefix comparison.
- [x] Build debug-only, normal foreground benchmark Activity to get real Android
      frame timings without Compose Test's virtual frame clock.
- [x] Build the modified debug APK and pass 25/25 focused unit tests.

## Outstanding hardware-cost tasks

- [-] **P0 — growing overflow:** large text copying and UI-frame work have been
      reduced, but a single selectable Text still grows without an upper layout
      bound. Must not fragment it into independently selectable nodes and break
      the existing Select All behavior.
- [-] **P0 — large turns:** accepted source snapshots avoid per-publication
      invalidations, but stable Markdown top-level nodes remain inside a
      non-lazy Column in one turn-level LazyColumn item. Need renderer-owned
      measured virtualization without guessed heights or lost selection/copy.
- [-] **P1 — fade:** large nodes skip GPU layers; quantify GPU cost of the
      remaining small-node animation before further algorithm changes.
- [-] **P1 — thinking:** refresh is now bounded, but full reasoning layout
      still scales with reasoning length. Preserve selection/copy while optimizing.
- [-] **P1 — speculative parsing:** nonblocking admission and boundary-based
      rescanning are implemented; a long speculative task that already holds
      the permit cannot be preempted. Investigate true task prioritization.
- [-] **P2 — hot path scans:** transient hashing and fade substring allocation
      removed; inline annotation/math processing needs targeted profiling.

## Device verification

- [x] Real foreground Android gfxinfo: Star 4, 60Hz, 120 Markdown blocks,
      baseline and streaming, plus long unfinished paragraph baseline/streaming.
      First one-shot data is recorded in PROBLEMS.md and the audit.
- [ ] Rerun the newly gated overflow stream build on the device; the recorded
      80% jank / p95 800ms is from before the overflow scroll gate.
- [ ] Record at least three reproducible before/after runs per scenario and
      compare p50/p95 frame times, jank, allocations, CPU and GPU/Perfetto
      on a normal foreground Activity; do not infer smoothness from one run.
- [ ] Run the three chat scroll Android regression cases again against the
      current build, plus Select All/copy regressions and broader lint/tests.
- [ ] Address remaining P0/P1 design problems, then re-run all checks.
- [x] Keep branch and PR synchronized: commit and push source, tests and
      reports before checking off work in Draft PR #61.
- [ ] Mark PR #61 ready only once all performance/correctness criteria pass.

The 25 passing unit tests and the previously passing 3/3 Android regressions
are behavior checks, not evidence that the outstanding P0 issues are solved.
