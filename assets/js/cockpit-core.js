/**
 * ============================================================================
 * COCKPIT CORE — Shared State, Viewport Scaling & Localization (Phase 1)
 * ============================================================================
 * Zero dependency on Three.js so the launcher shell parses and renders
 * the HUD, clock, telemetry, controls, and audio card in < 80ms.
 */

/* --- Deferred 3D Engine Shared Handles (populated by cockpit-3d.js) --- */
let clock = null;
let scene = null;
let camera = null;
let renderer = null;
let controls = null;
let carModel = null;
let nodes = {};
let hinges = {};
let leftLightMaterials = [];
let rightLightMaterials = [];
let trunkMaterial = null;
let lightTextures = {};
let maskedEmissiveTextures = {
  left_jinguang: null,
  left_yuanguang: null,
  right_jinguang: null,
  right_yuanguang: null
};
let wheelPivots = { flSteer: null, flRoll: null, frSteer: null, frRoll: null, rlRoll: null, rrRoll: null };
let wheelRollAngle = 0;
let currentSteerRad = 0;
let headlightBeamsGroup = null;
let groundBeamMesh = null;
let groundBeamMaterial = null;
let volumetricBeamMeshes = [];
let volumetricBeamMaterial = null;
let plateTexture = null;

/* --- 3D Window & Sunroof Mesh Targets --- */
const windowMeshes = { fl: null, fr: null, rl: null, rr: null };
const windowOffsets = { fl: 0, fr: 0, rl: 0, rr: 0 };
const targetWindowOffsets = { fl: 0, fr: 0, rl: 0, rr: 0 };
const windowStates = { fl: false, fr: false, rl: false, rr: false };
const windowLastDirection = { fl: 'down', fr: 'down', rl: 'down', rr: 'down' };

let sunroofMesh = null;
let sunroofPivot = null;
const SUNROOF_PIVOT_X = 0;
const SUNROOF_PIVOT_Y = 737.0;
const SUNROOF_PIVOT_Z = 184.72;
let targetSunroofRotX = 0;
let targetSunroofPosZ = SUNROOF_PIVOT_Z;
let targetSunroofPosY = SUNROOF_PIVOT_Y;

/* --- Dynamic Paint Texture Handles --- */
let carPaintCanvas = null;
let carPaintCtx = null;
let carPaintTexture = null;
let rawCheqiImg = null;

/* --- Camera Orbit & Demand-Driven Render State --- */
let renderRequested = true;
let isUserInteracting = false;
const HERO_CAM_ANGLE = 0.713;
let HERO_CAM_POS = null;
const CAM_ORBIT_RADIUS = 6876.77;
const CAM_ORBIT_Y = 2100;
let CAM_TARGET = null;
let camTween = null;
let autoReturnTimer = null;
let autoReturnDeadlineMs = 0;
let isCanvasTouched = false;
let touchStartedAtMs = 0;
let idleRenderCountdown = 60;
let lastRenderTime = 0;
const targetFps = 60;
const frameInterval = 1000 / targetFps;

/* --- Hardware Cooldown & Sync Timers --- */
let flasherTimer = 0;
let flasherPhase = false;
let toastTimer = null;
let manualDoorLockUntil = { fl: 0, fr: 0, rl: 0, rr: 0, trunk: 0 };
let lastCanDoors = { fl: null, fr: null, rl: null, rr: null, trunk: null };
let lastCanLights = { lowBeam: null, highBeam: null, fogFront: null, posLight: null, hazard: null, dirL: false, dirR: false };
let manualSunroofCooldown = 0;
let srLongPressed = false;
let srPressTimer = null;
let sunroofInactivityTimer = null;
let manualLightCooldown = 0;

function requestRender() {
  idleRenderCountdown = 45;
  renderRequested = true;
  if (typeof window._wake3DLoop === 'function') window._wake3DLoop();
}

/* ============================================================================
   VIEWPORT AUTO-SCALING & SCROLL LOCK
   ============================================================================ */
