# Launcher-style drag and drop (Jetpack Compose)

An independent Compose adaptation of the **architecture** used in AOSP Launcher3 /
[Lawnchair](https://github.com/LawnchairLauncher/lawnchair) (Apache-2.0). This
is original Kotlin code for Compose, **not** a copy of Launcher3's View classes.
Reference: `DragController`, `DragView`, `Workspace`, `CellLayout`,
`FolderPagedView`, and `ReorderAlgorithm` on Lawnchair's `16-dev` branch.

## Public API and dependency boundary

Import the single public facade instead of importing a dozen controller symbols:

```kotlin
import org.starfall.generative.extensions.launcher.Launcher
```

This directory is a self-contained source component compiled by the app module,
not a published Gradle library. It depends only on Kotlin/coroutines and Compose,
never on application modules, database, filesystem backends or remote APIs.
Hosts provide previews, application adapters and persistence callbacks. Run
`python3 scripts/check_extensions.py` to check the dependency boundary.

The facade **delegates to the existing implementation** and does not create a
second drag state or replace the session/transaction contracts. Inside a
`with(Launcher) { ... }` scope, use `.dragSource`, `.dropTarget`,
`.itemDropTarget`, `.folderMember` and `.autoScroll` as normal Modifier extensions.

Minimal transfer without a join region:

```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.starfall.generative.extensions.launcher.Launcher

@Composable
fun MoveTile(id: String, destinationLabel: String, onMoved: (String) -> Unit) = with(Launcher) {
    val state = rememberState<String>()
    Host(state, preview = { Text(it.item.data) }) {
        Row {
            Text(id, Modifier.size(120.dp).dragSource(
                state, key = id, data = id,
                onDragEnd = { if (it.accepted) onMoved(id) }
            ))
            Box(Modifier.size(120.dp).itemDropTarget(
                state, key = "destination", onReorderDrop = { true }
            )) { Text(destinationLabel) }
        }
    }
}
```

`Launcher` supplies nested type aliases for explicit signatures:
`State<T>`, `Item<T>`, `Session<T>`, `Outcome`, `Layout<T>`,
`Cell`, `ReorderHit`, `ReorderRule`, `ReorderContext`,
`DropPolicy`, `CollisionRule`, `Activation`, and `Style`
(e.g. `Launcher.State<String>`). It also exports
`Launcher.Collision` (Pointer, VisualCenter, Center, Overlap, Custom),
`Launcher.Reorder` (OnEntry, CrossCenter, Before, After, Custom),
`Launcher.ActivationMode` (LongPress, AfterSlop), `Launcher.defaults`,
`Launcher.geometry`, `Launcher.Hover`, `Launcher.HoverAction`,
`Launcher.folderGeometry`, `Launcher.FolderExit` and `.folderMember`.

Existing top-level Kotlin APIs remain valid for old callers and tests.
Keep one `Launcher.Host` above all removable folders/workspaces. For ordered
lists/grids, use `Launcher.Layout` with a hover effect and persist only an
accepted commit. The host owns storage, folder membership and capacity;
the extension only implements interactions.

## Reusable folder behavior

The Developer Options demo and the Providers folder both use the **same**
`Modifier.folderMember` interaction logic, `Launcher.folderGeometry` for
logical drop slots and `Launcher.autoScroll` for edge scrolling. Launcher does
**not** supply folder UI, item rendering, list/grid composables, or styling.
The optional `shared/ui/lists/LauncherCollection.kt` is a separate UI adapter
used by both consumers; other clients may render completely different layouts.
The demo uses a small Card, while Providers retains its full-screen animated
overlay, header, icons, cards, insets, AMOLED appearance and navigation. The
extension never imports application features or persists data.

A folder integration uses four cooperating primitives:

1. Keep one stable `Launcher.Host` above the workspace and removable folders.
2. Render any host-owned list/grid with independently measured logical
   slots via `Launcher.folderGeometry` and apply `.autoScroll` to its viewport.
   The app offers an optional `shared/ui/lists/LauncherCollection.kt` adapter
   for ordinary lazy lists/grids, with configurable columns, padding, spacing,
   animation and scrolling. It is intentionally outside Launcher.
3. Apply `.folderMember` to each rendered tile, passing the **logical slot**
   returned by the collection as `hitBounds`. The API combines drag source,
   reorder target, optional centered folder-join target and optional higher-
   priority self-drop target. The caller chooses collision rules, target
   priorities, acceptance policies, hover timing and drag callbacks. Optional
   source handles, drag artwork regions and return destinations remain supported.
4. Use `Launcher.FolderExit` on the stable owner for delayed folder exit and
   custom escape regions. The original drag continues into the workspace.
   On release, commit the host's in-memory order once; cancel restores its
   snapshot. Backend writes and error rollback are host responsibilities.

A screen can use `Launcher.Layout` for generic container membership and
ordering, or adapt its richer domain model to `onReorderEnter` /
`onReorderDrop` without placing database logic in Launcher. In particular,
Providers keeps its existing persistence/rollback model and its 5dp side-exit
policy. While a folder is open, disable workspace auto-scroll to prevent two
stacked scroll containers from reacting to the same pointer.

## Components

- `Launcher.Host` (`LauncherDragDropHost` internally): a single stable pointer owner and visual drag overlay;
  keep it mounted while folders appear/disappear. Long-press begins dragging.
- `.dragSource`: registers a stable-key item, its measured window bounds,
  and callbacks, without owning the gesture.
- `.dropTarget`: registers a rectangular hit area. Higher-priority targets
  win; equally ranked tiles win over large parent areas.
- `Launcher.State`: maintains one active item, pointer-to-shadow anchor,
  target enter/over/exit, accepted drop or cancellation. The original item can be
  disposed without canceling the gesture.
- `Launcher.Layout`: in-memory preview across named containers. No storage
  write occurs on hover. Commit only for an accepted drop and rollback on cancel.
- `Launcher.geometry`: nearest-cell detection based on measured layout bounds
  and the shadow's visual center.
- `Launcher.Hover` / `Launcher.HoverAction`: cancelable delayed reorder/folder-open trigger.
- `Launcher.FolderExit`: cancelable delayed close when the drag shadow leaves
  the folder's registered region, with an optional `extraExitRegion` callback
  for host-defined escape areas (such as the left/right display edges). The
  drag session survives closing the folder; a cancelled drop restores its
  original membership.
- `.autoScroll`: viewport-measured, frame-driven lazy list/grid scroll; can be
  disabled while a folder overlays its workspace.
- `Launcher.folderGeometry`: pure measured slot calculations, independent of UI.
  `shared/ui/lists/LauncherCollection.kt` is an optional host-owned renderer.
- `.folderMember`: unified source/reorder/join/self-drop registration, with
  item-specific callbacks and hit-testing policies.

## Integration contract

1. Call `Launcher.rememberState<T>()` and put one
   `Launcher.Host(state, preview = { ... })` **outside** all swappable
   folder/workspace content. Place the host around the region where both render.
2. Give every source a **stable unique key** and one `.dragSource`
   modifier (inside `with(Launcher)`). It hides the original while dragging/landing;
   the host paints the moving preview. Use real data IDs, never list indices.
3. Mark workspace/folder rectangles with `.dropTarget`. Child tiles may
   add higher-priority targets for insertion. Use distinct target keys such as
   `workspace`, `folder:<id>`, `slot:<id>`.
4. In each source's `onDragStart`, call `layout.begin(itemId)`. During hover,
   use `layout.hover(containerKey, tileKey, after = ...)`. To avoid jitter,
   trigger this from `Launcher.Hover` or `Launcher.HoverAction` (not every pointer tick).
5. Return `true` from the receiving target's `onDrop` only when permitted.
   In the **source** `onDragEnd`, call `layout.commit()` and persist once
   when `outcome.accepted`, otherwise call `layout.cancel()`.
6. For leaving an open folder, register its *content region* as a target and use
   `LauncherFolderExitEffect`. Only remove the folder UI, **not** the host or
   drag state. Workspace targets then continue receiving the same drag session.
7. Use the optional host-side `LauncherCollection` adapter, or render any
   custom lazy layout and apply `Modifier.autoScroll(state, lazyGridState)`.
   Register targets against logical slots rather than animated tiles.
8. Keep mutually exclusive surfaces' `enabled` flags synchronized with
   visibility; a visually covered workspace must not intercept a folder drag.

The host handles **internal touch dragging**. It deliberately does not replace
Android's platform `dragAndDropSource/Target` for exchanging `ClipData` with
other apps. It also does not persist entities, create folders, or impose a
particular grid span/occupancy policy: callers own those product-specific rules.

This directory is an independent reusable foundation. It has **not** replaced
the ongoing `ProvidersScreen` drag-and-drop implementation.

## Manual device sandbox

Open **App Menu → App Settings → Preferences → Developer → Launcher drag test**.
This navigates to a separate route (`launcher_drag_test`) with in-memory dummy
items only. Long-press a tile to reorder; tap Folder to open the overlay; move
a tile into the folder by holding over its icon; open the folder, long-press a
child, drag beyond the folder panel and hold for the delayed exit before
releasing on the workspace. Scroll near the grid edges, press Back/Cancel
during a drag to verify rollback, or tap Reset to restore the sample data.
The screen shows the active source, hovered target and outcome. No real
provider data or persistent storage is affected.

## Geometry and motion contract

All public geometry is in **window pixels**. Register sources on the real tile;
register targets on their visible hit regions. The host must remain in an
untransformed, unclipped overlay above both workspace and folder surfaces.
Sources keep their full transformed bounds for the shadow, even when partially
clipped by scrolling; clipped bounds only determine whether a touch can pick them up.
`dragRegion` optionally describes the artwork in source-local pixels. Its center,
scaled into window coordinates, is `session.visualCenter`. Pickup scales around
the original touch anchor, never recenters the item under the finger. Rectangular
list/grid surfaces support translation and scaling; arbitrary rotated previews
are not a substitute for Lawnchair's icon rendering pipeline.

Use `hitTest = Launcher.Collision.Pointer` for parent containers and
`Launcher.Collision.VisualCenter` for insertion slots/folder icons. Child targets
must reject their own dragged key. Keep covered workspace sources **and tile
targets** disabled while a folder is open; its container may remain a fallback.
Folder exit follows the artwork center, with a cancelable delay. A participating
drag stays eligible for exit even when a workspace preview has already changed
its container membership.

`launcherDragSource` now hides the entire original by default, including its
surface and accessibility subtree. Use `state.isDragging(key)` if a consumer
needs its own placeholder; `session != null` alone does not include landing.
`hideWhileDragging = false` explicitly opts out. The preview slot must render
presentation only; do not apply the source modifier to the shadow itself.

Accepted drop/cancel callbacks run **once, immediately**. The host then keeps a
render-only shadow while the committed/rolled-back source remeasures, and lands
on its latest bounds in a bounded 180 ms animation. `landingBounds` overrides
that destination for a folder preview or another special target, and is evaluated
again on every landing frame. Source `returnBounds` supplies a cancellation
fallback (e.g. the closed folder icon) when its original tile is no longer visible.
An offscreen insertion without a visible destination fades at release rather than
flying back to its pickup point after an accepted drop. Starting the
next drag interrupts the old visual tail without repeating persistence. Owner
cancellation/disposal rolls back a live transaction and releases the shadow.
Consumed synthetic releases from Compose cancellation never commit, even after
moving far enough to drag or disposing the original folder/source.

Apply native `LazyItemScope.animateItem` / `LazyGridItemScope.animateItem` to
stable-key siblings; the sandbox uses a 220 ms placement tween. Use
`LauncherHoverEffect` for reorder, not per-frame `onDragMove` writes. It requires
renewed pointer movement after each applied hover so animated siblings cannot
cascade under a stationary finger. Active auto-scroll intentionally permits
new slots to become hover candidates without additional finger movement.
Use `layout.enter(container)` for container background drops to preserve the
already previewed insertion; use `layout.hover(...)` for a specific slot.
A quick release must apply its final slot synchronously in `onDrop` if its delayed
hover has not executed yet (as demonstrated by the sandbox).

### Lawnchair reference

The adaptation follows these responsibilities from the `16-dev` branch:

| Reference | Contract used here |
| --- | --- |
| [DragView](https://github.com/LawnchairLauncher/lawnchair/blob/16-dev/src/com/android/launcher3/dragndrop/DragView.java) | Keep the touch registration point while translating the shadow. |
| [DropTarget.DragObject](https://github.com/LawnchairLauncher/lawnchair/blob/16-dev/src/com/android/launcher3/DropTarget.java) | Use the visible artwork center to choose an insertion location. |
| [Workspace](https://github.com/LawnchairLauncher/lawnchair/blob/16-dev/src/com/android/launcher3/Workspace.java) | Delay workspace reorder (650 ms); preview before committing. |
| [Folder](https://github.com/LawnchairLauncher/lawnchair/blob/16-dev/src/com/android/launcher3/folder/Folder.java) | Folder reorder 250 ms; exit-close 400 ms; nearest visual cell. |
| [FolderIcon](https://github.com/LawnchairLauncher/lawnchair/blob/16-dev/src/com/android/launcher3/folder/FolderIcon.java) | Spring-open after 800 ms; a quick drop may add without opening. |

`Launcher.defaults` centralizes those delays. The pickup scale/duration and
landing/placement durations are Compose presentation choices, not claims of
pixel-identical View animations. This library models ordered, single-cell lists
and grids; it does not implement widget spans, home-screen paging, fling-to-delete,
or Launcher3's full cell-occupancy solver.

### Replacing Compose DND consumers

- Lift one `Launcher.State` and `Launcher.Host` above swappable surfaces.
- Replace draggable/reorderable registrations with `.dragSource` and
  `.dropTarget` in a `with(Launcher)` scope, using the same stable domain IDs in every container.
- Render the host preview from `drag.item.data`; hide the original with the
  source's default policy, rather than fading its content independently.
- Keep hover changes in `Launcher.Layout`; persist `commit()` only in the
  source completion callback when `outcome.accepted`; `cancel()` restores order
  and membership on rejection, Back, or disposal.
- Replace drag-scroll helpers with `.autoScroll` on the real list/grid
  viewport. No guessed row sizes, screen offsets or separate folder drag owner.

The foundation and sandbox are independent of `com.mohamedrejeb.dnd`. Production
DND consumers remain separate until migrated through this contract; no dependency
removal or migration of live provider storage is implied by changing the sandbox.

### Reorder and join are independent

`Launcher.itemDropTarget` has **no join region by default**. Give ordinary items
only `onReorderDrop`; the center remains part of their full reorder hit area.
A grouping target explicitly supplies both `joinHitTest` and `onJoinDrop`:

```kotlin
with(Launcher) {
    Modifier.itemDropTarget(
        state = state,
        key = "slot:$id",
        policy = Launcher.DropPolicy(
            joinHitTest = if (canGroupIntoThisItem) Launcher.Collision.Center(0.5f, 0.5f) else null
        ),
        acceptsReorder = { it.key != id },
        acceptsJoin = { item -> canAcceptAnotherMember(item) },
        onJoinDrop = if (canGroupIntoThisItem) ({ drag -> addMember(drag.item) }) else null,
        onReorderDrop = { drag -> reorder(drag) }
    )
}
```

Both actions have separate keys, priorities, acceptance predicates and collision
policies. Disabled or rejected join eligibility leaves the reorder region
available, including its center. An application failure returned by an already
selected join callback still rolls back; it does not perform an unsolicited
reorder. Grouping is not restricted to a folder type: custom clients may opt any
item into joining, while the demo opts in only its actual folder.

Choosing **where to insert** is also independent of join eligibility. Use the
policy-aware `layout.hover(destination, targetKey, drag, targetBounds, policy, ...)`:

| Reorder policy | Behavior |
| --- | --- |
| `OnEntry` (default for the policy-aware overload and demo) | Move to the neighboring item's index on entry; forward/backward direction follows current transaction indices, so adjacent items need no center crossing |
| `CrossCenter` | Choose before/after from the artwork center and the target midpoint |
| `Before` / `After` | Fixed insertion side |
| `Custom { context -> ... }` | Choose using current source/target indices, drag geometry and layout direction |

For entry from another container, `OnEntry` uses the target half because there is
no source index in that destination yet. Pass `horizontal` and `rtl` for the
layout's reading order. Existing `hover(..., after = ...)` remains available for
clients that already decide their own insertion side. A hover that changes
nothing must not be recorded as an applied preview; a quick drop must recheck
its current side. An applied-preview cache must also compare logical bounds,
policy and insertion half, so returning to a relocated item or crossing the same
target midpoint is a fresh drop intent. Keep the default movement guard to prevent reordering cascades
under a stationary pointer after placement animations.

The demo's interaction dialog independently switches **Reorder on target entry**
and **Allow drops into folders**, alongside the existing geometry/dwell controls.
The hover dwell still applies; on-entry removes the geometric center-crossing
requirement, not the caller's configured delay.

### Interaction customization

The host accepts `activation` and `style` without requiring a new controller:

```kotlin
Launcher.Host(
    state = state,
    activation = Launcher.ActivationMode.LongPress(timeoutMillis = 350),
    style = Launcher.Style(pickupScale = 1.06f, landingDurationMillis = 140),
    preview = { drag -> Tile(drag.item.data) }
) { /* Any measured list, grid or custom layout. */ }
```

Use `Launcher.ActivationMode.AfterSlop` for a drag handle that should start without
waiting. Set `handleBounds` on `.dragSource` in **source-local pixels** to
restrict the pickup area; artwork and shadow retain their full source size. Taps
outside a handle continue to reach normal click/scroll handlers. A long press on
a clickable source (including a folder) consumes the drag without opening it;
a short tap still opens it. Changing activation while dragging cancels the gesture.

Target `hitTest` supports:

| Policy | Use |
| --- | --- |
| `Pointer` | Container regions under the finger |
| `VisualCenter` | Ordinary measured reorder slots |
| `Center(widthFraction, heightFraction)` | Folder-entry center with independent horizontal/vertical fractions |
| `Overlap(minimumFraction)` | Minimum fraction of dragged artwork covered by the target |
| `Custom { drag, bounds -> ... }` | Pure window-coordinate collision predicate, e.g. circular or application-specific regions |

For an animated list/grid, pass `hitBounds` in **window pixels** to use a logical
layout slot independently of the moving tile. Obtain it from the native layout's
visible item geometry, account for its actual content padding and RTL placement,
and clip it to its viewport. Leaving it null preserves measured drawing bounds.
Priorities and `accepts` still apply to every collision policy; rejection never
falls through to a different target during the same drop.

Keep **sources** behind an open folder disabled while leaving workspace **targets**
registered. The higher-priority folder region occludes covered targets; outside
its artwork-center region, workspace slots can already preview an insertion before
the folder finishes closing. For a container background/gap, `state.targetCells()`
returns live enabled regions: filter the desired container and exclude the dragged
item before calling `Launcher.geometry.closestSlot`. A folder-to-workspace entry
should use that visible slot, rather than append an invisible placeholder at the end.
Close an open folder when a workspace drop commits, including drops before the
exit-close timer has elapsed.

Register a full reorder target on a folder and a higher-priority `Center` target
for entry. Moving another folder can reject the entry target while still using
the reorder target. Keep nesting, capacity and storage policies in `accepts` and
transaction callbacks; the controller does not assume a particular item type.

`Launcher.Hover` exposes `delayMillis`, `movementThreshold` and
`requireMovementAfterHover`. Its default prevents stationary-pointer reorder
cascades. Turning off the movement guard permits continuous dwell-driven reorder;
use that only when the consumer deliberately handles successive layout changes.
`.autoScroll` retains independent edge width, speed, direction and
reverse-layout controls. While a drag is active, Launcher anchors the visible
LazyList/LazyGrid index and offset before an entry, hover, drop or completion
callback changes the item order. This prevents Lazy layouts from following the
previous first-visible item's key to a distant row. Consumers do not need
screen-specific scroll anchoring for drag reorders. The optional host-side
`LauncherCollection` uses native `animateItem`; custom layouts can animate
however needed. Host `style` controls pickup scale/duration, landing duration and
shadow elevation.

The demo's **List / Grid** and **Interaction settings** controls are session-only.
They vary column count, reorder dwell, spring-open dwell and the folder center
fraction, and use the same source/target policies as production callers.

### Validation

`LauncherDragDropTest` and `LauncherDragRobustnessTest` cover anchors, artwork vs
pointer targets, clipping, ordered cross-container moves, rejection, exceptions,
reentrant target disposal, exactly-once completion, landing interruption and RTL.
`LauncherHostInteractionTest` exercises host disposal, stationary hover and
frame-driven auto-scroll. `LauncherDragTestScreenTest` exercises real gestures,
folder entry/exit, rollback and workspace → folder → workspace in one gesture.
Android instrumentation counterparts live under `androidTest`:

- `LauncherDragDeviceTest`: sandbox folder entry/exit, quick drop, cancellation
  after closing the source folder, and workspace → folder → workspace.
- `LauncherFlexibleLayoutDeviceTest`: real variable-height lazy rows, grid/list
  switches and column changes during a gesture, unchanged sibling slots without
  a destination, source invisibility verified from captured pixels, ordered hover
  and rollback, touch anchoring, immediate commit with a separate landing tail,
  second-pointer cancellation, and auto-scroll past a disposed lazy source.
- `LauncherHostInteractionDeviceTest`: host disposal, stationary-hover stability,
  auto-scroll cancellation and physical-left-edge scrolling in horizontal RTL.
- `LauncherGesturePolicyDeviceTest`: clickable-folder long press, handle-based
  activation and custom long-press timing.
- `LauncherDragCollectionGeometryDeviceTest`: logical slot vs drawn bounds in
  list/grid, LTR/RTL, with content padding and an offset viewport.
- `LauncherDragStateDeviceTest`: target selection, reentrant disposal, stable
  source identity, transaction ownership and exactly-once completion on Android.

Run only these suites on one selected device (all data is in-memory; the test
Activity does not edit production provider storage):

```sh
ANDROID_HOME=/opt/android-sdk ./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest
adb -s "$ANDROID_SERIAL" install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s "$ANDROID_SERIAL" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class org.starfall.generative.features.settings.ui.LauncherDragDeviceTest,org.starfall.generative.features.settings.ui.LauncherDragCollectionGeometryDeviceTest,org.starfall.generative.extensions.launcher.LauncherFlexibleLayoutDeviceTest,org.starfall.generative.extensions.launcher.LauncherHostInteractionDeviceTest,org.starfall.generative.extensions.launcher.LauncherGesturePolicyDeviceTest,org.starfall.generative.extensions.launcher.LauncherItemDropPolicyDeviceTest,org.starfall.generative.extensions.launcher.LauncherDragStateDeviceTest \
  org.starfall.generative.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Set `ANDROID_SERIAL` from `adb devices` first. A passing functional suite proves
these interaction contracts on that device; it does not measure frame pacing,
prove accessibility parity, or validate migrated production storage callbacks.
After migration, keep testing each consumer's persistence callbacks, folder
policy, scrolling and lifecycle integration. Profile frame timing on the
actual supported devices before claiming animation performance parity.

### Flexible layouts

The controller is layout-agnostic and provides no UI. The optional adapter
`shared/ui/lists/LauncherCollection.kt` can render a list or vertical grid;
other layouts register the same drag sources and targets. `LazyColumn`, `LazyRow`, fixed or
adaptive `LazyVerticalGrid`/`LazyHorizontalGrid`, and custom measured containers
can all register sources/targets. Use `horizontal = true` for horizontal
geometry/scroll helpers, and `rtl = true` for horizontal insertion reading order.
Auto-scroll follows `LocalLayoutDirection` for horizontal layouts; pass
`reverseLayout = true` when the native list/grid reverses its layout direction.
A stationary long-press near a viewport edge must not scroll: auto-scroll starts
only after the pointer moves beyond the platform touch-slop threshold. This
avoids jumping from a middle-index pickup to the beginning of a long list
before the user even drags.
Row heights and source sizes may differ; geometry is always measured, never
computed from an index, a fixed cell size or an assumed column count. Changing
the container size or column count, or switching grid ↔ list while dragging,
keeps the captured item/anchor and refreshes target bounds. The sandbox also
preserves the viewport index/offset during reorder, preventing native key-based
scroll anchoring from following a moved first-visible item. The shadow retains
its pickup size until landing on the new layout's measured source bounds.

`LauncherFlexibleLayoutTest` covers unequal-height list rows, grid → list during
a drag, and list → grid with a subsequent column-count change. Consumers still
own the layout's spans and insertion policy; use `LauncherGridGeometry` with
the actual cells for nearest-slot selection across gaps or custom arrangements.

## Application migration checks

The app now uses Launcher instead of compose-dnd for Provider / Provider-folder,
Provider-model, MCP, and Speech lists. Launcher does not attempt to reproduce
unneeded legacy DnD features. The following **device** tests verify that the
required user interactions work; the Providers migration also has a dedicated
hard-case test suite (\`ProviderLauncherMigrationDeviceTest\`).

| Requirement | Device coverage | Acceptance |
| --- | --- | --- |
| Accurate, responsive drop in a long collection | `LauncherFeatureParityDeviceTest`: 48-item grid, indices 2→4, 22→24, 44→46, plus a 48-item lazy list near the end | Final stable-key order is exact, callback occurs within two Compose frames of release, no duplicate commit, and overlay disappears after landing |
| No writes on cancel | `LauncherFeatureParityDeviceTest.cancellingDragRollsBackWithoutPersisting` | Original order and membership restored; zero persistence callbacks |
| Configurable behavior | `LauncherFeatureParityDeviceTest.groupingPolicyCanBeChangedDuringDragWithoutRemountingHost`; `LauncherGesturePolicyDeviceTest`; `LauncherItemDropPolicyDeviceTest` | Activation/style, join-vs-reorder regions and live policy changes work without recreating the gesture owner |
| Layout, geometry, folder and auto-scroll | `LauncherFlexibleLayoutDeviceTest`, `LauncherDragCollectionGeometryDeviceTest`, `LauncherDragDeviceTest`, `LauncherHostInteractionDeviceTest` | Measured slots, RTL, variable-height rows, list/grid transitions, cross-folder transfers and disposal remain correct |
| Accessible touch targets and semantic navigation | `LauncherAccessibilityReadinessDeviceTest` | At least 48dp hit areas, readable labels, folder semantic click **and a move/reorder action operable without dragging** |
| Comparison against existing library | `ProviderDropLatencyDeviceTest` (existing DnD) and `LauncherFeatureParityDeviceTest` (launcher) | Collect logs on the **same device/build** for beginning/middle/end; check release-to-callback frame counts and overlay lifetime |

**Known blocker:** the sandbox does not yet expose a semantics-based reorder
action for TalkBack/keyboard-only users. The
`screenReaderCanMoveATileWithoutPerformingATouchDrag` test is an intentional
acceptance gate and is expected to **fail** until an actual accessible move
path is wired through the host's transaction/persistence callbacks. A drag
shadow disappearing from the semantics tree is not a substitute for this path.
Do not skip the test and claim accessibility readiness.

The frame-count assertion checks the **drop callback** (not screen refresh
rate, input latency or display jank). Compare the emitted
`LauncherMigrationGate` and `ProviderDropLatency` logs and, before
migration, profile frame pacing on the physical device with FrameTimeline
or Perfetto. Passing tests only on synthetic in-memory items does not verify
production database operations, folder policy or list recomposition cost.

Run on one device after explicitly authorizing the Android build/test:

```sh
export ANDROID_SERIAL='<adb-device-serial>'
./gradlew --offline --max-workers=1 :app:assembleDebug :app:assembleDebugAndroidTest
adb -s "$ANDROID_SERIAL" install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s "$ANDROID_SERIAL" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class org.starfall.generative.extensions.launcher.LauncherFeatureParityDeviceTest,org.starfall.generative.ProviderDropLatencyDeviceTest \
  org.starfall.generative.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class org.starfall.generative.features.settings.ui.LauncherAccessibilityReadinessDeviceTest \
  org.starfall.generative.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$ANDROID_SERIAL" logcat -d -s LauncherMigrationGate:I ProviderDropLatency:I
```

The production screens are now integrated with Launcher. The feature-level
device tests use callback probes and in-memory entries: they verify exactly-once
persistence requests without writing the actual application database. The
former DnD library must not be reintroduced merely to match its advanced APIs.


### Physical-device release latency measurements (2026-10-30)

Observed on Star 4 (720×1560, 60 Hz), 48 entries, three complete repeated
instrumentation runs for each implementation. The figures below are **median
release-to-completion-callback wall times**, not FPS or measured dropped frames:

| Source | Index 2 | Index 22 | Index 44 |
| --- | ---: | ---: | ---: |
| Launcher synthetic 3-column grid | 22.4 ms | 21.3 ms | 21.0 ms |
| dnd-compose / real MCP screen | 31.3 ms | 33.5 ms | 51.4 ms |
| dnd-compose / real Providers screen | 785.4 ms | 807.8 ms | 81.8 ms |

Launcher completed its callback in sample frame 0 at all three indices in each
repeated run, but its landing overlay disappeared after 15–17 sampled frames.
The existing Providers overlay disappeared after 27, 27 and 5 frames at the
three respective indices; MCP's overlay was not available through the same
test tag, so its disappearance is **not measured**. The extended overlay tail
is a presentation animation and must not be equated with blocked commit.
All 3×6 launcher tests and the 4 host-interaction device tests passed after
fixing unintended auto-scroll on a stationary pickup.

**Interpretation:** Launcher showed more consistent release response throughout
this synthetic long grid than the old library did in the two feature screens.
However, the screens and rendering workloads are not identical; the results
do not prove that Launcher has higher *rendered FPS* or lower frame jank
during a continuous drag in production. Use actual frame-timeline profiling
on comparable card layouts before making that stronger claim. The goal is
correct feature behavior and responsive drag/drop, **not** feature-for-feature
duplication of the older DnD dependency.


### Post-migration measurements on the actual feature screens

Final physical-device run on Star 4 (60 Hz), 48 feature cards, all 6
\`ProviderDropLatencyDeviceTest\` cases passed. Release-to-callback wall times
in milliseconds (one final run; earlier measurements were from the legacy
implementation):

| Consumer | Index 2 | Index 22 | Index 44 |
| --- | ---: | ---: | ---: |
| Providers using Launcher | 21.47 | 23.10 | 18.01 |
| MCP using Launcher | 22.27 | 22.84 | 22.84 |

Each callback completed in the first observed Compose test frame. Providers
drag overlay settled after 17 sampled frames. MCP's overlay has no corresponding
test tag, so its visual lifetime was not measured. This verifies callback
responsiveness, not continuous-drag rendered FPS. The final combined device
run passed all 20 tests (\`ProviderLauncherMigrationDeviceTest\`,
\`UnifiedReorderDeviceTest\`, and \`ProviderDropLatencyDeviceTest\`).

### Production consumers

- \`ProvidersScreen\` uses one stable host for workspace, folder panel, and drag
  shadow; folder membership is applied only on accepted drop.
- \`ProviderFolderOverlay\` uses measured child targets and an explicit
  grid-exit region so a provider can leave an open folder without losing its
  gesture session; cancellation restores the initial layout.
- \`ServerListScreen\` and \`SpeechServicesScreen\` use Launcher for inline
  reordering, preserving their original view-model callbacks.
- \`ProviderModelsPane\` reorders on release and commits through its existing
  editor callbacks, without writing changes for a cancelled gesture.

The wide workspace fallback reorders only on release. An item that has
already moved underneath the pointer remains a valid drop target for its own
drag session. This avoids cyclic target changes and spurious auto-scroll on
long lists.

### Provider folder edge interactions

Providers use a 72dp horizontal escape strip at either screen edge during an
active folder-member drag, with the existing 180ms exit delay. Releasing the
item subsequently commits its placement; merely crossing the escape strip
does not persist anything. When no drag session is active, a swipe **inward**
from the left or right 56dp edge strip, or a horizontal/downward swipe starting
in the folder header, dismisses the folder. Ordinary vertical scrolling in the
folder grid is unaffected. The gestures are feature UI behavior, not part of
the generic launcher drag controller.

### Folder-to-folder reorder on entry

An item can represent a folder while simultaneously owning its own child
container. The host decides which dragged items may reorder it versus
join it: configure Launcher.itemDropTarget with acceptsReorder and
acceptsJoin separately. Use the new optional onReorderEnter callback to
move other folders in the in-memory preview as soon as the drag enters their
slot; reserve onReorderDrop for accepting the final release. A folder
drag must not be accepted by a folder's join target. Persist only on an
accepted release and restore the original order on cancellation. The
Developer Settings demo now contains separate Alpha and Beta folders with
independent child containers and tests this folder-to-folder behavior.
