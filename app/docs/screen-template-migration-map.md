# Screen template migration map

This document records the screen-shell inventory for PR #62. The reusable
screen API lives under `shared/ui/screens/`; feature state and feature-specific
content remain in their owning packages.

## Implemented API

| API | Responsibility |
| --- | --- |
| `ScreenTemplate` | Secondary-screen AppBar, Back callback, edge-to-edge padding, and one typed content contract. |
| `ContentPreview` | Read-only vertically scrollable content. |
| `ContentVisualEditor` | Editor content with IME/navigation-bar handling and caller-owned padding. |
| `ContentTextEditor` | Full-content text-editing area with IME/navigation-bar handling. |
| `ContentList` | Collection content with centralized safe-area padding and loading/empty hooks. |
| `ScreenTransitionEffect` | Forward (right-to-left) and back (left-to-right) screen transitions with optional system Back. |
| `MainScreenTemplate` | Deliberately minimal marker for Chat; it does not add a secondary-screen shell. |

Content types are scope methods on `ScreenTemplateScope`. The scope has an
internal constructor, and the shell checks that exactly one content method was
attached. This prevents standalone content wrappers and mixed content kinds.

## Adopted screens

| Screen or flow | Content kind | Notes |
| --- | --- | --- |
| App data | `ContentList` | Existing transfer, repair, and confirmation behavior retained. |
| Developer options | `ContentList` | Existing settings cards and launcher action retained. |
| MCP server list | `ContentList` | Grid/list toggle, drag reorder, empty state, and dialogs retained. |
| Remote machines list | `ContentList` | Grid/list toggle, machine actions, and dialogs retained. |
| Speech services list | `ContentList` | Grid/list toggle, reorder, editor transition, and playback actions retained. |
| Feature Tools | `ContentList` | Existing tabbed tool configuration is still feature-owned. |
| Customization | `ContentVisualEditor` | Profile image, appearance controls, dialogs, and persistence remain feature-owned. |
| Image/video tool settings | `ContentVisualEditor` | Validation, JSON editing, and save/discard behavior remain feature-owned. |
| App settings | `ContentVisualEditor` | Existing two-tab settings content remains feature-owned. |
| About | `ContentPreview` | Release lookup and external links remain feature-owned. |
| Targeted editor loading/error state | `ContentPreview` | Existing retry and cancellation behavior retained before the editor mounts. |
| Chat | `MainScreenTemplate` | ChatAppBar, scrim, streaming, and composer are unchanged. |

## Retained specialized shells

Some screens intentionally keep their feature-specific AppBars because their
header changes with nested state or owns specialized interaction: Storage
(selection mode and media viewer), Providers (folder and drag state), terminal,
and editor pages with nested tabs. Their Back ownership now uses the shared
`shared/ui/screens` transition local. The existing retained navigation host and
retained nested-editor transition were moved into the same package so there is
one screen-transition implementation.

## Verification

Verified on 2026-11-04 (Star 4, Android 11; ADB via local forwarding):

- `:app:assembleDebug` and `:app:assembleDebugAndroidTest`: successful.
- `ScreenTemplateTest`: 9/9 local unit tests passed.
- `RetainedNavHostTest` and `RetainedScreenContentTest`: 5/5 local unit tests passed.
- `ScreenTemplateDeviceTest`: 4/4 physical-device UI tests passed after fixing a `ContentList` recomposition regression.
- Additional scope-related UI regressions: 14 passed; the 15th test and two remaining tests were interrupted by loss of the ADB forwarding connection, not recorded as passed or failed.
- `scripts/check_architecture.py`, `scripts/check_extensions.py`, and `git diff --check`: passed.

Remaining: a full screen-by-screen UI review (including dark/light/AMOLED and insets), any outstanding broader regressions, and merge-readiness verification after synchronization with `main`.
