# Dongfeng Shine Launcher — Architecture & Engineering Guide

> Current version: see `android:versionName` in `AndroidManifest.xml` (the single source of truth).

## Overview
**Titan Cockpit Launcher** (`com.dongfeng.shinecockpit`) is a hybrid Android Head-Unit launcher (Android WebView + Three.js WebGL + native Qinggan CAN-Bus, Tuner, Bluetooth & MediaSession bridges) designed for a `960×1080` portrait dashboard display.

Starting in `v2.1.0`, **Shine Wave** is compiled directly into the launcher under `com.dongfeng.shinecockpit.shinewave.*`, replacing cross-app broadcasts/AIDL latency with direct in-process state callbacks.

---

## Project Directory Layout

```text
DongfengShineApp/
├── AndroidManifest.xml                  # Launcher intent filters, permissions & services, and the app version
├── build_apk.ps1                        # Reproducible build pipeline (aapt -> javac -> dx -> zipalign -> apksigner)
├── README.md                            # Repository overview, build instructions, working rules & version plan
├── ARCHITECTURE.md                      # Architecture & cross-layer interface guide
├── CHANGELOG.md                         # Release history (newest at the top)
├── res/                                 # App icons & XML string resources
├── src/
│   ├── com/dongfeng/shinecockpit/
│   │   ├── MainActivity.java                   # WebView host, AndroidBridge JS API & ShineWave wiring
│   │   ├── CanBusClient.java                   # Native CAN-Bus telemetry reader & vehicle command dispatcher (trunk speed interlock)
│   │   ├── CockpitMediaListenerService.java    # NotificationListener for active MediaSession metadata & art
│   │   ├── BootReceiver.java                   # Auto-start on boot / screen wake
│   │   ├── LauncherWatchdog.java               # Crash watchdog: restarts the launcher, falls back to the factory launcher on a crash loop
│   │   ├── FuelWarningMonitor.java             # Low-fuel popup rules: levels from Settings (default 50% / 25%), once per level until refuelled
│   │   ├── FuelWarningVoice.java               # Says the low-fuel warning out loud (text-to-speech), or a chime if there is no voice
│   │   ├── TyreWarningMonitor.java             # Tyre-pressure popup rules: car's TPMS flag or under 1.9 bar, once per tyre until fixed
│   │   ├── SteeringWheelKeyHandler.java        # Steering-wheel (SWC) keys for OEM and standard media key events
│   │   ├── AppRepository.java                  # Installed & OEM head-unit apps: list, icons, cache and launch
│   │   ├── SystemControlHelper.java            # Wi-Fi / Bluetooth / cellular toggles, memory cleanup, default home, listener rebind
│   │   ├── AudioSpectrumAnalyzer.java          # Live audio energy, bass beat and 16-band spectrum for the visualizers
│   │   ├── GitHubUpdateChecker.java            # In-app updater: checks the latest GitHub Release, downloads and opens the installer
│   │   └── shinewave/                          # Integrated modular Shine Wave engine (21 classes)
│   │       ├── core/
│   │       │   ├── AudioSourceType.java        # 5 audio source enum (LOCAL_MUSIC, BLUETOOTH, CAR_RADIO, INTERNET_RADIO, EXTERNAL_APP)
│   │       │   ├── RepeatMode.java             # OFF / ALL / ONE repeat modes
│   │       │   ├── PlaybackStateModel.java     # Unified immutable state snapshot for UI sync
│   │       │   ├── CallInterruptionManager.java# Phone call pause/resume & audio ducking
│   │       │   ├── QingganAudioPolicyBridge.java # Switches the car's hardware audio channel (avoids the "silent until OEM music opens" bug)
│   │       │   └── ShineWaveEngine.java        # Central orchestrator across all sources, queue, shuffle & repeat
│   │       ├── player/
│   │       │   └── NativeStreamPlayer.java     # Local/HTTP media playback engine & equalizer/visualizer tap
│   │       ├── local/
│   │       │   ├── LocalMusicScanner.java      # USB & storage folder/file scanner & queue builder
│   │       │   └── ArtworkCacheManager.java    # Embedded album-art extraction & caching
│   │       ├── bluetooth/
│   │       │   ├── QingganBtController.java    # Native Qinggan BT audio stream & control adapter
│   │       │   ├── AvrcpMetadataTracker.java   # Bluetooth AVRCP track metadata listener
│   │       │   └── OnlineCoverArtFetcher.java  # Async album-art lookup for BT metadata
│   │       ├── radio/
│   │       │   ├── FactoryRadioBridge.java     # Hardware tuner & Qinggan factory radio AIDL/service bridge
│   │       │   ├── FmAmTunerController.java    # FM/AM tuning, step & seek controller
│   │       │   ├── DabBrowserController.java   # DAB+ ensemble/station browser
│   │       │   ├── OemStationRepository.java   # OEM preset merge + custom station name persistence
│   │       │   └── RadioAudioPolicyRouter.java # OEM audio focus/routing policy for tuner sources
│   │       ├── internet/
│   │       │   ├── LocalStreamProxy.java       # On-device relay (127.0.0.1 only) that keeps one live radio stream open for fast starts
│   │       │   ├── RadioBrowserClient.java     # Online station search & directory client
│   │       │   └── RadioStationStore.java      # Saved Internet Radio favorites persistence
│   │       └── external/
│   │           └── ExternalPlayerRouter.java   # Filtered external media app discovery (downloaded media + stock Music)
│   └── com/qinggan/canbus/                     # AIDL/Parcelable CAN telemetry structures (AirConditionState, VehicleState)
└── assets/
    ├── app.html                                # Lean semantic shell (960×1080 viewport)
    ├── *.png                                   # Refined launcher icons & transparent TPMS overlay
    ├── models/c65_car.glb                      # Binary 3D car model, loaded in Phase 2
    ├── textures/lights/                        # Headlight & tail-light textures for the 3D car
    ├── fonts/                                  # Local NotoSansArabic & PlusJakartaSans woff2 fonts
    ├── css/
    │   ├── cockpit-theme.css                   # Core HUD styling, pastel palette & modal glass animations
    │   ├── shinewave-card.css                  # Compact bottom player card (#swcard) & source drawer styling
    │   └── shinewave-studio.css                # Full-screen Shine Wave Studio pastel smoked-glass overlay
    └── js/
        ├── cockpit-core.js                     # Phase 1: Shared state, viewport scaler, I18N, Arabic-Indic digits & escapeHtml()
        ├── cockpit-ui.js                       # Phase 1: Instant HUD controllers, telemetry, apps, settings & CAN sync
        ├── shinewave-card.js                   # Phase 1: Audio widget UI, equalizer aura, source picker & desktop sim
        ├── shinewave-studio.js                 # Phase 1: Full Studio modal, pastel waveform, card-style time bar & simDb
        ├── cockpit-boot.js                     # Phase 2: Post-first-paint asynchronous loader for 3D pipeline
        ├── cockpit-3d.js                       # Phase 2: Three.js scene, shader glass, dynamic paint & freeze control
        ├── three.min.js                        # Three.js WebGL runtime
        ├── OrbitControls.js                    # 360° turntable camera controls (locally edited: touch-only listeners on touch screens)
        └── GLTFLoader.js                       # Binary GLTF parser
```

