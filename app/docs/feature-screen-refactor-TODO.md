# TODO — Feature Screen Refactor

## 1. Create reusable screen templates and content types

- [x] **ScreenTemplate**
  - Provide a reusable full-screen shell consisting of an AppBar and a required, explicitly typed content area.
  - **Require exactly one of the four supported content types:** `ContentPreview`, `ContentVisualEditor`, `ContentTextEditor`, or `ContentList`. Do not expose an unrestricted body slot that bypasses this requirement.
  - Enforce the relationship in the component API where practical (for example, a `ScreenTemplate`-scoped content DSL or typed content contract): the four content types are only valid inside `ScreenTemplate`, not as standalone screen wrappers or inside `MainScreenTemplate`.
  - Use an AppBar with a theme-aware `surface` background.
  - Place a Back button on the left side of the AppBar; route navigation through a callback supplied by the caller.
  - Expose slots for AppBar title and optional actions/custom content without constraining feature-specific UI.
  - Handle edge-to-edge layout, system bar insets, and content padding consistently with existing app appearance policies.
  - Do not apply ChatAppBar's custom gradient to ordinary screens.

- [x] **ContentPreview**
  - Read-only screen content: present information, previews, and non-editing components without adding editor-specific UI.
  - Accept feature-defined display components while centralizing content layout, spacing, scrolling, and safe-area handling.
  - Only attach through the required content contract of `ScreenTemplate`.

- [x] **ContentVisualEditor**
  - Screen content for editing through multiple controls, such as text inputs, switches, selectors, and other form components.
  - Standardize layout, spacing, keyboard/inset handling, and scrolling without owning feature-specific state, validation, or persistence.
  - Only attach through the required content contract of `ScreenTemplate`.

- [x] **ContentTextEditor**
  - Screen content whose sole purpose is text entry/editing; prioritize the text editing area and its keyboard behavior.
  - Provide the structure for a full-content text editor without mixing in unrelated settings or form controls.
  - Only attach through the required content contract of `ScreenTemplate`.

- [x] **ContentList**
  - Screen content for displaying collections of items.
  - Centralize list layout and appropriate scrolling, content padding, and empty/loading presentation hooks while leaving item rendering, selection, actions, and data ownership to callers.
  - Only attach through the required content contract of `ScreenTemplate`.

- [x] **ScreenTransitionEffect**
  - Extract a reusable navigation transition for screens using `ScreenTemplate`.
  - Support forward navigation (right to left) and back navigation (left to right), including system Back.
  - Integrate through the navigation layer; avoid duplicating transition logic in each feature.
  - Preserve screen state and existing navigation/back-stack behavior, and avoid unnecessary recomposition or visual jumps.

- [x] **MainScreenTemplate**
  - Add a minimal shared placeholder/marker representing the main/root screen category, intended for `ChatScreen`.
  - Keep it deliberately lightweight; do not duplicate `ScreenTemplate`'s AppBar, Back button, transition, or content-type responsibilities.
  - Maintain the distinction between `ChatScreen` and ordinary secondary screens; preserve ChatAppBar's existing appearance and behavior.

## 2. Integrate the shared components into the app

- [x] Inventory screen entry points, existing AppBars, transitions, and body layouts; classify each secondary screen as preview, visual editor, text editor, or list. See `app/docs/screen-template-migration-map.md`.
- [x] Implement the screen shell and its four content types in the appropriate shared UI layer without introducing feature dependencies.
- [x] Ensure `ScreenTemplate` requires a supported content type and that none of the four types is used outside its scope; do not silently fall back to a free-form body.
- [x] Migrate suitable secondary screens to `ScreenTemplate`, the appropriate `Content*` type, and `ScreenTransitionEffect` in manageable batches.
- [x] Apply `MainScreenTemplate` to `ChatScreen` where appropriate, without changing its existing UI or streaming behavior.
- [ ] Remove duplicated screen/AppBar/transition/content-layout code only after confirming that callers retain their intended behavior.
- [ ] Preserve feature-specific actions, UI state, background appearance, navigation, keyboard handling, and safe-area behavior.