function autoFitViewport() {
  const vp = document.getElementById('cockpit-viewport');
  if (!vp) return;
  const screenW = window.innerWidth;
  const screenH = window.innerHeight;
  const baseW = 960;
  const minH = 1080;

  const scale = Math.min(screenW / baseW, screenH / minH);
  const scaledW = screenW / scale;
  const scaledH = screenH / scale;

  vp.style.transformOrigin = 'top left';
  vp.style.transform = `scale(${scale})`;
  vp.style.left = '0px';
  vp.style.top = '0px';
  vp.style.width = `${scaledW}px`;
  vp.style.height = `${scaledH}px`;
}

window.addEventListener('scroll', function() {
  if (window.scrollX !== 0 || window.scrollY !== 0) window.scrollTo(0, 0);
}, { passive: false });

document.addEventListener('scroll', function() {
  if (document.documentElement.scrollTop !== 0) document.documentElement.scrollTop = 0;
  if (document.body.scrollTop !== 0) document.body.scrollTop = 0;
}, { passive: false });

/* ============================================================================
   CENTRAL APPLICATION STATE & FALLBACK MEDIA PRESETS
   ============================================================================ */
const state = {
  doors: { fl: 0, fr: 0, rl: 0, rr: 0, trunk: 0 },
  targetDoors: { fl: 0, fr: 0, rl: 0, rr: 0, trunk: 0 },
  lights: { dipped: false, main: false, fog: false, position: false },
  flashers: { left: false, right: false, hazard: false },
  sunroof: 'close',
  carColor: '#e2e8f0',
  roofOption: 'black',
  seatOption: 'sport',
  lipOption: 'orange',
  plateText: 'EGY | 123',
  lang: 'en',
  defaultPage: 'main',
  tpmsWarnings: { fl: false, fr: false, rl: false, rr: false },
  media: {
    source: 'shinewave',
    playing: true,
    progressPct: 42,
    elapsedSec: 84,
    totalSec: 225,
    trackIndex: 0
  },
  telemetry: {
    speed: 0,
    coolant: 90,
    ambient: 24,
    fuelCons: 6.2,
    range: 290,
    fuelPct: 31
  }
};

const MEDIA_SOURCES = {
  shinewave: {
    tracks: [
      { title: 'ShineWave Audio', artist: 'Lossless Hi-Fi Cockpit Sound' },
      { title: 'Cyber Pulse (Drive Mix)', artist: 'Aeolus Digital Acoustics \u2022 96kHz' },
      { title: 'Neon Horizon', artist: 'Dongfeng Sound Engineering' },
      { title: 'Velocity SoundStage', artist: 'ShineWave Dolby Atmos EQ' }
    ]
  },
  radio: {
    tracks: [
      { title: 'FM 100.6 MHz \u2022 Nogoum FM', artist: 'RDS Live Broadcast \u2022 Stereo HD' },
      { title: 'FM 92.7 MHz \u2022 Mega FM', artist: 'Cairo Radio Stream \u2022 RDS Active' },
      { title: 'FM 104.2 MHz \u2022 Nile FM', artist: 'Hit Music Station \u2022 SWC Ready' }
    ]
  },
  bt: {
    tracks: [
      { title: 'Bluetooth Audio \u2022 Cyber Drive', artist: 'Connected Phone \u2022 A2DP High-Bitrate' },
      { title: 'Midnight Horizon (Original Mix)', artist: 'Bluetooth Media Stream \u2022 AVRCP' },
      { title: 'Aeolus Acoustic Master', artist: 'Wireless Smartphone Audio' }
    ]
  },
  storage: {
    tracks: [
      { title: 'Internal Storage \u2022 Track 01', artist: 'Lossless Car Audio \u2022 /sdcard/Music' },
      { title: 'Desert Highway (Remastered)', artist: 'Local Head-Unit Storage \u2022 24-Bit' }
    ]
  },
  usb: {
    tracks: [
      { title: 'USB Drive \u2022 Folder 01 / Track 04', artist: 'External USB Media \u2022 MP3 320kbps' },
      { title: 'Synthwave Night Cruiser', artist: 'USB OTG Media Player' }
    ]
  }
};

function showLauncherToast(msg, durationMs) {
  const el = document.getElementById('launcher-toast');
  if (!el) return;
  el.innerText = msg;
  el.style.display = 'block';
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    el.style.display = 'none';
  }, durationMs || 2400);
}

/* ============================================================================
   UNIFIED LOCALIZATION, ARABIC-INDIC DIGITS & APP NAME MAPPER
   ============================================================================ */
