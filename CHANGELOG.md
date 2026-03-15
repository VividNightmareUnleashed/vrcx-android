# Changelog

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