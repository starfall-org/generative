# Architecture

## Design language

Generative uses **Jetpack Compose and Material 3**, with app-specific layouts and **Fluent UI System Icons**. The UI should feel clean, content-first, and consistent rather than decorating every area with extra containers.

- Use `GenerativeTheme`, `MaterialTheme.colorScheme`, and `MaterialTheme.typography` for colors and text. Respect system/light/dark themes, dynamic colors, custom palettes, and AMOLED mode; avoid hard-coded colors and typography when a theme token exists.
- Keep clear hierarchy through spacing, typography, and restrained surface contrast. Reuse shared cards, settings sections, top bars, dialogs, bottom sheets, and transitions before creating new variants. Use sensible list-based layouts for long content and keep chat focused on conversation content.
- Reasoning effort level and state labels retain their English API names in every locale (including None, Default, Active and X-High). Mark their dedicated string resources non-translatable; shared labels used elsewhere remain localizable.
- Technical API/protocol labels and parameter names in editor screens (for example Stream Response, Tool Support, Context window, Content Type, and model-generation options) use the canonical English text in every locale. Declare their string resources `translatable="false"` and do not provide localized overrides. Keep natural-language help, errors, descriptions, and ordinary actions translated.
- An unselected model, provider, profile, voice, or other item is a neutral setup state. Show a selection placeholder or disable the dependent action; do not show red warning text or error styling solely because no item is selected. Required-selection checks still apply when executing an action.
- Prefer shared UI components under `shared/ui` over duplicated implementations. Use string resources for user-facing text so screens remain localizable.
- Independent extensions own their models and logic. Host code supplies configuration, appearance and integrations through extension-owned contracts; extensions must not depend on application layers.
- **Appearance and UserProfile policy is shared, not per-screen.** A feature that reacts to theme/AMOLED, component transparency, user-profile image or background opacity must use shared appearance functions and components. Shared appearance helpers own Appearance-sensitive fills and borders (`prominentContainer*`, `nestedContainer*`, `appearanceContainer*`, `modalContainerColor`); `ProfileBackground` owns image blending; the shared `BottomSheet`, `Dialog`/`AlertDialog`, and dropdown menus own their themed modal surfaces. Supply feature-specific shape, icon, and semantic selection/error colors at the call site, but do not clone settings-dependent alpha, profile background or AMOLED logic. For overlays requiring additional contrast, use the shared `opacityBoost` option instead of overriding a shared fill with a fixed alpha. Opaque modal/drawer surfaces stay opaque while profile images are painted behind their content.
- **ChatAppBar scrim is intentionally independent:** render a `MaterialTheme.colorScheme.background` overlay only behind the ChatAppBar controls, from 60% opacity at the top of the status bar to 0% at the app-bar bottom. This scrim follows the themed/custom background color (light or dark) but ignores component transparency and UserProfile photo alpha; it fades scrolling content underneath through normal compositing. Never apply the fade directly to text, messages, media blocks, or their individual alpha values.
- Entity collections use `ItemCard`, `ListViewToggle`, and `FadeGridListContent`; expandable items can use `ItemSurface`. Use the shared dialog, sheet and menu components. Keep long dialog content independently scrollable.

## Icons

