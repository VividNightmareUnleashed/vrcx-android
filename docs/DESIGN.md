# VRCX for Android: phone design

The brief: translate the VRCX PC design to Android as faithfully as possible, without costing battery or load time.
Because the brief fixes the visual language, this document adds no new palette or typeface. It decides how the PC frame
maps onto a phone, and how PC-only input idioms (hover, right-click, Shift, drag, keyboard) get touch equivalents.

## 1. Visual system: VRCX's own, unchanged

| Aspect | Value (from upstream) |
|---|---|
| Themes | `system`/`light`/`dark`/`midnight` × default, blue, green, orange, red, rose, violet, yellow (`styles/globals.css`, `styles/themes/*`) |
| Surfaces | Frame `--sidebar` (#fafafa light, #171717 dark, #0a0a0a midnight); page card `--background` (light) or `--sidebar` (dark) with `1px var(--border)` and `--radius` 6px |
| Status colours | `--status-online #2ed319`, `--status-joinme #00b8ff`, `--status-askme #e97c03`, `--status-busy #c80928`, `--status-offline #737f8d`; platform and visibility colours as upstream |
| Type | Inter Variable plus bundled Noto Sans JP/SC/KR/TC, the upstream size scale (10/11/12/13/14 px), `--font-mono-cjk` for the status bar |
| Icons | Remix Icon for navigation (the same classes as the PC nav, including user-chosen folder and dashboard icons), lucide everywhere else |
| Motion | Upstream durations; sheets open in 250 ms on phones (upstream 500 ms); `prefers-reduced-motion` respected |

Phone-only tokens (defined once in `web/src/platform/android/mobile.css`):

```
--app-bar-h: 48px;     --dock-h: 56px;      --rail-w: 48px;
--safe-top/right/bottom/left   (from native insets; 0 in desktop builds)
--ime-bottom           (keyboard height)
--touch-min: 40px      (minimum hit area for icon buttons and rows on coarse pointers)
--app-chrome-h         (app bar + dock + safe areas; replaces upstream innerHeight-110 style maths)
```

## 2. Layout concept: the PC frame, folded

The PC window has three columns inside a sidebar-coloured frame: the nav on the left, the page card in the middle and the
friends panel on the right, with a 22px status bar below. On a phone the page card keeps the middle, and the two side
columns fold behind it, one on each side:

- The **nav** becomes a **dock** at the bottom (its first entries) plus the full **nav sheet** (everything else) that
  slides in from the left, where the nav lives on PC.
- The **friends panel** slides in from the right, where it lives on PC. It is one tap away from the dock, because on
  PC it is always visible.
- The **status bar** moves into the nav sheet's footer, unchanged. The app bar keeps a small game indicator, the one
  status item people check at a glance.
- The page card keeps its PC treatment (rounded card with a border, floating in the frame colour) at 6px margins.

The compact layout applies when `(max-width: 767px), (max-height: 500px)`. Wider and taller screens (tablets) keep the
upstream desktop frame, with the touch fixes from §5.

### 2.1 Phone, portrait

```
┌──────────────────────────────────────┐ ← --safe-top (status bar icons)
│ ▣ Feed                  ●  🔍  🔔•   │ app bar 48px, bg-sidebar, border-b
│                                      │   ▣ = the route's Remix icon, ● = game indicator
│ ╭──────────────────────────────────╮ │
│ │ page card (.x-container)         │ │   6px margin, radius 6px, 1px border
│ │                                  │ │
│ │   routed view                    │ │
│ │   (tables render as card lists)  │ │
│ │                                  │ │
│ ╰──────────────────────────────────╯ │
├──────────────────────────────────────┤
│  👥     ▣      ▣      ▣      ☰       │ dock 56px, bg-sidebar, border-t
│Friends Feed Locations GameLog Menu   │   icon 18px in 24px box, label 10.5px medium
└──────────────────────────────────────┘ ← --safe-bottom (gesture bar)
```

- **Dock slots:** `Friends` (toggles the friends panel), then the first three top-level entries of the user's own nav
  layout (`VRCX_customNavMenuLayoutList`, the same sanitised model the NavMenu uses), then `Menu` (opens the nav
  sheet). Defaults: Feed, Friends Locations, Game Log. A folder in one of the three slots opens its children in a
  dropdown above the dock, like the collapsed PC nav. Dashboards count as entries.
- **Active state:** the PC's active-row treatment: `bg-sidebar-accent` pill behind the icon, and the label in
  `text-sidebar-accent-foreground font-medium`. Notification dots as on PC.
- **App bar:** the title is the active nav entry's icon and label (a dashboard's name, or "Settings"). Tapping it scrolls
  the page to the top. Game indicator: hidden when no companion is paired; `--status-online` when VRChat is running,
  muted when not, hollow while disconnected. Tapping it opens Settings → PC companion. Search opens the Quick Search
  dialog. The bell opens the notification center sheet, or goes to the Notification route when the user chose the
  table layout; it shows the same unseen dot as on PC.

