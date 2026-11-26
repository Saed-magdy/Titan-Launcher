/**
 * ============================================================================
 * COCKPIT CORE — Shared State, Viewport Scaling & Localization (Phase 1)
 * ============================================================================
 * Zero dependency on Three.js so the launcher shell parses and renders
 * the HUD, clock, telemetry, controls, and audio card in < 80ms.
 */

/**
 * SECURITY: makes a piece of text safe to place inside the screen's HTML.
 *
 * App names, song titles, etc. come from outside (any installed app can choose its own name).
 * If a name contained HTML code such as <img onerror=...>, the screen would run it, and that
 * code could reach the car controls through AndroidBridge. This turns the special characters
 * (< > & " ') into harmless look-alikes, so the name is shown as plain text and never run.
 */
window.escapeHtml = function (text) {
  return String(text == null ? '' : text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
};

window.isArabicLang = function () {
  try {
    if (typeof state !== 'undefined' && state && state.lang) return state.lang === 'ar';
    return (localStorage.getItem('shine_cockpit_lang') || '') === 'ar';
  } catch (e) { return false; }
};

window.isRussianLang = function () {
  try {
    if (typeof state !== 'undefined' && state && state.lang) return state.lang === 'ru';
    return (localStorage.getItem('shine_cockpit_lang') || '') === 'ru';
  } catch (e) { return false; }
};

window.stripMediaExtension = function (str) {
  if (!str || typeof str !== 'string') return str || '';
  return str.replace(/\.(mp3|m4a|flac|wav|aac|ogg|wma|ape|alac|opus|mp4)$/i, '').trim();
};

window.formatTimeSec = function (sec, localizeDigits) {
  if (!sec || isNaN(sec) || sec < 0) sec = 0;
  var mm = Math.floor(sec / 60);
  var ss = Math.floor(sec % 60);
  var res = (mm < 10 ? '0' + mm : String(mm)) + ':' + (ss < 10 ? '0' + ss : String(ss));
  if (localizeDigits && window.isArabicLang() && typeof window.toLangDigits === 'function') {
    return window.toLangDigits(res);
  }
  return res;
};

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
let wheelPivots = { flSteer: null, flRoll: null, frSteer: null, frRoll: null, rlRoll: null, rrRoll: null };
let wheelRollAngle = 0;
let currentSteerRad = 0;
let headlightBeamsGroup = null;
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
  numFormat: (function() {
    try { return localStorage.getItem('shine_cockpit_num_format') || 'western'; } catch(e) { return 'western'; }
  })(),
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
  telemetry: {
    speed: 0,
    coolant: 90,
    ambient: 24,
    fuelCons: 6.2,
    range: 290,
    fuelPct: 31
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
  let useEastern = false;
  if (typeof state !== 'undefined' && state.numFormat) {
    useEastern = (state.numFormat === 'eastern');
  } else {
    try {
      useEastern = (localStorage.getItem('shine_cockpit_num_format') === 'eastern');
    } catch (e) {
      useEastern = false;
    }
  }
  if (!useEastern) return str;
  return str.replace(/[0-9]/g, function(d) { return AR_DIGITS[Number(d)]; });
};

window.setNumberFormat = function(fmt) {
  if (fmt !== 'eastern' && fmt !== 'western') fmt = 'western';
  if (typeof state !== 'undefined') state.numFormat = fmt;
  try { localStorage.setItem('shine_cockpit_num_format', fmt); } catch (e) {}

  const bWest = document.getElementById('num-fmt-western');
  const bEast = document.getElementById('num-fmt-eastern');
  if (fmt === 'eastern') {
    if (bEast) bEast.classList.add('active');
    if (bWest) bWest.classList.remove('active');
  } else {
    if (bWest) bWest.classList.add('active');
    if (bEast) bEast.classList.remove('active');
  }

  if (typeof updateClock === 'function') updateClock();
  if (typeof updateFuelDisplays === 'function') updateFuelDisplays();
  if (typeof updateTpmsOverallBadge === 'function') updateTpmsOverallBadge();
  if (typeof renderQuickAppsDeck === 'function') renderQuickAppsDeck();
  if (typeof window.refreshShineWaveLanguage === 'function') {
    window.refreshShineWaveLanguage();
  }
  if (typeof requestRender === 'function') requestRender();
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
    'lang-ru': 'الروسية',
    'm-sec-num-format': 'شكل الأرقام',
    'num-fmt-western': '1, 2, 3',
    'num-fmt-eastern': '١، ٢، ٣',
    'lbl-donate-title': '☕ لدعم استمرار وتطوير البرنامج',
    'lbl-donate-desc': 'امسح الرمز بكاميرا الهاتف للدعم عبر Buy Me a Coffee أو InstaPay',
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
    'lbl-check-updates': 'البحث عن تحديثات',
    'desc-check-updates': 'التحقق من وجود إصدارات جديدة على GitHub',
    'btn-check-updates': 'فحص الآن',
    'lbl-default-launcher': 'تعيين كلانشر افتراضي',
    'desc-default-launcher': 'تشغيل اللانشر تلقائياً مع زر الرئيسية',
    'lbl-settings-backup': 'نسخ واستعادة الإعدادات',
    'desc-settings-backup': 'احفظ إعدادات اللانشر على فلاشة USB أو ملف، واستعدها لاحقاً',
    'btn-settings-backup': 'نسخ احتياطي',
    'btn-settings-restore': 'استعادة',
    'btn-restore-cancel': 'إلغاء',
    'btn-restore-confirm': 'استبدال',
    'backup_ok': '✓ تم حفظ الإعدادات',
    'backup_ok_where': '✓ تم حفظ الإعدادات في {w}',
    'backup_fail': 'تعذّر حفظ الإعدادات',
    'restore_bad': 'هذا الملف ليس نسخة احتياطية من Titan Launcher',
    'restore_none': 'لم يتم العثور على نسخة احتياطية',
    'restore_confirm': 'استبدال إعداداتك الحالية بهذه النسخة ({d})؟',
    'restore_done': '✓ تمت استعادة الإعدادات',
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
    'lbl-check-updates': 'Check for Updates',
    'desc-check-updates': 'Check GitHub Releases for new APK versions',
    'btn-check-updates': 'Check Now',
    'lbl-default-launcher': 'Default Home Launcher',
    'desc-default-launcher': 'Set Titan Launcher as default system home',
    'lbl-settings-backup': 'Back Up & Restore Settings',
    'desc-settings-backup': 'Save your launcher settings to a USB stick or a file, and load them back',
    'btn-settings-backup': 'Back Up',
    'btn-settings-restore': 'Restore',
    'btn-restore-cancel': 'Cancel',
    'btn-restore-confirm': 'Replace',
    'backup_ok': '✓ Settings saved',
    'backup_ok_where': '✓ Settings saved to {w}',
    'backup_fail': 'Could not save the settings',
    'restore_bad': 'This file is not a Titan Launcher settings backup',
    'restore_none': 'No settings backup found',
    'restore_confirm': 'Replace your current settings with this backup ({d})?',
    'restore_done': '✓ Settings restored',
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
    'lbl-range': 'RANGE',
    'lang-ru': 'Russian',
    'm-sec-num-format': 'Number Format',
    'num-fmt-western': '1, 2, 3',
    'num-fmt-eastern': '١، ٢، ٣',
    'lbl-donate-title': '☕ Buy Me a Coffee / Support Dev',
    'lbl-donate-desc': 'Scan with your phone camera to support via Buy Me a Coffee or InstaPay'
  },
  ru: {
    'm-title': 'Настройки автомобиля и кокпита',
    'tab-lbl-locks': 'Настройки замков',
    'tab-lbl-launcher': 'Лаунчер и система',
    'tab-lbl-vehicle': '3D персонализация авто',
    'm-sec-lang': 'Язык интерфейса',
    'lang-en': 'Английский',
    'lang-ar': 'Арабский',
    'lang-ru': 'Русский',
    'm-sec-num-format': 'Формат чисел',
    'num-fmt-western': '1, 2, 3',
    'num-fmt-eastern': '١، ٢، ٣',
    'm-sec-color': 'Цвет кузова',
    'm-sec-roof': 'Цвет крыши',
    'roof-btn-black': 'Двухцветный',
    'roof-btn-body': 'В цвет кузова',
    'm-sec-seat': 'Цвет обивки салона',
    'seat-btn-sport': 'Спорт мульти',
    'seat-btn-black': 'Полностью черный',
    'seat-btn-yellow': 'Спорт желтый',
    'm-sec-lip': 'Акцент переднего бампера',
    'lip-btn-orange': 'Advance (Оранжевый)',
    'lip-btn-yellow': 'Sport (Желтый)',
    'm-sec-plate': 'Текст номерного знака',
    'm-sec-security': 'Безопасность и замки',
    'lbl-lock-speed': 'Автозапирание при 10 км/ч',
    'lbl-lock-walk': 'Запирание при удалении',
    'lbl-lock-off': 'Отпирание при выключении двигателя',
    'm-sec-launcher': 'Системная интеграция лаунчера',
    'lbl-check-updates': 'Проверка обновлений',
    'desc-check-updates': 'Проверить новые версии APK на GitHub',
    'btn-check-updates': 'Проверить',
    'lbl-default-launcher': 'Лаунчер по умолчанию',
    'desc-default-launcher': 'Сделать Titan Launcher главным экраном',
    'lbl-settings-backup': 'Резервная копия настроек',
    'desc-settings-backup': 'Сохранить настройки на USB-флешку или в файл и восстановить их',
    'btn-settings-backup': 'Сохранить',
    'btn-settings-restore': 'Восстановить',
    'btn-restore-cancel': 'Отмена',
    'btn-restore-confirm': 'Заменить',
    'backup_ok': '✓ Настройки сохранены',
    'backup_ok_where': '✓ Настройки сохранены в {w}',
    'backup_fail': 'Не удалось сохранить настройки',
    'restore_bad': 'Это не резервная копия настроек Titan Launcher',
    'restore_none': 'Резервная копия не найдена',
    'restore_confirm': 'Заменить текущие настройки этой копией ({d})?',
    'restore_done': '✓ Настройки восстановлены',
    'btn-set-default-launcher': 'Назначить',
    'lbl-auto-boot': 'Автозапуск при старте / экране',
    'desc-auto-boot': 'Автоматический запуск при включении экрана',
    'lbl-watchdog': 'Защита от сбоев (Watchdog)',
    'desc-watchdog': 'Перезапуск до 2 раз, затем возврат к заводскому лаунчеру',
    'sunroof-modal-title': 'Управление люком',
    'app-drawer-title': 'Установленные приложения',
    'quick-app-modal-title': 'Выберите приложение',
    'btn-sr-open': 'Открыть',
    'btn-sr-close': 'Закрыть',
    'btn-sr-tilt': 'Наклон',
    'btn-sr-stop': 'Стоп',
    'lbl-save-settings': 'Сохранить настройки',
    'save-success-btn': '✓ Успешно сохранено',
    'save-toast-txt': '✓ Настройки успешно сохранены',
    'tpms-card-title': 'TPMS Давление в шинах',
    'tpms-card-overlay-msg': '✓ СБРОС TPMS ВЫПОЛНЕН',
    'btn-reset-tpms': 'СБРОС',
    'tpms-confirm-msg': 'Пожалуйста, убедитесь, что давление во всех шинах в норме',
    'btn-tpms-cancel': 'Отмена',
    'btn-tpms-sure': 'Подтвердить',
    'tag-inst-fuel': 'ТЕКУЩИЙ',
    'tag-tripa': 'ПОЕЗДКА A',
    'unit-inst-fuel': 'л/100',
    'unit-avg-fuel': 'л/100',
    'unit-range': 'км',
    'unit-fuel-liters': 'л',
    'unit-ambient': '°C',
    'lbl-coolant': 'ОХЛАЖД. ЖИДКОСТЬ',
    'tag-coolant': 'ОХЛАЖД. ЖИДКОСТЬ',
    'tag-range': 'ЗАПАС ХОДА',
    'speed-unit-lbl': 'км/ч',
    'lbl-fuel': 'ТОПЛИВО',
    'lbl-ambient': 'УЛИЦА',
    'lbl-inst-fuel': 'ТЕКУЩИЙ',
    'lbl-range': 'ЗАПАС ХОДА',
    'lbl-donate-title': '☕ Поддержать разработку (Buy Me a Coffee)',
    'lbl-donate-desc': 'Отсканируйте камерой смартфона для поддержки (Buy Me a Coffee / InstaPay)'
  }
};

