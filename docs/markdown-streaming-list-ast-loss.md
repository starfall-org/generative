# Streaming Markdown: ordered-list items disappear

Status: **fixed in the working tree; targeted regression tests pass (23/23)**. Android lint was started but cancelled after 9 minutes without completing.

## Visible symptom

An assistant response containing one ordered list with three items renders the introductory paragraph, item `3. Gemini 4 Carbon`, and the concluding paragraph, but items `1. Gemini 4 Hydrogen` and `2. Gemini 4 Argon` are absent. The copied message contains all three entries. This is **one missing-content bug**, not a scrolling/app-bar defect.

## Confirmed cause

`IncrementalMarkdownParser` calls JetBrains Markdown's `EmptyStreamingMarkdownFile(...).append(chunk)` in `app/src/main/kotlin/org/starfall/generative/features/home/chat/ui/markdown/IncrementalMarkdownParser.kt`. The installed JetBrains Markdown JVM dependency is `org.jetbrains:markdown-jvm:0.7.9`.

With ordered-list items separated by blank lines and with continuation lines, the streaming parser **can discard its current `ORDERED_LIST` unstable tail when a fragment ends around an item header's hard-break/newline (sometimes with subsequent indentation)**. The discarded list is **not** moved into `stableChildren`. Later appends resume parsing at a later item's offset, so earlier items are absent from *both* `stableChildren` and `unstableTail`. `IncrementalMarkdownParser` merely relays the missing nodes; `MarkdownIncrementalDocument` cannot render them.

This is **not** caused by the `stableNodeCount` collection logic, the Markdown list composable, or cache eviction: the missing nodes are absent from the underlying `StreamingMarkdownFile.children` itself. The full GFM parser produces a valid three-item `ORDERED_LIST` for the same text.

## Minimal deterministic reproducer

Tested in the ModelScope environment with JetBrains Markdown JVM **0.7.9**, using this Kotlin logic:

```kotlin
import org.intellij.markdown.parser.EmptyStreamingMarkdownFile
import org.intellij.markdown.parser.MarkdownParser
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor

val source = "Before\n\n1. One  \n   one text\n\n2. Two  \n   two text\n\n3. Three  \n   three text\n\nAfter"
val stream = EmptyStreamingMarkdownFile(GFMFlavourDescriptor())
source.chunked(7).forEach(stream::append)
val full = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)

// stream.children: ORDERED_LIST[52, 76] with only item 3.
// full.children:   ORDERED_LIST[8, 76] with items 1, 2, 3.
```

Relevant trace (positions are character offsets, zero-based):

| Fragment accepted | Streaming `stableChildren` ordered lists | Streaming `unstableTail` ordered lists |
|---|---|---|
| `...2. Tw` | none | `[8,35]`, **2 items** |
| `o  \n   ` | none | **none**: items 1 and 2 lost |
| `two tex` | none | `[30,49]`, 1 item (item 2 only) |
| `...3. T` | none | `[30,56]`, 2 items |
| `hree  \n` | none | **none**: item 2 lost |
| `   thre` | none | `[52,70]`, 1 item (item 3 only) |
| final `\nAfter` | `[52,76]`, **only item 3** | none |

A second reproduction with the user's actual three-item Vietnamese Markdown and variable fragment lengths of 1–24 characters produced the same AST loss, including an instance retaining *only* `Gemini 4 Carbon`. The normal full parse contains all three entries.

## Why it persists after generation

`AssistantMessageCard.kt` sets `useIncrementalText` when a message is actively streaming **or** `liveTailMemory.hasObservedStream`. In `StreamingAssistantText(...)`, the call to `StreamingMarkdownRenderer(...)` passes `isStreaming = true` even when the parent message has finished. This retains the original incremental parser and its already-corrupted AST. The completed source/copy text can be correct while the visual nodes remain absent.

Relevant source files:

- `app/src/main/kotlin/org/starfall/generative/features/home/chat/ui/markdown/IncrementalMarkdownParser.kt`
- `app/src/main/kotlin/org/starfall/generative/features/home/chat/ui/markdown/MarkdownIncrementalDocument.kt`
- `app/src/main/kotlin/org/starfall/generative/features/home/chat/ui/markdown/StreamingMarkdownRenderer.kt`
- `app/src/main/kotlin/org/starfall/generative/features/home/chat/ui/AssistantMessageCard.kt`

## Implemented fix

`IncrementalMarkdownParser` now runs the canonical GFM parser against only the bounded **unsettled tail** of the append-only source. A conservative safe-block boundary freezes whole top-level AST nodes, including all entries of a blank-separated list as a unit. Already-frozen nodes and their offsets are retained, never re-parsed. Tail-local AST offsets are translated to global source offsets. The previous `StreamingMarkdownFile` is no longer used for authoritative render nodes; no full-message reparse is triggered after streaming stops. When a tail reaches its configured limit, the pre-existing plain-text overflow behavior still displays the entire uncommitted suffix.

The parser's reference-link publication also strips the bracket delimiters from link labels before indexing the link handler; full GFM link nodes include these delimiters.

Regression tests added:

- `IncrementalMarkdownParserTest.orderedListWithBlankSeparatedItemsSurvivesEverySevenCharacterFragment`: compares the entire AST against canonical GFM **after each fragment**, and checks all three list items.
- `IncrementalMarkdownParserTest.multiLineVietnameseOrderedListPreservesAllItemsAcrossRandomStreamBoundaries`: tests the reported text using 25 reproducible randomized fragmentations and asserts all three list items and full AST equality.
- `StreamingMarkdownPreparationTest.blankSeparatedOrderedListStaysVisibleWhileStreamingAndAfterFreeze`: verifies item visibility before and after stream completion.
- `StreamingMarkdownPreparationTest.completeOrderedListIsPresentOnColdReopen`: verifies the ordinary historical-message rendering path.

Targeted unit tests: **23 tests, 0 failures**, from the two classes above. Broad test-suite execution was stopped after failures in unrelated UI, media and scrolling tests; these were not modified. A pre-existing `BuiltInSkillToolsTest` compile error (suspend call outside a coroutine) required a temporary local test-only `runBlocking` wrapper for verification; the unrelated file was restored unchanged after the tests. Full Android lint was started but cancelled after 9 minutes without a result.

No commit or push performed.