### 2.2 Friends panel open

```
┌──────────────────────────────────────┐
│ 👥 Friends               ●  🔍  🔔   │
│ ┌──────────────────────────────────┐ │
│ │ [🔍 Quick search…]  ⟳  🔔  ⚙     │ │ Sidebar.vue, unchanged content
│ │ Friends (12/340) │ Groups (3)    │ │   (slides in from the right, translateX)
│ │ ▾ ME                             │ │
│ │ (◉) DisplayName                  │ │   rows keep 36px avatars, min-height 52px
│ │ ▾ FAVORITES (5)                 │ │
│ │ ...                              │ │
│ └──────────────────────────────────┘ │
├──────────────────────────────────────┤
│ [👥]    ▣      ▣      ▣      ☰       │ Friends slot active
└──────────────────────────────────────┘
```

`Sidebar.vue` is mounted exactly once for the life of the layout and hidden with `transform`/`visibility`, never with
`v-if` or `display:none`, because it owns Quick Search, the notification center, the favourite-group order dialog,
EditProfileDialog and the Ctrl+K handler, and its virtualizer must stay measurable.

### 2.3 Nav sheet (Menu)

```
┌────────────────────────────┬─────────┐
│ ▣ Feed                     │ scrim   │  width min(288px, 85vw), from the left
│ ▣ Friends Locations        │         │  NavMenu.vue as on PC: folders, dashboards,
│ ▣ Game Log                 │         │  "+ New dashboard", Help, Manage
│ ▸ Favorites                │         │  (Collapse and the resize strip are hidden)
│ ▸ Social                   │         │
│ ...                        │         │
│────────────────────────────│         │
│ ●Game 1h2m ●Servers ●WS    │         │  StatusBar.vue, wrapped over several lines,
│ 12:00 UTC+9   Uptime 3h    │         │  mono font; items with no Android meaning hidden
└────────────────────────────┴─────────┘
```

The sheet closes on navigation and on back.

### 2.4 Phone, landscape (height ≤ 500px)

The dock turns into a 48px icon rail on the left, the PC's collapsed nav. The app bar shrinks to 40px, and the friends
panel opens as a right-hand panel `min(360px, 45vw)` wide instead of full width.

```
┌────┬──────────────────────────────────────────┬──────────────┐
│ 👥 │ ▣ Game Log                    ● 🔍 🔔     │              │
│ ▣  │ ╭──────────────────────────────────────╮ │  friends     │
│ ▣  │ │ page card                            │ │  panel       │
│ ▣  │ ╰──────────────────────────────────────╯ │  (optional)  │
│ ☰  │                                          │              │
└────┴──────────────────────────────────────────┴──────────────┘
```

## 3. Content translation rules

### 3.1 Tables → card lists

`DataTableLayout.vue` gets an opt-in card mode (`mobileMode: 'auto'|'table'|'cards'`, default `auto`), used in compact
layout when any column declares `meta.mobile`. Cards reuse each column's own cell renderer (`FlexRender`), so badges,
avatars, links and colours look exactly like the PC cells. Slots:

```
╭──────────────────────────────────────────────╮
│ [leading]  title  titleSuffix       trailing │  leading = avatar/icon, trailing = time
│            badge · badge                     │
│            body (line-clamp 2)               │
│            footer (muted, 11px)      actions │  actions = kebab / explicit buttons
╰──────────────────────────────────────────────╯
```

- Row min-height `--touch-min` (40px). Rows get `select-none` and `-webkit-touch-callout: none` so long-press opens the
  row's context menu rather than text selection.
- The PC header context menu (sort, columns, page size) becomes a **View options** button in the toolbar, opening a
  sheet. Column drag-reorder and resize handles are disabled on coarse pointers.
- Pagination in compact layout: no page-size selector (it moves into View options), sibling count 0, 32px items.
- Tables inside dialogs without `meta.mobile` stay tables with horizontal scroll.

### 3.2 Dialogs

- **Entity dialogs** (User, World, Avatar, Group, plus Group moderation and Previous instances through
  `MainDialogContainer`) become full-screen pages. A 48px dialog app bar holds back (crumb history), the current crumb
  title and close. Below it is one vertical scroller: the 308px left rail (profile card) stacked above a sticky,
  horizontally scrollable `TabsUnderline` strip, then the tab content. Entity cards in grids become two per row.
- **Other dialogs** become full-screen pages with a sticky footer (the generic rule on `[data-slot=dialog-content]`).
  Small confirmations (alert dialogs, Prompt, OTP, choose-favourite-group, invite confirm) stay centred cards
  (`data-mobile="card"`).
