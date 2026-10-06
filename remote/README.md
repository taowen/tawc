# tawc_remote

TAWC's remote access: an embedded sshd that serves a distro either on the
local network or through an [sshyeet](https://sshyeet.com)-compatible
relay. Design, trust model and tests: [notes/remote-access.md](../notes/remote-access.md).

This file specifies the relay side, so any relay speaking it works in
place of sshyeet.com. It is what this crate expects (and what the fake
relay in `tests/support/` implements), matching sshyeet's v1 protocol.

## Relay protocol, v1

Two roles: the **agent** (the phone, outbound only) and **clients**
(`ssh -J <relay> <user>@<id>`). The relay splices the client's SSH bytes
into the agent's tunnel. It never sees the plaintext: SSH is end to end
between client and agent.

### 1. Tunnel

The agent dials `GET wss://<relay>/v1/tunnel` (a relay URL
`https://host/base` → `wss://host/base/v1/tunnel`; `http://` → `ws://`).
Request headers: `User-Agent: <agent string>`, and on reconnect
`fly-force-instance-id: <node>` from the last `ready` (a relay may ignore
it). HTTP 429 means "rate limited"; the agent backs off.

Every binary WebSocket message is a chunk of **one byte stream**; message
boundaries mean nothing. Other message types are ignored; Close ends the
tunnel. That stream carries **yamux** ([hashicorp spec](https://github.com/hashicorp/yamux/blob/master/spec.md)),
the relay as server (even stream ids), the agent as client (odd):

- 12-byte header: version 0, type (0 data, 1 window update, 2 ping,
  3 go away), flags (1 SYN, 2 ACK, 4 FIN, 8 RST), stream id, length.
- Initial window 256 KiB per stream in each direction; never send more
  than the peer's window. The agent's data frames are ≤ 64 KiB.
- Opens are eager: the agent opens with a zero-delta window update + SYN
  and accepts with + ACK, and expects the same from the relay. (Lazy
  SYN-on-first-data opens would deadlock: the relay speaks first.)
- Pings: answer ping+SYN with ping+ACK echoing the value. The agent pings
  every 25 s and drops the tunnel if a ping goes a whole interval
  unanswered.

### 2. Control stream

The first stream, opened by the agent. Newline-delimited JSON, one
message per line, each ≤ 16 KiB, strictly alternating. Unknown fields
are ignored. `[]byte` fields are standard base64 with padding.

```
relay → {"op":"challenge","v":1,"nonce":"<base64, ≥16 random bytes>"}
agent → {"op":"hello","v":1,"host_key":"ssh-ed25519 AAAA…","sig":"<base64>",
         "ttl":0,"agent":"tawc/12 android/arm64","id_words":2}
relay → {"op":"ready","id":"tidy-crab","jump":"relay.example",
         "node":"n1","region":"ams","expires":0,"notice":""}
     or {"op":"error","msg":"…"}
 … later, at any time:
relay → {"op":"bye","reason":"…","reconnect":true}
```

- **challenge**: `v` > 1 makes the agent give up ("update TAWC").
- **hello**: `host_key` is an authorized_keys-form public key (TAWC sends
  Ed25519; no comment). `sig` is an SSH-wire signature (`string
  "ssh-ed25519" ‖ string sig`) by that key over `"sshyeet hello v1\0" ‖
  nonce`; the relay must verify it. `ttl` is seconds wanted, 0 or absent
  = the relay's maximum. `agent` is free text. `id_words` is 1–8, 0 or
  absent = 2.
- **ready**: `id` must be the short id derived below from *this* key
  with `id_words` words, or the agent refuses it — the relay cannot pick
  names. `jump` is the address clients pass to `-J`: `host`, `host:port`,
  `ipv4[:port]` or `[ipv6][:port]`, host chars `[A-Za-z0-9.-]` (anything
  else is refused). `node` (≤ 64 chars, sent back on reconnect),
  `region` (≤ 16 chars of `[a-z0-9-]`), `expires` (informational) and
  `notice` (≤ 240 printable chars, shown to the user) are optional.
- **error**: `msg` `"session id taken"` means another key holds these
  words; the agent retries with one more word, then with a throwaway
  key. Any other error is fatal to that Start.
- **bye**: `reconnect: true` → the agent backs off (1 s doubling to 1
  min) and reconnects with the same key; `false` → it stops. A silent
  drop also reconnects, so a hello from the key already holding an id
  should replace that old tunnel, not get "session id taken".

The agent allows 30 s for the handshake.

### 3. Session ids

```
H     = SHA-256("sshyeet id v3\0" ‖ wire(host_key))
short = W[H bits 0..11] "-" W[H bits 11..22] "-" …    (id_words fields, from H[0:16])
long  = short "-" base32(H[16:26])                    (lowercase, no padding)
```

Fields are big-endian 11-bit slices; `W` is the RFC 1751 dictionary
(2048 words, lowercased; `src/sid/rfc1751.rs`). `wire(key)` is the SSH
wire encoding of the public key (the base64 in `host_key`, decoded). One
live tunnel per short id. The long id is self-certifying; routing it too
is optional.

### 4. Clients

The relay runs an SSH server at `jump` that clients use only as a jump
host: `ssh -J relay tidy-crab` opens a `direct-tcpip` channel to
`tidy-crab:22`. Client authentication at the relay is the relay's choice
(`none` accepted makes the command work with no keys). For a channel
to a live id, the relay opens a yamux stream to that agent, writes one
header line, then copies raw bytes both ways until either side closes:

```
{"from":"203.0.113.7:51234","via":"n1"}
```

`from` is the client's address (display only); `via` is optional. The
agent drops streams whose header doesn't arrive within 15 s, and refuses
(RST) more than 256 open streams. Unknown ids, other ports and other
channel types should be refused.

## Testing a relay

`TAWC_LIVE_RELAY=1 cargo test --test e2e live_relay` runs the agent
against sshyeet.com; point `relay-spike` at yours instead:

```
cargo run --example relay-spike https://relay.example
```
