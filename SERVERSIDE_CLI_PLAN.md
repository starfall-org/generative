# Serverside CLI integration plan and TODO

Status: phase 1 source implementation added; Android lint/unit tests and remote integration verification remain pending.
Branch: `feature/serverside`.
Upstream: https://github.com/starfall-org/serverside-generative

## Scope

Phase 1 adds a `Serverside CLI` switch directly below the AI tools switch in the selected machine's SideDrawer context menu. The switch selects Serverside mode for that machine. When a chat message is submitted with this mode enabled, check whether the CLI exists on that selected remote machine. If it is definitely missing, show a warning and automatically turn off Serverside mode for that machine.

SSH bootstrap now runs immediately when enabling the switch. Session control is deferred: do not create, attach, resume, stop, list, or send prompts to CLI sessions in this phase. The eventual enabled-mode workflow will interact with a Serverside session; phase 1 establishes the preference, validation, and workflow boundary only. Do not represent successful CLI detection as an active session.

The latest request authorizes implementation and requires handshake errors to automatically open a shared `ErrorDialog` containing `ErrorContainer`.

Implemented behavior:

- Enabling runs `serverside handshake --stdio` over the existing SSH transport without requiring an interactive terminal. Validate protocol v4, endpoint, fingerprint, ticket and expiration before saving the opt-in. The invitation is discarded after validation; TLS pairing/authentication is deferred along with session control. A checked switch records successful bootstrap, not current session readiness.
- A per-machine pending operation prevents duplicate UI submissions. Turning off cancels the operation. Revisions and selected-machine checks ignore stale bootstrap results. Errors leave the switch off and open the shared dialog; missing CLI has a localized explanation.
- Before provider execution, including ordinary queued dispatch and retries that use the generation entry point, run `serverside version` when the selected machine has opted in. A dedicated shell marker plus exit code distinguishes missing PATH executable from other failures. Missing CLI disables that machine and opens the dialog; the accepted message remains in conversation history with a generation error, so it can be retried. This replaces the earlier proposed draft-preserving rejection.
- Opt-in uses the existing ToolSettings DataStore serialization. No pairing ticket/client secret is persisted, no daemon is launched, and no session commands or TLS data connection are implemented.
- New tests cover invitation validation/redaction, missing-vs-daemon failure, toggle cancellation, send-time auto-disable, and shared dialog rendering/dismissal. Existing drawer coverage now asserts the new switch is present and off by default.

## Findings and verified upstream contract

- `features/home/drawer/ui/DrawerQuickShortcuts.kt` renders the selected machine's context menu using `ContextDropdownMenu`, `ContextMenuAction`, and a trailing `Switch`. Insert the new action immediately after the existing AI tools action.
- `features/home/shared/ui/ToolControls.kt` supplies drawer state and callbacks. Wire the new callback through the existing provider to `ChatViewModel` and `ChatToolSettingsActions`.
- Shared `ToolSettings` is persisted by `database/preferences/ToolSettingsStore.kt` using DataStore and `ImmediateState`. Extend this settings path rather than introducing another store or altering Room schemas.
- `features/home/chat/logic/service/ChatMessageActions.kt` owns message submission, including queued messages. Inspect queue draining, retry/regeneration, generation startup, and input consumption before placing an asynchronous check.
- `integrations/remote/RemoteMachineService.kt` exposes remote command execution through `RemoteMachineConnectionManager`. Reuse the supported transport without importing the machines feature into home.
- Existing regression coverage includes `DrawerRemoteMachineContextMenuTest.kt`, `ChatGenerationTest.kt`, and `RemoteMachineTest.kt` under `app/src/test/java/org/starfall/generative/`.
- Research now uses the local checkout `serverside-generative/` at commit `f33433043d74a32abd2257e2b372237c5f5e6028` (2026-11-06). Its working tree was clean during inspection. Earlier remote-access failures no longer block source research. This checkout is reference material, not an Android dependency or code to vendor into the app.
- The deployed binary is **`serverside`**, built from `cmd/serverside-generative`. `version` prints `serverside <version>` (default build version: `dev`) and returns success without opening a socket, creating a node, or requiring a running daemon. CLI errors reach `main`, which exits with code 1. There is no dedicated health/status subcommand in this entry point.
- `serve` starts the daemon. `handshake --stdio` requests a short-lived pairing ticket from an already running daemon through a local Unix control socket. `pair` issues a human invitation. Neither is a read-only installation probe. The latest request explicitly requires SSH handshake when enabling, so that bootstrap operation is separate from the send-time installation probe.
- Binary presence, binary executability, daemon availability, authenticated transport readiness, and session readiness are separate states. Missing daemon/socket, blocked port, incompatible executable, or failed authentication must not trigger the “CLI not installed” warning.
- The actual session transport is **protocol v4**, pinned TLS 1.3 over a separate reachable TCP endpoint, using a 4-byte big-endian JSON frame length and a 4 MiB frame limit. SSH/HTTP/WS only supplies pairing invitations; session traffic does not flow through CLI stdin/stdout. A successful SSH probe does not prove the advertised TLS endpoint is reachable.
- `auth` returns `paired`; clients must then perform `context_get`, `context_sync`, or `context_update` before session traffic. Shared configuration updates use revision/digest checks. Pairing credentials and pinning belong to a later integration phase, not the boolean preference.
- Provider execution (`handoff`, `start`, `sync`, `ack`) is distinct from structured coding-agent control (`agent_*`, enabled by `serve --enable-agent`) and terminal tools (`terminal_*`, enabled separately). Do not interpret `Serverside CLI` as a switch for Claude/Codex/OpenCode or automatically enable these capabilities.
- The README documents Linux installation and Linux/macOS PTY versus Windows shell fallback. SSH pairing still uses a Unix control socket. Phase 1 should initially use a POSIX remote-shell probe; Windows shell detection and Cloud Shell PATH/transport behavior require separate validation. Do not promise universal OS support from the presence of Windows agent/terminal files.
- The app's `RemoteMachineConnectionManager.runSshCommand` currently rejects execution unless a terminal is open. A background probe cannot simply call the public `execute` path and treat this rejection as CLI absence.

