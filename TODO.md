# TODO — Shared Layer Reorganization

Scope: reorganize `app/src/main/kotlin/org/starfall/generative/shared/` by **responsibility, ownership, and real reuse**, without changing behavior. The branch starts from `main`. Do not implement the parallel screen/component API refactors here.

## 1. Audit shared ownership and define the target structure

- [ ] Inventory every file under `shared/`, its declarations, all callers (including tests, navigation, manifest, and reflection references), and whether it serves one feature, multiple features, or the application shell.
- [ ] Classify files as reusable **model**, **storage/state**, **pure logic/helper**, **platform/integration adapter**, **generic UI primitive**, **reusable subfeature**, or **feature-owned UI/logic**. Make a migration map (`old path → owner → new path → reason → affected callers`) before moving files.
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

- [ ] **Fix confirmed upward dependencies.**
  - `shared/auth/OAuthCallbackService.kt` references `MainActivity` when constructing its notification PendingIntent. Delegate the app-return Intent decision to an outer host/wiring boundary without breaking OAuth foreground service handling.
  - `shared/model/McpInfo.kt` references `BuildConfig.CONTENT_API_MCP_URL` inside `resolvedUrl()`. Move build-specific fallback resolution to configuration/host code while preserving explicit URL priority, preset fallback and credential handling.
  - Note the related `features/home/shared/logic/ChatBackgroundService.kt` → `MainActivity` import for coordination; do not expand this branch into unrelated Home refactoring.
- [ ] **Separate rendering from lower-level logic where genuinely mixed.**
  - `shared/code/CodeSyntaxHighlighting.kt`: keep language detection/token ranges independent and relocate Compose `Color` / `SpanStyle` / `AnnotatedString` mapping into `shared/ui/code/`.
  - `shared/terminal/RemoteTerminalBridge.kt`: isolate Compose window handling (`DialogWindowProvider`) and presentation state where feasible, retaining Termux bridge behavior, IME, sessions and input.
  - Review `shared/preview/CodePreviewActivity.kt` as an Android presentation entry point instead of an ordinary shared utility; keep preview models/renderers at their correct ownership boundary.
- [ ] **Move feature-specific state/components toward their real owner.**
  - Audit `shared/ui/InstructionControls.kt` and `shared/ui/ToolControls.kt`: both currently support Home UI and are provided through navigation. Move Home-specific contracts/state closer to `features/home/shared/`, keeping the app wiring external.
  - Audit `shared/ui/media/ChatAttachmentSelection.kt` and its indirect users such as `MediaFileCard`. Keep chat-specific selection state with `features/home/chat/`; pass generic callbacks/parameters to shared media views rather than making them import a feature.
  - Audit `shared/ui/machines/MachineDialogTitle.kt` and `MachineToolsDialog.kt`: return machine-specific UI to its owner only where no cross-feature consumer exists; preserve multi-feature reuse when genuine.
  - Audit `shared/ui/ProfileMcpPermissions.kt` (no direct production call found in the initial scan). Confirm indirect/test usage before moving or deleting; do not remove based on grep alone.
- [ ] **Move complete presentation capabilities out of generic primitive buckets when justified.**
  - Assess `shared/ui/terminal/RemoteTerminalScreen.kt`, `RemoteTerminalAppBar.kt`, `RemoteTerminalPanels.kt`, and `RemoteTerminalDialogs.kt`. Prefer the owning Machines feature if single-purpose, or a cohesive reusable terminal subfeature if truly shared; preserve navigation callbacks and screen lifecycle.
  - Treat `shared/ui/model/` as one Model Picker capability (used by multiple features). Consider `shared/subfeatures/model-picker/` for its full picker workflow, but keep small reusable model-related UI primitives reusable; preserve selection, search, scroll, animations and state.
  - Keep `shared/subfeatures/explorer/` centralized and independent of feature/database backends. Do not move its UI back into `extensions/`.
  - Review `shared/ui/logging/` as a coherent global debug UI, rather than scattering it among unrelated Settings and app-shell files.
