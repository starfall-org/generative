# TODO — Shared Component Refactor

## Current implementation status

The first implementation slice is complete on `refactor/components`. The six shared primitives
now reuse the existing `BottomSheet`, appearance, `ItemCard`, and input foundations. Representative
callers have been migrated and focused Robolectric coverage was added for search text retention,
key autocomplete, AppMenu grouping, model catalog search, and model picker behavior.

The remaining work is intentionally tracked below: finish the broader caller migration and duplicate
removal, coordinate with `refactor/feature-screens`, run supported device checks, and collect UI
feedback before treating the refactor as complete.

## 1. Create reusable UI components

- [x] **ContentSheet**
  - Provide a shared BottomSheet template for content whose structure is predefined by its use case, rather than a generic list or a context menu.
  - Support a configurable, optional Header: callers can show or hide it without having to reproduce sheet layout code.
  - When shown, the Header can expose a title and appropriate actions; when hidden, do not reserve empty header space.
  - Reuse the application's existing BottomSheet foundation, appearance policies, content-aware initial height, maximum height below the status bar, and inset/IME handling.
  - Expose only the customization points required by the defined content, rather than implementing another unrestricted sheet wrapper.

- [x] **ContextMenuSheet**
  - Replace action-oriented `DropdownMenu` usages throughout the app with a shared context-menu BottomSheet.
  - **No Header.** Present context actions directly, with consistent icons, labels, spacing, enabled/disabled states, and destructive styling where appropriate.
  - Support contextual actions, dismissal, and placement/selection context without duplicating feature-owned action behavior.
  - Preserve the distinction between action menus and input-selection dropdowns: `ExposedDropdownMenu` used for completions or field selection is not a context menu and must remain supported.

- [x] **ItemListSheet**
  - Provide a reusable BottomSheet specialized for lists. **No Header.**
  - **Accept only a list of `ItemCard` entries**, with an API that enforces this contract rather than allowing arbitrary non-card list content.
  - Treat `ModelPickerSheet` as the reference implementation for the most demanding behaviors and configuration needs.
  - Offer optional, composable list controls where required: search (using `ExpandableSearchField`), filters, groups/sections, selection modes, selected-item indication, loading/error/empty states, and item actions.
  - Preserve scroll position, item identity, dynamic list updates, keyboard dismissal, and BottomSheet sizing/expansion behavior.
  - Keep item data and feature-specific actions outside the shared component; do not force every `ItemListSheet` caller to enable all advanced features.
  - Do not add a sheet Header when optional search/filter controls are present; place them within the sheet content layout.

- [x] **ExpandableSearchField**
  - Extend the shared search component so callers no longer have to reimplement search expansion, collapse, and focus management.
  - Own the collapsed search button, expanded input appearance, clear/close behavior, focus requests, keyboard/IME actions, and coherent animations/width transitions.
  - Expose controlled search text, change callbacks, customization for placeholder/actions, and appropriate keyboard behavior without owning feature-specific filtering or data fetching.
  - Preserve entered text when closing the keyboard unless the caller explicitly requests clearing it; make close/collapse rules predictable and configurable.
  - Reuse in AppBars, BottomSheets, and other relevant layouts rather than maintaining separate implementations.

- [x] **KeyValueField**
  - Create a reusable template for a paired Key input and Value input with consistent layout, labels, spacing, and input/focus behavior.
  - Support **Key autocomplete/completions through an `ExposedDropdownMenu`** (the expanded suggestion dropdown), with caller-supplied suggestions and selection callbacks.
  - Support editable custom Keys alongside suggestions, without forcing a suggestion to be selected.
  - Expose appropriate Value input variants (plain text, literal text, secret/masked text, or multiline where applicable) without embedding feature-specific storage or validation.
  - Respect the app's literal-input IME policy for keys, tokens, URLs, and raw values; preserve editing state when suggestions open/close.
  - Keep the autocomplete dropdown distinct from `ContextMenuSheet`, which is exclusively for contextual actions.

- [x] **ComponentGroup**
  - Provide a reusable way to group related UI components and features, with `AppMenuScreen` as the reference design.
  - Centralize optional group title/description, item spacing, container styling, rounded corners (including single-item groups), separators, and consistent appearance/transparency.
  - Support feature-defined children and optional group actions without coupling the component to `AppMenuScreen` or any particular feature's data.
  - Make the component usable throughout the app for menus, settings, and other grouped interfaces; avoid feature-local copies of the same grouping layout.

## 2. Integrate the shared components into the app

- [x] Inventory current BottomSheets, `DropdownMenu` usages, search fields, Key/Value editors, and grouped UI; classify each use case before replacing it.
- [x] Implement the six components in the appropriate shared UI layer, reusing existing foundations instead of creating parallel appearance or modal systems.
- [x] Migrate representative predefined-content BottomSheets to `ContentSheet`, with the Header visible only where needed.
- [x] Migrate representative action-oriented menus to headerless `ContextMenuSheet`; keep input completions and appropriate `ExposedDropdownMenu` fields intact.
- [x] Migrate representative list BottomSheets to headerless `ItemListSheet`, including speech pickers and stable `ItemCard` entries.
- [x] Replace repeated search expansion/focus logic with `ExpandableSearchField`, and repeated Key/Value input layouts with `KeyValueField`.
- [x] Apply `ComponentGroup` to the `AppMenuScreen` reference and shared appearance shape calculation.
- [ ] Remove duplicate implementations once migrated; preserve existing functionality, navigation, feature-specific state, accessibility, and appearance policies.
- [ ] Coordinate with the separate `refactor/feature-screens` work so screen templates and these components can compose cleanly without duplicate responsibilities.

## 3. Verify and collect UI feedback

- [ ] Perform static checks for layer/dependency boundaries, leftover duplicate UI patterns, and incorrect component usage.
- [x] Add focused UI tests for expandable search text retention and key autocomplete; preserve existing AppMenu and ModelPicker UI coverage.
- [x] Test search expansion/collapse, text retention, and key selection/autocomplete in `KeyValueField`.
- [ ] Test `ComponentGroup` appearance and layout with zero, one, and multiple items, including light/dark/AMOLED themes.
- [ ] Verify BottomSheet animation, content-driven height, scrolling, safe-area insets, keyboard interaction, context-action dismissal, and accessible touch targets on Android.
- [ ] Run the agreed lint/tests/device checks when approved and supported by the environment; avoid repeated unnecessary Gradle builds.
- [ ] Present the refactored interface for user evaluation and collect feedback before considering the refactor complete.
