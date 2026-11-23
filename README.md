# Titan Cockpit Launcher (`v2.1.0`)

**Titan Cockpit Launcher** (`com.dongfeng.shinecockpit`) is a custom portrait (`960×1080`) head-unit launcher and integrated media cockpit built for Dongfeng Aeolus Shine / Qinggan Android head units (`Android 8.1+` / API 27+ WebView + Three.js WebGL + native CAN-Bus & audio integration).

Version `2.1.0` fully merges the **Shine Wave** audio engine directly into the launcher process (`com.dongfeng.shinecockpit.shinewave`) for zero-latency (`0 ms` in-memory) response, unified steering-wheel controls, and a borderless smoked-glass pastel UI.

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

1. Install a Java JDK (11 or newer) and the Android SDK command-line tools, then the two packages the build needs:
   ```powershell
   sdkmanager "build-tools;28.0.3" "platforms;android-28"
   ```
2. Tell the script where the SDK is, either with `-SdkDir <folder>` or by setting the `ANDROID_SDK_ROOT` environment variable.
3. **Release build for the car**, signed with the head unit's platform key (keep these files outside the repository and never commit them):
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\build_apk.ps1 -PlatformKey "D:\keys\platform.pk8" -PlatformCert "D:\keys\platform.x509.pem"
   ```
   Or set `TITAN_PLATFORM_KEY` and `TITAN_PLATFORM_CERT` once and run the script without those two options.
4. **Check-only build** (no platform key needed). It is signed with a throwaway test key, so it will not install over the car's launcher:
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\build_apk.ps1 -TestSign
   ```
5. The result is a single file: `bin\Titan_Launcher_v<version>.apk`. Add `-CopyTo "E:\"` (for example a USB stick) to also copy it elsewhere.
6. For UI preview & testing on desktop without a head unit, serve `assets/` with any HTTP server (e.g. `jwebserver`) and open `/app.html` at a `960×1080` viewport.

---

## Author & Copyright

- **Created by:** Dr. Saed
- **Contact:** `saedmagdysms@gmail.com`
