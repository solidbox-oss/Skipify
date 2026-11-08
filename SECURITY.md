# Security

## Reporting a vulnerability

Please report security problems privately through GitHub:
**Security → Report a vulnerability** on this repository. Do not open a public issue for them.
You will get an answer as soon as possible, and a fix will be released before details are made
public.

## Design

Skipify holds two powerful capabilities: adb-level rights (through its own helper, or Shizuku) and a
VPN. It is built so that neither can be turned against the phone.

**Skipify's helper**
- Skipify pairs with Wireless debugging like a computer would, with its own key. The private key is
  generated on the phone, stored only in Skipify's private storage and encrypted with a key held by
  the Android Keystore. Pairing uses Android's own protocol (TLS 1.3 and SPAKE2 with the six-digit
  code); the code never leaves the phone.
- Skipify connects only to `127.0.0.1`, starts the helper (`app_process` from Skipify's own installed
  APK) and closes the connection once the helper has answered. The helper has the same rights as
  `adb shell`, no more.
- The helper hands its Binder to Skipify through Skipify's content provider together with a random
  token, and Skipify accepts it only from the shell user with the token it stored. The helper in turn
  answers only calls from Skipify's app uid.
- It has no "run a command" call. It knows a fixed list of actions (stop Spotify, start or reopen
  it, allow its background audio, press play, open a Spotify link, read which app is in front and
  whether Spotify is muted or frozen, mute or unmute Spotify's playback, read whether Android Auto
  is using Spotify, read whether Spotify runs its playback service, whether it may run in the
  background and why it last closed, allow it to run in the background, turn the music volume back on, grant Skipify `WRITE_SECURE_SETTINGS`, and remove a copy
  of Skipify that an older update put into another Android user) and builds every command itself
  from an action number and validated arguments. The user-related actions only ever name Skipify's
  own package, and removing a copy is refused for the user Skipify runs in.
- `WRITE_SECURE_SETTINGS` is granted only to Skipify, and Skipify uses it only to switch Wireless
  debugging (`adb_wifi_enabled`): on, when its helper has to be started again (after a reboot,
  when Wi-Fi is back, or when you tap "Skipping paused"), and, if you turned on automatic restart
  and the phone passed the test, off again right after. It never changes USB debugging or
  Developer options.
- Rooted phones can start the helper with root instead of Wireless debugging (setup screen, "Use
  root"). Root is used for that one start only: Skipify asks the root app to run the start command as
  the shell user (`su 2000`), and the helper gives up root itself if it was started as root
  anyway, before it does anything else. It then has exactly the rights and actions described
  here; no other command goes through su.
- The helper exits when Skipify is uninstalled. You can revoke Skipify's pairing at any time under
  Wireless debugging → Paired devices.
- Limitation: Android's adb daemon writes the command that starts the helper, token included, to
  the system log. Reading that log needs the same adb rights the helper already has, so this gives
  nothing to an app that does not already have them.

**Privileged shell (Shizuku or the helper)**
- The only commands Skipify runs are fixed: force-stop and start Spotify, return to the previous app
  or the home screen, press play or pause, open a Spotify playlist or album link, allow Spotify's
  background-audio app-ops (reset to default when Skipify is turned off, once Spotify is not
  playing), mute and unmute Spotify's
  playback (the `PLAY_AUDIO` app-op, only during an ad while Android Auto is connected), give the
  restarted Spotify a 30-second battery allowlist entry so it can start its playback service,
  allow Spotify to run in the background (`RUN_ANY_IN_BACKGROUND`, only when Skipify finds it
  restricted), turn the music volume back on after a muted ad (only if it is still muted), and read
  media, audio, task, service and content-provider state, and why Spotify last closed.
- Every value inserted into a command (package and component names, Spotify URIs) is checked
  against a strict allow-list pattern and single-quoted. Anything else is rejected; unit tests
  cover injection attempts.
- No component accepts commands from other apps. Skipify's broadcast receiver is not exported, and
  all `PendingIntent`s are immutable.

**DNS filter**
- The VPN applies to Spotify only and carries only DNS traffic.
- Queries are forwarded from a protected socket to one IPv4 resolver. A reply is accepted only if
  it comes from that resolver's address and port 53 and carries the query's ID, so other hosts
  cannot inject answers.
- Rules are validated when saved and again when loaded; invalid lines are ignored. The upstream
  must be an IPv4 literal, so no lookup happens while choosing it.
- Malformed or oversized DNS packets are dropped, and the number of lookups in flight is bounded.

**Data**
- Settings, DNS rules and the activity log are stored in the app's private storage and excluded
  from cloud backup and device transfer. The activity log is capped in size.
- Release builds contain no debug or info logging; nothing about what you play is written to the
  system log.
- Skipify has no analytics, no crash reporting and no network access beyond forwarding Spotify's DNS
  lookups. Nothing about the user or the phone is sent by Skipify.

**Build**
- Release APKs are minified. When a private release key is configured, Gradle signs with APK
  Signature Scheme v2 and v3; the signing key is not stored in this repository.
- The Gradle wrapper distribution is pinned by SHA-256 and validated in CI.

## Verifying a local build

If you configured a release key, inspect the signed artifact before distributing it:

```
APK=app/build/outputs/apk/release/app-release.apk
sha256sum "$APK"
apksigner verify --print-certs "$APK"
```

Without a configured release key, Gradle creates `app-release-unsigned.apk`; sign it with the
intended release key before sharing it as an installable app.

Skipify has no configured release repository or automatic update feed; distribute only APKs you built
and signed yourself.
