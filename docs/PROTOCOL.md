# VRCX Companion protocol, version 1

The Windows companion (`companion/`) streams VRChat log files and two process flags to paired phones on the same local
network. It is a read-only data source. It never writes to the VRChat folder or registry, never accepts commands other
than the ones listed here, and never opens outbound connections. The phone (`android/.../companion`) is the client.

This document fixes the wire format. How the phone mirrors and parses the log files is in `docs/ARCHITECTURE.md` §8.

## 1. Network scope

- "Local address": IPv4 `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16`, `127.0.0.0/8`,
  `100.64.0.0/10` (CGNAT, used by some home mesh setups); IPv6 `fe80::/10`, `fc00::/7`, `::1`; IPv4-mapped
  IPv6 of the above.
- Companion: the TCP listener accepts, and the discovery responder answers, only peers with a local address. Other
  peers are closed immediately without any data sent. The companion makes no outbound connections of any kind: no
  update checks and no telemetry.
- Phone: connects only to local addresses. A QR code or manual address pointing anywhere else is refused with an
  error.
- Windows Firewall: the companion offers to add inbound rules (TCP port and UDP discovery port) restricted to profile
  `Private` and remote address `LocalSubnet`. This needs one elevation (`netsh advfirewall`); it is optional because
  Windows also prompts on first listen.

## 2. Ports and discovery

- TCP `49460` (session). UDP `49461` (discovery). Both can be changed in the companion's settings file; the
  discovery reply announces the TCP port.
- Discovery request (phone → UDP broadcast `255.255.255.255:49461` and each interface's directed broadcast), UTF-8
  JSON:
  `{"t":"vrcx-discover","v":1}`
- Discovery reply (companion → unicast to the request's source address and port):
  `{"t":"vrcx-companion","v":1,"id":"<companionId>","name":"<machine name>","port":49460,"fp":"<fingerprint>","pairing":<bool>}`
  - `companionId`: random UUID generated on first run and persisted.
  - `fp`: base64url (no padding) of SHA-256 over the DER `SubjectPublicKeyInfo` of the companion's TLS certificate.
  - `pairing`: whether a pairing window is open right now.
- The Android emulator and some guest networks block broadcast, so the phone also supports manual `host[:port]` entry.

## 3. Transport security

- TLS 1.3 (1.2 allowed) over TCP. The companion's certificate is a self-signed ECDSA P-256 certificate
  (CN = `VRCX Companion <machine>`, valid 20 years), created on first run and stored DPAPI-protected (CurrentUser) at
  `%APPDATA%\VRCX-Companion\identity.bin`.
- The phone accepts the server certificate **only** if its SPKI SHA-256 equals the expected `fp` (from the QR code, a
  discovery reply during pairing, or the stored pairing record). Hostname verification is not used. No client
  certificate.
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
`EncryptedSharedPreferences` or an Android Keystore-wrapped file. The companion stores
`{deviceId, deviceName, tokenSha256, pairedAtUtc, lastSeenUtc}` DPAPI-protected in `%APPDATA%\VRCX-Companion\devices.bin`.
On failure the companion sends `{"t":"pairFail","reason":"code"|"expired"|"closed"}` and closes the connection.

### 5.3 Authentication (every later connection)

`{"t":"auth","deviceId":"…","token":"…"}` → `{"t":"authOk"}` or `{"t":"authFail"}` followed by a close. A device that is
revoked in the companion ("Paired devices… → Forget") gets `authFail` and shows "pairing removed on the PC".

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

After that come live updates. A second `subscribe` on the same connection restarts the sequence.

### 5.6 `snapshot`

```
{"t":"snapshot","files":[{"name":"…","fileId":"<24 lowercase hex digits: 8 of the volume serial, 16 of the NTFS file index>","creationTimeUtcTicks":<int64>,
  "lastWriteTimeUtcTicks":<int64>,"length":<int64>}, …]}
```
Values are exactly what `FileInfo.Refresh()` reports (`CreationTimeUtc.Ticks`, `LastWriteTimeUtc.Ticks`, `Length`),
including any staleness, to keep upstream parity. A snapshot is sent at most once per poll (1000 ms), and only when a
value changed. A file missing from the snapshot has been deleted: the phone deletes its mirror copy. A replaced file
(same name, new `fileId`) is sent as one snapshot without the entry, then one with it.

### 5.7 `data` and `truncate`

- `data` frames (type `0x02`) are contiguous per file: `offset` equals the number of bytes the phone already has for
  that `(name, fileId)`, except right after a `truncate` or in answer to a `fetch`.
- The companion opens logs with `FileShare.ReadWrite | FileShare.Delete`, reads from its last sent offset to the
  handle's current length, and never transcodes, splits lines, strips BOMs or normalizes line endings.
- `{"t":"truncate","name":"…","fileId":"…","newLength":<int64>}` when a file shrank below the sent offset.

### 5.8 `process`

`{"t":"process","vrchatRunning":<bool>,"steamVrRunning":<bool>,"pcUtcNowMs":<int64>}` on subscribe and on every change,
polled every 1000 ms. `vrchatRunning`: any non-exited process named `VRChat` (case-insensitive). `steamVrRunning`: any
process named `vrserver`. It travels on the same ordered queue as `data`, so the phone always applies it after the
bytes that were written before the change was observed.

### 5.9 Liveness and flow control

- `{"t":"heartbeat","pcUtcNowMs":<int64>}` every 5 s. The phone estimates clock skew from it
  (`skew = pcUtcNowMs − phone receive time`, smoothed).
- The phone sends `{"t":"ping"}` when it receives a frame and has sent nothing for 10 s, so an idle but healthy phone
  talks at least every ~15 s. The phone closes after 20 s without receiving anything (heartbeats arrive every 5 s);
  the companion closes after 65 s without receiving anything.
- `{"t":"ack","bytes":<int64>}` from the phone at least every 512 KiB received and at `syncComplete`. `bytes` is the
  running total of the **wire size** of every data frame (`0x02`) received on this connection: 4-byte length prefix
  + type byte + payload. The companion keeps at most 4 MiB of unacknowledged data frames in flight and clamps acks to
  what it has sent.
- `{"t":"fetch","name":"…","fileId":"…","fromOffset":<int64>}` asks the companion to resend a file from an offset
  (for example after the phone lost its mirror).

### 5.10 Reconnects

- The phone reconnects with exponential backoff (1 s, doubling up to 60 s, plus jitter). On network change it
  reconnects immediately, retrying discovery if the stored hosts fail.
- On reconnect the phone sends `subscribe` with its `have` list, so no byte is sent twice.
- Only one session per device: a new authenticated session from the same `deviceId` closes the older one.

## 6. Versioning

`v` in `hello` and the discovery messages is the protocol version. Additive fields can be added without a version
bump, and receivers ignore unknown fields. A breaking change increments `v`.

## 7. Test vectors

Implementations on both sides must include unit tests for:
- the pairing HMACs, with `codeKey="ABCDE12345"`, `fp="fp-test"`, `helloNonce="hn"`, `clientNonce="cn"` (the nonces
  are used as the literal strings `hn` and `cn` in the message):
  - client proof = `lminVMWbovCkRt0N33ED9SLaV7-Nqw-Tcinojo8YL3M`
  - server proof = `Y1SCIkysnwsJGNruwoktsQix-sFYpCeiexDg-x56VvI`;
- frame encoding and decoding round trips, including DEFLATE bodies and the 1 MiB limit;
- the local-address filter (both IPv4 and IPv6 lists above, plus public addresses rejected).
