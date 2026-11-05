# Contributing to Generative

Thank you for your interest in Generative. Contributions should improve the app for its users, keep existing user data safe, and fit the current Kotlin/Jetpack Compose architecture. You do not need to be a professional Android developer to report a problem, suggest an improvement, or help with documentation; code changes should follow the repository guidance below.

## What you may contribute

Contributions may include:

- Bug fixes, accessibility improvements, UI polish, and user-requested features.
- Provider/API compatibility fixes, MCP integration improvements, and media or speech behavior fixes.
- Performance, reliability, privacy, and security improvements.
- Unit tests and other focused regression coverage.
- Corrections to user-facing text, translations, README content, and other documentation.
- Reproducible bug reports and clear feature suggestions, even when you are not submitting code.

Keep changes focused on the issue being addressed. Explain visible behavior changes and note any trade-offs or limitations.

## What must not be changed or contributed

- Do not commit API keys, access tokens, OAuth credentials, signing keys, passwords, personal data, or local machine configuration.
- Do not add tracking, hidden data collection, credential exfiltration, or other behavior that violates user privacy or security.
- Do not silently remove existing functionality, change data formats, or introduce destructive database changes without a clear reason, compatibility handling, and appropriate migration where needed.
- Do not add unrelated dependencies, generated build outputs, IDE state, or local-only files to a change.
- Do not modify `legacyApp/` merely to implement a feature in the current app. The active implementation is in `app/`; touch `legacyApp/` only when the task explicitly concerns that legacy variant or a change demonstrably needs to keep it compatible.
- Do not reorganize unrelated code or rewrite whole files when a small, targeted change is sufficient.

All contributions must comply with the repository [LICENSE](LICENSE). By submitting code for inclusion, you represent that you have the right to submit it and agree that an accepted, merged code contribution is licensed under the repository license. Under license v1.1, commercial-use permission is limited to an **Active Contributor**: at least one qualifying code contribution must have been accepted and merged in the previous 12 months. The 12-month period resets with each new qualifying code contribution; documentation-only, issue, review, sponsorship, discussion, and other non-code activity does not renew it. When the period expires, commercial use must stop unless a separate written commercial license permits it. See [LICENSE](LICENSE) for the complete terms.

## Development environment

The active Android app uses Kotlin, Jetpack Compose, and Gradle. Use JDK 17 and Android SDK 36 with build-tools 36.0.0. Open the repository root in Android Studio and sync Gradle, or use another Android development environment. The Gradle wrapper is included; a separate Gradle installation is not required. Configure `sdk.dir` in your untracked `local.properties` file or set `ANDROID_HOME`.

Useful commands:

```bash
# Build the current app's debug APK
./gradlew :app:assembleDebug

# Run unit tests and lint for the current app
./gradlew :app:testDebugUnitTest :app:lintDebug

# Run instrumented tests (requires a connected device or emulator)
./gradlew :app:connectedDebugAndroidTest
```

## Code organization and architecture

See the [Android developer documentation](app/docs/README.md) for the project structure, UI conventions, and profiling notes. The public `docs/` folder contains the user-facing website.

The current implementation is under `app/src/main/kotlin/org/starfall/generative/`. Place changes according to responsibility and who uses the code:

- `features/`: feature-oriented vertical slices in the project’s UI-First Clean Architecture–inspired structure. Each capability groups presentation (`ui/`), application workflow/use-case coordination and state holders (`logic/`), and feature-specific state/data types (`model/`). A feature may span multiple screens and is not defined by a `FeaturesScreen`/`AppMenuScreen` entry. The project uses `logic/` and `model/` rather than a canonical `domain/`/`data/` split, so place code by responsibility and dependency direction.
- `features/home/`: chat, conversation drawer, and chat-related shared UI and logic.
- `integrations/`: code whose primary purpose is communicating with external services, including provider APIs, MCP, media-generation APIs, and remote-machine connections.
- `database/`: Room databases, DAOs, entities, migrations, database-backed repositories, and storage mappers.
- `shared/`: reusable `ui/` (presentation only), `subfeatures/` (complete UI or UI + behavior), `helpers/` (logic-only support), `model/` (data contracts), and `state/` (RAM coordination). Persistent preferences belong in `database/preferences/`, and non-UI services belong in `integrations/` or the owning feature.
- `navigation/`: app navigation destinations and routing.
- `appwiring/`: application-level dependency construction and initialization.

Follow this dependency direction: `shared` ← `integrations` ← `database` ← `features` ← `navigation` ← `appwiring`. In particular:

- `shared/` and `integrations/` must not import from `database/` or `features/`; `database/` must not import from `features/`.
- Feature packages must not import from each other, and one feature must never import another feature.
- Code used by only one layer should normally stay with that layer. Move code to `shared/` when it is genuinely used by multiple layers; external API calls and protocol handling belong in `integrations/`.
- Keep UI rendering in Compose and screen state/actions in the appropriate ViewModel or state holder. Within a feature, UI may depend on logic/model, logic may depend on model, and model must not depend on UI or logic. Inject dependencies rather than constructing unrelated services inside UI composables.
- Use lifecycle-aware collection for UI flows and propagate coroutine cancellation.
- Keep files focused and preferably at or below 500 lines; split only when it improves clarity and ownership.

Before changing package structure or moving a file, inspect its callers, tests, imports, and package references. Update those references to the new location rather than creating a duplicate implementation. Preserve the dependency boundaries above.

## User data, resources, and configuration

- Keep Room entities, DAOs, and migrations in `database/room/`, repositories in `database/repositories/`, DataStore implementations in `database/preferences/`, and generic state infrastructure in `shared/state/`. Keep Android Keystore encryption in `integrations/security/`, shared by Room persistence and MCP OAuth. For schema changes, provide a compatible migration and preserve existing users' data.
- Keep the original Room schema snapshots in `app/schemas/org.starfall.generative.database.AppDatabase/` and the copied schema history for the relocated class in `app/schemas/org.starfall.generative.database.room.AppDatabase/`. A package-only relocation must not change schema contents/hashes, the database filename, schema version, migration SQL, or credential key alias.
- Add app resources under `app/src/main/res/` and raw assets under `app/src/main/assets/`.
- Do not check in `local.properties`, build outputs, signing material, or machine-specific configuration.
- Keep credentials out of logs, error messages, test fixtures, sample links, and documentation.
- When changing provider or MCP behavior, handle unsuccessful responses, cancellation, and malformed input without exposing secrets.

## Validation and review

Before submitting a code change, run the most relevant checks available:

1. Build the debug app with `./gradlew :app:assembleDebug`.
2. Run relevant unit tests; run `./gradlew :app:testDebugUnitTest :app:lintDebug` when appropriate.
3. For navigation, lifecycle, or state changes, check system Back, rotation, process recreation, and behavior while chat generation is active on a device/emulator when practical.
4. For bug fixes, add or update regression tests when feasible.

If you cannot run a check, say so clearly. In the pull request or change summary, describe the problem, the behavior changed, the checks performed, and any known limitations. Do not claim a check passed unless it actually ran successfully.

Versioning and release publishing are maintained through the repository's existing build/release configuration; contributors should not change release automation as part of an unrelated feature or bug fix.
