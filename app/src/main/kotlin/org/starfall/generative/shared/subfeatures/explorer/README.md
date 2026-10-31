# Explorer sub-feature (Jetpack Compose)

`shared/subfeatures/explorer/` is the app's single, source-agnostic file explorer built with
Jetpack Compose and Material 3. It lives in `shared/subfeatures/` because it is a large reusable
design shared by several features, and it deliberately follows the same visual and motion
language as the rest of the app instead of defining its own.

## Files and public contract

- `logic/ExplorerRepository.kt`: `ExplorerLocation`, `ExplorerItem`, and `ExplorerRepository`.
- `ui/ExplorerScreen.kt`: the reusable Compose screen, navigation, search, and host callbacks.
- `ui/ExplorerTopBar.kt`: AppBar with the folder title/breadcrumb, inline search, and actions.
- `ui/ExplorerItemCard.kt`: the shared `ItemCard` entry plus its context menu.
- `ui/ExplorerDialogs.kt`: create/rename and delete dialogs.

An `ExplorerLocation` has an opaque `sourceId`, a source-relative `path` (slash separated), and a
display `label`. An empty `sourceId` represents the explorer home, which the host may populate with
source entries. `parent()` walks up a directory and returns home when the source root is reached.
A feature can instead start at its own source root (`initialLocation`); Back there calls `onClose`.
Each listed `ExplorerItem` supplies its name, location, directory flag, and optional
size/modified values. Use stable, unique location identities for entries.

The **host** implements the suspending `ExplorerRepository` methods:

| Method | Responsibility |
| --- | --- |
| `list(location)` | List immediate children of a location (or source roots at home). |
| `createFolder(parent, name)` | Create a directory in the selected location. |
| `rename(item, newName)` | Rename the specified entry. |
| `delete(item)` | Delete the specified entry. |

The host must enforce permissions, validate names and paths, and decide how deletion works for
non-empty directories. Optional suspending transfer callbacks `onUploadFile(location, uri)` and
`onDownloadFile(item, uri)` are supplied to `ExplorerScreen` by the host. They handle all bytes and
backend access; the explorer only launches Android's document picker and passes its selected
`content://` URI to the host. Omitting either callback hides that action. The explorer does not
assume a particular filesystem or network protocol. It delegates file opening to the separate
suspending `onOpenFile(ExplorerItem)` callback, so the host can select a viewer or launch an
external application.

## Embed in a host

```kotlin
import androidx.compose.runtime.Composable
import org.starfall.generative.shared.subfeatures.explorer.logic.ExplorerItem
import org.starfall.generative.shared.subfeatures.explorer.logic.ExplorerLocation
import org.starfall.generative.shared.subfeatures.explorer.logic.ExplorerRepository
import org.starfall.generative.shared.subfeatures.explorer.ui.ExplorerScreen

@Composable
fun MyFiles(
    repository: ExplorerRepository,
    onClose: () -> Unit,
    openFile: suspend (ExplorerItem) -> Unit
) {
    ExplorerScreen(
        repository = repository,
        initialLocation = ExplorerLocation(sourceId = "files", label = "My files"),
        onClose = onClose,
        onOpenFile = openFile,
        // Optional: integrate with the host's local, virtual, or remote backend.
        onUploadFile = { directory, pickedUri -> /* host imports pickedUri */ },
        onDownloadFile = { item, destinationUri -> /* host exports item */ }
    )
}
```

Use `initialLocation = ExplorerLocation()` when the host supports a multi-source home and pass
`refreshKey` when the host modifies the displayed files externally.

## Shared design and interaction

The screen reuses the app's presentation components rather than its own variants:

- `TopAppBar` with `edgeToEdgeTopAppBarColors()`, shared Fluent icons, the shared
  `ListViewToggle`, and the shared `AppBarSearchField`; the title shows the folder name with a
  breadcrumb subtitle.
- `ItemCard` for every entry in both list and grid modes, with `ListViewToggle`,
  `FadeGridListContent` and `animateItem()` providing the same view-mode fade and item motion as
  other entity collections.
- `EmptyState`, `LoadingIndicator`, shared `DropdownMenu`/`DropdownMenuItem` for the per-entry
  menu, the shared `AlertDialog` and `OutlinedTextField` for create/rename/delete dialogs, and
  `scrollableScaffoldPadding` so entries travel behind the AppBar and system bars.
- All user-facing text comes from string resources, so the explorer is localized like the rest of
  the app.

The AppBar displays the current source/folder, Back, a compact search button, and (when not
searching) New folder, Upload (when supported), List/Grid and Refresh actions. Tapping Search
expands a single-line search field **in the AppBar**, focuses it, and replaces the other actions
with Close search. Submitting the IME search action, tapping Close, pressing Back or losing input
focus retracts the field. Its query remains in memory and **keeps filtering** after the field
retracts; the Search icon is accented while the filter is active. Reopen Search to edit or clear
it. A non-empty filter matches filenames case-insensitively in the current directory only; it does
not recursively search descendants or call the repository again. Changing directories clears the
query.

Tap a directory to enter it, or a file to invoke `onOpenFile`. Per-entry menus (and long-press)
offer Download (files only, when supported), Rename and Delete; the AppBar offers New folder
within a source. Upload accepts multiple documents from the system file picker; Download opens the
system Save As picker with the original name and inferred MIME type. Canceled pickers do nothing,
and transfers are performed through the supplied suspending callbacks. Successful uploads reload
the directory; after a partially successful batch, the listing is also refreshed when a later file
fails. These operations use confirmation dialogs, call the injected repository, then reload the
current location. A spinner indicates loading; errors from repository or viewer callbacks show a
snackbar. The screen maintains view mode, query, current location and dialogs in Compose memory
only.

## Dependency boundary and host integrations

Explorer is presentation and state only. It may depend on Compose, platform APIs and other
`shared/` helpers, but it must not import `integrations/`, `database/`, `features/`, `navigation/`
or `appwiring/`, and it never accesses an application database, filesystem backend or remote API
itself. Backend implementations and external viewers remain the host's responsibility.

The MediaStorage and SFTP hosts both implement upload/download using streams, so files are not
loaded into memory all at once. The local adapter refuses to overwrite existing names and removes
incomplete uploads; the SFTP adapter uploads to a temporary remote path before publishing the
file. Download failure may leave a partial document in a third-party destination (Android's
provider owns it), which the user can remove with their file manager.

Current hosts include `features/storage/logic/MediaStorageExplorerRepository.kt` with
`features/storage/ui/StorageExplorerScreen.kt`, and
`features/machines/shared/logic/RemoteMachineExplorerRepository.kt` with
`features/machines/shared/ui/RemoteMachineFilesScreen.kt`. These are examples, not dependencies of
Explorer. The Android `DocumentsProvider` is a separate system integration, not the explorer's
navigation backend.

## Verification

- `python3 scripts/check_extensions.py` checks the remaining extension boundaries.
- `app/src/test/java/org/starfall/generative/ExplorerUiTest.kt` exercises simple Compose
  integration and location behavior.
- `app/src/androidTest/java/org/starfall/generative/shared/subfeatures/explorer/ExplorerScreenDeviceTest.kt`
  exercises navigation, searching, collapsing/reopening the AppBar search, file opening,
  transfer-action visibility and repository operations using an in-memory fake.
- `app/src/test/java/org/starfall/generative/ExplorerStorageTest.kt` covers local upload/download
  bytes, name collisions, invalid paths and interrupted transfer cleanup.

Build or run instrumentation tests only in a provisioned Android environment, with an explicitly
selected device. No backend credentials are needed for the in-memory tests.