- **Sheets** become full width.
- Opening a dialog on a phone never pops the keyboard unless the dialog exists to take text input (OTP, Quick Search,
  Prompt).

### 3.3 PC input idioms → touch

| PC idiom | Phone equivalent |
|---|---|
| Right-click context menu | Long-press (reka's 700 ms timer), plus an explicit kebab/dropdown wherever the menu holds primary actions (user rows, game-log rows, instance rows, share/moderation submenus flattened) |
| Hover-revealed buttons (`opacity-0 group-hover:opacity-100`) | Always visible on coarse pointers (`pointer-coarse:opacity-100`) |
| Tooltip-only information | Long-press shows the tooltip (TooltipWrapper). Information users need (exact dates, full log text, notification details, action labels) is shown inline in compact layout |
| Hover cards | Open on tap (`enableTouch`) |
| Shift-held instant delete / leave-group | A "quick actions" toggle in the table toolbar sets the same `ui.shiftHeld` state |
| Keyboard shortcuts (Ctrl+K, Ctrl+D, Alt+arrows, Ctrl+R) | App-bar search button; Direct Access nav entry reads the clipboard; pagination buttons; pull-to-refresh is not added (the refresh buttons stay) |
| Drag to reorder (nav editor, favourite groups) | Kept, with dnd-kit's touch sensor delay of 250 ms; a hint line says "long-press and drag" |
| Mouse-wheel zoom on images | Pinch-zoom and double-tap in FullscreenImagePreview (`touch-action: none` on the image) |

### 3.4 Screens with bespoke layouts

The phone rules:
- toolbars wrap into two rows: search full width first, then filters as a horizontally scrollable toggle strip or a
  Select;
- date ranges go into a bottom sheet with one month;
- dashboards stack their panels vertically (widgets ~45vh);
- charts size from `--app-chrome-h` instead of `innerHeight - 110`;
- the Favorites group list moves into a left sheet opened from the toolbar;
- Settings rows wrap label and control;
- Login is a single scrollable column.

## 4. Empty and unavailable states

- Game Log, Player List and the game-log dashboard widgets show a companion-aware empty state when no companion is
  paired or connected: "Game log needs the VRCX Companion on your PC", then one action, "Set up PC companion", which
  opens Settings → PC companion. Location rows derived from the API still appear, so the empty state only shows when
  the list is actually empty.
- Hidden PC-only features are removed completely, not shown disabled: no dead toggles.

## 5. Tablets (desktop frame)

On tablets the upstream `MainLayout` frame is used unchanged. The touch rules from §3.3 apply there too through
`pointer-coarse:` variants. The nav resize strip and the table column resize handles are disabled on coarse pointers.

## 6. Back button

`window.__vrcxAndroid.handleBack()` returns `true` when it handled the press:
1. If a reka dismissable layer is open (`[data-dismissable-layer]`, topmost last in DOM order):
   - if it is the main entity dialog and `$pinia.ui.dialogCrumbs.length > 1`, call `$pinia.ui.jumpBackDialogCrumb()`;
   - otherwise dispatch `keydown` `Escape` on `document`, so modal promises (confirm/prompt/OTP) resolve normally.

   A layer that blocks Escape (for example the database upgrade dialog) is left open and the press counts as
   handled.
2. Else, if the friends panel or the nav sheet is open, close it.
3. Else, if the router has history (`history.state.back`), `router.back()`.
4. Else return `false`. Native code then calls `moveTaskToBack(true)`; the app never finishes itself on back.

## 7. Performance rules for UI work

- No `backdrop-filter` on overlays in compact layout. No new shadows or animations beyond the table in §1.
- `KeepAlive` in the phone layout gets `max=6` (upstream keeps every view alive).
- Keep upstream virtualization. Card mode must render only the current page of rows.
- Hidden panels use `transform` and `visibility:hidden`, so they are not painted but stay mounted.
- No layout reads in scroll handlers. The compact-mode media query is a single shared `useMediaQuery` instance.

## 8. Review against the brief

What was considered and rejected, and why:
- **A Material 3 bottom navigation bar with fixed destinations and Material colours.** It would read as a generic Android
  app, not VRCX. The dock uses the user's own VRCX nav layout, Remix icons, and the PC nav's active and dot styling.
- **Edge-swipe drawers.** These conflict with Android gesture navigation, which uses both screen edges for back.
  Panels open from explicit buttons instead.
- **Dropping the page card for edge-to-edge content.** The card floating in the frame colour is the most recognisable
  VRCX trait, so it stays at 6px margins.
- **A permanent status strip above the dock.** It would cost 22px of height on every screen. It lives in the nav sheet
  footer, and the one glanceable item (game state) sits in the app bar.
