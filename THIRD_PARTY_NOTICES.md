# Third-Party Notices

Titan Cockpit Launcher includes some files that were **not** written for this project.
They are listed here with their owners and terms. The project's own [LICENSE](LICENSE)
does **not** apply to them: each one keeps its owner's terms.

---

## Open-source parts (allowed to share, with their notices)

| Part | Files | Owner | License | Full text |
|---|---|---|---|---|
| Three.js (3D engine) | `assets/js/three.min.js` | three.js authors | MIT | [licenses/MIT-three.js.txt](licenses/MIT-three.js.txt) |
| OrbitControls, GLTFLoader (from the Three.js examples) | `assets/js/OrbitControls.js`, `assets/js/GLTFLoader.js` | three.js authors | MIT | [licenses/MIT-three.js.txt](licenses/MIT-three.js.txt) |
| Noto Sans Arabic (font) | `assets/fonts/NotoSansArabic.woff2` | The Noto Project Authors | SIL Open Font License 1.1 | [licenses/OFL-NotoSansArabic.txt](licenses/OFL-NotoSansArabic.txt) |
| Plus Jakarta Sans (font) | `assets/fonts/PlusJakartaSans.woff2` | The Plus Jakarta Sans Project Authors | SIL Open Font License 1.1 | [licenses/OFL-PlusJakartaSans.txt](licenses/OFL-PlusJakartaSans.txt) |

`OrbitControls.js` has been changed for this project (touch screens use touch events only).

---

## Car-maker interface classes (used only so the launcher can talk to the car)

| Part | Files | Notes |
|---|---|---|
| Qinggan CAN-bus interface definitions | `src/com/qinggan/canbus/VehicleState.java`, `src/com/qinggan/canbus/AirConditionState.java` | Describe the commands and values of the head unit's own CAN-bus service (`com.qinggan.canbus.service`). They must match the head unit exactly, including the order of every entry, because the car reads each entry's position. They are included only for interoperability with that service. Qinggan's rights in them are not changed by this project's license. |

---

## Car-maker graphics (taken from the car's original head-unit software)

| Part | Files | Changes made for this launcher |
|---|---|---|
| 3D car model | `assets/models/c65_car.glb` | Mesh and material adjustments |
| Headlight and tail-light textures | `assets/textures/lights/*.png` | Mesh and material adjustments |
| App icon | `res/drawable/ic_launcher.png` | Visual cleanup |
| Settings gear image | `assets/setting_gear_refined.png` | Visual cleanup |
| Tyre-pressure (TPMS) image | `assets/tpms_graphic_transparent.png` | Visual cleanup |

These files were extracted from the original software of the car's head unit and then
adjusted for this launcher (confirmed by the author in
[issue #15](https://github.com/Saed-magdy/Titan-Launcher/issues/15)). They belong to the
vehicle manufacturer or its head-unit supplier, not to this project.

- They are **not** offered under this project's [LICENSE](LICENSE), and the changes made
  to them do not change who owns them.
- They are included only so the launcher can show the same car on the same car's screen.
  Do not reuse them anywhere else.
- If you are the owner of any of these files and want them removed, please open an issue
  and they will be removed.

---

## Names and trademarks

"Dongfeng", "Aeolus", "Shine" and "Qinggan" are names and trademarks of their owners. They
are used here only to say which car and head unit this launcher is made for.
**This project is not affiliated with, endorsed by, or supported by Dongfeng Motor, Qinggan,
or any vehicle manufacturer.**
