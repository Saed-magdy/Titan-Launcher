# Titan Cockpit Launcher

> The current version number is written in one place only: `android:versionName` in [AndroidManifest.xml](AndroidManifest.xml). See [Releasing a new version](#releasing-a-new-version).

**Titan Cockpit Launcher** (`com.dongfeng.shinecockpit`) is a custom portrait (`960×1080`) head-unit launcher and integrated media cockpit built for Dongfeng Aeolus Shine / Qinggan Android head units (`Android 8.1+` / API 27+ WebView + Three.js WebGL + native CAN-Bus & audio integration).

Since version `2.1.0` the launcher fully merges the **Shine Wave** audio engine directly into the launcher process (`com.dongfeng.shinecockpit.shinewave`) for zero-latency (`0 ms` in-memory) response, unified steering-wheel controls, and a borderless smoked-glass pastel UI.

---

## Key Features

- **Integrated Shine Wave Engine (`19` modular Java classes):**
  - **5 Audio Sources:** USB/Local Files (`LOCAL_MUSIC`), Bluetooth A2DP/AVRCP (`BLUETOOTH`), OEM Car Radio FM/AM/DAB+ (`CAR_RADIO`), Internet Radio (`INTERNET_RADIO`), and External Media Apps (`EXTERNAL_APP`, filtered to downloaded media apps + stock Music).
  - **Transport & Queue Controls:** Shuffle, Repeat (`OFF` / `ALL` / `ONE`), Active Queue viewer, call interruption auto-duck/pause/resume, and named Car Radio quick favorites (`OemStationRepository`).
  - **Borderless Smoked-Glass UI:** Compact card (`#swcard`) with instant source switching and pulsing pastel aura + full driver-reach glass studio (`Shine Wave Player`) with live pastel waveform visualizer, dual card-style squiggle time scrubber, and tap-outside-panel dismissal.
- **Two-Phase Instant Boot (`< 80 ms` HUD + Async 3D):**
  - Instant clock, CAN telemetry cards, quick controls, and audio card render on Frame 1 before asynchronously loading the Three.js 3D vehicle scene (`cockpit-3d.js`).
  - Automatically pauses 3D WebGL rendering (`window.setCar3DFrozen(true)`) while full-screen overlays (such as the Shine Wave Studio) are open to maximize responsiveness.
- **Native Hardware Bridges:**
  - Direct Qinggan CAN-Bus telemetry (`CanBusClient.java`) and vehicle controls (doors, lights, sunroof, locks, TPMS, temperatures).
  - Unified Steering-Wheel Control (SWC) handling, notification `MediaSession` listener (`CockpitMediaListenerService.java`), and boot watchdog (`BootReceiver.java`).
- **Desktop Browser Simulation Mode:**
  - Opening `assets/app.html` over any local static HTTP server automatically runs interactive frontend simulations for telemetry and all 5 Shine Wave audio sources when `window.AndroidBridge` is absent.

---

## Project Structure

See [ARCHITECTURE.md](ARCHITECTURE.md) for the complete module layout and cross-layer JS ↔ Java bridge contract.

---

## Building from Source

The launcher builds without Gradle using standard Android SDK Build-Tools (`aapt`, `dx`, `zipalign`, `apksigner`) and JDK (`javac --release 8`):

1. Install Android SDK `build-tools/28.0.3` and `platforms/android-9/android.jar` (or update the tool paths at the top of `build_apk.ps1`).
2. Supply platform/release signing keys (`platform.pk8` / `platform.x509.pem` referenced in `build_apk.ps1`, or replace with your own keystore when not deploying as a privileged system app).
3. Run:
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\build_apk.ps1
   ```
4. For UI preview & testing on desktop without a head unit, serve `assets/` with any HTTP server (e.g. `jwebserver`) and open `/app.html` at a `960×1080` viewport.

---

## Releasing a new version

The in-app updater compares the version installed in the car with the newest GitHub Release. For that to work, three things must carry **the same number**:

1. **`AndroidManifest.xml`**: raise `android:versionName` (for example `2.1.3` → `2.1.4`) **and** add 1 to `android:versionCode` (Android refuses an update whose code is not higher).
2. **The GitHub Release tag**: `v` + the same versionName, for example `v2.1.4`.
3. **The APK attached to that Release**: built *after* step 1, so it really contains the new number.

If the tag says `v2.2.1` but the APK inside still says `2.1.3`, every car will be offered the "new" update again and again, because after installing it still reports `2.1.3`.

Also add a section for the new version at the top of [CHANGELOG.md](CHANGELOG.md), with the same number, describing what changed. (The changelog is history, so older version numbers in it are expected and correct.)

---

## Author & Copyright

- **Created by:** Dr. Saed
- **Contact:** `saedmagdysms@gmail.com`
