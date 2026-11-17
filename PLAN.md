# PLAN — Server Connection

Status: **partially implemented; outstanding work and validation tracked in [TODO.md](TODO.md)**
Repositories examined: `~/generative` (Android) and `~/serverside-generative` (Go v5 API), 2026-11-17.

## 1. Goal and non-negotiable behavior

Add **Server Connection** as an optional remote chat workspace. When enabled from **SideDrawer → Remote Services**, the existing Chat screen and conversation drawer display **the selected ServerSide server's conversations instead of local conversations**. Disabling it restores the local workspace exactly as left. Do **not** upload, migrate, merge, or overwrite local chats merely because the switch changes.

- Preserve the existing SSH machine selection, tool enablement, Remote SSH screens, Chat appearance, transitions, message rendering, and local persistence behavior.
- ServerSide is the authority for remote chat data and revisions; its v5 conversations are always durable **on the server**. The app's local chat memory/sync preferences do not alter this contract.
- Distinguish each workspace by connection identity (`serverConnectionId`) and session ID; never mix chat IDs, retained screen caches, input drafts, pagination, selections, title updates, delete actions, or generation states across local and remote workspaces.
- An offline or unauthenticated remote workspace stays visibly remote with an explicit reconnect/error state. **Never silently switch back to local or send a prompt locally.** The user can switch off explicitly.
- Never implement Android workarounds that distort local chat models or UX to fit missing server features. Record missing server requirements in `~/serverside-generative/Problems.md` and resolve them in that repository before enabling the affected behavior.

## 2. User-facing entry points

### 2.1 AppMenu

- In `features/menu/ui/AppMenuScreen.kt`, extend the existing `app-menu-group-remote` `ComponentGroup`: **Remote SSH** first, **Server Connection** immediately below in the same rounded group.
- Add its navigation callback in `navigation/AppNavigation.kt` and a `SERVER_CONNECTION` destination. Use existing `ListScreenTemplate` for connection management and `EditorScreenTemplate` for add/edit; use the established add → search → list/grid → menu app-bar order where applicable.
- Server Connection screen: saved connection list (friendly name, endpoint, connection/auth status), add/edit/delete, test/reconnect, pair or re-pair, revoke/disconnect. No new full-screen navigation framework or unrelated SSH settings changes.
- Editor: name, **transport**, endpoint/host, pairing/import flow and verified TLS/SSH trust details; show protocol/capabilities, pairing status and actionable errors. Never display secrets or accept an unverified certificate silently.

### 2.2 SideDrawer → Remote Services

- Extend `features/home/drawer/ui/DrawerQuickShortcuts.kt` within the **existing expandable Remote Services card**, without changing the sibling Instructions and Media cards.
- **Row 1:** current SSH machine selector and its existing options/enable semantics, unchanged.
- **Row 2 directly underneath:** **Remote Server** selector (saved ServerSide connections), with a **trailing Switch** visible on the row itself. Selecting a server does not enable remote chat by itself; the switch controls the active workspace.
- When no server is selected, show a neutral placeholder; tapping the selector opens a shared selection sheet and provides access to connection management. Switching on requires a saved, paired connection; otherwise keep it off and explain the next action.
- On successful activation the drawer chat list, search and chat content are sourced exclusively from that server, and a clear but restrained remote-workspace indicator is shown. Switching off immediately restores the last local chat, list, drafts and scroll state. Switching servers preserves each server's last selected session and never shows another server's conversation during loading.
- When a connection fails after activation, retain remote mode, show an offline/error status and offer reconnect; never redirect send to the local `AiService`.
- Keep **SSH tool enablement** independent from **Remote Server chat enablement**: enabling a machine does not select the chat backend, and enabling the remote backend does not enable machine tools.

## 3. Verified ServerSide v5 contract (not an invented REST API)

Sources: `~/serverside-generative/docs/client-api-surface.md`, `README.md`, `internal/rpc/dispatch.go`, `internal/chatsession/{manager,run}.go`, `internal/node/{v5,http_pairing,transport}.go`.

