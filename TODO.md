# TODO — Shared Layer Reorganization

Scope: reorganize `app/src/main/kotlin/org/starfall/generative/shared/` by **responsibility, ownership, and real reuse**, without changing behavior. The branch starts from `main`. Do not implement the parallel screen/component API refactors here.

## 1. Audit shared ownership and define the target structure

- [ ] Inventory every shared declaration and caller; the current baseline and completed ownership batches are recorded in `app/docs/shared-migration-map.md`.
- [ ] Classify the remaining shared files; the migration map records owner, destination, reason and callers for the completed batches.
- [ ] Enforce ownership rules:
  - `shared/model/`: cross-feature data types/serialization, never UI, feature workflows, or `BuildConfig`.
  - `shared/storage/`: app-wide state primitives and DataStore preferences; Room-backed repositories stay in `database/`.
  - `shared/auth/`, `files/`, `icons/`, `logging/`, `media/`, `networking/`, `terminal/`, and `code/`: cohesive reusable non-screen capabilities; move external API clients to `integrations/` where applicable.
  - `shared/ui/`: composable presentation and appearance primitives with no feature-owned flow, persistence or app navigation.
  - `shared/subfeatures/`: larger, cohesive **actually reusable** experiences with their own UI/logic; retain `shared/subfeatures/explorer/` as the canonical Explorer.
  - `features/<owner>/`: UI and state confined to that feature; use the feature's internal `shared/` only where its subfeatures reuse the code.
  - `navigation/` / `appwiring/`: application routing, root Activity decisions and host configuration.
- [ ] Establish concise `shared/ui/` categories. Evaluate `inputs/` (editable fields, switch/slider/completion), `text/` (display/Markdown/selection), `layout/` (layout only), `appearance/` or `theme/` (single visual-policy source), `screens/` (reusable shell/transition), `lists/`, `dialogs/`, `sheets/`, `menus/`, `icons/`, `code/`, and `media/`. Avoid empty or arbitrarily single-file packages.
- [ ] Preserve explicitly shared and stable infrastructure (e.g. icon registry, `shared/ui/dialogs/`, `shared/ui/sheets/`, `shared/subfeatures/explorer/`). Do not move files just to make the directory tree symmetrical.
- [ ] Record the baseline before changes: imports by layer, feature reuse per component, stray Compose dependencies, and any suspected unused code; distinguish confirmed dependency breaches from potential organizational improvements.

## 2. Reorganize shared in small, behavior-preserving batches

- [x] **Fix confirmed upward dependencies.**
  - `shared/auth/OAuthCallbackService.kt` now receives the app-return Intent from the host through `OAuthReturnIntentProvider`, without changing OAuth foreground service handling.
  - `shared/model/McpInfo.kt` now accepts a host-provided Content API endpoint; explicit URL priority, preset fallback and credential handling are preserved.
  - Note the related `features/home/shared/logic/ChatBackgroundService.kt` → `MainActivity` import for coordination; do not expand this branch into unrelated Home refactoring.
- [ ] **Separate rendering from lower-level logic where genuinely mixed.**
  - `shared/code/CodeSyntaxHighlighting.kt` keeps language detection/token ranges independent; Compose `Color` / `SpanStyle` / `AnnotatedString` mapping now lives in `shared/ui/code/CodeHighlighting.kt`.
  - [x] `RemoteTerminalBridge.kt` no longer imports `DialogWindowProvider` or resolves Compose windows; Machines UI supplies the host `Window?`, while Termux bridge behavior, IME, sessions and input remain in shared terminal infrastructure.
  - [x] Reviewed `shared/preview/CodePreviewActivity.kt` as an Android presentation entry point; it remains under `shared/preview` because the manifest and shared code header reference the complete preview capability.
- [ ] **Move feature-specific state/components toward their real owner.**
  - `InstructionControls.kt` and `ToolControls.kt` now live in `features/home/shared/ui/`; navigation still supplies the contracts from the outside.
  - [x] Move `ChatAttachmentSelection` to `features/home/chat/ui/` and pass generic selection callbacks/parameters to shared media views; `MediaFileCard` no longer imports chat state.
  - [x] Audited `MachineDialogTitle.kt` and `MachineToolsDialog.kt`; both are used by Machines and the Home drawer, so their existing machine-scoped shared package is retained.
  - [x] Removed `shared/ui/ProfileMcpPermissions.kt` after a repository-wide search confirmed no production, test, manifest or reflection caller; `McpAccess` storage/model types remain unchanged.
