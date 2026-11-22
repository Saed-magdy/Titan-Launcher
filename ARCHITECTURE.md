# Dongfeng Shine Launcher (`v2.1.0`) — Architecture & Engineering Guide

## Overview
**Titan Cockpit Launcher** (`com.dongfeng.shinecockpit`) is a hybrid Android Head-Unit launcher (Android WebView + Three.js WebGL + native Qinggan CAN-Bus, Tuner, Bluetooth & MediaSession bridges) designed for a `960×1080` portrait dashboard display.

Starting in `v2.1.0`, **Shine Wave** is compiled directly into the launcher under `com.dongfeng.shinecockpit.shinewave.*`, replacing cross-app broadcasts/AIDL latency with direct in-process state callbacks.

---

## Project Directory Layout

```text
DongfengShineApp/
├── AndroidManifest.xml                  # Launcher intent filters, permissions & services (v2.1.0)
├── build_apk.ps1                        # Reproducible build pipeline (aapt -> javac -> dx -> zipalign -> apksigner)
├── README.md                            # Repository overview & build instructions
├── ARCHITECTURE.md                      # Architecture & cross-layer interface guide
├── res/                                 # App icons & XML string resources
├── compile_stubs/                       # Qinggan OS framework compile-time stubs
├── src/
│   ├── com/dongfeng/shinecockpit/
│   │   ├── MainActivity.java                   # WebView host, AndroidBridge JS API, SWC keys & ShineWave wiring
│   │   ├── CanBusClient.java                   # Native CAN-Bus telemetry reader & vehicle command dispatcher
│   │   ├── CockpitMediaListenerService.java    # NotificationListener for active MediaSession metadata & art
│   │   ├── BootReceiver.java                   # Auto-start on boot / screen wake + crash watchdog
│   │   └── shinewave/                          # Integrated modular Shine Wave engine (19 classes)
│   │       ├── core/
│   │       │   ├── AudioSourceType.java        # 5 audio source enum (LOCAL_MUSIC, BLUETOOTH, CAR_RADIO, INTERNET_RADIO, EXTERNAL_APP)
│   │       │   ├── RepeatMode.java             # OFF / ALL / ONE repeat modes
│   │       │   ├── PlaybackStateModel.java     # Unified immutable state snapshot for UI sync
│   │       │   ├── CallInterruptionManager.java# Phone call pause/resume & audio ducking
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
│   │       │   ├── RadioBrowserClient.java     # Online station search & directory client
│   │       │   └── RadioStationStore.java      # Saved Internet Radio favorites persistence
│   │       └── external/
│   │           └── ExternalPlayerRouter.java   # Filtered external media app discovery (downloaded media + stock Music)
│   └── com/qinggan/canbus/                     # AIDL/Parcelable CAN telemetry structures (AirConditionState, VehicleState)
└── assets/
    ├── app.html                                # Lean semantic shell (960×1080 viewport)
    ├── *.png                                   # Refined launcher icons & transparent TPMS overlay
    ├── fonts/                                  # Local NotoSansArabic & PlusJakartaSans woff2 fonts
    ├── css/
    │   ├── cockpit-theme.css                   # Core HUD styling, pastel palette & modal glass animations
    │   ├── shinewave-card.css                  # Compact bottom player card (#swcard) & source drawer styling
    │   └── shinewave-studio.css                # Full-screen Shine Wave Studio pastel smoked-glass overlay
    └── js/
        ├── cockpit-core.js                     # Phase 1: Shared state, viewport scaler, I18N & Arabic-Indic digits
        ├── cockpit-ui.js                       # Phase 1: Instant HUD controllers, telemetry, apps, settings & CAN sync
        ├── shinewave-card.js                   # Phase 1: Audio widget UI, equalizer aura, source picker & desktop sim
        ├── shinewave-studio.js                 # Phase 1: Full Studio modal, pastel waveform, card-style time bar & simDb
        ├── cockpit-boot.js                     # Phase 2: Post-first-paint asynchronous loader for 3D pipeline
        ├── cockpit-3d.js                       # Phase 2: Three.js scene, shader glass, dynamic paint & freeze control
        ├── car-model-data.js                   # Embedded base64 GLB car model & light textures
        ├── three.min.js                        # Three.js WebGL runtime
        ├── OrbitControls.js                    # 360° turntable camera controls
        └── GLTFLoader.js                       # Binary GLTF parser
```

---

## Two-Phase Instant Boot Pipeline

To prevent synchronous parsing of `three.min.js`, `OrbitControls.js`, `GLTFLoader.js`, and `car-model-data.js` from delaying cold startup on Snapdragon head units, the launcher boots in two phases:

### Phase 1 — Instant Interactive HUD (`< 80 ms`)
1. `MainActivity.setupWebView()` initializes the WebView dark canvas background (`0xFF061014`).
2. `app.html` loads local CSS (`cockpit-theme.css`, `shinewave-card.css`, `shinewave-studio.css`) and local `woff2` fonts.
3. `cockpit-core.js`, `cockpit-ui.js`, `shinewave-card.js`, and `shinewave-studio.js` execute immediately without blocking on Three.js.
4. Clock, CAN telemetry cards, quick apps, and the `#swcard` audio widget become interactive on Frame 1.

### Phase 2 — Asynchronous 3D Vehicle Engine (Post-Paint)
1. Right after Phase 1 paints, `cockpit-boot.js` dynamically injects `three.min.js`, `OrbitControls.js`, `GLTFLoader.js`, `car-model-data.js`, and `cockpit-3d.js`.
2. When full-screen modals such as the Shine Wave Studio open, `window.setCar3DFrozen(true)` pauses the WebGL render loop to keep UI interactions silky smooth.

---

## Key Cross-Layer Interfaces (`window.AndroidBridge`)
- **Native → WebView:**
  - `window.onCanTelemetry(json)` updates speed, coolant, ambient temp, doors, lights, sunroof, locks, and TPMS.
  - `window.onShineWaveState(json)` updates `#swcard` and syncs the open Shine Wave Studio header, repeat, shuffle, metadata, and artwork.
  - `window.onShineWaveLocalData` / `onShineWaveRadioData` / `onShineWaveInternetData` / `onShineWaveAppsData` / `onShineWaveQueueData` populate Studio tabs asynchronously.
  - `applySavedSettingsJson(json)` restores persisted language, car personalization, and lock toggles.
- **WebView → Native (`window.AndroidBridge`):**
  - Vehicle & System: `postCanCommand(cmd, data)`, `launchApp(pkg)`, `openCamera360()`, `getInstalledAppsJson()`, `openConnectivitySettings(type)`, `setWifiEnabled(bool)`, `setBluetoothEnabled(bool)`.
  - Shine Wave Engine: `sendShineWaveCommand(cmd)`, `seekMediaTo(sec)`, `toggleShineWaveShuffle()`, `cycleShineWaveRepeat()`, `browseShineWaveLocal(...)`, `getShineWaveRadioState()`, `saveShineWaveRadioFavorite(freqKhz, name)`, `searchShineWaveInternetRadio(...)`, `getShineWaveExternalApps()`, `requestShineWaveQueue()`, `getAudioVisualizerData()`.