| Concern | Actual server behavior | Planned client behavior |
| --- | --- | --- |
| Discover | `GET /v1/capabilities`, RPC `capabilities.get` | Verify protocol 5 and operations before enabling controls; fail closed on incompatible versions. |
| HTTP/WS pairing | Complete `https://host/connect#code=...&pin=sha256/...&v=1` URL or identical QR; `POST /v1/pair` | Parse fragment locally, verify HTTPS hostname **and SPKI pin before redeeming**, then persist returned paired identity securely; never log/store the one-time code. |
| HTTP auth | `POST /v1/auth` with `client_id`/`secret`; bearer lifetime currently 900 s | Keep bearer in RAM, renew on expiry/401 safely, never log it; use for RPC and SSE. |
| HTTP transport | `POST /v1/rpc` with `{id, op, session_id?, data?}`; `GET /v1/events?session_id=&after=` SSE or JSON long poll | Start with pinned HTTPS RPC + SSE/long-poll, with one serializer and error model; use authenticated WSS where appropriate. |
| TCP/SSH transport | TLS/TCP length-prefixed JSON (4-byte big-endian); SSH pairing only via `serverside pair --stdio --transport tcp`, data via `serverside stream --stdio` | Separate SSH/TCP adapter; reuse existing remote SSH access only through a clean adapter, strict SSH host-key verification; no automatic cross-identity transport fallback. |
| Remote chat list | `session.list {offset, limit}` → `{items, next_offset}` with tombstones | Page through **remote** list; exclude tombstones from active UI but process deletions. Server metadata/sorting gaps are tracked in `Problems.md`. |
| Open/read | `session.open` with optional initial `conversation`/transient `runtime`; `session.get` with optional `if_revision` | New remote session uses server-generated empty conversation on open; existing remote session opens then gets authoritative snapshot. Do not send a local chat as initial conversation unless explicit future import is designed. |
| Generate | `session.configure` for transient runtime, `session.run {request_id,content}`, `session.cancel` | Unique stable request IDs; display progress but treat full `conversation` snapshots as durable authority. No blind replay when delivery is uncertain. |
| Updates/recovery | Events `delta`, `conversation` with `revision`, `completed`, `error`, `event_gap`; per-active-session `seq` | Stream deltas, reconcile snapshots by revision, on gap/restart reopen and `session.get`; discard obsolete cursors after session re-open/expiry. |
| Edit/delete | `session.save` with `expected_revision` and `idempotency_key`; `session.delete` with CAS + tombstone; `session.close` releases RAM only | CAS with conflict UI/reload, never last-write-wins. Closing/disconnecting must never be interpreted as deletion. |
| Provider/tool runtime | `runtime.source="stored"` selects server-managed provider; `mobile_ephemeral` supplies session-scoped RAM-only credentials; `tool.list`/`tool.call` expose explicit tool operations | Default to **server-managed runtime**, with user-selected server model. Never silently transmit local API keys, MCP credentials or tool settings; future local handoff requires explicit permission. |

**Current limitations are not Android requirements:** `session.run` is text-only, allows only a subset of providers/configurations and rejects attachments/tool/reasoning histories; stored-chat handoff is limited to about 3 MiB (4 MiB frame). These gaps and the lack of a typed lightweight server provider/model picker endpoint are recorded in `Problems.md`.

## 4. Target module boundaries and interfaces

Follow `AGENTS.md` and `app/architecture.md`. Keep the **protocol/client implementations in `integrations/serverside/`**, never in Compose, local model API adapters, `shared/` helpers, or `extensions/`. This is a reference file/interface map; some components are already implemented under nearby module names, while others remain planned:

```text
integrations/serverside/
  ServerSideClient.kt          # transport-independent suspend API / Flow of server events
  ServerSideProtocol.kt        # v5 RPC envelopes, operations, errors, capability DTOs
  ServerSideSession.kt         # session/list/snapshot/revision/event DTOs
  ServerSidePairing.kt         # one-time URL parsing + trusted pairing contract
  ServerSideHttpTransport.kt   # pinned HTTPS, auth, RPC, SSE/long-poll, WSS if needed
  ServerSideSshTransport.kt    # SSH stdio + TLS/TCP framed transport (separate stage)
  ServerSideRuntime.kt         # remote provider selection / transient handoff contract
features/serverconnection/
  model/                       # connection editor and workspace UI states
  logic/                       # connection management, remote chat coordinator, selection
  ui/                          # list + editor, pairing and status UI
shared/model/                  # only truly cross-feature neutral models, if necessary
database/room/                 # saved connection records and encrypted paired credentials
database/repositories/         # RAM-first connection catalog / serial persistence
database/preferences/          # selected connection, workspace toggle and per-server last chat
navigation/AppNavigation.kt    # feature callbacks, destination, local/remote chat source selection
appwiring/AppContainer.kt      # own and inject one connection service/coordinator
```

