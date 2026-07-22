# SSH Mobile

An Android SSH client ported from [SSHClient](https://github.com/jgcoopersmith/SSHClient) —
the same terminal, SFTP browser and peer-chat features, reshaped for a phone.

Kotlin + Jetpack Compose, Material 3, minSdk 26.

## What carried over from the desktop client

| Desktop (WinForms) | Mobile |
| --- | --- |
| `TerminalPanel` + SSH.NET `ShellStream` | `ssh/SshSession.kt` — sshj, coroutines, `SharedFlow` |
| `TerminalPanel.StripAnsi` | `ssh/Ansi.kt` — line-for-line port, plus OSC and backspace |
| `SftpBrowserForm` | `ssh/SftpSession.kt` + `ui/SftpScreen.kt` |
| `ChatServer` / `PeerConnection` | `chat/ChatServer.kt` — same wire protocol |
| `ChatPanel` | `ui/SessionScreen.kt` chat pane |
| `MainForm` + `TabControl` | `ui/SessionViewModel.kt` + `ui/SessionScreen.kt` |
| `MainForm` left panel (two lists) | `ui/HomeScreen.kt` — two tabs |
| `ConnectionDialog`, `PeerDialog` | `ui/Dialogs.kt` |
| `ConnectionManager`, `PeerManager` (JSON in %APPDATA%) | `data/Store.kt` — SharedPreferences |
| `ConnectionProfile`, `PeerProfile` | `data/Models.kt` |

The chat wire protocol is unchanged — `HELLO:<name>` handshake then `MSG:<text>`
per line — so a phone and a desktop SSHClient can chat with each other.

## What mobile changes

- **Passwords and key passphrases are encrypted at rest** with an AES/GCM key in
  the Android keystore. The desktop wrote them as cleartext into
  `connections.json`, which is not defensible on a phone.
- **Host keys are pinned on first use.** SSH.NET accepted any host key silently;
  sshj requires a verifier, and a phone hops between untrusted networks. A
  changed fingerprint aborts the connection and says what changed.
- **The full BouncyCastle provider replaces Android's.** The platform preloads a
  stripped BC under the same name that omits X25519, which fails the handshake
  against any current OpenSSH. See `ssh/Crypto.kt`.
- **Foreground service** so Android doesn't freeze the socket when backgrounded.
- **Control-key bar** (Ctrl-C/D/Z/L, Tab, Esc, arrows) since a soft keyboard
  can't produce those chords.
- **Private keys come from the Storage Access Framework** rather than a
  filesystem path, and the URI permission is persisted across restarts.
- **SFTP is single-select** — there are no modifier keys to multi-select with —
  and download/upload go through SAF pickers.
- **Settings screen** for chat display name, chat listen port and PTY geometry.
  The desktop took its chat name from `Environment.UserName` and always let the
  OS pick the port.

## Not carried over

- ANSI colour is still stripped rather than rendered, exactly as on the desktop.
  This is a line-oriented shell view, not a full VT100 emulator.
- `ChatConnectDialog` — the peer dialog covers quick-connect on its own.

## Building

Toolchain: JDK 21, Gradle 8.11.1 (via the checked-in wrapper), AGP 8.9.2,
Kotlin 2.0.21, compileSdk/targetSdk 36, minSdk 26.

```bash
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

Unit tests (ANSI stripper, host key prompt bridge, SFTP path and size helpers):

```bash
./gradlew testDebugUnitTest
```

### Version numbering

`.githooks/pre-commit` bumps the last component of `versionName` and increments
`versionCode` in `app/build.gradle.kts` on every commit, so each commit ships a
unique, increasing version — shown at the bottom of Settings. Never bump them by
hand. Enable the hook once per clone:

```bash
git config core.hooksPath .githooks
```

`versionCode` moves alongside `versionName` because Android refuses to install
an update whose code has not increased.

### Release builds

`assembleRelease` signs the APK when it can find a key, and produces an unsigned
one when it can't — so a fresh checkout still builds. Supply the key either
through an untracked `keystore.properties` at the repo root:

```properties
storeFile=/absolute/path/to/sshmobile-release.jks
storePassword=…
keyAlias=sshmobile
keyPassword=…
```

or through `SSHMOBILE_STORE_FILE`, `SSHMOBILE_STORE_PASSWORD`,
`SSHMOBILE_KEY_ALIAS` and `SSHMOBILE_KEY_PASSWORD` in the environment, for CI.

```bash
./gradlew assembleRelease
```

`keystore.properties`, `*.jks` and `*.keystore` are gitignored. **The signing key
cannot be regenerated** — every future update must be signed with the same key
or Android will reject it as a different app.

R8 is deliberately off for release. sshj resolves ciphers, key algorithms and
transports reflectively through its Factory service lists, so a missing keep
rule shows up as a runtime handshake failure rather than a build error.
`proguard-rules.pro` carries the rules for whenever it's turned on and tested
against a real server.

JDK 25 will not work — Gradle 8.11.1 rejects it. Android Studio's bundled JBR is
a JDK 21; point `JAVA_HOME` at it if your system default is newer.

`local.properties` is not committed — point `sdk.dir` at your Android SDK, or
let Android Studio write it on first sync.

## Status

Builds clean and the 22 unit tests pass.

The signed release build runs on a Galaxy S24+ (Android 15) and **connects to a
real SSH server** — handshake, host key confirmation and interactive shell all
work.

Peer chat has been exercised against the desktop client: an inbound connection
opens its window and messages arrive. SFTP connects and lists directories.

Still unexercised: SFTP uploads, downloads and deletes; outbound chat messages;
and key authentication (only password auth has been used).
