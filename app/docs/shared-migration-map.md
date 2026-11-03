# Shared layer migration map

This map records the completed `refactor/shared` batches. It is intentionally
ownership-focused: package-only moves retain Kotlin symbols and persisted data
formats unless noted.

## Baseline

- Branch: `refactor/shared`, based on `main`.
- Initial shared tree: 308 Kotlin files, including generated Fluent icon sources.
- Static checks before implementation: extension boundary passed; the shared
  tree contained direct `MainActivity`, `BuildConfig`, and Compose mapping
  dependencies identified in `TODO.md`.
- Architecture check: `scripts/check_architecture.py` now checks upward shared
  and integration imports, database/integration Compose rendering, feature
  cycles, and feature model boundaries.

## Completed moves and boundary changes

| Old path | Owner | New path / boundary | Reason | Affected callers |
| --- | --- | --- | --- | --- |
| `shared/ui/InstructionControls.kt` | Home shared UI | `features/home/shared/ui/InstructionControls.kt` | Contracts contain Home chat-option state and navigation callbacks. | `AppNavigation`, Home sheets, drawer, UI tests |
| `shared/ui/ToolControls.kt` | Home shared UI | `features/home/shared/ui/ToolControls.kt` | Contracts contain Home tool and machine actions. | `AppNavigation`, Home sheets, drawer, UI tests |
| `shared/ui/text/{OutlinedTextField,EditorTextField,HeaderNameField,ApiKeyField,AppBarSearchField}.kt` | Shared input primitives | `shared/ui/inputs/` | These are editable controls; `shared/ui/text/` remains for rendering and selection. | Feature editors, Explorer, terminal, tests |
| `shared/code/CodeSyntaxHighlighting.kt` Compose mapping | Shared code presentation | `shared/ui/code/CodeHighlighting.kt` | Tokenization and language detection are reusable non-UI logic; colors and `AnnotatedString` are UI mapping. | Code block surfaces and rendering tests |
| `shared/auth/OAuthCallbackService.kt` → `MainActivity` | App host wiring | `OAuthReturnIntentProvider` contract + `GenerativeApplication` implementation | OAuth service no longer imports the concrete root Activity; foreground handling and notification reopening remain unchanged. | OAuth service, application host |
| `shared/model/McpInfo.kt` → `BuildConfig` | App host configuration | `McpInfo.resolvedUrl(endpoint)` + endpoint injection in `AppContainer`, `McpRepository`, and MCP integrations | Models remain serializable and build-independent; explicit URLs still win and preset fallback remains available. | MCP repository, initializer, service/OAuth, MCP UI |
| `shared/files/CrashReports.kt` → `BuildConfig` | App host configuration | `CrashReportStore(file, appVersion)` + version supplied by `GenerativeApplication` | Crash file logic is reusable and no longer imports generated build configuration. | Application and crash tests |
| `shared/ui/media/ChatAttachmentSelection.kt` | Home chat state | `features/home/chat/ui/ChatAttachmentSelection.kt`; shared media receives selection callbacks/values | Chat-specific retained selection no longer leaks into generic media presentation. | Chat screen, retained conversations, navigation, media cards |
| `shared/ui/ProfileMcpPermissions.kt` | None confirmed | Deleted after repository-wide caller search | Orphaned UI had no production, test, manifest or reflection entry point; shared `McpAccess` model and persistence remain. | None |
| `shared/ui/terminal/{RemoteTerminalScreen,RemoteTerminalAppBar,RemoteTerminalPanels,RemoteTerminalDialogs}.kt` | Machines feature | `features/machines/shared/ui/terminal/` | The complete terminal presentation is used only by Machines navigation and terminal tests; backend/session infrastructure remains reusable under `shared/terminal/`. | `AppNavigation`, terminal tests, device test |
| `shared/terminal/RemoteTerminalBridge.kt` Compose window lookup | Shared terminal adapter | Host supplies `Window?` when constructing the bridge; the bridge no longer imports `DialogWindowProvider` or resolves Compose windows itself. | Keeps IME, session, input and Termux rendering behavior in the bridge while moving host-window knowledge to Machines UI. | `RemoteTerminalScreen` |

## Audited and intentionally retained

| Area | Evidence | Decision |
| --- | --- | --- |
| `shared/ui/model/` | Model picker and reasoning controls are consumed by Home, Chat Options, Tools and provider/editor flows. | Retain as shared model-picker UI; moving it would cross feature boundaries without reducing ownership ambiguity. |
| `shared/ui/logging/` | `DebugLogOverlay` is mounted by the application shell in `MainActivity`, and its panel/dialog/label files form one coherent debug capability. | Retain as a global shared UI capability rather than scattering it into Settings or app wiring. |
| `shared/preview/CodePreviewActivity.kt` and `CodePreviewDocument.kt` | The activity is registered in `AndroidManifest.xml`; shared code headers launch it, while document generation is covered by preview behavior. | Retain the preview capability under `shared/preview`; the activity is an Android entry point, not a generic helper. |
| `shared/ui/machines/MachineDialogTitle.kt` and `MachineToolsDialog.kt` | Machine dialogs are used by both the Machines list and Home drawer. | Retain as shared Machines UI; consumers are multiple and the package is already machine-scoped. |

## Deliberately retained ownership

- `shared/subfeatures/explorer/` remains the canonical reusable Explorer.
- Fluent icon source files remain under `shared/ui/icons/fluentui/`; icon
  storage and favicon services remain under `shared/icons/`.
- Dialogs, sheets, logging, media, terminal, theme and storage stay shared
  because their callers span multiple features or the application shell.
- Model picker, terminal screen and preview activity remain follow-up audits; no
  compatibility wrapper was added to conceal an incomplete move.

## Verification commands

```text
python3 scripts/check_architecture.py
python3 scripts/check_extensions.py
git diff --check
```

The affected unit-test batch was run offline with two Gradle workers:

```text
rtk run ./gradlew :app:testDebugUnitTest --offline --no-daemon --max-workers=2 \
  --tests org.starfall.generative.CodeRenderingTest \
  --tests org.starfall.generative.ContentApiMcpTest \
  --tests org.starfall.generative.CrashReportsTest \
  --tests org.starfall.generative.DefaultDataInitializerContentApiTest
```
