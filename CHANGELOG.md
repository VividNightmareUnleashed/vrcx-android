# Changelog

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