---

## Two-Phase Instant Boot Pipeline

To prevent synchronous parsing of `three.min.js`, `OrbitControls.js`, `GLTFLoader.js`, and the 3D car model (`models/c65_car.glb`) from delaying cold startup on Snapdragon head units, the launcher boots in two phases:

### Phase 1 — Instant Interactive HUD (`< 80 ms`)
1. `MainActivity.setupWebView()` initializes the WebView dark canvas background (`0xFF061014`).
2. `app.html` loads local CSS (`cockpit-theme.css`, `shinewave-card.css`, `shinewave-studio.css`) and local `woff2` fonts.
3. `cockpit-core.js`, `cockpit-ui.js`, `shinewave-card.js`, and `shinewave-studio.js` execute immediately without blocking on Three.js.
4. Clock, CAN telemetry cards, quick apps, and the `#swcard` audio widget become interactive on Frame 1.

### Phase 2 — Asynchronous 3D Vehicle Engine (Post-Paint)
1. Right after Phase 1 paints, `cockpit-boot.js` starts downloading `models/c65_car.glb` in the background and dynamically injects `three.min.js`, `OrbitControls.js`, `GLTFLoader.js`, and `cockpit-3d.js`.
2. When full-screen modals such as the Shine Wave Studio open, `window.setCar3DFrozen(true)` pauses the WebGL render loop to keep UI interactions silky smooth.

---

## Key Cross-Layer Interfaces (`window.AndroidBridge`)
- **Native → WebView:**
  - `window.onCanTelemetry(json)` updates speed, coolant, ambient temp, doors, lights, sunroof, locks, and TPMS.
  - `window.onShineWaveState(json)` updates `#swcard` and syncs the open Shine Wave Studio header, repeat, shuffle, metadata, and artwork.
  - `window.onShineWaveLocalData` / `onShineWaveRadioData` / `onShineWaveInternetData` / `onShineWaveAppsData` / `onShineWaveQueueData` populate Studio tabs asynchronously.
  - `applySavedSettingsJson(json)` restores persisted language, car personalization, and lock toggles.
- **WebView → Native (`window.AndroidBridge`):**
  - Vehicle: `postCanCommand(cmd, data)` (the trunk is refused above 5 km/h or when the speed cannot be read).
  - Apps & System: `launchApp(pkg)`, `getInstalledAppsJson()`, `getConnectivityStatusJson()`, `openConnectivitySettings(type)`, `setWifiEnabled(bool)`, `setBluetoothEnabled(bool)`, `cleanMemory()`, `dispatchSystemNav(action)`, `controlMedia(...)`, `seekMediaBy(sec)`.
  - Settings & Maintenance: `saveSettings(json)`, `getSettings()`, `setDefaultLauncher()`, `setAutoBoot(bool)`, `isWatchdogEnabled()`, `setWatchdogEnabled(bool)`, `checkForUpdates()`.
  - Shine Wave Engine: `sendShineWaveCommand(cmd)`, `seekMediaTo(sec)`, `toggleShineWaveShuffle()`, `cycleShineWaveRepeat()`, `browseShineWaveLocal(...)`, `getShineWaveRadioState()`, `saveShineWaveRadioFavorite(freqKhz, name)`, `searchShineWaveInternetRadio(...)`, `getShineWaveExternalApps()`, `requestShineWaveQueue()`, `getAudioVisualizerData()`, plus the radio (`tuneShineWaveRadio`, `stepShineWaveRadio`, `seekShineWaveRadio`, `startShineWaveRadioAutoScan`, `playShineWaveDab`...) and queue/station helpers.
  - The complete, always-current list is every method marked `@JavascriptInterface` in `MainActivity.java`.

### Safety rules for the bridge
- The bridge can control the car, so the WebView may only show the launcher's own pages (`file:///android_asset/`); `MainActivity.isLauncherOwnPage()` refuses everything else, and universal file access is off.
- Any text that comes from outside (app names, station names, song titles, artists) must be shown with `escapeHtml()` or `textContent`, never inserted as raw HTML.
