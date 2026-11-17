# Changelog

## [2.1.0] - 2026-11-17

Brings VRCX up to its v2026.11.14 release.

### Added

- **What's new in VRCX v2026.11.14.** Login with a QR code, group roles and
  permissions, group and avatar gallery editing, moderation reports, group
  invites and blocked groups under Tools, renaming instances, language ratios
  in the instance tooltip, memos in the player list, in-app password reset,
  and the rest of upstream's
  [release notes](https://github.com/vrcx-team/VRCX/releases/tag/v2026.11.14).
- **QR code login on the phone itself.** A phone can't scan its own screen, so
  the QR login has an Open link on this phone button: approve the login in
  your browser and come back, and VRCX signs in. Scanning from another device
  still works.
- **Now playing.** The game log picks up the ProTV "now playing" lines worlds
  can write, as on PC.
- **Group roles on phones.** The new group roles dialog stacks its list and
  editor on phone screens.
- **VRCVideoCacher prompt.** When a YouTube video fails with "Sign in to
  confirm", VRCX offers VRCVideoCacher's store page, which opens in the Steam
  app when it's installed.

### Changed

- **No purchasing on Android.** The new VRChat Store and Purchase History
  tools, store tabs, marketplace search tab, Buy buttons and free store item
  prompts are hidden. Marketplace listings stay visible.
- **Animated images pause in the background.** Animated avatars, nameplates
  and profile effects pause, and status polling slows, while VRCX isn't on
  screen.

## [2.0.1] - 2026-10-21

### Fixed

- **Scan QR code did nothing on some phones.** Where Google Play services
  can't run its QR scanner (common on phones sold in China), VRCX now scans
  with its own camera screen instead, after asking for camera access. If
  camera access is refused, it says so and points you to Search or Enter
  address.
- **Empty band at the top of profiles on phones.** User, world, avatar and
  group dialogs no longer add the status bar height twice.

## [2.0.0] - 2026-09-26

VRCX Android is rebuilt from scratch. Instead of a separate app that imitates
VRCX, it now runs VRCX itself: the desktop app's own frontend, with its
features and its look, on a native Android host. The 1.x app lives on in the
`legacy` branch.

This is an update to 1.x: it installs over 1.7.0 and keeps the same signing
key. Sign in again after updating. Data from 1.x is not carried over and is
removed on first start; to bring your history along, import your PC's VRCX
database instead.

### Added

- **Everything VRCX does with the VRChat API.** Friends list and Friends
  Locations, the feed, notifications and invites, user, world, avatar, group
  and instance dialogs, search, favorites, the gallery, moderation, charts and
  tools, with VRCX's local database behind them.
- **The PC layout folded for a phone.** An app bar, a dock with Friends, your
  first three navigation entries and Menu, the friends panel sliding in from
  the right, and tables that turn into cards. Tablets get the desktop layout
  with touch-sized controls.
- **Windows companion.** A small tray app that sends VRChat's log and whether
  VRChat and SteamVR are running to the phone over your local network, for the
  game log, player list, current instance and the in-VR / outside-VR
  notification filters. Paired by QR code or a 10-character code, over TLS
  pinned at pairing; it never talks to anything outside your network.
- **Import your PC data.** Settings → PC companion → Import VRCX database
  brings over `VRCX.sqlite3` and `VRCX.json`, and Export database saves a copy.
- **Background mode.** Keeps the VRChat connection open while you're signed in
  or a companion is connected, without wake locks or polling. On by default,
  off in Settings → System. Start on boot is optional.
- **A new icon.** The VRCX bubble with the Android robot peeking over it,
  with a version for themed icons.
- **Android integrations.** VRCX notifications as Android notifications,
  text-to-speech, `vrchat://`, `vrcx://` and vrchat.com links, sharing, the
  Screenshot Metadata tool on your phone's photos, and prints, stickers and
  emoji saved to Pictures/VRCX.

### Not on Android

The VR overlay and wrist feed, Discord Rich Presence, launching VRChat on the
PC, registry backup and the PC folder shortcuts. They're hidden rather than
left broken.
