# Changelog

## [1.5.1] - 2026-04-25

### Fixed

- **Release token privacy** — Release builds no longer install OkHttp
  request logging, and the WebSocket connection now uses a dedicated client
  so the pipeline `auth` token cannot be printed through request URLs.
- **Authenticated image loading** — Coil uses a separate image client that
  keeps VRChat auth cookies for protected media without routing image 401s
  through the global session-expiry path.
- **Account-switch teardown** — Logout and account changes now reset
  account-scoped runtime state, cancel in-flight deduplicated requests, and
  guard late repository refreshes so previous-account data cannot reappear in
  the next session.
- **Login, search, and gallery edge cases** — Email-only 2FA submits through
  the email OTP flow, canceled searches no longer publish stale failed states,
  and large gallery uploads are rejected before the app reads the full file
  into memory.
- **WebSocket and cookie concurrency** — Reconnect callbacks are guarded by a
  connection generation, and cookie storage is synchronized across OkHttp
  callbacks and logout.
- **Notifications, feed, and backup safety** — Invite payloads now match the
  documented VRChat request bodies, feed lists order by local row id, Android
  15 boot notifications create their channel before posting, and backup rules
  exclude account-scoped database and DataStore files.
- **Release evidence** — The release scanner verifies explicit APKs with
  `apksigner` and rejects stale, debug, or unsigned artifacts unless an
  existing signed APK is intentionally scanned with `--skip-build`.

## [1.5.0] - 2026-04-17

A broad stability, correctness, and polish pass on top of the 1.4.x feature
set. If your friends list is busy, you were seeing the bulk of your feed
activity get duplicated; if you opened groups or worlds with a lot of
instances, you were watching the screen hang; if you were an admin in a
group, you were seeing Remove buttons that never worked. Those are all
gone. On top of that, custom profile pictures now show up in every list
instead of just the detail screen, deep links from VRChat's website land
where you expect them to, and the Charts page behaves sensibly on
low-activity ranges.

### New

- **Friend-request management** — You can now explicitly decline an
  incoming friend request and cancel an outgoing one from the relevant
  profile screens, instead of having to visit the VRChat website to
  resolve either.
- **Group member removal** — Admins with the `group-members-manage`
  permission can remove members directly from group detail. The control
  is gated on the permission itself (not just "has a role"), so regular
  members don't see a button that would only return a 403.
- **Activity History and Friends Roster rebrand** — The two Android-scope
  utility screens previously labelled "Game Log" and "Player List" are
  now surfaced as "Activity History" and "Friends Roster" across the
  profile shortcuts and Tools quick links. Existing nav routes and
  notification deep links continue to work.
- **Settings sign-out** — Settings now includes a Privacy section with a
  Sign Out control that cleanly tears down auth state, clears cached
  favorites, and stops the background websocket service — matching the
  profile sign-out path in a more discoverable location.

### Improved

- **Profile pictures in every list** — Feed, Friends, Friends Locations,
  Favorites, Search, Group Detail, Player List, and Dashboard all honor
  `profilePicOverride` now. Users with a custom profile picture but a
  default VRChat avatar no longer render as the generic robot
  everywhere except their detail screen.
- **Deep links from VRChat's website** — `https://vrchat.com/home/...`
  links now land on the correct in-app destination no matter how many
  trailing path segments VRChat adds. That means URLs like
  `/home/world/{id}/info`, `/home/group/{id}/posts/{postId}`, or any
  future subpath route cleanly instead of dumping users on the feed.
- **Notifications inbox** — Notifications persist to the local database
  and render instantly on cold start from cache, so you see the most
  recent inbox state before the network round-trip completes. V1 and V2
  notifications continue to share a unified list.
- **Failed-request caching** — 404 and 403 responses are now cached
  globally through the request deduplication layer, so retries and
  re-renders don't refetch endpoints the server has already told us
  aren't available.
- **Busy-world loading** — The World Detail screen caps active-instance
  detail lookups at 20 and fetches them in parallel, so popular worlds
  with hundreds of advertised instances stop hanging the screen on
  sequential round trips.
- **Charts screen** — Daily activity now uses a custom bar chart that
  renders correctly for any data density — sparse days, a full week,
  or a full 90-day range. The range filter and metric cards stay
  visible on empty ranges with an inline "No activity in this range"
  message, so you can still change the range without backing out of
  the screen. Most Visited Worlds rows now have a visible bar track.
