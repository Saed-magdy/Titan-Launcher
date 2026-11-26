# Titan Cockpit Launcher

> The current version number is written in one place only: `android:versionName` in [AndroidManifest.xml](AndroidManifest.xml). See [Version numbers](#version-numbers).
>
> **Developers and AI coding tools:** before changing anything, read [Working on this project](#working-on-this-project) and [Version numbers](#version-numbers).

**Titan Cockpit Launcher** (`com.dongfeng.shinecockpit`) is a custom portrait (`960×1080`) head-unit launcher and integrated media cockpit built for Dongfeng Aeolus Shine / Qinggan Android head units (`Android 8.1+` / API 27+ WebView + Three.js WebGL + native CAN-Bus & audio integration).

Since version `2.1.0` the launcher fully merges the **Shine Wave** audio engine directly into the launcher process (`com.dongfeng.shinecockpit.shinewave`) for zero-latency (`0 ms` in-memory) response, unified steering-wheel controls, and a borderless smoked-glass pastel UI.

> ⚠️ **Safety and no warranty:** this launcher can send commands to the car (trunk, windows, sunroof, lights). It is provided **as is, without any warranty**; you install and use it at your own risk. Do not operate the screen while driving: keep your eyes on the road.
>
> This is an independent project. It is **not affiliated with, endorsed by, or supported by** Dongfeng Motor, Qinggan, or any vehicle manufacturer. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

---

## Key Features

- **Integrated Shine Wave Engine (`21` modular Java classes):**
  - **5 Audio Sources:** USB/Local Files (`LOCAL_MUSIC`), Bluetooth A2DP/AVRCP (`BLUETOOTH`), OEM Car Radio FM/AM/DAB+ (`CAR_RADIO`), Internet Radio (`INTERNET_RADIO`), and External Media Apps (`EXTERNAL_APP`, filtered to downloaded media apps + stock Music).
  - **Transport & Queue Controls:** Shuffle, Repeat (`OFF` / `ALL` / `ONE`), Active Queue viewer, call interruption auto-duck/pause/resume, and named Car Radio quick favorites (`OemStationRepository`).
  - **Borderless Smoked-Glass UI:** Compact card (`#swcard`) with instant source switching and pulsing pastel aura + full driver-reach glass studio (`Shine Wave Player`) with live pastel waveform visualizer, dual card-style squiggle time scrubber, and tap-outside-panel dismissal.
- **Screen Theme (Night Mode):** Settings → Launcher → Screen Theme: Day (default), Night, or Auto, which darkens the background and the 3D car while the headlights are on (after 5 seconds, so tunnels don't make it flicker). Text and warnings keep full brightness.
- **Two-Phase Instant Boot (`< 80 ms` HUD + Async 3D):**
  - Instant clock, CAN telemetry cards, quick controls, and audio card render on Frame 1 before asynchronously loading the Three.js 3D vehicle scene (`cockpit-3d.js`).
  - Automatically pauses 3D WebGL rendering (`window.setCar3DFrozen(true)`) while full-screen overlays (such as the Shine Wave Studio) are open to maximize responsiveness.
- **Native Hardware Bridges:**
  - Direct Qinggan CAN-Bus telemetry (`CanBusClient.java`) and vehicle controls (doors, lights, sunroof, locks, TPMS, temperatures).
  - Unified Steering-Wheel Control (SWC) handling, notification `MediaSession` listener (`CockpitMediaListenerService.java`), start-on-boot (`BootReceiver.java`), and a crash watchdog (`LauncherWatchdog.java`) that restarts the launcher after a crash and falls back to the car's original launcher if it keeps crashing.
- **Desktop Browser Simulation Mode:**
  - Opening `assets/app.html` over any local static HTTP server automatically runs interactive frontend simulations for telemetry and all 5 Shine Wave audio sources when `window.AndroidBridge` is absent.

---

## Project Structure

See [ARCHITECTURE.md](ARCHITECTURE.md) for the complete module layout and cross-layer JS ↔ Java bridge contract.

---

## Building from Source

The launcher builds without Gradle using standard Android SDK Build-Tools (`aapt`, `dx`, `zipalign`, `apksigner`) and JDK (`javac --release 8`):

1. Install a Java JDK (11 or newer) and the Android SDK command-line tools, then the two packages the build needs:
   ```powershell
   sdkmanager "build-tools;28.0.3" "platforms;android-28"
   ```
2. Tell the script where the SDK is, either with `-SdkDir <folder>` or by setting the `ANDROID_SDK_ROOT` environment variable.
3. **Release build for the car**, signed with the head unit's platform key (keep these files outside the repository and never commit them):
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\build_apk.ps1 -PlatformKey "D:\keys\platform.pk8" -PlatformCert "D:\keys\platform.x509.pem"
   ```
   Or set `TITAN_PLATFORM_KEY` and `TITAN_PLATFORM_CERT` once, or copy `build.local.example.ps1` to `build.local.ps1` and write the two locations there (that file stays on your PC: git ignores it). Then run the script without those two options.
4. **Check-only build** (no platform key needed). It is signed with a throwaway test key, so it will not install over the car's launcher:
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\build_apk.ps1 -TestSign
   ```
5. The result is a single file: `bin\Titan_Launcher_v<version>.apk`. Add `-CopyTo "E:\"` (for example a USB stick) to also copy it elsewhere.
6. For UI preview & testing on desktop without a head unit, serve `assets/` with any HTTP server (e.g. `jwebserver`) and open `/app.html` at a `960×1080` viewport.

---

## Working on this project

These rules are for everyone who changes the code: the author, other developers working from a fork, and AI coding tools.

### Branches

| Branch | What it holds |
|---|---|
| `main` | Only versions that were tested in the car. Releases are made from `main` only. |
| `dev` | The testing branch. Every change goes here first. |
| `fix/<name>`, `feature/<name>`, `security/<name>`, `docs/<name>` | One short-lived branch per change, always started from the latest `dev`. |

### How a change reaches the car

1. Start a new branch from the latest `dev`, with **one change per branch**, so each one can be accepted, rejected or undone on its own.
2. Open a pull request (PR) into **`dev`**. Developers working from a fork open it from their fork into `dev` of this repository.
3. Merge it with **"Create a merge commit"**. If the feature has to go later, the **Revert** button on that PR undoes the whole thing in one click.
4. Test `dev` in the car.
5. When `dev` is good, release it to `main` as described in [Releasing a new version](#releasing-a-new-version-from-dev-to-main).

**Urgent car bug:** a `hotfix/<name>` branch from `main`, a PR into `main`, a release, then merge `main` back into `dev` so both stay the same.

### Rules

- Never commit or push directly to `main` or `dev`, and never force-push them or rewrite their history.
- Do not raise the version number inside a fix or feature PR; that happens only in the release step.
- Write commit messages and PR descriptions in plain words: **what was wrong, what changed, and how to test it in the car.**
- Before opening a PR, make sure it builds: `build_apk.ps1 -TestSign` (see [Building from Source](#building-from-source)).
- Never commit signing keys (`*.pk8`, `*.pem`, `*.jks`, `*.keystore`) or APK files. `.gitignore` already blocks them.
- The screen (`assets/`) is a web page that can control the car through `AndroidBridge`. Show any text that comes from outside (app names, station names, song titles) with `escapeHtml()`, never as raw HTML.

---

## Version numbers

Every build has two numbers, both in `AndroidManifest.xml`:

- **`android:versionName`**: the number people see, written `MAJOR.MINOR.PATCH`, for example `2.1.6`.
- **`android:versionCode`**: a plain counter that only Android looks at. Android refuses to install an update whose code is not higher than the one already in the car.

### Which part of the version to raise

| Change | Raise | Example |
|---|---|---|
| Bug fixes only, nothing new for the driver | **PATCH** | `2.1.6` → `2.1.7` |
| New features (a new screen, a new audio source, the mascot...) | **MINOR**, and PATCH goes back to 0 | `2.1.7` → `2.2.0` |
| A big redesign, or anything that changes how people use the launcher | **MAJOR**, and the others go back to 0 | `2.2.0` → `3.0.0` |

Rules that always apply:

- **versionCode goes up by 1 for every build that leaves this PC**, test builds included. It is never reused and never lowered.
- **The versionName never goes backwards.** If a number was set by mistake (for example `2.2.1` instead of `2.1.2`), the next release continues *above* it. Going back would make the car refuse the update, and the updater would think the car is already newer.

### Releasing a new version (from `dev` to `main`)

The number is raised **only in this final step**, never inside a feature or fix PR:

1. On `dev`, raise `versionName` and `versionCode` in `AndroidManifest.xml` as described above. Commit it on its own: `Release v2.1.7`.
2. Add a section for the new version at the **top** of [CHANGELOG.md](CHANGELOG.md) and keep the older sections below it: the changelog is the full history.
3. Open a PR from `dev` into `main` and merge it.
4. Build the APK from `main`, *after* step 1, so it really contains the new number.
5. On GitHub, create a Release with the tag `v` + the same number (for example `v2.1.7`), from `main`, and attach the APK.

The in-app updater compares the version in the car with the newest GitHub Release. If the tag says `v2.1.7` but the APK inside still says `2.1.6`, every car will be offered the "new" update again and again.

### Test builds (pre-releases from `dev`)

A test build is named after the **last release**, plus `-dev.` and a counter: the first test build after `2.1.6` is `2.1.6-dev.1`, then `2.1.6-dev.2`, and so on.

- Give each test build its own higher `versionCode`.
- Publish it on GitHub as a **pre-release** with the tag `v2.1.6-dev.1`, from `dev`. Cars never receive pre-releases through the updater.
- When the real `2.1.7` comes out, a test car on `2.1.6-dev.2` is offered it normally, because 2.1.7 is higher.

Do **not** name a test build after the coming version (`2.1.7-dev`): the updater would treat it as already being 2.1.7, and the test car would never be offered the real 2.1.7.

---

## Author & Copyright

- **Created by:** Dr. Saed
- **Contact:** `saedmagdysms@gmail.com`

---

## License

Titan Cockpit Launcher is **free for noncommercial use** under the [PolyForm Noncommercial License 1.0.0](LICENSE): you may use, study, change and share it for personal, hobby, research or teaching purposes. Selling it, or using it in a commercial product or service, needs written permission from Dr. Saed.

Third-party parts (Three.js, the fonts, the 3D car model and the Qinggan car-interface classes) keep their own owners' terms; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Found a security problem? Please report it privately: see [SECURITY.md](SECURITY.md).