Proposed transport-independent client contract (method names illustrative; wire operations and payloads above are authoritative):

```kotlin
interface ServerSideClient {
    suspend fun capabilities(connectionId: String): ServerCapabilities
    suspend fun pairHttp(connectionId: String, invitationUrl: String): PairedIdentity
    suspend fun authenticate(connectionId: String)
    suspend fun listSessions(connectionId: String, offset: Int, limit: Int): RemoteSessionPage
    suspend fun openSession(connectionId: String, sessionId: String, runtime: RemoteRuntime?): RemoteSnapshot
    suspend fun getSession(connectionId: String, sessionId: String, ifRevision: Long? = null): RemoteSnapshotResult
    suspend fun configureSession(connectionId: String, sessionId: String, runtime: RemoteRuntime)
    suspend fun run(connectionId: String, sessionId: String, requestId: String, content: String)
    suspend fun cancel(connectionId: String, sessionId: String, requestId: String)
    fun events(connectionId: String, sessionId: String, after: Long): Flow<ServerEvent>
    suspend fun saveSession(connectionId: String, sessionId: String, expectedRevision: Long, idempotencyKey: String, conversation: RemoteConversation): RemoteSnapshot
    suspend fun deleteSession(connectionId: String, sessionId: String, expectedRevision: Long, idempotencyKey: String)
    suspend fun closeSession(connectionId: String, sessionId: String)
}
```

- `ServerSideProtocol` owns strict v5 decoding, request/reply correlation, error codes, version and feature negotiation; transport interfaces deal only in framed JSON/events. No API credentials in debug logs.
- Remote chat coordinator in `features/serverconnection/logic` owns lifecycle, selected connection/session, reconnect, revisions, cancellation, remote list cache and mapping server JSON to existing render models. It must **not** route remote sends into `ChatGeneration`, the local `ConversationRepository` or `AiService`.
- Keep remote **conversation rendering** on existing `ChatScreen`/`ConversationSideDrawer` through source/callback abstraction at the navigation boundary, while separating local and remote write/delete/generation implementations. Do not copy ChatScreen or alter message markup for remote mode.
- Separate remote session ID namespace and workspace-keyed `RetainedChatConversations`/render cache; retain one local chat state independently so switching does not trigger an unwanted local DB refresh or lose drafts.
- Server snapshots preserve unknown JSON fields when round-tripped; map known fields to Kotlin `Conversation` only for display. CAS updates must not silently discard unsupported server fields.
- Store connection metadata as app-controlled records; store long-lived paired secrets through existing `SecretCipher`/encrypted Room patterns (and backup policy). Never put secrets into DataStore, saved state, deep-link history, logs or cleartext exports. Credentials are explicitly revoked/removed on delete with confirmation; distinguish local deletion of connection from server-side identity revocation.

## 5. Workspace and request lifecycle

1. **Configure** in AppMenu: add a connection, choose transport, validate reachability/trust, pair and save encrypted identity; test capabilities. Do not mark connected just because a URL was saved.
2. **Select** Remote Server in SideDrawer without changing the chat backend. If the switch is enabled, selecting another server transitions to that server's workspace after an atomic selection update; do not show stale conversations from the former server.
3. **Enable** switch: resolve the selected paired connection → authenticate → read capabilities → `session.list` → restore last remote ID or show new-chat welcome. During load, show a genuine loading state; no false veil over empty data.
4. **Open** remote chat: `session.open`, then authoritative snapshot; apply active server model/runtime before generation. On missing runtime after expiry/restart, reconfigure; never infer or upload local credentials.
5. **Send**: stage user input separately until `session.run` is acknowledged; server stores the user turn. Correlate events by connection/session/request, render `delta` transiently and commit only revisioned `conversation` snapshots. Keep input on failure if no durable receipt; inspect session status before retrying uncertain requests.
6. **Reconnect**: refresh bearer or SSH transport; reopen session; recover via `session.get` on `event_gap`, daemon restart, cursor reset or ambiguous delivery; never automatically rerun an uncertain request or double-execute a tool.
7. **Edit/delete**: send `session.save/delete` with CAS revision and stable idempotency key; on conflict fetch latest revision and prompt user. Never delete local chats from remote mode or remote chats from local mode.
8. **Disable**: switch view back to local immediately; release only transient subscriptions and network resources according to lifecycle, not durable server chats. A remote generation may continue server-side; on next enable, restore its snapshot/status. Do not call `session.cancel/delete` as a side effect of switching off.