function setLanguage(lang) {
  if (lang !== 'ar' && lang !== 'en' && lang !== 'ru') lang = 'en';
  state.lang = lang;
  document.documentElement.lang = lang;
  try { localStorage.setItem('shine_cockpit_lang', lang); } catch (e) {}

  const bAr = document.getElementById('lang-ar');
  const bEn = document.getElementById('lang-en');
  const bRu = document.getElementById('lang-ru');
  const dirTargets = [
    document.querySelector('.credits-modal-box'),
    document.getElementById('modal-box-inner'),
    document.querySelector('#tpms-modal .modal-box'),
    document.getElementById('settings-restore-box'),
    document.querySelector('#tpms-confirm-modal .modal-box'),
    document.querySelector('#quick-app-select-modal .modal-box')
  ];

  if (bAr) bAr.classList.toggle('active', lang === 'ar');
  if (bEn) bEn.classList.toggle('active', lang === 'en');
  if (bRu) bRu.classList.toggle('active', lang === 'ru');

  if (lang === 'ar') {
    dirTargets.forEach(box => {
      if (box) { box.classList.remove('ltr-mode'); box.classList.add('rtl-mode'); }
    });
    const drawerOverlay = document.getElementById('app-drawer-overlay');
    if (drawerOverlay) drawerOverlay.setAttribute('dir', 'rtl');
  } else {
    dirTargets.forEach(box => {
      if (box) { box.classList.remove('rtl-mode'); box.classList.add('ltr-mode'); }
    });
    const drawerOverlay = document.getElementById('app-drawer-overlay');
    if (drawerOverlay) drawerOverlay.setAttribute('dir', 'ltr');
  }

  // Sync number format button active states
  const curFmt = state.numFormat || 'western';
  const bWest = document.getElementById('num-fmt-western');
  const bEast = document.getElementById('num-fmt-eastern');
  if (bWest) bWest.classList.toggle('active', curFmt === 'western');
  if (bEast) bEast.classList.toggle('active', curFmt === 'eastern');

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