const AR_DIGITS = ['\u0660', '\u0661', '\u0662', '\u0663', '\u0664', '\u0665', '\u0666', '\u0667', '\u0668', '\u0669'];

window.toLangDigits = function(val) {
  const str = String(val === undefined || val === null ? '' : val);
  const isAr = (typeof state !== 'undefined' && state.lang === 'ar') || (document.documentElement.lang === 'ar');
  if (!isAr) return str;
  return str.replace(/[0-9]/g, function(d) { return AR_DIGITS[Number(d)]; });
};

window.localizeAppName = function(pkg, rawName, isAr) {
  const p = String(pkg || '').toLowerCase();
  const raw = String(rawName || '').replace(/\u00C3\u0082\u00C2\u00B0|\u00C2\u00B0/g, '\u00B0').trim();
  const r = raw.toLowerCase();
  if (isAr) {
    let arLabel = '';
    if (p === 'cam360' || r.includes('360')) arLabel = '\u0643\u0627\u0645\u064A\u0631\u0627 \u0663\u0666\u0660\u00B0 \u0627\u0644\u0645\u062D\u064A\u0637\u064A\u0629';
    else if (p === 'vehicle' || r.includes('vehicle control')) arLabel = '\u062A\u062D\u0643\u0645 \u0627\u0644\u0633\u064A\u0627\u0631\u0629';
    else if (p.includes('spotify') || r.includes('spotify')) arLabel = '\u0633\u0628\u0648\u062A\u064A\u0641\u0627\u064A';
    else if (p === 'music' || r === 'music' || r.includes('music player') || p.includes('music')) arLabel = '\u0645\u0634\u063A\u0644 \u0627\u0644\u0645\u0648\u0633\u064A\u0642\u0649';
    else if (p === 'maps' || p.includes('apps.maps') || r.includes('google maps') || r === 'maps') arLabel = '\u062E\u0631\u0627\u0626\u0637 \u062C\u0648\u062C\u0644';
    else if (p.includes('waze') || r.includes('waze')) arLabel = '\u0627\u0644\u0645\u0644\u0627\u062D\u0629 \u0648\u064A\u0632';
    else if (p.includes('youtube') || r.includes('youtube')) arLabel = '\u064A\u0648\u062A\u064A\u0648\u0628';
    else if (p === 'radar' || p.includes('radarbot') || r.includes('radar')) arLabel = '\u0643\u0627\u0634\u0641 \u0627\u0644\u0631\u0627\u062F\u0627\u0631';
    else if (p === 'video' || p.includes('vlc') || r.includes('video') || r === 'vlc') arLabel = '\u0645\u0634\u063A\u0644 \u0627\u0644\u0641\u064A\u062F\u064A\u0648';
    else if (p === 'radio' || r.includes('radio') || r.includes('fm')) arLabel = '\u0627\u0644\u0631\u0627\u062F\u064A\u0648';
    else if (p.includes('phone') || p.includes('dialer') || r.includes('bluetooth phone') || r === 'phone') arLabel = '\u0647\u0627\u062A\u0641 \u0627\u0644\u0628\u0644\u0648\u062A\u0648\u062B';
    else if (p.includes('zlink') || p.includes('carlink') || r.includes('carplay')) arLabel = '\u0622\u0628\u0644 \u0643\u0627\u0631\u0628\u0644\u0627\u064A';
    else if (p.includes('gearhead') || r.includes('android auto')) arLabel = '\u0623\u0646\u062F\u0631\u0648\u064A\u062F \u0623\u0648\u062A\u0648';
    else if (p.includes('dashcam') || r.includes('dashcam') || r.includes('dvr')) arLabel = '\u0643\u0627\u0645\u064A\u0631\u0627 \u0627\u0644\u062A\u0633\u062C\u064A\u0644';
    else if (p.includes('tpms') || r.includes('tpms') || r.includes('tyre pressure')) arLabel = '\u0636\u063A\u0637 \u0627\u0644\u0625\u0637\u0627\u0631\u0627\u062A';
    else if (p.includes('voice') || r.includes('voice assistant')) arLabel = '\u0627\u0644\u0645\u0633\u0627\u0639\u062F \u0627\u0644\u0635\u0648\u062A\u064A';
    else if (p.includes('.eq') || p.includes('dsp') || r.includes('equalizer') || r.includes(' eq')) arLabel = '\u0645\u0648\u0632\u0639 \u0627\u0644\u0635\u0648\u062A';
    else if (p.includes('ambient') || r.includes('ambient')) arLabel = '\u0627\u0644\u0625\u0636\u0627\u0621\u0629 \u0627\u0644\u0645\u062D\u064A\u0637\u064A\u0629';
    else if (p.includes('documentsui') || p.includes('file') || r.includes('file') || r === 'files') arLabel = '\u0645\u062F\u064A\u0631 \u0627\u0644\u0645\u0644\u0641\u0627\u062A';
    else if (p.includes('chrome') || r.includes('chrome')) arLabel = '\u0645\u062A\u0635\u0641\u062D \u0643\u0631\u0648\u0645';
    else if (p.includes('weather') || r.includes('weather')) arLabel = '\u0627\u0644\u0637\u0642\u0633';
    else if (p.includes('deskclock') || r.includes('clock')) arLabel = '\u0627\u0644\u0633\u0627\u0639\u0629 \u0648\u0627\u0644\u0645\u0646\u0628\u0647';
    else if (p.includes('gallery') || r.includes('gallery')) arLabel = '\u0645\u0639\u0631\u0636 \u0627\u0644\u0635\u0648\u0631';
    else if (p.includes('calculator') || r.includes('calculator')) arLabel = '\u0627\u0644\u0622\u0644\u0629 \u0627\u0644\u062D\u0627\u0633\u0628\u0629';
    else if (p.includes('vending') || r.includes('app store') || r.includes('play store')) arLabel = '\u0645\u062A\u062C\u0631 \u0627\u0644\u062A\u0637\u0628\u064A\u0642\u0627\u062A';
    else if (p.includes('settings') || r === 'settings' || r === 'system settings') arLabel = '\u0625\u0639\u062F\u0627\u062F\u0627\u062A \u0627\u0644\u0646\u0638\u0627\u0645';
    else if (p.includes('com.titan.pip') || p === 'pip' || r.includes('titan pip')) arLabel = '\u062A\u0642\u0633\u064A\u0645 \u0627\u0644\u0634\u0627\u0634\u0629 PiP';
    else if (p === 'clean' || r.includes('device care') || r.includes('ram')) arLabel = '\u062A\u0646\u0638\u064A\u0641 \u0627\u0644\u0630\u0627\u0643\u0631\u0629';
    else arLabel = raw;
    return window.toLangDigits(arLabel);
  }
  if (p.includes('com.titan.pip') || p === 'pip' || r.includes('titan pip')) return 'Titan PiP';
  return raw || 'App';
};

