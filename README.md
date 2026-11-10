# Fleet Strike

A two-player naval battle game for Android phones and tablets. Each player hides five ships on a 10×10 grid and takes turns calling shots at the other's fleet. Tablets link directly over **Wi-Fi** (same network or hotspot) or **Bluetooth**, with no internet server involved. There's also a practice mode against the computer (Easy / Normal / Hard).

**Download:** https://alexcch-byte.github.io/fleet-strike/

**Play in a browser:** https://alexcch-byte.github.io/fleet-strike/play/

## Browser version

`docs/play/` is the same game for any phone, tablet or computer, with no install and no build step (plain HTML, CSS and JavaScript modules):

- **Play a friend nearby:** each player on their own device. The host gets a 4-letter code and a QR code; the friend scans it or types the code. The two pages find each other through the free [PeerJS](https://peerjs.com) matchmaking server, then talk directly over WebRTC (via PeerJS's relay only if a direct path can't be found). It needs an internet connection.
- **Pass and play:** two players share one device. A curtain hides each fleet whenever the device changes hands.
- **Practice:** against the computer, Easy / Normal / Hard.

The rules, computer gunner, message protocol and fair-play check are ported from the app (`docs/play/js/rules.js`, `session.js`). The browser game and the Android app don't play each other: browsers can't open the Wi-Fi sockets or Bluetooth links the app uses.

Run its tests with Node 22 or newer:

```bash
node --test "tests/web/*.test.mjs"
```

To try it locally, serve `docs/` (for example `python -m http.server --directory docs`) and open `/play/`.

## Features

- Drag-and-drop fleet placement (tap a ship to rotate, Shuffle for a random layout)
- Wi-Fi play: the host announces itself with a UDP broadcast beacon; the joiner picks it from a list or types the host's IP
- Bluetooth play: RFCOMM sockets, joining from paired devices or a scan, no pairing required
- Fair-play check: each player commits to a SHA-256 fingerprint of their layout before the battle. At the end, both fleets are revealed and every hit/miss/sunk report is verified
- Optional house rule: a hit earns another shot
- Rematches with a running score; the loser fires first next round
- Synthesised sound effects, haptics on hits, keeps the screen awake during a match
- Layouts for tablets and for phones held upright or sideways
- Turns with the device even when rotation lock is on, or stays in landscape or portrait (home screen → Screen)

## Project layout

| Path | What |
| --- | --- |
| `app/src/main/java/.../game` | Rules: grid, ships, fleets, shot resolution, fair-play commitments, computer gunner |
| `app/src/main/java/.../net` | Wire protocol (`Msg`), `Link` abstraction, Wi-Fi (TCP + UDP beacon) and Bluetooth (RFCOMM) transports |
| `app/src/main/java/.../session` | `MatchSession` (one player's side of the protocol) and `BotPlayer` (practice opponent on an in-memory link) |
| `app/src/main/java/.../ui` | Compose screens: home, lobby, deployment, battle |
| `app/src/test` | Unit tests, including full computer-vs-computer matches over the real protocol and a cheating opponent |
| `docs/` | The GitHub Pages download page |
| `docs/play/` | The browser version |
| `tests/web` | Tests for the browser version's rules and protocol (Node) |

## Build

Open the folder in Android Studio, or from a terminal:

```bash
./gradlew testDebugUnitTest assembleRelease
```

The release APK is written to `app/build/outputs/apk/release/app-release.apk` and is signed with the local debug key, so you can side-load it but not publish it on Google Play.

## Release a new version

1. Bump `versionCode` / `versionName` in `app/build.gradle.kts`.
2. Build the APK as above and copy it to `FleetStrike.apk`.
3. `gh release create vX.Y FleetStrike.apk --title "Fleet Strike X.Y" --notes "..."`

The download button always points at `releases/latest/download/FleetStrike.apk`, so the page needs no change.
