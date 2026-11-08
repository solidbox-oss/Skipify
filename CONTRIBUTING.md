# Contributing

Thanks for helping improve Skipify.

## Reporting bugs and ideas

Open an issue from this repository's **Issues** tab. Bugs, questions and feature ideas are handled
in public, so everyone can find them and follow along. The bug form asks for:

- Skipify's **Copy diagnostics** report (in the Settings section of Skipify): phone model, Android
  version and how Skipify skips, nothing about what you listen to
- Spotify version
- What you expected and what happened
- The relevant lines from **Recent activity** in Skipify (they contain no personal data beyond ad
  and song titles; remove anything you prefer not to share)

The only exception is a security vulnerability: report it privately as described in
[SECURITY.md](SECURITY.md), so it can be fixed before it is public.

## Building from source

Requires JDK 17 and the Android SDK (API 35).

Use an existing checkout of this project, then change into its Android module:

```bash
cd /path/to/project/android
./gradlew testDebugUnitTest      # unit tests
./gradlew assembleDebug          # debug build (installs alongside a release build)
./gradlew assembleRelease        # release build, see signing below
```

Release builds are signed with a key that is not in this repository. To sign your own, create
`~/.gradle/skipify-signing.properties` (or set `SKIPIFY_SIGNING` to another path):

```properties
storeFile=/path/to/keystore.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Without it, `assembleRelease` produces an unsigned APK. The included GitHub Actions workflow runs
tests and uploads a debug APK artifact; it does not publish releases. Any GitHub release must be
published from a Skipify-controlled repository, and Skipify does not fetch or install it automatically.

## Project layout

```
android/app/src/main/java/app/skipify/mobile/
├── AdWatcher.java          notification listener: reads Spotify's media session, detects ads
├── Skipper.java            the restart-skip: force-stop, restart, resume
├── SleepTimer.java         Skipify's own sleep timer
├── DnsVpnService.java      Spotify-only DNS filter (VpnService)
├── Dns.java, Rules.java    DNS packet handling and rules
├── Shell.java              one entry point: Skipify's helper, else Shizuku
├── HelperControl.java      starts and watches the helper, reboot and per-phone test
├── HelperSetupActivity     guided pairing with Wireless debugging (or Use root)
├── Root.java               starts the helper with root on rooted phones
├── PairingService.java     finds the pairing port, takes the code from the notification
├── adb/                    small adb client: key, pairing (SPAKE2), TLS connection, mDNS
├── helper/                 the helper process and its fixed command table
├── MainActivity.java       main screen
├── ShizukuSetupActivity    guided Shizuku setup (optional)
└── Skipify.java, Prefs.java, Events.java, …
docs/HOW-IT-WORKS.md        design notes and measurements
```

## Pull requests

1. Fork and create a branch from `main`.
2. Keep changes focused; one topic per pull request.
3. Match the existing style: plain Java, no new dependencies without discussion, comments that
   explain *why*.
4. Run the checks before opening the pull request:

   ```bash
   cd android
   ./gradlew lintRelease testDebugUnitTest assembleDebug
   ```

5. If you change how skips work, test on a real device in each case: screen off, phone locked,
   in another app, and in Spotify.

Privileged actions are only added to the fixed command table (`helper/Commands.java`); every value
passed into a command goes through `Commands.arg` or another allow-list check, with a unit test.

By contributing you agree that your contributions are licensed under the GPL-3.0.

Skipify is derived from [Hush](https://github.com/coldMEW/Hush); this is an attribution acknowledgement,
not a download or release source.