Source references within the local checkout:

- `README.md`: installation, pairing, persistence, protocol v4, and runtime limitations.
- `cmd/serverside-generative/main.go`: binary commands, side-effect-free `version`, defaults, and exit behavior.
- `internal/node/handshake.go`: Unix control socket and ticket issuance; requires a live daemon.
- `internal/node/node.go` and `internal/node/transport.go`: protocol version, invitation fields, TLS/framing, context gate, and dispatch.
- `testclient/client.py`: reference client for fingerprint verification, pairing, context sync, and reconnect.
- `docs/structured-agents.md`: separate opt-in structured-agent API and lifecycle.

These conclusions come from source inspection, not a running remote daemon or executed upstream tests.

All Kotlin paths above are relative to `app/src/main/kotlin/org/starfall/generative/` unless explicitly qualified.

## Proposed phase 1 behavior

1. No machine selected: retain the existing menu visibility; no global Serverside switch.
2. Machine selected: display `Serverside CLI` directly below AI tools, with an icon and accessible switch. Clicking the row and clicking the switch perform the same single state change. Opening the menu does not run a probe. Enabling the switch immediately runs SSH bootstrap and only publishes on after successful validation; failures automatically open ErrorDialog.
3. Store opt-in per stable machine ID, defaulting to off for existing installations. AI tools and Serverside are independent switches; changing one must not silently toggle the other. Switching machines reads that machine's preference. Unsetting the selection makes Serverside inactive without probing the previously selected machine.
4. On text-chat submission, capture the selected machine ID and its current opt-in. Off means the existing send flow proceeds unchanged. On means run a bounded, cancellable, noninteractive availability check on that remote machine before provider execution. The message is already accepted into conversation history.
5. Found: allow the existing chat flow to continue in phase 1. This is an explicit interim behavior; prompts are not routed to a CLI session until the later session integration. No session-connected status is displayed.
6. Definitely missing: publish the disabled state immediately through the settings owner, persist it through the existing serialized update path, and present one localized warning for the affected submission. Proposed English: “Serverside CLI was not found in the command path on %1$s. Serverside CLI has been turned off.” Vietnamese: “Không tìm thấy Serverside CLI trong PATH trên %1$s. Tính năng Serverside CLI đã được tắt.”
7. Send-time missing CLI stops provider execution and retains the accepted message/attachments in conversation history with an error. The next explicit retry/send uses the existing chat flow with the switch off. Do not silently run the failed request through a different execution path.
8. Connection failure, authentication failure, timeout, permission failure, unsupported transport, and cancellation are distinct from missing CLI. Report a suitable error, retain accepted input in conversation history, and retain the switch unless absence is positively established. Enable-time handshake failure leaves its switch off. Never disable mode merely because a probe failed.
9. A delayed result must not disable another machine, override a newer off/on toggle, consume a changed draft, or show an error in an unrelated conversation. Capture an operation identity and revalidate state before applying results. Prevent duplicate probes/warnings from repeated taps. Recheck queued sends at dispatch if they can reach generation without this guard.

Item 5 is interim behavior while session control remains deferred. Item 7 describes the implementation using the existing accepted-message/generation-error lifecycle.

## Architecture and implementation plan

### 1. Use the verified detection contract