## 6. Server-side blockers and compatibility gates

Record server-only changes in `~/serverside-generative/Problems.md` and fix **there**, independently. The Android feature should check negotiated capabilities and provide truthful unavailable states, not manufacture compatibility by deleting local fields, downgrading chat, faking completed operations, or reusing local message execution in remote mode.

- A typed server-owned provider/model catalog and a clear execution-capability contract are needed for a proper remote model picker.
- Lightweight, complete, update-time-ordered remote chat list entries/pagination are needed for existing drawer behavior without fetching every conversation.
- File/media/reasoning/tool-capable session execution and large snapshot transfer require server support before the corresponding Android actions are enabled remotely.
- Confirm cross-client owner visibility before promising one shared server chat history across **different** paired identities.

## 7. Delivery stages and verification

- [ ] **A — Server contract alignment:** review/resolve `Problems.md` P1/P2 interface blockers, establish JSON fixtures and capability tests; document feature-level unsupported behavior. No Android-side shape hacks.
- [ ] **B — Connection UI and storage:** AppMenu entry under Remote SSH, list/editor, pairing, encrypted connection records, lifecycle and basic integration tests; ensure existing Remote SSH unaffected.
- [ ] **C — Transport client:** implement pinned HTTPS URL/QR pairing, token auth/renewal, common RPC, SSE/long poll, cancellation, reconnect; SSH stdio/TCP support as a separate interoperable adapter, with strict identity-scoped fallback.
- [ ] **D — Remote workspace:** add SideDrawer row/switch, workspace-aware list and retained ChatScreen source, per-server last session/drafts/scroll, real loading/error/offline behavior, remote create/open/rename/delete; no local history migration.
- [ ] **E — Execution:** remote model picker from server-owned catalog, runtime selection, text streaming, revision reconciliation, idempotent send and cancel, interrupted-generation recovery. Only enable media/tools/reasoning/attachments once server supports them.
- [ ] **F — Complete regression:** unit tests for v5 codec, revocation/auth, TLS pin failure, bad pairing URL, WS/HTTP fallback, event gaps and cursor reuse, revision conflicts and duplicates; Go contract tests; Compose tests for both drawer rows/switch and backend separation; real Android/ServerSide tests on lost Wi-Fi, app restart, server restart, multi-server switching and backup/restore.

**Acceptance criteria:** Remote SSH remains first in its AppMenu group; Server Connection second. SideDrawer Remote Services has SSH selection followed by Remote Server selection with trailing switch. Remote mode shows only the selected server's chats and sends only to that server; off restores local UI/data untouched. Secrets remain protected, version/conflict/error states are handled, server-owned history survives reconnect/restart, and no unsupported server feature is falsely presented as working. Pass lint and tests **after implementation**; this document-only planning step requires no Android build.

## 8. Out of scope for this plan

Do not implement features, modify local chat schema or Go server APIs, build, commit, push, or publish a release as part of merely writing the plan. Do not assume that enabling remote mode means synchronizing local data, enabling SSH tools, or silently sharing local API credentials with a server.

## Follow-up: local read-through cache for remote histories (implemented 2026-11-17)

Remote chat **contents** now have an encrypted, bounded, non-backed-up cache in the app's private `cacheDir`, separate from Room local chat history. Pairing identity, server identity and connection ID partition the cache. Cached items are shown immediately and may be browsed read-only if offline; they are not treated as authoritative for edits or generation. On every successful connection, Android still checks the server's list and revision. When it has a complete matching snapshot it requests `session.open`/`session.get` with `if_revision` and receives only `{session_id, revision, event_seq, unchanged: true}`; otherwise it retrieves the server's authoritative history. The Go protocol change lives in `serverside-generative`, not in the Android model. Cache entries are removed on deleted chats, profile deletion, re-pair and revocation; bounded storage can be evicted by Android without losing actual conversations. Full transcripts never enter the local ConversationRepository or app backup.
