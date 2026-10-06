# Remote access: wake on connect

Builds on [notes/remote-access.md](../notes/remote-access.md). Future
version; not started.

## Goal

Remote access that's off until needed: `ssh -J sshyeet.com root@tidy-crab`
against a sleeping phone wakes it, TAWC starts the agent, and the login
goes through after a few seconds' wait. No always-on tunnel, no battery
cost while idle.

## Shape

Push over **UnifiedPush**, not ntfy directly. ntfy's Android app is a
UnifiedPush distributor, so from TAWC's side the two are the same thing,
and users can pick another distributor (NextPush, a self-hosted ntfy…).
No FCM, which keeps it F-Droid clean. TAWC registers with the connector
library (`org.unifiedpush.android:connector`, FOSS) and gets back an
endpoint URL (e.g. `https://ntfy.sh/upAbC123…?up=1`). A POST to that URL
delivers a message to TAWC's receiver, and the ntfy app's own connection
wakes the phone.

The endpoint URL is the capability. Anyone who has it can wake the
agent. That's acceptable only because a woken agent is **key login
only** (below), so waking gets an attacker no further than a login
prompt, and they spend battery doing it.

## Only with key login

The passphrase is regenerated on every Start, so after a remote wake you
wouldn't know it. The wake option is therefore offered only with key
login in relay mode. The command then stays stable, because the host key
and relay id are saved per device. Pushed payloads never carry secrets.

## Phases

1. **Manual wake, no relay change.** Add a "Wake on push" checkbox on the
   Remote access screen (key login + relay only). When it's on, the
   screen shows the endpoint URL (tap to copy, sensitive clip). Run
   `curl -d wake <url>`, then ssh about 10 s later. On a message, the
   receiver starts `RemoteSession` from the saved settings (same distro,
   relay, key source) with idle close forced on, so the agent shuts
   itself down again. Keys are refetched, falling back to the last
   fetched set when offline (cache them at each successful fetch). This
   phase alone is useful and proves the Android side.
2. **Relay-triggered (upstream sshyeet change).** Propose it to the
   author; it's their service and protocol:
   - `hello` gains an optional `wake` URL. It's already bound to our
     host key by the hello signature. The relay keeps `id → url` past
     the session for some days, refreshed by each hello; a hello
     without `wake` clears it.
   - A client dial to an absent id that has a registration makes the
     relay POST a fixed payload (`{"v":1}`, nothing else) to the URL,
     rate-limited per id (e.g. once a minute). It then holds the client
     stream until that id's agent connects, with a timeout of ~45 s.
   - Relay-side concerns for them: SSRF (https only, maybe only known
     push hosts), storage, abuse. Optionally WebPush encryption
     (RFC 8291, which UnifiedPush supports) so the push server can't
     read the payload either, though the payload says nothing anyway.

## Android risks (verify first)

- **Starting an FGS from the background.** Android 12+ blocks
  foreground-service starts from background broadcasts unless the app is
  exempt. The UnifiedPush receiver probably needs the battery-optimization
  exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; Keep awake
  avoided it as Play-restricted, see
  [notes/session-service.md](../notes/session-service.md); check
  [play-store.md](play-store.md)).
  Measure on the physical target with the app swiped away, screen off,
  and unplugged (wireless adb).
- **The process may be dead.** The broadcast cold-starts it. The receiver
  must use `goAsync()` and hold a short wakelock until the agent reaches
  `ready`. It also must not touch UI, and the tawcroot envelope has to be
  buildable without an activity.
- **After the wake.** The tunnel still needs the CPU awake while someone
  is logged in. A woken session should probably turn on Keep awake
  (`SessionWake`) automatically for as long as it has clients.
- **Distributor missing.** If no distributor is installed, the checkbox
  explains that and links to ntfy on F-Droid.

## Steps

1. Spike: connector library, receiver that logs and starts the agent from
   saved settings; measure the background-start exemption on the phone
   (with and without battery-optimization exemption).
2. Phase 1 UI, key cache, forced idle close, wake rate limit on our side
   (ignore pushes while running; at most one start per minute).
3. Tests: broker action to fake a push; device test waking via a real
   ntfy.sh POST behind `TAWC_LIVE_RELAY=1`.
4. Notes + `notes/building.md` (new dependency).
5. Phase 2 once/if the relay supports it: send `wake` in hello, keep the
   registration fresh, drop manual-only wording.