On a supported POSIX remote shell, resolve the fixed executable name `serverside` in the SSH account's noninteractive PATH (for example with `command -v serverside`). Use an explicitly structured probe response to distinguish lookup absence from shell/transport failure; do not classify every nonzero exit as missing. An executable installed outside this PATH is “not found in the remote execution environment”, not proof that no binary exists anywhere on disk. Avoid arbitrary filesystem scans and assumptions about `~/.local/bin` or login-shell startup files.

If lookup succeeds, run `serverside version` with a bounded timeout and validate its exit status and expected output prefix. Accept `serverside dev`; this is a valid upstream default and is not a protocol-version guarantee. Failed execution, unexpected output, and permission errors yield an unavailable/incompatible result, not confirmed absence. Retain only bounded diagnostic output and do not log credentials. `version` is a compatibility hint, not authenticated server identity.

Do not use `serve`, `handshake`, `ticket`, `pair`, or an authenticated data connection for the send-time installation check. Enabling uses `handshake --stdio` separately as explicitly requested. Do not install software, modify shared configuration, or start a terminal/daemon as a side effect of the probe. Bootstrap issues an ephemeral invitation through the running daemon. Validate the exact probe under SSH and supported Cloud Shell transport before shipping. Warn about the executable not being found in PATH rather than claiming exhaustive machine-wide absence.

### 2. Add preference and state mutations

Extend `shared/model/Tools.kt` with a backward-compatible per-machine Serverside preference (proposed map keyed by machine ID). Keep it separate from AI-tool permission maps. Add focused mutations in `ChatToolSettingsActions`, expose them from `ChatViewModel`, and extend `ToolControls` and its existing provider. Inspect all settings serialization, backup/restore, machine deletion, and temporary-chat paths; ensure defaults preserve older payloads and cleanup removes orphaned IDs consistently.

### 3. Add the drawer action and resources

Reuse the existing shared context menu, themed switch, and icon conventions. Use a stable test tag such as `drawer_serverside_switch_<machineId>`. Keep `Serverside CLI` as one non-translatable product label; provide English and Vietnamese warning/help strings with matching placeholders. Do not add a new settings screen, CLI console, or custom appearance policy.

### 4. Implement the availability adapter

Place protocol/command execution in `integrations/serverside/` (or a cohesive existing remote integration package), with typed results such as available, missing, and probe failure. Inject the capability into the home workflow through application wiring. Keep transport code outside UI, models, and extensions. Reuse existing connection lifecycle and cancellation. Add a narrowly scoped background-probe operation in the remote integration using the existing SSH connection acquisition, exec-channel cleanup, timeout, and cancellation mechanisms, without the interactive-terminal precondition. Keep that precondition for existing terminal/tool execution callers. A missing terminal must never be classified as a missing CLI. Avoid a second SSH client or duplicated connection lifecycle; extract a small internal exec helper only if needed. Validate Cloud Shell through its existing connection path and report unsupported transport distinctly.

### 5. Guard chat submission

Keep orchestration in home logic, using a small coordinator if needed. Preserve the existing synchronous acceptance contract; probe failures are reported through the existing generation-error lifecycle after message acceptance. Publish warning events as typed state/events and resolve localized text in UI using shared warning components. Handle queued sends, switching machines, and late results. Do not add session APIs or placeholder responses that imply CLI execution.

### 6. Validate the complete change

Run lint and unit tests together once implementation is complete and prerequisites are available. Do not build, commit, or push without an explicit request. Do not download toolchains/dependencies to compensate for an offline environment.

## TODO (easy to hard)

- [x] Inspect architecture, current drawer action, preference owner, send entry point, and relevant tests.
- [x] Record phase 1 scope and source references in this file.
- [x] Inspect the local upstream checkout; record executable, version-command semantics, transport/protocol, platform caveats, and commit SHA.
- [ ] Validate POSIX PATH lookup and bounded `serverside version` probing on actual supported remote transports; distinguish missing, incompatible, and transport failures.
- [x] Use the existing accepted-message/generation-error lifecycle for send-time failures.
- [ ] Inspect every `ToolSettings` consumer, provider, serializer, backup path, machine cleanup path, and send/queue caller affected by the change.
- [x] Add backward-compatible per-machine preference and coordinator mutations; add tests for old settings defaults and state isolation (execution pending).
- [x] Wire `ToolControls` and add the drawer switch immediately below AI tools.
- [x] Add English/Vietnamese failure explanations and the fixed `Serverside CLI` label.
- [x] Add narrow background SSH bootstrap/probe paths without requiring an open terminal, preserving existing tool execution restrictions.
- [x] Implement bounded, cancellable send-time detection using PATH lookup and `serverside version`, independently of the enable-time SSH handshake.
- [x] Add provider-execution guard, ErrorDialog state, and missing-CLI auto-disable. Retain accepted messages in conversation history.
- [ ] Verify queue dispatch, cancellation, machine/conversation changes, and stale results on a provisioned Android environment.
- [ ] Test menu ordering, row/switch parity, defaults, persistence, machine selection/unset, and independence from AI tools.
- [ ] Test found/missing CLI, connection/auth/timeout/permission errors, cancellation, one-time warning, auto-disable persistence, retained conversation messages/attachments, and absence of session commands.
- [ ] Test queued dispatch and stale results after changing machines or toggling off/on.
- [x] Inspect prerequisites: JDK 25 and Android SDK/platform 36 exist; Gradle 9.5.0 distribution/cache is absent. Do not invoke Gradle or download prerequisites.
- [ ] Run `git diff --check`; run `./gradlew --offline :app:lintDebug :app:testDebugUnitTest` together if provisioned. Report passed/failed/not-run separately. If extension files change, also run `python3 scripts/check_extensions.py`.
- [ ] Manually verify the menu and send behavior against machines with and without CLI, including supported Cloud Shell transport; confirm no session is created or controlled.

