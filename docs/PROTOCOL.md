# VRCX Companion protocol, version 1

The Windows companion (`companion/`) streams VRChat log files and two process flags to paired phones on the same local
network. It is a read-only data source. It never writes to the VRChat folder or registry, never accepts commands other
than the ones listed here, and never opens outbound connections. The phone (`android/.../companion`) is the client.

This document fixes the wire format. How the phone mirrors and parses the log files is in `docs/ARCHITECTURE.md` §8.

## 1. Network scope

- "Local address": IPv4 `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16`, `127.0.0.0/8`,
  `100.64.0.0/10` (CGNAT, used by some home mesh setups); IPv6 `fe80::/10`, `fc00::/7`, `::1`; IPv4-mapped
  IPv6 of the above.
- Companion: the TCP listener accepts a connection, and the discovery responder answers a request, only when both hold:
  - the peer has a local address;
  - the connection or request arrived on an interface whose network Windows categorizes as **Private** or **Domain**
    (Network List Manager, `INetworkListManager`), or on loopback. For a TCP connection the interface is the one that
    owns the local address the connection arrived at; for a discovery request it is the interface the datagram arrived
    on (`IP_PKTINFO`). A **Public** network is refused unless the user chose "Allow on this network" for it in the
    Status window; that choice is stored by network profile id in `settings.json` (`allowedPublicNetworks`). A network
    Windows has not identified, or an interface that belongs to no network, is refused. When Windows cannot report
    categories at all (no answer since start), only the address check applies and the Status window says so; once it
    has answered, a later failure keeps the last answer.

  Refused peers are closed without any data sent (TCP: reset). Many home Wi-Fi networks are Public by default, so the
  Status window lists the connected networks, explains how to set a home network to Private (Windows Settings >
  Network & internet > the network's properties > Network profile type) and offers the opt-in; the tray shows a notice
  the first time a phone is ignored on a network, and the pairing window warns when the PC's main network is Public.
  Categories are only read, never changed.
- Companion connection budgets: at most **3** connections per source address may be in the handshake (TLS plus the
  `pair`/`auth` message, which must arrive within **10 s** of the accept); a further one from that address is closed at
  once. At most **32** handshakes in total: a further connection replaces the oldest handshake of the address that
  holds the most. Authenticated sessions count against a separate budget of **16**, so sockets that never
  authenticate cannot lock paired phones out. A peer that authenticates while every session slot is taken is closed
  without `authOk` (after a successful `pair`, right after `paired`, so the phone keeps the pairing and reconnects).
- The companion makes no outbound connections of any kind: no update checks and no telemetry.
- Phone: connects only to local addresses. A QR code or manual address pointing anywhere else is refused with an
  error.
- Windows Firewall: the companion offers to add inbound rules (TCP port and UDP discovery port) restricted to profile
  `Private` and remote address `LocalSubnet`. This needs one elevation (`netsh advfirewall`); it is optional because
  Windows also prompts on first listen.

## 2. Ports and discovery

- TCP `49460` (session). UDP `49461` (discovery). Both can be changed in the companion's settings file; the
  discovery reply announces the TCP port.
- Discovery request (phone → UDP broadcast `255.255.255.255:49461` and each interface's directed broadcast), UTF-8
  JSON of at most 1024 bytes:
  `{"t":"vrcx-discover","v":1}`
- Discovery reply (companion → unicast to the request's source address and port, under the conditions of §1, at most
  20 replies per second):
  `{"t":"vrcx-companion","v":1,"id":"<companionId>","name":"<machine name>","port":49460,"fp":"<fingerprint>","pairing":<bool>}`
  - `companionId`: random UUID generated with the identity (§3) and persisted.
  - `fp`: base64url (no padding) of SHA-256 over the DER `SubjectPublicKeyInfo` of the companion's TLS certificate.
  - `pairing`: whether a pairing window is open right now.
- The Android emulator and some guest networks block broadcast, so the phone also supports manual `host[:port]` entry.

## 3. Transport security

- TLS 1.3 (1.2 allowed) over TCP. The companion's certificate is a self-signed ECDSA P-256 certificate
  (CN = `VRCX Companion <machine>`, valid 20 years), created on first run.
- Key storage: the private key is a persisted, **non-exportable** CNG key in the user's key store (Microsoft Software
  Key Storage Provider, which protects it with DPAPI for the user), created once under a fixed name derived from the
  path of `identity.bin` and opened by that name on every start. `%APPDATA%\VRCX-Companion\identity.bin`, protected
  with DPAPI (CurrentUser), holds the companion id, the certificate (DER) and the key's name and provider, not the key.
  An `identity.bin` or key that cannot be read is replaced by a new identity with a new companion id (phones pair
  again).
- The phone accepts the server certificate **only** if its SPKI SHA-256 equals the expected `fp` (§5.2.1). Hostname
  verification is not used. No client certificate.
- Everything below runs inside TLS.

## 4. Framing

Every frame is:

```
uint32 BE  length   // number of bytes that follow (type + payload), ≤ 1 048 576
uint8      type     // 0x01 = control (JSON), 0x02 = file data
payload
```

- Control (`0x01`): UTF-8 JSON object with a string field `t` (message type).
- File data (`0x02`):
  ```
  uint16 BE  headerLength
  header     UTF-8 JSON: {"name":"output_log_2024-01-01_09-59-00.txt","fileId":"<id>","offset":<int64>,"len":<raw length>,"z":0|1}
  body       raw bytes (z=0) or raw DEFLATE, RFC 1951, no zlib header (z=1), inflating to exactly `len` bytes
  ```
  The companion reads chunks of at most 256 KiB. It uses `z=1` when the compressed body is smaller than the raw one.
- An unknown control type is ignored. An unknown frame type or a length above the limit closes the connection.

## 5. Connection flow

```
phone                                  companion
  ── TCP connect + TLS (pin fp) ──────────►
  ◄──────────── hello {t,v,id,name,nonce,pairing}
  ── pair {…}  or  auth {…} ──────────────►
  ◄──────────── paired {…} / authOk / authFail(+close)
  ◄──────────── info {…}
  ── subscribe {sinceUtcTicks, have[]} ───►
  ◄──────────── snapshot, process, data…, syncComplete
  ◄──────────── (live) data / snapshot / truncate / process / info / heartbeat
  ── ack {bytes} / fetch {…} / ping ──────►
  ── idle {on} ───────────────────────────►
  ◄──────────── idle {on}   (confirmation)
```

### 5.1 `hello` (companion → phone, first frame)

`{"t":"hello","v":1,"id":"<companionId>","name":"<machine>","nonce":"<base64url 32 random bytes>","pairing":<bool>}`

A phone that does not support `v` closes the connection and shows "update the companion".

### 5.2 Pairing

The companion's tray menu has "Pair new device". It opens a pairing window that stays valid for **5 minutes** or until
one device pairs, and closes after 5 failed attempts. The window shows:

- a **pairing code**: 10 characters of Crockford base32 (50 random bits), displayed as `XXXXX-XXXXX`. The phone
  normalizes input (uppercase, drops `-` and spaces, maps `O→0` and `I/L→1`);
- a **QR code** containing
  `vrcxc://pair?v=1&id=<companionId>&n=<url-encoded name>&h=<comma-separated local addresses>&p=<port>&fp=<fp>&c=<code>`.
  `h` lists IPv4 addresses first, then unique-local IPv6; link-local IPv6 is left out because its zone id means
  nothing on the phone. `c` is the normalized code without the dash.

`VrcxCompanion.exe --pair` opens this window (in the running instance if there is one); starting the companion a second
time without arguments opens its Status window.

`codeKey` = the 10 normalized ASCII characters as bytes. `fp` is the fingerprint the phone pinned for this connection.
In the HMAC messages below, `fp`, `helloNonce` (from `hello.nonce`) and `clientNonce` (from `pair.nonce`) are the
base64url strings exactly as transmitted, and the message is UTF-8 encoded.

Phone → companion:
```
{"t":"pair","deviceId":"<uuid, persisted on the phone>","deviceName":"<Build.MODEL>",
 "nonce":"<base64url 32 bytes>",
 "proof":"<base64url HMAC-SHA256(codeKey, "vrcxc-pair-v1|client|" + fp + "|" + helloNonce + "|" + clientNonce)>"}
```
The companion recomputes `proof` with its own `fp`, so a man in the middle with another certificate cannot relay the
pairing. On success:
```
{"t":"paired","token":"<base64url 32 random bytes>",
 "proof":"<base64url HMAC-SHA256(codeKey, "vrcxc-pair-v1|server|" + fp + "|" + clientNonce + "|" + helloNonce)>"}
```
The phone verifies the server proof. If it matches, it stores `{companionId, name, fp, token, hosts, port}` in
`EncryptedSharedPreferences`. The companion stores `{deviceId, deviceName, tokenSha256, pairedAtUtc, lastSeenUtc}`
DPAPI-protected in `%APPDATA%\VRCX-Companion\devices.bin`; only the token's SHA-256 is kept.
On failure the companion sends `{"t":"pairFail","reason":"code"|"expired"|"closed"}` and closes the connection. The
phone sends no proof when `hello.pairing` is false.

#### 5.2.1 Where the pinned fingerprint comes from, and what each mode assumes

The phone takes `fp` from the best source it has, and every later connection is pinned to the stored `fp`:

| Mode | `fp` pinned for the pairing connection | Assumption |
|---|---|---|
| QR code | `fp` from the QR code, checked during the TLS handshake | The QR is read from the PC's own screen. |
| Discovered PC | `fp` from the discovery reply the user picked | No active attacker on the LAN during the pairing. |
| Manual address | none: the presented certificate is accepted and its `fp` captured ("capture mode") | Same as discovery. |

- **QR pairing** authenticates the PC through the screen. An attacker on the network cannot complete TLS with the phone,
  never sees a proof, and can only guess the code online, 5 attempts per window. This is the recommended mode.
- **Discovery and manual pairing** trust whoever answers first, for this one connection (trust on first use). The proof
  binds the fingerprint, so a man in the middle still cannot relay the pairing to the real PC. But an attacker that
  intercepts the attempt (ARP or DHCP spoofing, a faster fake discovery reply) receives
  `HMAC(codeKey, …|attacker fp|attacker helloNonce|clientNonce)` and can try all 2^50 codes offline against it:
  2^49 HMAC-SHA256 on average, about 5.7 years on one CPU core or about a day on one high-end GPU. Only a result
  within the same 5-minute window is useful: the attacker could then pair its own device with the real PC, whose
  window is still open because the phone's attempt never reached it, and receive a log-stream token. That takes
  hundreds of GPUs. The companion limits the exposure: it only listens on Private or explicitly allowed networks (§1),
  the window closes after one pairing or 5 minutes, it shows the name of the device that paired, and "Paired
  devices… → Forget" revokes a token (§5.3).
- A companion id the phone has already paired keeps its key: when `hello.id` names a stored pairing, the connection's
  fingerprint must equal the stored one in every mode, otherwise the phone stops before sending a proof and reports
  `fingerprint` ("forget it and pair again"). A QR code, discovery reply or captured certificate therefore cannot
  replace the key of a paired PC. A PC that really gets a new identity also gets a new id (§3).
- A balanced PAKE (CPace or SPAKE2 with the code as password and the transcript bound to `fp`) would remove the
  offline guess for discovery and manual pairing. It is left for a future protocol version.

### 5.3 Authentication (every later connection)

`{"t":"auth","deviceId":"…","token":"…"}` → `{"t":"authOk"}` or `{"t":"authFail"}` followed by a close. A device that is
revoked in the companion ("Paired devices… → Forget") gets `authFail` and shows "pairing removed on the PC".

Revocation removes the device first, then closes its registered sessions. A connection that passed the token check
just before registers its session only if the device is still paired; otherwise it is closed right after `authOk`
(reason `revoked`), and its next attempt gets `authFail`. No session of a forgotten device survives.

### 5.4 `info` (companion → phone, after `paired`/`authOk`, and whenever a value changes)

```
{"t":"info","companionVersion":"1.0.0","machineName":"…","pcUtcNowMs":<int64>,
 "tz":{"windowsId":"W. Europe Standard Time","ianaId":"Europe/Berlin"|null,"supportsDst":true,
       "baseUtcOffsetMin":60,"currentUtcOffsetMin":120},
 "logDir":"<absolute path, for display only>","dirExists":true}
```
Time-zone changes are detected with `SystemEvents.TimeChanged` plus a 60 s check after `TimeZoneInfo.ClearCachedData()`.

### 5.5 Subscription

Phone → companion:
```
{"t":"subscribe","sinceUtcTicks":<int64 .NET ticks of the phone's LogWatcher tillDate, or 0>,
 "have":[{"name":"…","fileId":"…","length":<int64>}, …]}
```
The companion then sends, in this order, on its single ordered writer queue:
1. `snapshot` (5.6) with every `output_log_*.txt` file;
2. `process` (5.8);
3. `data` frames for every file whose `lastWriteTimeUtcTicks >= sinceUtcTicks`, starting at the `have` length when the
   phone's `fileId` matches (or at 0 otherwise), up to the current end of file, oldest file first (by creation time);
4. `{"t":"syncComplete"}`.

After that come live updates. A second `subscribe` on the same connection restarts the sequence. The sequence is sent
at once also while the phone is idle (§5.11).

### 5.6 `snapshot`

```
{"t":"snapshot","files":[{"name":"…","fileId":"<24 lowercase hex digits: 8 of the volume serial, 16 of the NTFS file index>","creationTimeUtcTicks":<int64>,
  "lastWriteTimeUtcTicks":<int64>,"length":<int64>}, …]}
```
Values are exactly what `FileInfo.Refresh()` reports (`CreationTimeUtc.Ticks`, `LastWriteTimeUtc.Ticks`, `Length`),
including any staleness, to keep upstream parity. A snapshot is sent at most once per poll (1000 ms), and only when a
value changed (while the phone is idle, at most once per batch, §5.11). A file missing from the snapshot has been
deleted: the phone deletes its mirror copy. A replaced file (same name, new `fileId`) is sent as one snapshot without
the entry, then one with it.

### 5.7 `data` and `truncate`

- `data` frames (type `0x02`) are contiguous per file: `offset` equals the number of bytes the phone already has for
  that `(name, fileId)`, except right after a `truncate` or in answer to a `fetch`.
- The companion opens logs with `FileShare.ReadWrite | FileShare.Delete`, reads from its last sent offset to the
  handle's current length, and never transcodes, splits lines, strips BOMs or normalizes line endings.
- `{"t":"truncate","name":"…","fileId":"…","newLength":<int64>}` when a file shrank below the sent offset.

### 5.8 `process`

`{"t":"process","vrchatRunning":<bool>,"steamVrRunning":<bool>,"pcUtcNowMs":<int64>}` on subscribe and on every change,
polled every 1000 ms (also while the phone is idle). `vrchatRunning`: any non-exited process named `VRChat`
(case-insensitive). `steamVrRunning`: any process named `vrserver`. It travels on the same ordered queue as `data`, so
the phone always applies it after the bytes that were written before the change was observed.

### 5.9 Liveness and flow control

- `{"t":"heartbeat","pcUtcNowMs":<int64>}` when the companion has sent nothing for **5 s** (**30 s** while the phone is
  idle, §5.11). Any frame counts: while data flows, no heartbeat is sent. The phone estimates the clock skew from the
  `pcUtcNowMs` of `info`, `heartbeat` and `process` (`sample = pcUtcNowMs − phone receive time`; network delay only
  lowers a sample, so the estimate is the largest of the last 8 samples).
- The phone sends nothing on its own schedule. After each frame it receives, if it has sent nothing for **10 s** (**45 s**
  once the companion confirmed idle mode), it sends an `ack` of the bytes consumed since the last ack, or a
  `{"t":"ping"}` when there is nothing to acknowledge. The companion speaks at least every 5 s, so a healthy phone
  talks at least every ~15 s (every ~75 s while idle).
- Receive timeouts: the phone closes after **20 s** without receiving anything (**90 s** while idle mode is
  confirmed); the companion closes a session after **20 s** without receiving anything (**90 s** while the phone is
  idle), and a connection that has not sent a valid `pair` or `auth` within **10 s** of the accept.
- `{"t":"ack","bytes":<int64>}`: `bytes` is the running total of the **wire size** of every data frame (`0x02`) the
  phone has consumed (handed to its log mirror, in order) on this connection: 4-byte length prefix + type byte +
  payload. The phone acks when at least **512 KiB** were consumed since its last ack, after `syncComplete` when
  anything was consumed since its last ack, and as its keep-alive (above). Totals only ever increase.
- The companion keeps at most **4 MiB** of data frames unacknowledged: it sends the next frame only when the bytes in
  flight plus that frame fit, or when nothing is in flight. It clamps acks to what it has sent and ignores decreasing
  ones.
- `{"t":"fetch","name":"…","fileId":"…","fromOffset":<int64>}` asks the companion to resend a file from an offset
  (for example after the phone lost its mirror).

### 5.10 Reconnects

- While the app is visible, the phone reconnects with exponential backoff (1 s, doubling up to **60 s**, ±15 %
  jitter). When every stored address fails, it runs a 1.5 s broadcast discovery for the companion's id and
  fingerprint and tries the addresses that answered; an address is stored only after a pinned connection through it
  worked.
- While the app is hidden, the cap is **10 minutes** and no broadcast discovery runs. The phone still retries at once
  when a Wi-Fi network becomes available (default or not), when the default network changes, and when the app becomes
  visible.
- A default-network change (another network becomes the default, or it is lost) also closes a working connection and
  reconnects at once.
- On reconnect the phone sends `subscribe` with its `have` list, so no byte is sent twice.
- Only one session per device: a new authenticated session from the same `deviceId` closes the older one.

### 5.11 Idle mode

Phone → companion, after authentication: `{"t":"idle","on":true}` when the app goes to the background, and
`{"t":"idle","on":false}` when it comes back. A session that starts while the app is hidden sends `on:true` right
after authentication. Only changes are sent; each connection starts in the normal mode.

The companion answers every `idle` at once (outside the ordered queue) with the same message and the value it applied:
`{"t":"idle","on":<bool>}`. While the phone is idle, the companion:
- sends a heartbeat only after 30 s without sending anything (§5.9);
- closes the session after 90 s without receiving anything;
- queues log growth (`snapshot`, `truncate`, `data`) at most every **10 s**; each batch covers everything since the
  previous one, so batching changes when bytes travel, never which bytes or in which order;
- does not hold back a process change: it queues the pending growth and then the `process` message on the poll that
  observed the change, keeping the ordering of §5.8. The subscribe sequence (§5.5), `fetch` answers and `info` are
  not delayed either.

After `on:false`, growth is queued again on the next poll (within 1 s) and heartbeats return to 5 s.

The phone switches its own timings (45 s keep-alive, 90 s read timeout) only when it receives the confirmation of
`on:true`, and back (10 s, 20 s) with the confirmation of `on:false`. A companion that does not know `idle` ignores it
(§4) and never confirms, so the phone keeps the normal timings with it. `idle` before authentication is ignored.

## 6. Versioning

`v` in `hello` and the discovery messages is the protocol version. Additive fields and messages (such as `idle`,
§5.11) can be added without a version bump, and receivers ignore unknown fields and message types. A breaking change
increments `v`.

## 7. Test vectors

Implementations on both sides must include unit tests for:
- the pairing HMACs, with `codeKey="ABCDE12345"`, `fp="fp-test"`, `helloNonce="hn"`, `clientNonce="cn"` (the nonces
  are used as the literal strings `hn` and `cn` in the message):
  - client proof = `lminVMWbovCkRt0N33ED9SLaV7-Nqw-Tcinojo8YL3M`
  - server proof = `Y1SCIkysnwsJGNruwoktsQix-sFYpCeiexDg-x56VvI`;
- frame encoding and decoding round trips, including DEFLATE bodies and the 1 MiB limit;
- the local-address filter (both IPv4 and IPv6 lists above, plus public addresses rejected);
- the idle mode: its confirmation, the stretched timings on both sides, and that a phone without a confirmation keeps
  the normal ones.
