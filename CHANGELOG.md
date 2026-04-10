# Changelog

## [1.4.0] - 2026-04-10

### New

- **Dashboard** — Added a new dashboard view for checking your recent VRChat activity and friend-related feed data in one place
- **Game Log** — Added a dedicated game-log screen for browsing feed-backed history with the same account-aware data used elsewhere in the app
- **Player List** — Added a player-list utility screen from Profile for quickly reaching another common desktop-style workflow on Android
- **Tools screen** — Added a new tools hub with quick links to utility screens and an ID jump flow for opening users, worlds, avatars, and groups directly
- **Group posts** — Group detail now shows group posts in addition to core group metadata, making groups feel much more complete in the Android client

### Improved

- **Search** — User search now supports bio-based search and last-login sorting, world search supports mode and tag filtering, and avatar search supports remote provider sources
- **Remote avatar providers** — Remote avatar searches now deduplicate duplicate provider results and report provider failures more clearly instead of silently behaving like a zero-result search
- **Favorites** — Favorite loading is more complete and more resilient, with better preferred-tag selection and safer fallback behavior when favorite metadata endpoints are temporarily unavailable
- **Group membership flows** — Joining and leaving groups is now more reliable when refresh requests fail after the mutation succeeds, so the app no longer gets stuck showing guessed membership state
- **Feed-backed screens** — Feed and log style views now respect a configurable feed-history limit from Settings, making it easier to trade history depth for a lighter local dataset
- **Profile shortcuts** — Profile now exposes the new utility screens directly, which makes the expanded app surface much easier to discover
- **Credits and version display** — The credits screen now reports the real app version from the build instead of a hardcoded string

### Fixed

- **Cross-account favorites cache** — In-memory favorites state is cleared correctly across auth session changes, preventing favorite data from bleeding between accounts in the same process
- **Filtered world pagination** — World filtering no longer strands matching results on later backend pages when browsing non-search world modes
- **Remote avatar error states** — Invalid provider URLs, empty responses, malformed JSON, and HTTP failures now surface as actionable errors instead of a misleading `No results found`
- **Group detail cache correctness** — A failed follow-up refresh after join or leave no longer poisons the cached group record, so later visits refetch the authoritative server state
- **Android 15 reboot behavior** — Reboot handling now favors a clear reopen notification path that better matches current Android background-execution rules
- **Favorites fallback behavior** — Adding a favorite no longer depends on every metadata lookup succeeding before the request can be sent
- **Regression coverage** — Added repository-level tests for group membership cache invalidation and remote avatar provider failure handling

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