## Deferred work

Session discovery/creation/attachment, prompt routing and streaming, stop/resume/reconnect, session ownership, session management UI, and automatic CLI installation are outside phase 1.

The later transport phase must cover invitation validation, certificate pinning, encrypted client-secret storage, protocol-v4 compatibility, and a reachable TCP endpoint. A boolean preference must never store tickets, fingerprints, or reconnect secrets. Perform the mandatory context handshake without blindly overwriting server configuration; shared context is durable while API keys and execution state are RAM-only on the server. ACK provider events only after successful local persistence; handle replay gaps and daemon restarts explicitly. Structured agent events have their own cursor/lifecycle and must not be confused with provider session ACKs. Do not implement these mechanisms as part of the availability probe.

## Acceptance criteria for phase 1

- The selected machine's context menu contains the new switch immediately below AI tools.
- Serverside opt-in is per machine, defaults off, survives supported persistence/restore paths, and does not modify AI-tool permissions.
- Submitting while enabled checks the selected remote machine; confirmed CLI absence warns and automatically disables only that machine's Serverside setting.
- Transport failures do not masquerade as missing CLI; bootstrap ignores stale results and failed sends retain their accepted input in conversation history.
- Enabling performs SSH bootstrap immediately, and any bootstrap error automatically opens shared ErrorDialog with ErrorContainer.
- No CLI session is created, controlled, or sent a prompt in this phase, and successful detection is not presented as a connected session.
- Required checks and remaining verification gaps are reported accurately.


## Verification record (2026-11-06)

- PASS: `git diff --check`.
- PASS: six offline executions of the actual fixed SSH shell commands against a temporary mock executable, covering missing PATH executable, successful version/bootstrap command dispatch, and daemon failure. This verifies shell classification only, not SSH or a live daemon.
- PASS: Kotlin PSI syntax parsing for 15 changed/new Kotlin source/test files using the installed compiler. This is not Android compilation or type checking. The temporary parser harness initially failed to initialize the compiler API and was corrected before the successful run.
- PASS: focused English/Vietnamese resource XML validation, duplicate-key check, matching `%1$s` placeholders, and invariant product label.
- FAIL (pre-existing): `python3 scripts/check_architecture.py` reports 14 dependency violations. Each reported import was checked against `HEAD` and already exists there; no new violation is reported.
- FAIL (pre-existing coverage gaps): `python3 scripts/localization_check.py --locale vi` reports 83 missing Vietnamese strings. Both new translatable strings are present with matching placeholders. Unrelated translations were preserved.
- NOT RUN: Android lint/unit tests because the Gradle wrapper distribution is not cached. Once provisioned, run `./gradlew --offline :app:lintDebug :app:testDebugUnitTest` together. New Android/JUnit tests have not been executed or type checked.
- NOT RUN: live SSH/Cloud Shell/daemon integration; no configured remote test machine was used. No app build, commit, push, dependency download, or upstream checkout modification was performed.


## Verification update (2026-11-07)

- PASS: `./gradlew :app:assembleDebug` completed successfully (39 tasks executed). Kotlin and Java compilation passed; the debug APK was generated at `app/build/outputs/apk/debug/app-debug.apk`.
- PASS: Kotlin PSI syntax parsing across 1,101 Kotlin files and an offline comparison of all 23 navigation destinations after splitting route registration from `AppNavigation.kt`. These checks supplement the successful Android compilation.
- PASS: `git diff --check` and the extension dependency boundary check. The architecture checker still reports the same 14 pre-existing violations, with no new violations.
- NOT RUN: Android lint/unit tests and live SSH/Cloud Shell/daemon integration. Build success does not establish those checks.
- The Gradle wrapper distribution, required SDK packages and build dependencies are now available after the explicitly requested debug build.
