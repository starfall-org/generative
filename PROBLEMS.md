# Stream performance problems

Branch: `fix/stream-performance` (from `main`).

## Symptom

With auto-scroll **off**, scrolling the chat by hand stutters while the AI is
generating text. Auto-follow is not involved, so the cause is main-thread and
GPU contention between streaming updates and user scrolling.

Status: found by reading code only. Nothing has been profiled yet. The items
below are suspects, ordered by how likely they are.

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
  Still to check: whether the streaming path really uses frozen chunks, and
  how large the "live tail" gets (a long code block or table stays one tail).
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

1. Disable the fade while the list is scrolling:
   provide `LocalStreamingTextFade = false` when
   `listState.isScrollInProgress`. Smallest change, probably the biggest win.
2. While scrolling, apply streamed text less often (batch it, then catch up
   when scrolling stops).
3. When `autoScroll` is off, do not attach `onGloballyPositioned` and
   `layoutBounds` to the latest message.
4. Check that the chunked streaming path is active, and look at the size of the
   live tail.
5. Cheaper `showJumpToLatest` (read less state, or update it only when
   scrolling stops).

## How to confirm

Use Profile GPU Rendering or Perfetto while the jank happens.

- Slow **draw** frames point to item 1.
- Slow **measure/layout** frames point to items 2 and 3.
- Compare with the fade turned off (fix 1) to confirm.

## Notes

- Fix 1 changed: instead of pausing the fade while scrolling (a workaround that only
  hid the cost), the fade draw path was restructured. All active ranges now share one
  layer and one content pass (was one layer and one content pass per range), the union
  path is rebuilt only when the oldest range expires, and nothing is allocated per frame
  except the mask gradients. Not profiled yet.

- `STREAM_FADE_DURATION_MS` is currently 120 ms in the code, which differs from
  the 480 ms mentioned earlier. Check which value is intended.
- `plans/streaming-markdown-chunking.md` was not found in this checkout.