const I18N = {
  ar: {
    'm-title': 'إعدادات السيارة والواجهة',
    'tab-lbl-locks': 'إعدادات القفل',
    'tab-lbl-launcher': 'إعدادات اللانشر',
    'tab-lbl-vehicle': 'تخصيص السيارة',
    'm-sec-lang': 'اللغة',
    'lang-en': 'الإنجليزية',
    'lang-ar': 'العربية',
    'm-sec-color': 'لون طلاء السيارة',
    'm-sec-roof': 'لون السقف',
    'roof-btn-black': 'سقف أسود',
    'roof-btn-body': 'لون الهيكل',
    'm-sec-seat': 'لون الفرش',
    'seat-btn-sport': 'رياضي ملون',
    'seat-btn-black': 'أسود بالكامل',
    'seat-btn-yellow': 'رياضي أصفر',
    'm-sec-lip': 'لمسة الصدام الأمامي',
    'lip-btn-orange': 'رياضي برتقالي',
    'lip-btn-yellow': 'رياضي أصفر',
    'm-sec-plate': 'نص لوحة السيارة',
    'm-sec-security': 'إعدادات الأمان والقفل',
    'lbl-lock-speed': 'قفل تلقائي عند سرعة ١٠ كم/س',
    'lbl-lock-walk': 'قفل تلقائي عند الابتعاد',
    'lbl-lock-off': 'فتح تلقائي عند إطفاء المحرك',
    'm-sec-launcher': 'إعدادات مشغل النظام',
    'lbl-default-launcher': 'تعيين كلانشر افتراضي',
    'desc-default-launcher': 'تشغيل اللانشر تلقائياً مع زر الرئيسية',
    'btn-set-default-launcher': 'تعيين الآن',
    'lbl-auto-boot': 'تشغيل تلقائي مع فتح الشاشة',
    'desc-auto-boot': 'فتح اللانشر تلقائياً عند إقلاع النظام',
    'lbl-watchdog': 'حماية إعادة التشغيل التلقائي',
    'desc-watchdog': 'إعادة التشغيل مرتين فقط عند أي خطأ، ثم التحويل للانشر السيارة الأصلي لمنع التعليق',
    'sunroof-modal-title': 'التحكم في فتحة السقف',
    'app-drawer-title': 'التطبيقات المثبتة',
    'quick-app-modal-title': 'اختر التطبيق',
    'btn-sr-open': 'فتح',
    'btn-sr-close': 'إغلاق',
    'btn-sr-tilt': 'إمالة',
    'btn-sr-stop': 'إيقاف',
    'lbl-save-settings': 'حفظ الإعدادات',
    'save-success-btn': '✓ تم حفظ الإعدادات بنجاح',
    'save-toast-txt': '✓ تم حفظ وتثبيت جميع الإعدادات',
    'tpms-card-title': 'ضغط الإطارات',
    'tpms-card-overlay-msg': '✓ تم ضبط ضغط الإطارات',
    'btn-reset-tpms': 'إعادة ضبط',
    'tpms-confirm-msg': 'يرجى التأكد أولاً من ضبط ضغط جميع الإطارات للمعدل الطبيعي',
    'btn-tpms-cancel': 'إلغاء',
    'btn-tpms-sure': 'تأكيد',
    'tag-inst-fuel': 'لحظي',
    'tag-tripa': 'الرحلة أ',
    'unit-inst-fuel': 'لتر / ١٠٠',
    'unit-avg-fuel': 'لتر / ١٠٠',
    'unit-range': 'كم',
    'unit-fuel-liters': 'لتر',
    'unit-ambient': '\u00B0\u0645',
    'lbl-coolant': 'حرارة المحرك',
    'tag-coolant': 'حرارة المحرك',
    'tag-range': 'المدى المتبقي',
    'speed-unit-lbl': 'كم/س',
    'lbl-fuel': 'الوقود',
    'lbl-ambient': 'الحرارة',
    'lbl-inst-fuel': 'لحظي',
    'lbl-range': 'المدى'
  },
  en: {
    'm-title': 'Vehicle & Cockpit Settings',
    'tab-lbl-locks': 'Lock Settings',
    'tab-lbl-launcher': 'Launcher & System',
    'tab-lbl-vehicle': 'Vehicle 3D Personalization',
    'm-sec-lang': 'Language',
    'lang-en': 'English',
    'lang-ar': 'Arabic',
    'm-sec-color': 'Vehicle Paint Color',
    'm-sec-roof': 'Roof Color',
    'roof-btn-black': 'Two Tone',
    'roof-btn-body': 'One Tone',
    'm-sec-seat': 'Seat Upholstery Color',
    'seat-btn-sport': 'Sport Multi',
    'seat-btn-black': 'All Black',
    'seat-btn-yellow': 'Sport Yellow',
    'm-sec-lip': 'Front Bumper Lip Accent',
    'lip-btn-orange': 'Advance',
    'lip-btn-yellow': 'Sport',
    'm-sec-plate': 'License Plate Text',
    'm-sec-security': 'Vehicle Security & Locks',
    'lbl-lock-speed': 'Auto-Lock at 10 km/h',
    'lbl-lock-walk': 'Walk-Away Auto-Lock',
    'lbl-lock-off': 'Engine Shutdown Auto-Unlock',
    'm-sec-launcher': 'Launcher System Integration',
    'lbl-default-launcher': 'Default Home Launcher',
    'desc-default-launcher': 'Set Titan Launcher as default system home',
    'btn-set-default-launcher': 'Set Default',
    'lbl-auto-boot': 'Auto-Start on Boot / Screen',
    'desc-auto-boot': 'Launch automatically on system boot / screen wake',
    'lbl-watchdog': 'Auto-Restart & Crash Loop Breaker',
    'desc-watchdog': 'Restart max 2 times on crash, then fallback to factory car launcher',
    'sunroof-modal-title': 'Sunroof Control',
    'app-drawer-title': 'Installed Applications',
    'quick-app-modal-title': 'Choose Application',
    'btn-sr-open': 'Open',
    'btn-sr-close': 'Close',
    'btn-sr-tilt': 'Tilt',
    'btn-sr-stop': 'Stop',
    'lbl-save-settings': 'Save Settings',
    'save-success-btn': '✓ Saved Successfully',
    'save-toast-txt': '✓ Settings saved permanently',
    'tpms-card-title': 'TPMS',
    'tpms-card-overlay-msg': '✓ TPMS RESET COMPLETED',
    'btn-reset-tpms': 'RESET',
    'tpms-confirm-msg': 'Please ensure the selected tire is inflated to normal first',
    'btn-tpms-cancel': 'Cancel',
    'btn-tpms-sure': 'Confirm',
    'tag-inst-fuel': 'INSTANT',
    'tag-tripa': 'TRIP A',
    'unit-inst-fuel': 'L/100',
    'unit-avg-fuel': 'L/100',
    'unit-range': 'km',
    'unit-fuel-liters': 'L',
    'unit-ambient': '°C',
    'lbl-coolant': 'COOLANT',
    'tag-coolant': 'COOLANT',
    'tag-range': 'RANGE',
    'speed-unit-lbl': 'km/h',
    'lbl-fuel': 'FUEL',
    'lbl-ambient': 'OUTSIDE',
    'lbl-inst-fuel': 'INSTANT',
    'lbl-range': 'RANGE'
  }
};