## 3. Verify and collect UI feedback

- [x] Run dependency-direction checks (`scripts/check_architecture.py`, `scripts/check_extensions.py`) and `git diff --check`.
- [ ] Complete the post-merge duplication and navigation audit across all migrated screens.
- [x] Add checks/tests proving that a `ScreenTemplate` cannot be created without one of the four content types, and that content types cannot be placed outside it.
- [x] Add or update relevant unit and Android UI tests for all four content types, AppBar slots, Back navigation, forward/back transitions, scrolling, keyboard insets, and screen state preservation. Added `ScreenTemplateTest`; retained-navigation tests continue to cover transition/state behavior.
- [x] Compile `assembleDebug` and `assembleDebugAndroidTest`; run 9 ScreenTemplate unit tests, 5 retained-navigation unit tests, and 4 focused Android UI tests on Star 4 (Android 11).
- [ ] Finish broader regression checks and review any failed tests before declaring merge readiness.
- [x] Confirm the ScreenTemplate UI tests pass on an actual Android 11 device (Back, preview scrolling, list empty-state recomposition, text input, saveable transition state).
- [ ] Review screenshots and interactive flows for light/dark/AMOLED, edge-to-edge insets, and specialized screens.
- [ ] Present the updated interface for user evaluation and collect feedback before considering the refactor complete.

## Verification notes (2026-11-04)

- The first device run was invalid because the device screen was asleep/locked; after waking/unlocking it, three tests passed and `ContentList` exposed a genuine recomposition bug.
- `ScreenTemplateScope` now identifies a content call site across recompositions; two separate content sites still violate the exactly-one-content rule.
- `ScreenTemplateTest`: 9/9 passed (including new recomposition and duplicate-content regressions). `RetainedNavHostTest`: 4/4 passed. `RetainedScreenContentTest`: 1/1 passed.
- `ScreenTemplateDeviceTest`: 4/4 passed on the Star 4 after reinstalling the fixed APK.
- Both architecture and extensions dependency scripts passed; Gradle debug app/test APK build passed.
- A follow-up Android device regression run passed 14 tests (Speech Services 3, Remote Navigation 2, Chat Options/Tools 7, ChatAppBar 1, BottomSheet insets 1); the ADB local-forward connection dropped while the 15th of 17 tests was running. No result was obtained for tests 15–17.
- Extended JVM regressions: 22/25 passed. All three failures (`ModelSheetTransitionTest.longPickerExpandsExistingSelectedModelSheetWithoutReopeningIt`, `QuickActionsSheetTransitionTest.temporaryChatCaptureStaysInTheSessionCache`, and `QuickActionsSheetTransitionTest.cameraAttachesCapturedPhotoAlongsideExistingAttachments`) were reproduced unchanged on `main` using the same test command, so they are known baseline failures rather than regressions introduced by this branch.
- Focused green tests do not replace a full interactive review of every screen or the entire 422-method Android test suite.

## Final review follow-up (2026-11-04)

- Addressed all six inline CodeRabbit comments: removed global `MissingTranslation` lint suppression, updated asynchronous clipboard errors for locale changes, removed duplicated Tools tabs padding, restored vertically centered yet scrollable loading/error content, required explicit saveable transition keys, and separated the two invalid `setContent` assertions. Also guarded `ScreenTransitionEffect` system Back with the retained-screen active flag (additional review finding).
- Rebuilt debug and Android-test APKs successfully after the review changes; 11 `ScreenTemplateTest`, 5 retained-navigation tests and 10 isolated Chat tests passed.
- A broad JVM run was interrupted by the execution time limit after showing unrelated failures; isolated Chat tests passed, and three sheet/media failures were confirmed on `main`. Do not describe the full JVM suite as green.
- The localization checker reports existing missing translations (including Vietnamese and Tier 3 locales); this PR restores the default lint behavior rather than hiding them globally. Locale coverage is a separate baseline concern.
- The device stayed offline via Tailscale/ADB; the three interrupted Android tests and final physical visual review remain unavailable. Earlier 4/4 focused device tests and 14 additional on-device regressions passed before it disconnected.