Source: [Fluent UI System Icons](https://composables.com/icons/icon-libraries/fluentui-system-icons).

- Prefer downloading **individual Kotlin Compose `ImageVector` sources** with `python scripts/get_icon.py <icon>`; they live under `shared/ui/icons/fluentui/<style>/` and are registered automatically in `FluentUi.Regular`, `FluentUi.Filled`, and the corresponding style object. Do not add the whole icon library as a Gradle dependency.
- Use **Regular** as the default for common actions and navigation. Use **Light** when a deliberately lighter stroke suits the context. Keep the style consistent among related actions and states; do not mix styles arbitrarily.
- Use `SharedIcon(FluentUi.Regular.ArrowClockwise, ...)` for Kotlin-vector usage, rather than calling Material 3 `Icon` directly for shared icons. `Icons.Outlined`, `Icons.Default`, `Icons.Filled`, and `Icons.AutoMirrored` preserve older Material symbol names while mapping Fluent UI icons to `ImageVector`. Keep Fluent icons as individual Kotlin sources; do not reintroduce Fluent drawable XMLs. Non-Fluent icons such as `Terminal` may remain drawable-backed.
- Tint with `MaterialTheme.colorScheme`; avoid hard-coded icon colors. Icon-only controls need a meaningful content description, while decorative icons or icons next to an equivalent label should not repeat it.
- Choose icons by meaning and clarity, not merely appearance. Keep the app logo and other branded artwork separate from action icons.

## UI interaction rules

- **Dialog:** Place icon-only buttons in the dialog header and buttons with text in its footer. Keep these areas visible when content scrolls.
- **BottomSheet:** Place every button in the sheet header and use icon-only buttons. Search fields must expand and collapse. List items in a sheet must use `ItemCard`; if an `ItemCard` list is not appropriate, use an `ExposedDropdownMenu` instead of a bottom sheet.
- **Full screen:** Place icon-only controls that affect the screen as a whole in its AppBar. Reserve the body for functional components and their local interactions.
- **DropdownMenu:** Use it for a three-dot button's context menu and actions that open other features. Every menu item has both text and an icon. Do not use it to select an attribute value.
- **ExposedDropdownMenu:** Use it for an editable dropdown or equivalent field that selects a known attribute value. Do not use it to open another feature.

## Project organization

The app uses a customized feature-first architecture. Organize code by responsibility and actual reuse, not by menu entry or screen count. A feature is a distinct capability that end users can recognize and interact with; it can own multiple screens, workflows and models. Inside a feature, UI may depend on logic and models, logic may depend on models, and models must not depend on UI or logic. Sub-features do not import one another; code they share belongs in their feature's `shared/`.

- **`features/`**: User-facing capabilities and their presentation, workflows and feature-specific state and models. Do not import another feature; place shared functionality in the appropriate shared layer.
- **`integrations/`**: Non-UI service implementations, external APIs, platform services, and protocols (including speech synthesis/playback and durable skill-file storage). Feature-specific service workflows belong inside their owning feature instead.
- **`database/`**: Durable persistence, with Room database, entities, DAOs and migrations in `database/room/`, DataStore-backed preference implementations in `database/preferences/`, and persistence repositories and mappers in `database/repositories/`.
- **`shared/`**: Reusable foundations organized by responsibility: UI-only components, combined UI/behavior subfeatures, logic-only helpers, models, and state coordination. Keep code close to its owner until genuinely reused; do not put standalone services in a generic shared package.
- **`extensions/`**: Highly complex, difficult-to-maintain capabilities that require strong isolation. Dependencies point into extensions only: an extension never imports `shared/`, `integrations/`, `database/`, `features/`, `navigation/` or `appwiring/`. Extensions do not access a database, filesystem backend or remote API; hosts implement extension-owned contracts. Extensions must not contain UI configuration code; reusable UI and configuration stay in `shared/subfeatures/` or with the owning feature.
- **`shared/subfeatures/`**: Complete reusable UI designs, or reusable components that own both presentation and behavior. Current examples include `auth/`, `crashreports/`, `explorer/`, `icons/`, `logging/`, `media/`, `modelpicker/` and `terminal/`. Keep feature-specific configuration and external services in the owning feature or integration layer.
- **`features/machines/shared/ui/terminal/`**: Machines-owned remote terminal presentation. Its reusable Termux-based rendering, bridge, session, and input infrastructure lives in `shared/subfeatures/terminal/`; the screen and its supporting app bar/panels/dialogs do not belong to generic shared UI.
- **`shared/helpers/`**: Non-UI supporting logic (for example `audio/`, `code/`, `media/`, `networking/`, and `speech/`), not feature-complete services.
- **`shared/model/`**: Shared data contracts and pure model types.
- **`shared/state/`**: Reusable in-memory state coordination and ordered persistence orchestration (`ImmediateState`).
- **`shared/ui/`**: UI-only presentation components and policies, including `theme/`, code highlighting, screens, and generic icons, without feature behavior. Shared UI obtains appearance and profile policies from reusable shared components and helpers.
- **`shared/ui/screens/`**: Reusable secondary-screen shell, typed content contracts, retained screen state, and forward/back transitions. `MainScreenTemplate` is only a root-screen marker for Chat and does not add a secondary-screen shell.
- **Screen navigation has one host and back stack.** `navigation/RetainedNavHost` renders every full-screen destination, including feature-owned editors and previews, with the shared motion policy in `ScreenMotion`. Features register draft-dependent screens through `ScreenDestination` and the host-provided `ScreenNavigator` contract; registration adds real Navigation destinations without a feature-local stack or animation host. The parent remains composed to retain its draft and provide live callbacks. Only the foreground destination owns screen-level Back actions. Keep sheet navigation independent of full-screen navigation.
- **`shared/ui/inputs/`**: Reusable editable controls and literal/text input behavior. Rendering-only text, Markdown, selection and streaming helpers remain under `shared/ui/text/`.

`extensions/` and `shared/` are independent foundations. Application dependency direction remains `integrations` ← `database` ← `features` ← `navigation` ← `appwiring`; a layer can depend only on an appropriate foundation or an earlier application layer. `shared/` and `integrations/` must not import `database/` or `features/`; `database/` must not import `features/`. Screen transitions use callbacks wired by the navigation layer. Run `python3 scripts/check_extensions.py` after extension changes.

Preserve the Room schema history under `app/schemas/`; package-only moves must not change schema versions, database names, migrations or credential keys.

### Storage policy

- Preference value types live in `shared/model/preferences/`, independently of DataStore and Compose. Locale configuration lives in `shared/helpers/locale/`. `ApplicationPreferencesOwner` supplies the application-owned settings flow and `StringPreferences` contract to shared consumers and integrations; the migration-aware DataStore adapter remains in `database/preferences/`. Application wiring supplies terminal-preference cleanup to the remote-machine service.
- Use Jetpack Preferences DataStore for application settings, customization and other reusable preference state. Migrate existing SharedPreferences values without loss.
- Store dynamic, capability-specific data in Room/SQLite, including credentials, model providers, MCP servers, speech services, Feature Tools profiles and instructions. Keep database access in `database/` and encrypt credentials before persistence.
- Keep one application-scoped instance for each database and DataStore; share repositories and stores through application wiring. Read one Room snapshot to initialize an application-owned memory catalog. Room/SQLite is passive persistence and must not publish invalidations or re-read/reorder active UI after writes; repositories explicitly publish RAM edits immediately, then persist through their serial writers. DataStore preferences retain their reactive flow.
- Migrate existing installations and backup/restore paths without silently discarding data. Retain the legacy source until the destination has been written successfully; make migration safe to retry.

## Maintaining the architecture

- Inspect the current source, callers and tests before modifying an existing capability. Treat documentation as design guidance and verify implementation details in code. Preserve established behavior unless the task explicitly changes it.
- Keep cross-cutting policies, reusable UI and state shared; keep feature-specific behavior in its owning feature. Introduce abstractions only where they clarify a responsibility or support actual reuse.
- Scope long-running work, persistence and external communication to the appropriate lifecycle. Preserve cancellation, error handling, credentials and user data when changing a workflow.
- Preserve user-controlled scrolling, input composition and accessibility when changing interactive components. Keep persistent controls visible and provide meaningful labels for icon-only actions.
- Preserve database compatibility and migrations when changing persisted models. Separate stored data, external protocol models and presentation state according to their responsibilities.
