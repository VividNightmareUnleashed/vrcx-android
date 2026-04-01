# Changelog

## [1.3.0] - 2026-04-01

### Improved

- **Session restore** — Auth cookies and remembered session state now live in secure storage and are restored before background WebSocket startup, making process-death recovery much more reliable
- **Notifications inbox** — Notification V2 actions, invite/request handling, and inbox loading are more resilient when the app resumes from a cold start or a failed fetch
- **Request deduplication** — Concurrent GET requests now share a true in-flight result instead of serially rerunning the same network call
- **Boot reconnect** — Reboot restore now checks both secure and legacy auth state and uses a WorkManager reconnect path on Android 15+ when the background service setting is enabled
- **Local notifications** — Instance-closed notifications are tracked locally and can be dismissed without calling remote hide endpoints
- **Regression coverage** — Added tests for recovery-code OTP routing and local instance-closed notifications

### Fixed

- **Recovery code login** — Eight-digit recovery codes now work end to end from the login flow and use the dedicated VRChat OTP verification endpoint instead of the authenticator-code endpoint
- **Friend Log parity** — Friend Log now backfills current friends on sync and records friend, unfriend, display-name, and trust-level changes as they happen
- **Friend status alerts** — Status change notifications now compare against the pre-update friend state so they fire reliably again
- **Notification parity** — Notification V2 events and instance-closed events now surface through the Android notification flow instead of being silently dropped

## [1.1.0] - 2026-03-15

### New

- **World Detail screen** — Tap any world in Search or Friends Locations to see full details: banner image, description, capacity, platform support, tags, and active instances
- **Avatar Detail screen** — Tap any avatar in Search or My Avatars to see details with Select Avatar and Favorite actions
- **Charts screen** — View your instance activity history with daily visit charts and most-visited worlds (accessible from Profile)
- **Invite system** — Send invites and request invites directly from User Detail
- **Profile editing** — Edit your VRChat status and bio from the Profile screen
- **User notes** — Add and edit personal memos on any user's profile, saved locally

### Improved

- **Friends Locations** — Now shows world names, thumbnails, and capacity instead of raw IDs. Added Online/Favorite/Active segment tabs and search
- **Notifications** — V1 and V2 notifications merged into one list. Added type filter chips and Accept/Decline actions for invites (not just friend requests)
- **Search** — Added pagination (Previous/Next) and all result types (worlds, avatars, groups) are now tappable to view details
- **Feed** — Added search bar, VIP-only filter, and Load More for browsing older entries
- **Friends** — Added sort options (Name, Last Seen, Trust Rank) and VIP filter
- **Friend Log** — Added search and type filter chips (Friend, Unfriend, DisplayName, TrustLevel)
- **User Detail** — Redesigned with Info/Groups/Worlds tabs, favorite star, invite buttons, show/hide avatar moderation, and memo editing
- **Favorites** — Now shows actual names, avatars, and thumbnails instead of raw IDs. Added unfavorite with confirmation
- **My Avatars** — Added search, visibility filter (Public/Private), and platform filter (PC/Quest). Tap now opens detail instead of immediately selecting
- **Moderation** — Expanded from 2 types to 6 (Block, Mute, Hide Avatar, Show Avatar, Interact Off, Interact On) with search and removal confirmation
- **Settings** — Version number now updates automatically. Added Friend Location and Friend Status notification toggles
- **Login** — Added password visibility toggle and Remember Me option

### Fixed

- Friends Locations no longer shows empty when friends are in public instances
- Friend active events now handled correctly (VRChat API field name inconsistency)
- Notification updates no longer corrupt existing notification data
- Notification badge count no longer drifts over time
- World name resolution no longer loses data under concurrent loads
- Charts no longer crashes with empty activity data

### Real-time sync improvements

- Friend presence now tracks traveling state, platform (PC/Quest/iOS), and instance details
- Notifications from other devices sync automatically (mark as read, dismiss, respond)
- Gallery auto-refreshes when uploading content from VRChat