function setLanguage(lang) {
  if (lang !== 'ar' && lang !== 'en') lang = 'en';
  state.lang = lang;
  document.documentElement.lang = lang;
  try { localStorage.setItem('shine_cockpit_lang', lang); } catch (e) {}

  const bAr = document.getElementById('lang-ar');
  const bEn = document.getElementById('lang-en');
  const dirTargets = [
    document.getElementById('modal-box-inner'),
    document.querySelector('#tpms-modal .modal-box'),
    document.querySelector('#tpms-confirm-modal .modal-box'),
    document.querySelector('#quick-app-select-modal .modal-box')
  ];

  if (lang === 'ar') {
    if (bAr) bAr.classList.add('active');
    if (bEn) bEn.classList.remove('active');
    dirTargets.forEach(box => {
      if (box) { box.classList.remove('ltr-mode'); box.classList.add('rtl-mode'); }
    });
    const drawerOverlay = document.getElementById('app-drawer-overlay');
    if (drawerOverlay) drawerOverlay.setAttribute('dir', 'rtl');
  } else {
    if (bEn) bEn.classList.add('active');
    if (bAr) bAr.classList.remove('active');
    dirTargets.forEach(box => {
      if (box) { box.classList.remove('rtl-mode'); box.classList.add('ltr-mode'); }
    });
    const drawerOverlay = document.getElementById('app-drawer-overlay');
    if (drawerOverlay) drawerOverlay.setAttribute('dir', 'ltr');
  }

  const dict = I18N[lang];
  for (let id in dict) {
    const el = document.getElementById(id);
    if (el) {
      const txtSpan = el.querySelector('.sr-btn-text');
      if (txtSpan) txtSpan.innerText = dict[id];
      else el.innerText = dict[id];
    }
  }
  if (typeof updateAmbientDisplay === 'function' && typeof cachedAmbient !== 'undefined') {
    updateAmbientDisplay(cachedAmbient);
  }
  if (typeof updateFuelDisplays === 'function') updateFuelDisplays();
  if (typeof updateCanStatusBadge === 'function' && typeof cachedCanConnected !== 'undefined') {
    updateCanStatusBadge(cachedCanConnected);
  }
  if (typeof updateTpmsOverallBadge === 'function') updateTpmsOverallBadge();
  if (typeof updateClock === 'function') updateClock();
  if (typeof renderQuickAppsDeck === 'function') renderQuickAppsDeck();
  if (typeof window.refreshShineWaveLanguage === 'function') {
    window.refreshShineWaveLanguage();
  }

  idleRenderCountdown = 120;
  requestRender();
}
window.setLanguage = setLanguage;
