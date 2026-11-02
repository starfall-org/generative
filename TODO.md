# TODO — Feature Screen Refactor

## 1. Create reusable screen templates and content types

- [ ] **ScreenTemplate**
  - Provide a reusable full-screen shell consisting of an AppBar and a required, explicitly typed content area.
  - **Require exactly one of the four supported content types:** `ContentPreview`, `ContentVisualEditor`, `ContentTextEditor`, or `ContentList`. Do not expose an unrestricted body slot that bypasses this requirement.
  - Enforce the relationship in the component API where practical (for example, a `ScreenTemplate`-scoped content DSL or typed content contract): the four content types are only valid inside `ScreenTemplate`, not as standalone screen wrappers or inside `MainScreenTemplate`.
  - Use an AppBar with a theme-aware `surface` background.
  - Place a Back button on the left side of the AppBar; route navigation through a callback supplied by the caller.
  - Expose slots for AppBar title and optional actions/custom content without constraining feature-specific UI.
  - Handle edge-to-edge layout, system bar insets, and content padding consistently with existing app appearance policies.
  - Do not apply ChatAppBar's custom gradient to ordinary screens.

- [ ] **ContentPreview**
  - Read-only screen content: present information, previews, and non-editing components without adding editor-specific UI.
  - Accept feature-defined display components while centralizing content layout, spacing, scrolling, and safe-area handling.
  - Only attach through the required content contract of `ScreenTemplate`.

- [ ] **ContentVisualEditor**
  - Screen content for editing through multiple controls, such as text inputs, switches, selectors, and other form components.
  - Standardize layout, spacing, keyboard/inset handling, and scrolling without owning feature-specific state, validation, or persistence.
  - Only attach through the required content contract of `ScreenTemplate`.

- [ ] **ContentTextEditor**
  - Screen content whose sole purpose is text entry/editing; prioritize the text editing area and its keyboard behavior.
  - Provide the structure for a full-content text editor without mixing in unrelated settings or form controls.
  - Only attach through the required content contract of `ScreenTemplate`.

- [ ] **ContentList**
  - Screen content for displaying collections of items.
  - Centralize list layout and appropriate scrolling, content padding, and empty/loading presentation hooks while leaving item rendering, selection, actions, and data ownership to callers.
  - Only attach through the required content contract of `ScreenTemplate`.

- [ ] **ScreenTransitionEffect**
  - Extract a reusable navigation transition for screens using `ScreenTemplate`.
  - Support forward navigation (right to left) and back navigation (left to right), including system Back.
  - Integrate through the navigation layer; avoid duplicating transition logic in each feature.
  - Preserve screen state and existing navigation/back-stack behavior, and avoid unnecessary recomposition or visual jumps.

- [ ] **MainScreenTemplate**
  - Add a minimal shared placeholder/marker representing the main/root screen category, intended for `ChatScreen`.
  - Keep it deliberately lightweight; do not duplicate `ScreenTemplate`'s AppBar, Back button, transition, or content-type responsibilities.
  - Maintain the distinction between `ChatScreen` and ordinary secondary screens; preserve ChatAppBar's existing appearance and behavior.

## 2. Integrate the shared components into the app

- [ ] Inventory screen entry points, existing AppBars, transitions, and body layouts; classify each secondary screen as preview, visual editor, text editor, or list.
- [ ] Implement the screen shell and its four content types in the appropriate shared UI layer without introducing feature dependencies.
- [ ] Ensure `ScreenTemplate` requires a supported content type and that none of the four types is used outside its scope; do not silently fall back to a free-form body.
- [ ] Migrate suitable secondary screens to `ScreenTemplate`, the appropriate `Content*` type, and `ScreenTransitionEffect` in manageable batches.
- [ ] Apply `MainScreenTemplate` to `ChatScreen` where appropriate, without changing its existing UI or streaming behavior.
- [ ] Remove duplicated screen/AppBar/transition/content-layout code only after confirming that callers retain their intended behavior.
- [ ] Preserve feature-specific actions, UI state, background appearance, navigation, keyboard handling, and safe-area behavior.

## 3. Verify and collect UI feedback

- [ ] Perform static checks for dependency direction, duplicate implementations, and navigation regressions.
- [ ] Add checks/tests proving that a `ScreenTemplate` cannot be created without one of the four content types, and that content types cannot be placed outside it.
- [ ] Add or update relevant unit and Android UI tests for all four content types, AppBar slots, Back navigation, forward/back transitions, scrolling, keyboard insets, and screen state preservation.
- [ ] Run the agreed verification steps when the environment and user approval permit; avoid unnecessary repeated Gradle builds.
- [ ] Check the resulting UI on an Android device, including light/dark/AMOLED themes, edge-to-edge insets, transitions, list scrolling, and text/form editing.
- [ ] Present the updated interface for user evaluation and collect feedback before considering the refactor complete.