- [ ] **Move complete presentation capabilities out of generic primitive buckets when justified.**
  - [x] Moved `RemoteTerminalScreen.kt`, `RemoteTerminalAppBar.kt`, `RemoteTerminalPanels.kt`, and `RemoteTerminalDialogs.kt` to `features/machines/shared/ui/terminal/`; navigation callbacks and screen lifecycle remain unchanged.
  - [x] Audited `shared/ui/model/` as one Model Picker capability used by multiple features; retain it in shared UI to preserve one selection/search/scroll/animation implementation.
  - Keep `shared/subfeatures/explorer/` centralized and independent of feature/database backends. Do not move its UI back into `extensions/`.
  - [x] Audited `shared/ui/logging/` as a coherent global debug UI mounted by the app shell; retain the package intact.
- [ ] **Normalize the generic `shared/ui/` structure.**
  - Editable controls (`OutlinedTextField`, `EditorTextField`, `HeaderNameField`, `ApiKeyField`, `AppBarSearchField`) now live under `shared/ui/inputs/`; `text/` retains rendering, Markdown, selection, wrapping and streaming effects.
  - Reclassify policy files mixed into `shared/ui/layout/` (`CardAppearance`, `ContainerTransparency`, `InputAreaAppearance`, `AmoledBorder`) without duplicating `shared/theme/` or existing Appearance/UserProfile helpers.
  - Reclassify `ScreenCover`, `RetainedScreenContent`, `ScreenSlideMotion`, `SystemBars` and `EdgeToEdgeContent` according to shared screen/window responsibilities, coordinating with `refactor/feature-screens`.
  - Review top-level `shared/ui/` files (`ErrorContainer`, `FeaturesLogo`, `LoadingIndicator`, `RuntimeAppLocale`, `ToolSwitch`) for intuitive ownership. Keep widely reused primitives discoverable; do not force renames without value.
  - Preserve Fluent `ImageVector` sources and registration in `shared/ui/icons/fluentui/`; keep icon asset/storage services separate under `shared/icons/`.
- [ ] **Respect the other planning branches.** `refactor/components` owns `ContentSheet`, `ContextMenuSheet`, `ItemListSheet`, `ExpandableSearchField`, `KeyValueField`, `ComponentGroup` and their behavior-driven migrations. `refactor/feature-screens` owns `ScreenTemplate`, the four `Content*` types, screen transitions and screen migrations. This branch owns **shared package boundaries and ownership**; coordinate naming, avoid duplicate APIs and do not implement the other branches' features.
- [ ] Apply one cohesive migration per batch. Update package declarations, imports, references, tests, navigation, Android manifest registrations, reflection/serialization names and documentation. Do not leave duplicate wrappers just to conceal missed callers.
- [ ] Preserve behavior: AMOLED/dynamic colors, appearance transparency, `ProfileBackground`, ChatAppBar independent gradient, dialog/sheet animation and insets, touch/keyboard/accessibility, chat scrolling/streaming, OAuth return flow, stored data and backup compatibility.
- [ ] Do not change Room schemas, database names, credential keys, DataStore keys or serialization formats for package-only relocations. Keep `extensions/` isolated; generic Compose drag mechanics there are **not** automatically app-owned UI and are outside this branch's main scope.

## 3. Verify architecture and close the refactor

- [x] Add `scripts/check_architecture.py` for cross-feature imports; upward `shared/` and `integrations/` dependencies; feature model boundaries; and Compose rendering inside `integrations/` and `database/`.
- [x] Keep and run the existing `scripts/check_extensions.py` check. The new architecture check only rejects Compose rendering imports in `integrations/` and `database/`.
- [ ] Search for orphaned/duplicated components and imports after each migration; verify apparent dead code with all entry points and tests before removal.
- [ ] Perform fast static checks (`git diff --check`, architecture scripts) before expensive tests. Run Gradle/unit/Android tests only if approved, in cohesive batches rather than repeated builds.
- [ ] When verification is permitted, regression-test OAuth notification reopening, Terminal screen and IME, Model Picker selection/search/scroll, Explorer adapters, Chat attachment selection, MCP permissions, Debug Log, and all relevant sheet/dialog/light/dark/AMOLED states.
- [ ] Produce before/after dependency and directory inventories, with evidence of no feature import cycles or unintended shared-to-feature dependencies.
- [ ] Update `AGENTS.md` and `app/docs/architecture.md` to the **implemented** layout only after the changes are verified. Remove obsolete paths/docs and retain migration notes for future contributors.
- [ ] Review the final organization for unnecessary micro-packages, duplicate abstractions, behavior changes, and overlap with the two parallel refactor branches before marking the TODO complete.
