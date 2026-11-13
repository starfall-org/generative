# Tool call and result memory handoff

## User objective

Remove the app-imposed limit on the number of tool calls and prevent old tool-call arguments and results from exhausting RAM. The user proposed making older payloads reclaimable under memory pressure. Ordinary chat text does not need the same treatment.

The interface must remain responsive while storage is slow or unavailable: opening sheets, navigating, and deleting messages must not wait on database loading. Tool payload reads and writes must run off the UI thread. Do not replace the call-count limit with a larger fixed limit.

## Current working-tree state

The working tree already contains uncommitted changes from this task and other work. Inspect `git status` and the diff; preserve all existing changes. Do not commit, push, or build unless explicitly requested. Read `AGENTS.md` and `app/architecture.md` before editing.

Implemented in the current working tree:

- Removed the 16-call checks in the normal tool-response path and the separate 128-call Devin check. Existing individual argument-size checks are separate from the number of calls.
- Added generation-scoped, indexed temporary storage in `app/src/main/kotlin/org/starfall/generative/integrations/modelapis/ToolJsonFileStore.kt`. The normal streaming and non-streaming paths use it to load tool calls and tool-result history incrementally. Temporary data is stored in the app cache and is not Room data.
- Added incremental request serialization, a stored JSON reader, and fragment accumulation under `shared/helpers/networking/`. OpenAI, Responses, Anthropic, Google, Ollama, Devin, and the relevant account adapters were adjusted to use the tool-generation path. Storage and request work runs off the UI thread.
- The store is closed at the end of a generation, including cancellation paths. Stale spill files are cleaned on the next initialization of the tool cache.

## Reclaimable payloads in chat state — implemented, awaiting verification

The working tree now has a `ToolPayload` indirection in `shared/model/ToolPayload.kt`. `ToolActivity.arguments` and `.response` no longer strongly hold stored strings. The `ToolPayloadCache` is a bounded, evictable LRU; short payloads are also released after a successful save or cold load. Unsaved, failed-save and temporary-chat payloads retain their strong copies until a recoverable source exists. `GenerativeApplication` clears this cache on Android memory-pressure callbacks.

`ConversationRepository` and the Room DAO read large tool columns lazily and in chunks, pin payloads before a transaction may overwrite their sources, and only release strong copies after the write commits. `RoomToolPayloadSource` also constrains each chunk lookup by activity ID, so replacing a call does not return another call's data. Deleting conversations invalidates their cache entries.

`ToolDetailsSheet` loads arguments/results asynchronously and displays a loading or failed state. `ToolHistoryReplay`, skill/MCP routing, generated-media placeholders, and context-usage code were adjusted for the payload type. `ToolPayloadSerializer` continues to write plain JSON strings for backward-compatible backups and imports.

Tests were updated to construct `ToolPayload` instead of assigning `String` to `ToolActivity`, and new cases cover LRU eviction/reloading, thousands of short calls, a slow storage source, delayed sheet presentation, failed-save/temporary data, backup JSON round-trip and replacing/deleting activity rows. A full successful verification is still required before calling the implementation complete.

Do not evict entire inactive chat pages or drafts as a substitute for isolating tool payloads. Keep existing unrelated modifications. Do not commit, push, or build unless explicitly asked.

## Verification status

- `git diff --check` currently passes.
- An earlier implementation passed `:app:lintDebug` and 28 selected tests, but these predate the payload-model changes and are **not** verification of this version.
- Current lint/test attempts first exposed a Kotlin nested-class receiver error in `ConversationRepository.PayloadKeys` and an unescaped apostrophe in the English `tool_details_load_failed` resource; both have been corrected.
- Subsequent Gradle runs on both installed JDK 25 and JDK 21 were canceled after unusually long execution with no conclusive lint or unit-test results. **Do not claim they passed.**
- The previous broader suite had separate fixture/mock failures in `ToolHardeningTest`, a skill-resource assertion and an MCP fixture; these have not been rechecked.

Run `:app:lintDebug` and appropriate `:app:testDebugUnitTest` selections after the environment permits, preferably offline. In particular, confirm the new `ToolPayloadCacheTest`, `ConversationIncrementalPersistenceTest`, `ConversationSaveJournalTest`, `ChatActionsMediaRegressionTest`, `ToolChatIntegrationTest`, `DirectMediaAttachmentTest`, `GeneratedMediaPlaceholderTest`, `BuiltInSkillToolsTest`, and `ContextApiUsageTest`. Do not claim Android device/UI performance without measuring it.