- **Favorites loading** — The loading spinner stays up through the
  initial preload attempt, so you no longer see a false "No favorites"
  flash before the fetches actually complete on a slow or flaky
  network. Adding a favorite from any screen (e.g. Avatar Detail)
  patches the relevant bulk cache in place instead of waiting for the
  next screen entry to refresh.
- **Avatar and world list lifecycle** — The whole app switched its
  state collection to `collectAsStateWithLifecycle`, which trims wasted
  work when screens aren't visible and generally makes the app lighter
  on battery in the background.
- **Network retry behavior** — 429 (rate-limited) responses respect a
  bounded retry delay instead of letting the server pin the app into a
  multi-minute backoff; 401 (unauthorized) responses route through
  `AuthRepository` so session teardown is centralized.
- **Dashboard layout** — The top bar stays visible while you scroll;
  it's no longer folded into the scrollable list. The underlying flow
  combine also uses stable composition for less flicker during updates.
- **Group detail loading** — Group metadata, members, posts, and
  instances now load in parallel rather than sequentially, so opening
  a group feels closer to instant.
- **Search screen** — The avatar search "VRCHAT" source is now labelled
  "My Avatars" to make its scope obvious.
- **Destructive-action confirmations** — Blocking a user, unfriending a
  user, and similar irreversible actions from User Detail now ask for
  confirmation before firing, matching desktop VRCX's prompts.

### Fixed

- **Duplicate feed entries** — This was the big one. Feed rows for the
  same friend event (typically "Moved to <world>") were sometimes
  appearing twice at near-identical timestamps, especially after
  reconnects, logout/login cycles, or for friends who hop between a
  world and "private" frequently. The repository-level dedup was only
  keeping a 10-second in-memory window, and it was cleared entirely on
  re-login. The fix now (a) orders the "latest row" lookup by the
  monotonic row id rather than a text timestamp, (b) matches on
  content *and* a 5-minute recency window, and (c) remembers that a
  friend briefly passed through an unpersisted `offline`/`private`
  state via any of the three pipeline event types so a real
  `wrld_X → private → wrld_X` revisit is distinguished from a
  pipeline re-emit.
- **Session resume on stale cookies** — Opening the app with a stored
  but server-side invalidated cookie used to loop between
  `LoggingIn` → `Error` on every launch. The interceptor-driven 401
  handler now tears down the cookie, dedup cache, favorites, and
  background websocket service, so the next launch starts fresh at
  the login screen instead of retrying a dead session.
- **Sign-out completeness** — The Privacy and Profile sign-out paths
  (and the interceptor-driven 401 path) all route through a single
  `AuthRepository.logout()` that invalidates the session server-side,
  clears local state, and stops the websocket foreground service.
  You can no longer end up with a running background notification
  tied to a logged-out account.
- **Android 15 boot reconnect** — On Android 15, reboot handling
  surfaces a "tap to reconnect" notification. Previously that
  notification fired unconditionally on every boot for every user.
  It now only fires if you have the background service enabled
  and you're actually logged in, so logged-out users and users who
  disabled the background service stop seeing reboot spam.
- **Favorite additions and deletions** — Adding a world or avatar
  favorite from anywhere in the app now immediately shows up in the
  Favorites list with the correct name and thumbnail, even if you're
  already sitting on the Favorites screen when the add happens.
  Deletions drop the entity from the bulk cache in the same step, so
  the list updates without a refresh.
- **Profile and Tools shortcut labels** — The "Shortcuts" list on
  Profile and the "Quick Links" on Tools now say "Activity History"
  and "Friends Roster" instead of the old "Game Log" / "Player List"
  text, matching the Android-scoped naming used elsewhere in the app.
- **Group members kick button** — Previously the kick button was
  enabled for any member (it used role presence as the admin check),
  and tapping it threw an error snackbar every time for regular
  members. Now it's only shown to users whose role actually carries
  the remove-members permission.
- **Gallery uploads** — The uploaded filename and extension match the
  actual MIME type of the picked image, rather than whatever extension
  happened to be on the source URI, which previously caused VRChat to
  reject otherwise-valid images.

## [1.4.1] - 2026-04-10

### Improved

- **Release transparency** — Added a local `.env`-driven release evidence flow that signs the APK, computes its SHA-256, and builds a VirusTotal report bundle for release publishing
- **Release documentation** — Documented the signed-build and VirusTotal workflow so contributors can publish a verifiable APK hash and linked scan report with each release

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
