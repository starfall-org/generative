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

## Hardware-cost audit priorities (source-verified, not yet fixed)

Full findings: [`app/docs/stream-hardware-audit.md`](app/docs/stream-hardware-audit.md).

- [x] Audit batching, snapshot ownership, Markdown parser, Compose layout, thinking and GPU fade.
- [ ] **P0 — bounded rendering:** prevent growing overflow fallback from repeatedly
      copying and laying out all overflow text as a single Text node.
- [ ] **P0 — long-turn scalability:** address the top-level Markdown blocks all being
      rendered in a non-lazy Column under one turn-level LazyColumn item; preserve
      selection/copy and no approximate heights in chat core.
- [ ] **P1 — fade GPU:** quantify full-node saveLayer/DstIn cost on every fade frame;
      optimize to affected content rather than merely hiding text during scrolling.
- [ ] **P1 — thinking:** remove unnecessary whole-reasoning materialization and
      full-text layout for each reasoning update, especially with collapsed preview.
- [ ] **P1 — offscreen preparation:** ensure speculative Markdown/LaTeX work cannot
      monopolize the one shared Markdown parser permit ahead of visible content.
- [ ] **P2 — incremental scans:** reduce repeated string slicing, SHA-256 fingerprinting,
      math-delimiter scanning and fade-prefix allocation on every tail update.

## Outstanding — do not mark PR ready yet

- [ ] **Get valid frame measurements.** The synthetic Compose Test benchmarks could
      not measure real overlap of scrolling and stream updates (zero valid frames or
      gestures delayed until after stream). Details in `PROBLEMS.md`.
      Run the test in a *normal foreground Activity*, not the Compose Test clock,
      and profile using Perfetto or Android FrameMetrics.
- [ ] Compare baseline vs long, actively streaming Markdown while manually scrolling
      with auto-scroll off, including p50/p95 frame times, slow draw/layout frames,
      and device refresh-budget violations.
- [ ] Fix any remaining source of jank supported by the profile, then re-test.
- [ ] Run broader lint/unit/device coverage after the final source changes.
- [x] Commit/push the implementation, tests and audit report on branch
      `fix/stream-performance` and synchronize draft PR #61 with the pushed files.
- [ ] Keep PR #61 Draft until device performance is validated.

The existing 18 passing tests in the initial targeted JVM run plus all four
throttle tests on the corrected rerun, and 3/3 Android regressions, do **not**
establish that the user's real-time manual scrolling is smooth.
