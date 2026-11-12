<p align="center"><img src="docs/icon.svg" width="96" alt="Barkeep icon"></p>

<h1 align="center">Barkeep</h1>
<p align="center"><b>Hide the battery percentage on Samsung One UI — keep the battery icon.</b><br>
A number-free battery icon and a status icon row you arrange yourself, for Samsung Galaxy phones on One UI 8.5 and 9.</p>

<p align="center">
  <a href="https://shoccho.github.io/barkeep/">Website</a> ·
  <a href="https://github.com/shoccho/barkeep/releases/latest/download/Barkeep.apk">Download the APK</a> ·
  <a href="https://shoccho.github.io/barkeep/hide-battery-percentage-one-ui/">How to hide the battery percentage on One UI</a>
</p>

---

**How do I hide the battery percentage on One UI 9?** One UI 9 only lets you show the battery with the percentage inside it, or the percentage alone — there's no setting for the icon without the number. Barkeep hides Samsung's battery and draws a number-free battery icon in the same place — and goes further:

- **Five styles** — Capsule, Classic, Dot, Camera ring (wraps the punch-hole camera) and Edge line.
- **Three placements** — *Free* (anywhere), *Samsung's spot* (painted exactly over Samsung's battery, hiding its number) and *Own icons* (Samsung's right-side icons redrawn in your order, with the pill rightmost).
- **Own icons** — 11 One UI status icons (Wi-Fi with generation and 6 GHz, both SIMs with 5G/LTE, Bluetooth, NFC, hotspot, VPN, data saver, alarm, DND, airplane, mute/vibrate) using your phone's own Samsung icon art.
- **Colour** — follow the theme, match the status-bar icon colour live, white, black or custom; status colours for charging / power saving / low; opacity and a contrast rim.
- **Behaviour** — charging bolt and shimmer, pulse below 5%, hide in full screen or landscape, start after restart, Quick Settings tile.
- **Battery right now** — watts, current, voltage, temperature, health, charge cycles and time to full.
- **Private** — no internet permission.

## Install

Download [`Barkeep.apk`](https://github.com/shoccho/barkeep/releases/latest/download/Barkeep.apk) from the latest release and open it. Requires Android 16 or later (One UI 8.5 or 9); built and tuned on a Galaxy A56.

Optional one-time adb grant, so Barkeep can hide Samsung's icons and restore them later:

```sh
adb shell pm grant com.github.shoccho.barkeep android.permission.WRITE_SECURE_SETTINGS
```

Release APKs are signed with a certificate whose SHA-256 is
`51:EC:86:9B:CC:79:F5:E3:A6:9F:FD:AB:32:00:7E:EA:2E:14:E6:65:76:70:40:63:77:FE:95:C2:65:01:BC:96`.

## Build

```sh
./gradlew assembleDebug        # debug build
./gradlew testDebugUnitTest    # unit tests
```

Release builds are signed from a git-ignored `keystore.properties` (format in `app/build.gradle.kts`); without it they fall back to the debug key.

## Release

```sh
scripts/release.sh v1.2 "What changed"
```

Builds and tests, then uploads `Barkeep.apk` to a new GitHub release. The website's download button always points at the latest release.

The website lives in [`docs/`](docs/) and is served by GitHub Pages.

---

Not affiliated with Samsung. Galaxy and One UI are trademarks of Samsung Electronics.