- [ ] **Normalize the generic `shared/ui/` structure.**
  - Move truly editable controls from `shared/ui/text/` (`OutlinedTextField`, `EditorTextField`, `HeaderNameField`, `ApiKeyField`, `AppBarSearchField`) toward `inputs/` where suitable. Reserve `text/` for rendering, Markdown, selection, wrapping and streaming effects.
  - Reclassify policy files mixed into `shared/ui/layout/` (`CardAppearance`, `ContainerTransparency`, `InputAreaAppearance`, `AmoledBorder`) without duplicating `shared/theme/` or existing Appearance/UserProfile helpers.
  - Reclassify `ScreenCover`, `RetainedScreenContent`, `ScreenSlideMotion`, `SystemBars` and `EdgeToEdgeContent` according to shared screen/window responsibilities, coordinating with `refactor/feature-screens`.
  - Review top-level `shared/ui/` files (`ErrorContainer`, `FeaturesLogo`, `LoadingIndicator`, `RuntimeAppLocale`, `ToolSwitch`) for intuitive ownership. Keep widely reused primitives discoverable; do not force renames without value.
  - Preserve Fluent `ImageVector` sources and registration in `shared/ui/icons/fluentui/`; keep icon asset/storage services separate under `shared/icons/`.
- [ ] **Respect the other planning branches.** `refactor/components` owns `ContentSheet`, `ContextMenuSheet`, `ItemListSheet`, `ExpandableSearchField`, `KeyValueField`, `ComponentGroup` and their behavior-driven migrations. `refactor/feature-screens` owns `ScreenTemplate`, the four `Content*` types, screen transitions and screen migrations. This branch owns **shared package boundaries and ownership**; coordinate naming, avoid duplicate APIs and do not implement the other branches' features.
- [ ] Apply one cohesive migration per batch. Update package declarations, imports, references, tests, navigation, Android manifest registrations, reflection/serialization names and documentation. Do not leave duplicate wrappers just to conceal missed callers.
- [ ] Preserve behavior: AMOLED/dynamic colors, appearance transparency, `ProfileBackground`, ChatAppBar independent gradient, dialog/sheet animation and insets, touch/keyboard/accessibility, chat scrolling/streaming, OAuth return flow, stored data and backup compatibility.
- [ ] Do not change Room schemas, database names, credential keys, DataStore keys or serialization formats for package-only relocations. Keep `extensions/` isolated; generic Compose drag mechanics there are **not** automatically app-owned UI and are outside this branch's main scope.

## 3. Verify architecture and close the refactor

- [ ] Extend or add lightweight static architecture checks for cross-feature imports; unexpected subfeature imports; `shared/` and `integrations/` depending on `database/`, `features/`, `navigation/`, `appwiring/` or a concrete root Activity; feature model → UI/logic; Compose rendering inside `integrations/` and `database/`; and extension boundary violations.
- [ ] Keep the existing `scripts/check_extensions.py` check. Distinguish acceptable Compose runtime/state types and generic Compose interaction infrastructure from actual Material3/Compose rendering in the wrong layer.
- [ ] Search for orphaned/duplicated components and imports after each migration; verify apparent dead code with all entry points and tests before removal.
- [ ] Perform fast static checks (`git diff --check`, architecture scripts) before expensive tests. Run Gradle/unit/Android tests only if approved, in cohesive batches rather than repeated builds.
- [ ] When verification is permitted, regression-test OAuth notification reopening, Terminal screen and IME, Model Picker selection/search/scroll, Explorer adapters, Chat attachment selection, MCP permissions, Debug Log, and all relevant sheet/dialog/light/dark/AMOLED states.
- [ ] Produce before/after dependency and directory inventories, with evidence of no feature import cycles or unintended shared-to-feature dependencies.
- [ ] Update `AGENTS.md` and `app/docs/architecture.md` to the **implemented** layout only after the changes are verified. Remove obsolete paths/docs and retain migration notes for future contributors.
- [ ] Review the final organization for unnecessary micro-packages, duplicate abstractions, behavior changes, and overlap with the two parallel refactor branches before marking the TODO complete.
