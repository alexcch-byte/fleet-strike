# Fleet Strike

A two-player naval battle game for Android tablets. Each player hides five ships on a 10×10 grid and takes turns calling shots at the other's fleet. Tablets link directly over **Wi-Fi** (same network or hotspot) or **Bluetooth**, with no internet server involved. There's also a practice mode against the computer (Easy / Normal / Hard).

**Download:** https://alexcch-byte.github.io/fleet-strike/

## Features

- Drag-and-drop fleet placement (tap a ship to rotate, Shuffle for a random layout)
- Wi-Fi play: the host announces itself with a UDP broadcast beacon; the joiner picks it from a list or types the host's IP
- Bluetooth play: RFCOMM sockets, joining from paired devices or a scan, no pairing required
- Fair-play check: each player commits to a SHA-256 fingerprint of their layout before the battle. At the end, both fleets are revealed and every hit/miss/sunk report is verified
- Optional house rule: a hit earns another shot
- Rematches with a running score; the loser fires first next round
- Synthesised sound effects, haptics on hits, keeps the screen awake during a match
- Landscape (side-by-side boards) and portrait layouts

## Project layout

| Path | What |
| --- | --- |
| `app/src/main/java/.../game` | Rules: grid, ships, fleets, shot resolution, fair-play commitments, computer gunner |
| `app/src/main/java/.../net` | Wire protocol (`Msg`), `Link` abstraction, Wi-Fi (TCP + UDP beacon) and Bluetooth (RFCOMM) transports |
| `app/src/main/java/.../session` | `MatchSession` (one player's side of the protocol) and `BotPlayer` (practice opponent on an in-memory link) |
| `app/src/main/java/.../ui` | Compose screens: home, lobby, deployment, battle |
| `app/src/test` | Unit tests, including full computer-vs-computer matches over the real protocol and a cheating opponent |
| `docs/` | The GitHub Pages download page |

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
