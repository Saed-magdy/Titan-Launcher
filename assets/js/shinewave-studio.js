/* ShineWave Integrated Glass Studio - Pastel Smoked-Glass UI inside Titan Launcher.
   Features: Top-Left Ergonomic Offset Layout, Enlarged Driving Controls,
   Live Pastel Waveform Scrubber, Named Radio Presets, & Full Interactive Simulation.
*/
(function () {
  'use strict';

  var activeTab = 'LOCAL_MUSIC';
  var queueViewOpen = false;
  var localFolder = '';
  var localQuery = '';
  var internetTab = 'popular';
  var internetQuery = '';
  var editingRadioName = '';
  var localData = null;
  var radioData = null;
  var internetData = null;
  var appsData = null;
  var queueData = null;
  var isUserScrubbing = false;
  var waveRafId = 0;
  var wavePhase = 0;
  var timeWaveAmp = 0;
  var mascotEnabled = true;
  var mascotPhase = 0;
  var mascotBlinkTimer = 0;

  try {
    var storedMascot = localStorage.getItem('shinewave_mascot_enabled');
    if (storedMascot !== null) mascotEnabled = (storedMascot === '1');
  } catch (e) {}

  var TABS = [
    { key: 'LOCAL_MUSIC',    en: 'USB & Files',    ar: 'فلاشة / ملفات',  ru: 'USB и файлы',       icon: 'M15 7v4h1v2h-3V5h2l-3-4-3 4h2v8H8v-2.07c.7-.37 1.2-1.08 1.2-1.93 0-1.21-.99-2.2-2.2-2.2-1.21 0-2.2.99-2.2 2.2 0 .85.5 1.56 1.2 1.93V13c0 1.11.89 2 2 2h3v3.05c-.71.37-1.2 1.1-1.2 1.95 0 1.22.99 2.2 2.2 2.2 1.21 0 2.2-.98 2.2-2.2 0-.85-.49-1.58-1.2-1.95V15h3c1.11 0 2-.89 2-2v-2h1V7h-4z' },
    { key: 'BLUETOOTH',      en: 'Bluetooth',      ar: 'بلوتوث',         ru: 'Bluetooth',         icon: 'M17.71 7.71L12 2h-1v7.59L6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 11 14.41V22h1l5.71-5.71-4.3-4.29 4.3-4.29zM13 5.83l1.88 1.88L13 9.59V5.83zm1.88 10.46L13 18.17v-3.76l1.88 1.88z' },
    { key: 'CAR_RADIO',      en: 'Car Radio',      ar: 'راديو السيارة',  ru: 'Радио авто',        icon: 'M3.24 6.15C2.51 6.43 2 7.17 2 8v12c0 1.1.89 2 2 2h16c1.11 0 2-.9 2-2V8c0-1.11-.89-2-2-2H8.3l8.26-3.34L15.88 1 3.24 6.15zM7 20c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3zm13-8h-2v-2h-2v2H4V8h16v4z' },
    { key: 'INTERNET_RADIO', en: 'Internet Radio', ar: 'راديو الإنترنت', ru: 'Онлайн радио',     icon: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z' },
    { key: 'EXTERNAL_APP',   en: 'Media Apps',     ar: 'تطبيقات الميديا',ru: 'Медиа приложения',  icon: 'M4 8h4V4H4v4zm6 12h4v-4h-4v4zm-6 0h4v-4H4v4zm0-6h4v-4H4v4zm6 0h4v-4h-4v4zm6-10v4h4V4h-4zm-6 4h4V4h-4v4zm6 6h4v-4h-4v4zm0 6h4v-4h-4v4z' }
  ];

  /* ---------- Interactive Desktop Simulation State (when AndroidBridge absent) ---------- */
  var simDb = {
    localTracks: [
      { id: 'sim_1', folder: '/USB/Arabic Hits', title: 'عكس اللي شايفينها', artist: 'عمرو دياب', durationSec: 245 },
      { id: 'sim_2', folder: '/USB/Arabic Hits', title: 'تملي معاك', artist: 'عمرو دياب', durationSec: 268 },
      { id: 'sim_3', folder: '/USB/Arabic Hits', title: 'أنا لحبيبي', artist: 'فيروز', durationSec: 215 },
      { id: 'sim_4', folder: '/USB/Chill Night', title: 'Midnight Highway Drive', artist: 'Synthwave', durationSec: 232 },
      { id: 'sim_5', folder: '/USB/Chill Night', title: 'Velvet Horizon', artist: 'Deep Chill', durationSec: 198 }
    ],
    localIdx: 0,
    radio: {
      radioType: 'FM', band: 'FM', freqKhz: 100600, status: 'إشارة قوية • ستيريو',
      stations: [
        { name: 'نجوم FM', freq: 100600, favorite: true },
        { name: 'راديو 9090', freq: 90900, favorite: true },
        { name: 'راديو هيتس', freq: 88200, favorite: true },
        { name: 'ميجا FM', freq: 92700, favorite: false },
        { name: 'نغم FM', freq: 105300, favorite: false }
      ],
      dabItems: [
        { mediaId: 'dab_1', title: 'BBC Radio 1 DAB+' },
        { mediaId: 'dab_2', title: 'Nile FM Digital' }
      ]
    },
    internet: [
      { uuid: 'in_1', name: 'Nogoum FM 100.6', url: 'http://example.com/1', country: 'EG', favorite: true },
      { uuid: 'in_2', name: 'Rotana FM', url: 'http://example.com/2', country: 'SA', favorite: true },
      { uuid: 'in_3', name: 'Al-Jazeera Mubasher Audio', url: 'http://example.com/3', country: 'QA', favorite: false },
      { uuid: 'in_4', name: 'Lounge Chillout Radio', url: 'http://example.com/4', country: 'FR', favorite: false }
    ],
    apps: [
      { pkg: 'com.qinggan.app.music', label: 'الموسيقى', icon: '', selected: false },
      { pkg: 'com.spotify.music', label: 'Spotify', icon: '', selected: true },
      { pkg: 'com.anghami', label: 'Anghami', icon: '', selected: false },
      { pkg: 'com.google.android.apps.youtube.music', label: 'YouTube Music', icon: '', selected: false }
    ]
  };

  function bridge() { return window.AndroidBridge || null; }
  function isAr() {
    try {
      if (typeof state !== 'undefined' && state && state.lang) return state.lang === 'ar';
      return (localStorage.getItem('shine_cockpit_lang') || '') === 'ar';
    } catch (e) { return false; }
  }
  function isRu() {
    try {
      if (typeof state !== 'undefined' && state && state.lang) return state.lang === 'ru';
      return (localStorage.getItem('shine_cockpit_lang') || '') === 'ru';
    } catch (e) { return false; }
  }
  function fmtTime(sec) {
    sec = Math.max(0, Math.floor(sec || 0));
    var m = Math.floor(sec / 60), s = sec % 60;
    var txt = (m < 10 ? '0' + m : m) + ':' + (s < 10 ? '0' + s : s);
    return (window.toLangDigits) ? window.toLangDigits(txt) : txt;
  }
  function escHtml(str) {
    return String(str || '').replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  /* ---------- Simulation Helpers when no AndroidBridge ---------- */
  window.simSwitchShineWaveSource = function (key) {
    var m = window.ShineWaveCardState;
    if (!m) return;
    m.source = key;
    if (key === 'LOCAL_MUSIC') {
      var t = simDb.localTracks[simDb.localIdx] || simDb.localTracks[0];
      m.title = t.title; m.artist = t.artist; m.dur = t.durationSec; m.pos = 24; m.playing = true;
    } else if (key === 'BLUETOOTH') {
      m.title = 'Starboy - The Weeknd'; m.artist = 'Bluetooth Audio'; m.dur = 230; m.pos = 48; m.playing = true;
    } else if (key === 'CAR_RADIO') {
      var fStr = simDb.radio.band === 'AM' ? ('AM ' + simDb.radio.freqKhz + ' kHz') : ('FM ' + (simDb.radio.freqKhz / 1000).toFixed(1) + ' MHz');
      m.title = fStr; m.artist = 'راديو السيارة'; m.dur = 0; m.pos = 0; m.playing = true;
    } else if (key === 'INTERNET_RADIO') {
      m.title = simDb.internet[0].name; m.artist = 'Internet Radio'; m.dur = 0; m.pos = 0; m.playing = true;
    } else if (key === 'EXTERNAL_APP') {
      var sel = simDb.apps.filter(function (a) { return a.selected; })[0] || simDb.apps[0];
      m.title = sel.label + ' — Now Playing'; m.artist = sel.label; m.dur = 205; m.pos = 18; m.playing = true;
    }
    if (typeof window.refreshShineWaveCardUI === 'function') window.refreshShineWaveCardUI();
    renderPlayerHeader();
  };

  window.simStepShineWaveTrack = function (delta) {
    var m = window.ShineWaveCardState;
    if (!m) return;
    if (m.source === 'LOCAL_MUSIC') {
      simDb.localIdx = (simDb.localIdx + delta + simDb.localTracks.length) % simDb.localTracks.length;
      window.simSwitchShineWaveSource('LOCAL_MUSIC');
    } else if (m.source === 'CAR_RADIO') {
      simDb.radio.freqKhz += (delta * 100);
      window.simSwitchShineWaveSource('CAR_RADIO');
      if (activeTab === 'CAR_RADIO') renderBody();
    }
  };

  function simBrowseLocal(folder, query) {
    var q = (query || '').trim().toLowerCase();
    var folders = [];
    var tracks = [];
    if (q) {
      tracks = simDb.localTracks.filter(function (t) {
        return t.title.toLowerCase().indexOf(q) >= 0 || t.artist.toLowerCase().indexOf(q) >= 0;
      });
    } else if (!folder) {
      folders = [
        { path: '/USB/Arabic Hits', name: 'Arabic Hits', count: 3 },
        { path: '/USB/Chill Night', name: 'Chill Night', count: 2 }
      ];
    } else {
      tracks = simDb.localTracks.filter(function (t) { return t.folder === folder; });
    }
    localData = {
      currentFolder: folder || '',
      currentFolderName: folder ? folder.split('/').pop() : '',
      parentFolder: '',
      totalTracks: simDb.localTracks.length,
      hasSavedQueue: true,
      folders: folders,
      tracks: tracks
    };
    renderBody();
  }

  /* ---------- DOM Creation ---------- */
  function ensureDom() {
    if (document.getElementById('sw-studio-overlay')) return;
    var overlay = document.createElement('div');
    overlay.id = 'sw-studio-overlay';
    overlay.className = 'interactive';
    overlay.innerHTML =
      '<div class="swst-top-brand-bar">' +
        '<div class="swst-brand-emblem"><svg viewBox="0 0 24 24"><path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"/></svg></div>' +
        '<div class="swst-brand-title">Shine Wave Player</div>' +
      '</div>' +
      '<div class="swst-panel" id="swst-panel">' +
        '<div class="swst-header">' +
          '<div class="swst-tabs" id="swst-tabs"></div>' +
        '</div>' +
        '<div class="swst-player-card">' +
          '<div class="swst-player-top">' +
            '<div class="swst-cover-wrap" id="swst-cover-wrap">' +
              '<svg viewBox="0 0 24 24"><path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"/></svg>' +
              '<img class="swst-cover-img" id="swst-cover-img" alt="">' +
            '</div>' +
            '<div class="swst-meta">' +
              '<div class="swst-track-title" id="swst-title">ShineWave Audio</div>' +
              '<div class="swst-track-artist" id="swst-artist"></div>' +
              '<div class="swst-wave-wrap" id="swst-wave-wrap"><canvas class="swst-wave-canvas" id="swst-wave-canvas"></canvas></div>' +
              '<div class="swst-progress-row">' +
                '<span class="swst-time-txt" id="swst-pos">00:00</span>' +
                '<div class="swst-time-bar-wrap" id="swst-time-bar-wrap"><canvas class="swst-time-bar-canvas" id="swst-time-bar-canvas"></canvas></div>' +
                '<span class="swst-time-txt" id="swst-dur">00:00</span>' +
              '</div>' +
            '</div>' +
            '<div class="swst-mascot-wrap" id="swst-mascot-wrap">' +
              '<canvas class="swst-mascot-canvas" id="swst-mascot-canvas"></canvas>' +
            '</div>' +
          '</div>' +
          '<div class="swst-controls-row">' +
            '<button type="button" class="swst-ctrl-btn" id="swst-btn-shuffle"></button>' +
            '<div class="swst-ctrls-main">' +
              '<button type="button" class="swst-ctrl-btn" id="swst-btn-prev"><svg viewBox="0 0 24 24"><path d="M6 6h2v12H6zm3.5 6l8.5 6V6z"/></svg></button>' +
              '<button type="button" class="swst-ctrl-btn primary" id="swst-btn-play"><svg viewBox="0 0 24 24"><path id="swst-play-path" d="M8 5v14l11-7z"/></svg></button>' +
              '<button type="button" class="swst-ctrl-btn" id="swst-btn-next"><svg viewBox="0 0 24 24"><path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/></svg></button>' +
            '</div>' +
            '<div style="display:flex;align-items:center;gap:8px;">' +
              '<button type="button" class="swst-ctrl-btn" id="swst-btn-repeat"></button>' +
              '<button type="button" class="swst-ctrl-btn" id="swst-btn-queue"></button>' +
              '<button type="button" class="swst-ctrl-btn" id="swst-btn-mascot"></button>' +
            '</div>' +
          '</div>' +
        '</div>' +
        '<div class="swst-body" id="swst-body"></div>' +
      '</div>';

    var viewport = document.getElementById('cockpit-viewport') || document.body;
    viewport.appendChild(overlay);

    var panel = document.getElementById('swst-panel');
    var clickInsidePanel = false;
    if (panel) {
      panel.addEventListener('click', function () {
        clickInsidePanel = true;
      });
    }
    overlay.addEventListener('click', function (e) {
      var inside = clickInsidePanel || (panel && panel.contains(e.target));
      clickInsidePanel = false;
      if (!inside) window.closeShineWaveStudio();
    });

    document.getElementById('swst-btn-prev').addEventListener('click', function () {
      var b = bridge();
      if (b) b.sendShineWaveCommand('previous');
      else if (window.simStepShineWaveTrack) window.simStepShineWaveTrack(-1);
    });
    document.getElementById('swst-btn-play').addEventListener('click', function () {
      var m = window.ShineWaveCardState || {};
      var b = bridge();
      if (b) b.sendShineWaveCommand('toggle');
      else {
        m.playing = !m.playing;
        if (window.refreshShineWaveCardUI) window.refreshShineWaveCardUI();
        renderPlayerHeader();
      }
    });
    document.getElementById('swst-btn-next').addEventListener('click', function () {
      var b = bridge();
      if (b) b.sendShineWaveCommand('next');
      else if (window.simStepShineWaveTrack) window.simStepShineWaveTrack(1);
    });
    document.getElementById('swst-btn-shuffle').addEventListener('click', function () {
      var m = window.ShineWaveCardState || {};
      var b = bridge();
      if (b) b.toggleShineWaveShuffle();
      else {
        m.shuffle = !m.shuffle;
        if (window.refreshShineWaveCardUI) window.refreshShineWaveCardUI();
        renderPlayerHeader();
      }
    });
    document.getElementById('swst-btn-repeat').addEventListener('click', function () {
      var m = window.ShineWaveCardState || {};
      var b = bridge();
      if (b) b.cycleShineWaveRepeat();
      else {
        m.repeatMode = (m.repeatMode === 'OFF') ? 'ALL' : (m.repeatMode === 'ALL' ? 'ONE' : 'OFF');
        if (window.refreshShineWaveCardUI) window.refreshShineWaveCardUI();
        renderPlayerHeader();
      }
    });
    document.getElementById('swst-btn-queue').addEventListener('click', function () {
      queueViewOpen = !queueViewOpen;
      if (queueViewOpen) {
        if (bridge() && bridge().requestShineWaveQueue) bridge().requestShineWaveQueue();
        else queueData = { items: simDb.localTracks.map(function (t, i) { return { index: i, id: t.id, title: t.title, artist: t.artist, durationSec: t.durationSec, active: i === simDb.localIdx }; }) };
      }
      renderPlayerHeader();
      renderBody();
    });
    document.getElementById('swst-btn-mascot').addEventListener('click', function () {
      mascotEnabled = !mascotEnabled;
      try { localStorage.setItem('shinewave_mascot_enabled', mascotEnabled ? '1' : '0'); } catch (e) {}
      renderPlayerHeader();
    });

    bindWaveScrub();
  }

  function bindWaveScrub() {
    var waveWrap = document.getElementById('swst-wave-wrap');
    var timeWrap = document.getElementById('swst-time-bar-wrap');
    if (!waveWrap && !timeWrap) return;
    var activeWrap = null;
    var activePad = 0;

    function calcRatio(e) {
      if (!activeWrap) return 0;
      var pt = (e.touches && e.touches[0]) ? e.touches[0] : e;
      var r = activeWrap.getBoundingClientRect();
      var usable = Math.max(1, r.width - activePad * 2);
      return Math.max(0, Math.min(1, (pt.clientX - r.left - activePad) / usable));
    }
    function onMove(e) {
      if (!isUserScrubbing || !activeWrap) return;
      var m = window.ShineWaveCardState || {};
      var effDur = m.dur > 0 ? m.dur : 225;
      m.pos = Math.round(calcRatio(e) * effDur);
      window.syncShineWaveStudioTime(m.pos, effDur);
      drawStudioWaveform();
      drawStudioTimeBar();
      if (window.refreshShineWaveCardUI) window.refreshShineWaveCardUI();
      if (e.cancelable) e.preventDefault();
    }
    function onEnd() {
      if (!isUserScrubbing) return;
      isUserScrubbing = false;
      activeWrap = null;
      var m = window.ShineWaveCardState || {};
      if (bridge()) bridge().seekMediaTo(Math.round(m.pos || 0));
    }
    function attach(elTarget, pad) {
      if (!elTarget) return;
      elTarget.addEventListener('touchstart', function (e) {
        isUserScrubbing = true; activeWrap = elTarget; activePad = pad; onMove(e);
      }, { passive: false });
      elTarget.addEventListener('touchmove', onMove, { passive: false });
      elTarget.addEventListener('touchend', onEnd);
      elTarget.addEventListener('mousedown', function (e) {
        isUserScrubbing = true; activeWrap = elTarget; activePad = pad; onMove(e);
      });
    }
    attach(waveWrap, 0);
    attach(timeWrap, 10);
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onEnd);
  }

  /* ---------- Live Pastel Waveform & Card-Style Squiggle Time Bar ---------- */
  function startWaveLoop() {
    if (waveRafId) return;
    function tick() {
      waveRafId = 0;
      var overlay = document.getElementById('sw-studio-overlay');
      if (!overlay || !overlay.classList.contains('active')) return;
      drawStudioWaveform();
      drawStudioTimeBar();
      drawStudioMascot();
      waveRafId = requestAnimationFrame(tick);
    }
    waveRafId = requestAnimationFrame(tick);
  }

  function stopWaveLoop() {
    if (waveRafId) { cancelAnimationFrame(waveRafId); waveRafId = 0; }
  }

  function drawStudioTimeBar() {
    var c = document.getElementById('swst-time-bar-canvas');
    var wrap = document.getElementById('swst-time-bar-wrap');
    if (!c || !wrap) return;
    var w = wrap.clientWidth || 360;
    var h = wrap.clientHeight || 24;
    var dpr = window.devicePixelRatio || 1;
    if (c.width !== Math.round(w * dpr) || c.height !== Math.round(h * dpr)) {
      c.width = Math.round(w * dpr);
      c.height = Math.round(h * dpr);
    }
    var ctx = c.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);

    var m = window.ShineWaveCardState || {};
    var targetAmp = m.playing ? 1.0 : 0.0;
    timeWaveAmp += (targetAmp - timeWaveAmp) * 0.12;

    var pad = 10, x0 = pad, x1 = w - pad, mid = h / 2;
    var effDur = m.dur > 0 ? m.dur : 225;
    var frac = Math.max(0, Math.min(1, (m.pos || 0) / effDur));
    var xp = x0 + (x1 - x0) * frac;
    ctx.lineCap = 'round';

    ctx.beginPath();
    ctx.moveTo(xp, mid);
    ctx.lineTo(x1, mid);
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.18)';
    ctx.lineWidth = 4;
    ctx.stroke();

    var A = timeWaveAmp * 4.0, k = (Math.PI * 2) / 28;
    ctx.beginPath();
    for (var x = x0; x <= xp; x += 1.5) {
      var edge = Math.min(1, (xp - x) / 16);
      var y = mid + Math.sin(x * k - wavePhase) * A * edge;
      if (x === x0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    }
    ctx.lineTo(xp, mid);

    var g = ctx.createLinearGradient(x0, 0, Math.max(xp, x0 + 1), 0);
    g.addColorStop(0, '#93c5fd');
    g.addColorStop(1, '#a7f3d0');
    ctx.strokeStyle = g;
    ctx.lineWidth = 4;
    ctx.shadowColor = 'rgba(147, 197, 253, 0.72)';
    ctx.shadowBlur = 10;
    ctx.stroke();

    ctx.shadowBlur = 12;
    ctx.shadowColor = '#bae6fd';
    ctx.fillStyle = '#f8fafc';
    ctx.beginPath();
    ctx.arc(xp, mid, 6.8, 0, Math.PI * 2);
    ctx.fill();
    ctx.shadowBlur = 0;
  }

  function drawStudioWaveform() {
    var c = document.getElementById('swst-wave-canvas');
    var wrap = document.getElementById('swst-wave-wrap');
    if (!c || !wrap) return;
    var w = wrap.clientWidth || 480;
    var h = wrap.clientHeight || 32;
    var dpr = window.devicePixelRatio || 1;
    if (c.width !== Math.round(w * dpr) || c.height !== Math.round(h * dpr)) {
      c.width = Math.round(w * dpr);
      c.height = Math.round(h * dpr);
    }
    var ctx = c.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);

    var m = window.ShineWaveCardState || {};
    var spec = (window.getShineWaveAudioSpectrum) ? window.getShineWaveAudioSpectrum() : { beat: 0, energy: 0, bands: [] };
    var effDur = m.dur > 0 ? m.dur : 225;
    var frac = Math.max(0, Math.min(1, (m.pos || 0) / effDur));
    if (m.playing) wavePhase += 0.075;

    var barCount = 28;
    var gap = 5;
    var barW = Math.max(4, (w - (barCount - 1) * gap) / barCount);
    var midY = h / 2;

    for (var i = 0; i < barCount; i++) {
      var pct = i / (barCount - 1);
      var played = pct <= frac;

      // Realistic audio spectrum: mirror from center or logarithmic distribution across bands
      var bVal = 0.15;
      if (m.playing) {
        if (spec.bands && spec.bands.length) {
          // Map to spectrum bands smoothly across the whole width
          var bIdx = Math.floor(Math.abs(pct - 0.5) * 2 * (spec.bands.length - 1));
          var rawBand = spec.bands[bIdx] || (spec.energy * 0.8) || 0.35;
          // Harmonic wave motion across all bars so none remain frozen
          var waveMod = 0.45 * Math.sin(i * 0.52 + wavePhase) + 0.25 * Math.cos(i * 0.88 - wavePhase * 1.3);
          bVal = Math.max(0.18, Math.min(1.0, rawBand * 0.75 + waveMod * 0.35 + (spec.beat * 0.25)));
        } else {
          var pulse = Math.sin(i * 0.48 + wavePhase) * 0.45 + Math.cos(i * 0.92 - wavePhase * 1.4) * 0.35 + 0.45;
          bVal = Math.max(0.18, Math.min(0.95, pulse));
        }
      } else {
        // Elegant resting baseline
        bVal = 0.14 + 0.08 * Math.sin(i * 0.6);
      }

      var barH = Math.max(4, bVal * (h - 2));
      var x = i * (barW + gap);
      var y = midY - barH / 2;

      // Premium glowing gradient styling
      var grad = ctx.createLinearGradient(0, y, 0, y + barH);
      if (played) {
        grad.addColorStop(0, '#7dd3fc');
        grad.addColorStop(0.5, '#93c5fd');
        grad.addColorStop(1, '#a7f3d0');
      } else {
        grad.addColorStop(0, 'rgba(255, 255, 255, 0.45)');
        grad.addColorStop(1, 'rgba(186, 230, 253, 0.15)');
      }

      ctx.fillStyle = grad;
      ctx.beginPath();
      var rad = Math.min(barW / 2, barH / 2);
      if (ctx.roundRect) ctx.roundRect(x, y, barW, barH, rad);
      else ctx.rect(x, y, barW, barH);
      ctx.fill();

      // Subtle top/bottom neon glow cap on energetic peaks
      if (m.playing && bVal > 0.65) {
        ctx.fillStyle = '#ffffff';
        ctx.beginPath();
        ctx.arc(x + barW / 2, y + 1.5, 1.2, 0, Math.PI * 2);
        ctx.arc(x + barW / 2, y + barH - 1.5, 1.2, 0, Math.PI * 2);
        ctx.fill();
      }
    }
  }

  /* ---------- Interactive Animated Musical Mascot (دمية الموسيقى) ---------- */
  function drawStudioMascot() {
    var c = document.getElementById('swst-mascot-canvas');
    var wrap = document.getElementById('swst-mascot-wrap');
    if (!c || !wrap || !mascotEnabled) return;

    var w = wrap.clientWidth || 104;
    var h = wrap.clientHeight || 104;
    var dpr = window.devicePixelRatio || 1;
    if (c.width !== Math.round(w * dpr) || c.height !== Math.round(h * dpr)) {
      c.width = Math.round(w * dpr);
      c.height = Math.round(h * dpr);
    }
    var ctx = c.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);

    var m = window.ShineWaveCardState || {};
    var isPlaying = !!m.playing;
    var spec = (window.getShineWaveAudioSpectrum) ? window.getShineWaveAudioSpectrum() : { beat: 0, energy: 0, bands: [] };
    var beatVal = spec.beat || 0;
    var energyVal = spec.energy || 0;

    var cx = w / 2;
    var cy = h / 2 + 6;

    var bounce = 0;
    var tilt = 0;
    var headBob = 0;
    var handBob = 0;
    var earPulse = 0;

    if (isPlaying) {
      mascotPhase += 0.085 + beatVal * 0.09;
      // Groovy bounce with the beat
      bounce = Math.abs(Math.sin(mascotPhase * 1.8)) * (7 + beatVal * 9 + energyVal * 4);
      tilt = Math.sin(mascotPhase * 0.9) * 0.14;
      headBob = Math.sin(mascotPhase * 1.8) * 3;
      handBob = Math.cos(mascotPhase * 1.8) * 7;
      earPulse = Math.sin(mascotPhase * 3.6) * 2 + beatVal * 4;
      mascotBlinkTimer++;
    } else {
      // Gentle calm breathing stance when music is paused
      mascotPhase += 0.035;
      bounce = Math.sin(mascotPhase) * 1.8;
      tilt = Math.sin(mascotPhase * 0.5) * 0.02;
      headBob = 0;
      handBob = 0;
      earPulse = 0;
      mascotBlinkTimer = 0;
    }

    // 1. Soft Dynamic Ground Shadow
    ctx.save();
    var shadowW = 22 - bounce * 0.35;
    var shadowH = Math.max(2, 5.5 - bounce * 0.12);
    var shadowAlpha = Math.max(0.12, 0.45 - bounce * 0.02);
    ctx.fillStyle = 'rgba(0, 0, 0, ' + shadowAlpha.toFixed(2) + ')';
    ctx.beginPath();
    ctx.ellipse(cx, h - 10, Math.max(10, shadowW), Math.max(2, shadowH), 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();

    var bodyY = cy - bounce;

    // 2. Floating Hands / Mittens
    ctx.save();
    var leftHandY = bodyY + 3 + handBob;
    var rightHandY = bodyY + 3 - handBob;
    var handGrad = ctx.createLinearGradient(0, bodyY - 10, 0, bodyY + 10);
    handGrad.addColorStop(0, '#bae6fd');
    handGrad.addColorStop(1, '#38bdf8');

    ctx.fillStyle = handGrad;
    ctx.shadowColor = 'rgba(56, 189, 248, 0.45)';
    ctx.shadowBlur = 6;

    // Left hand
    ctx.beginPath();
    ctx.arc(cx - 24, leftHandY, 5.5, 0, Math.PI * 2);
    ctx.fill();

    // Right hand
    ctx.beginPath();
    ctx.arc(cx + 24, rightHandY, 5.5, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();

    // 3. Body / Torso
    ctx.save();
    var bodyGrad = ctx.createLinearGradient(0, bodyY - 14, 0, bodyY + 12);
    bodyGrad.addColorStop(0, '#e0f2fe');
    bodyGrad.addColorStop(0.5, '#7dd3fc');
    bodyGrad.addColorStop(1, '#0284c7');
    ctx.fillStyle = bodyGrad;
    ctx.shadowColor = 'rgba(14, 165, 233, 0.55)';
    ctx.shadowBlur = 10;

    ctx.beginPath();
    if (ctx.roundRect) ctx.roundRect(cx - 16, bodyY - 12, 32, 22, 11);
    else ctx.rect(cx - 16, bodyY - 12, 32, 22);
    ctx.fill();

    // Chest mini-equalizer / core heart
    ctx.shadowBlur = 0;
    var coreBg = 'rgba(6, 15, 22, 0.7)';
    ctx.fillStyle = coreBg;
    ctx.beginPath();
    if (ctx.roundRect) ctx.roundRect(cx - 9, bodyY - 7, 18, 12, 4);
    else ctx.rect(cx - 9, bodyY - 7, 18, 12);
    ctx.fill();

    // Tiny pulsing chest bars
    var barColors = ['#7dd3fc', '#a7f3d0', '#fde68a'];
    for (var bi = 0; bi < 3; bi++) {
      var barH = isPlaying ? (3 + Math.abs(Math.sin(mascotPhase * 2.5 + bi * 1.2)) * 6) : 3;
      ctx.fillStyle = isPlaying ? barColors[bi] : 'rgba(148, 163, 184, 0.6)';
      ctx.fillRect(cx - 6 + bi * 4.5, bodyY + 3 - barH, 3, barH);
    }
    ctx.restore();

    // 4. Mascot Head & Helmet (Tilts and Bobs)
    ctx.save();
    ctx.translate(cx, bodyY - 18 - headBob);
    ctx.rotate(tilt);

    // Top Antenna Stalk & Glowing Orb
    ctx.strokeStyle = '#bae6fd';
    ctx.lineWidth = 2.5;
    ctx.lineCap = 'round';
    ctx.beginPath();
    ctx.moveTo(0, -18);
    ctx.lineTo(0, -25);
    ctx.stroke();

    var orbGrad = ctx.createRadialGradient(0, -26, 1, 0, -26, 6);
    if (isPlaying) {
      orbGrad.addColorStop(0, '#ffffff');
      orbGrad.addColorStop(0.4, '#6ee7b7');
      orbGrad.addColorStop(1, '#059669');
      ctx.shadowColor = '#6ee7b7';
      ctx.shadowBlur = 12 + beatVal * 8;
    } else {
      orbGrad.addColorStop(0, '#ffffff');
      orbGrad.addColorStop(0.5, '#93c5fd');
      orbGrad.addColorStop(1, '#2563eb');
      ctx.shadowColor = '#93c5fd';
      ctx.shadowBlur = 6;
    }
    ctx.fillStyle = orbGrad;
    ctx.beginPath();
    ctx.arc(0, -26, 4.5 + (isPlaying ? beatVal * 1.5 : 0), 0, Math.PI * 2);
    ctx.fill();
    ctx.shadowBlur = 0;

    // Glowing DJ Headphones (Left & Right)
    var hpGrad = ctx.createLinearGradient(-26, 0, -18, 0);
    hpGrad.addColorStop(0, '#0284c7');
    hpGrad.addColorStop(1, '#38bdf8');

    // Headphone band over head
    ctx.strokeStyle = 'rgba(224, 242, 254, 0.8)';
    ctx.lineWidth = 3.5;
    ctx.beginPath();
    ctx.arc(0, -4, 21, Math.PI * 1.1, Math.PI * 1.9);
    ctx.stroke();

    // Left earcup
    ctx.fillStyle = hpGrad;
    ctx.beginPath();
    if (ctx.roundRect) ctx.roundRect(-25 - earPulse * 0.4, -9, 7 + earPulse * 0.4, 18, 3.5);
    else ctx.rect(-25, -9, 7, 18);
    ctx.fill();

    // Right earcup
    ctx.beginPath();
    if (ctx.roundRect) ctx.roundRect(18, -9, 7 + earPulse * 0.4, 18, 3.5);
    else ctx.rect(18, -9, 7, 18);
    ctx.fill();

    // Earcup neon pulse aura when playing
    if (isPlaying && earPulse > 0.5) {
      ctx.strokeStyle = 'rgba(110, 231, 183, 0.6)';
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ctx.arc(-22, 0, 9 + earPulse * 0.8, Math.PI * 0.6, Math.PI * 1.4);
      ctx.stroke();
      ctx.beginPath();
      ctx.arc(22, 0, 9 + earPulse * 0.8, -Math.PI * 0.4, Math.PI * 0.4);
      ctx.stroke();
    }

    // Outer Helmet Shell
    var headGrad = ctx.createLinearGradient(0, -20, 0, 16);
    headGrad.addColorStop(0, '#ffffff');
    headGrad.addColorStop(0.65, '#e0f2fe');
    headGrad.addColorStop(1, '#bae6fd');
    ctx.fillStyle = headGrad;
    ctx.shadowColor = 'rgba(14, 165, 233, 0.35)';
    ctx.shadowBlur = 10;
    ctx.beginPath();
    if (ctx.roundRect) ctx.roundRect(-20, -18, 40, 32, 16);
    else ctx.rect(-20, -18, 40, 32);
    ctx.fill();

    // Dark Cyber Visor Face Screen
    ctx.shadowBlur = 0;
    ctx.fillStyle = '#061622';
    ctx.beginPath();
    if (ctx.roundRect) ctx.roundRect(-16, -14, 32, 24, 12);
    else ctx.rect(-16, -14, 32, 24);
    ctx.fill();

    // Visor glass subtle top reflection arc
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.35)';
    ctx.lineWidth = 1.8;
    ctx.beginPath();
    ctx.arc(0, -11, 11, Math.PI * 1.15, Math.PI * 1.85);
    ctx.stroke();

    // Digital Eyes & Expression
    var isBlinking = isPlaying && (mascotBlinkTimer % 140 > 132);

    if (isPlaying && !isBlinking) {
      // Happy dynamic digital eyes
      ctx.fillStyle = '#67e8f9';
      ctx.shadowColor = '#67e8f9';
      ctx.shadowBlur = 8;

      var eyeW = 4.2;
      var eyeH = 6.5 + (beatVal * 2.5);

      // Left eye
      ctx.beginPath();
      if (ctx.roundRect) ctx.roundRect(-10, -5 - (eyeH - 6.5) / 2, eyeW, eyeH, 2.2);
      else ctx.rect(-10, -5, eyeW, eyeH);
      ctx.fill();

      // Right eye
      ctx.beginPath();
      if (ctx.roundRect) ctx.roundRect(10 - eyeW, -5 - (eyeH - 6.5) / 2, eyeW, eyeH, 2.2);
      else ctx.rect(10 - eyeW, -5, eyeW, eyeH);
      ctx.fill();

      // Specular eye sparkles
      ctx.fillStyle = '#ffffff';
      ctx.beginPath();
      ctx.arc(-8, -4, 1.2, 0, Math.PI * 2);
      ctx.arc(8, -4, 1.2, 0, Math.PI * 2);
      ctx.fill();

      // Cute rosy blush cheeks
      ctx.shadowBlur = 0;
      ctx.fillStyle = 'rgba(244, 114, 182, 0.45)';
      ctx.beginPath();
      ctx.arc(-10, 4.5, 2.5, 0, Math.PI * 2);
      ctx.arc(10, 4.5, 2.5, 0, Math.PI * 2);
      ctx.fill();

      // Happy smile
      ctx.strokeStyle = '#a7f3d0';
      ctx.lineWidth = 1.8;
      ctx.lineCap = 'round';
      ctx.beginPath();
      ctx.arc(0, 2.5, 3.8, 0.15 * Math.PI, 0.85 * Math.PI);
      ctx.stroke();

    } else if (isPlaying && isBlinking) {
      // Blinking eyes
      ctx.strokeStyle = '#67e8f9';
      ctx.lineWidth = 2;
      ctx.lineCap = 'round';
      ctx.shadowColor = '#67e8f9';
      ctx.shadowBlur = 6;
      ctx.beginPath();
      ctx.moveTo(-11, -2);
      ctx.lineTo(-6, -2);
      ctx.moveTo(6, -2);
      ctx.lineTo(11, -2);
      ctx.stroke();
    } else {
      // Calm sleeping / resting stance when paused
      ctx.strokeStyle = '#93c5fd';
      ctx.lineWidth = 2;
      ctx.lineCap = 'round';
      ctx.shadowColor = '#93c5fd';
      ctx.shadowBlur = 5;

      // Cute sleepy curved lines: (˘ ˘)
      ctx.beginPath();
      ctx.arc(-8, -2, 3.2, Math.PI * 1.1, Math.PI * 1.9);
      ctx.stroke();
      ctx.beginPath();
      ctx.arc(8, -2, 3.2, Math.PI * 1.1, Math.PI * 1.9);
      ctx.stroke();

      // Calm sleeping mouth
      ctx.strokeStyle = 'rgba(186, 230, 253, 0.6)';
      ctx.lineWidth = 1.4;
      ctx.beginPath();
      ctx.moveTo(-2, 3);
      ctx.lineTo(2, 3);
      ctx.stroke();
    }

    ctx.restore();

    // 5. Floating Musical Sparkles when energetic
    if (isPlaying && (beatVal > 0.4 || energyVal > 0.45)) {
      ctx.save();
      var noteY = (bodyY - 26 - ((mascotPhase * 24) % 36));
      var noteX = cx + Math.sin(mascotPhase * 2) * 16;
      var noteAlpha = Math.max(0, 1 - (bodyY - 26 - noteY) / 36);
      ctx.fillStyle = 'rgba(253, 230, 138, ' + noteAlpha.toFixed(2) + ')';
      ctx.font = 'bold 12px sans-serif';
      ctx.fillText('♪', noteX, noteY);
      ctx.restore();
    }
  }

  /* ---------- Public Lifecycle ---------- */
  window.openShineWaveStudio = function (tabKey) {
    ensureDom();
    activeTab = tabKey || (window.ShineWaveCardState && window.ShineWaveCardState.source) || 'LOCAL_MUSIC';
    queueViewOpen = false;
    document.body.classList.add('sw-studio-open');
    if (typeof window.setCar3DFrozen === 'function') {
      window.setCar3DFrozen(true);
    }
    var overlay = document.getElementById('sw-studio-overlay');
    overlay.classList.add('active');
    renderAll();
    loadTabData(activeTab);
    startWaveLoop();
  };

  window.closeShineWaveStudio = function () {
    stopWaveLoop();
    document.body.classList.remove('sw-studio-open');
    var overlay = document.getElementById('sw-studio-overlay');
    if (overlay && overlay.classList.contains('active')) {
      overlay.classList.remove('active');
    }
    if (typeof window.setCar3DFrozen === 'function') {
      window.setCar3DFrozen(false);
    }
  };

  window.refreshShineWaveStudioLanguage = function () {
    var overlay = document.getElementById('sw-studio-overlay');
    if (overlay && overlay.classList.contains('active')) {
      renderAll();
    }
  };

  window.syncShineWaveStudioState = function () {
    var overlay = document.getElementById('sw-studio-overlay');
    if (overlay && overlay.classList.contains('active')) {
      renderPlayerHeader();
    }
  };

  window.syncShineWaveStudioTime = function (posSec, durSec) {
    var overlay = document.getElementById('sw-studio-overlay');
    if (!overlay || !overlay.classList.contains('active')) return;
    var posEl = document.getElementById('swst-pos');
    var durEl = document.getElementById('swst-dur');
    if (posEl) posEl.textContent = fmtTime(posSec);
    if (durEl) durEl.textContent = fmtTime(durSec);
  };

  /* ---------- Native Async Callbacks ---------- */
  window.onShineWaveLocalData = function (data) { localData = data; if (activeTab === 'LOCAL_MUSIC') renderBody(); };
  window.onShineWaveRadioData = function (data) { radioData = data; if (activeTab === 'CAR_RADIO') renderBody(); };
  window.onShineWaveInternetData = function (data) { internetData = data; if (activeTab === 'INTERNET_RADIO') renderBody(); };
  window.onShineWaveAppsData = function (data) { appsData = data; if (activeTab === 'EXTERNAL_APP') renderBody(); };
  window.onShineWaveQueueData = function (data) { queueData = data; if (queueViewOpen) renderBody(); };

  function loadTabData(tab) {
    var b = bridge();
    if (b) {
      if (tab === 'LOCAL_MUSIC' && b.browseShineWaveLocal) b.browseShineWaveLocal(localFolder, localQuery, false);
      else if (tab === 'CAR_RADIO' && b.getShineWaveRadioState) b.getShineWaveRadioState();
      else if (tab === 'INTERNET_RADIO' && b.searchShineWaveInternetRadio) b.searchShineWaveInternetRadio(internetTab, internetQuery);
      else if (tab === 'EXTERNAL_APP' && b.getShineWaveExternalApps) b.getShineWaveExternalApps();
      return;
    }
    // Browser simulation loaders
    if (tab === 'LOCAL_MUSIC') simBrowseLocal(localFolder, localQuery);
    else if (tab === 'CAR_RADIO') { radioData = simDb.radio; renderBody(); }
    else if (tab === 'INTERNET_RADIO') {
      internetData = { stations: simDb.internet.filter(function (s) {
        if (internetTab === 'favorites') return s.favorite;
        if (internetQuery) return s.name.toLowerCase().indexOf(internetQuery.toLowerCase()) >= 0;
        return true;
      }) };
      renderBody();
    } else if (tab === 'EXTERNAL_APP') {
      appsData = { apps: simDb.apps };
      renderBody();
    }
  }

  function renderAll() {
    var panel = document.getElementById('swst-panel');
    if (panel) {
      panel.classList.toggle('rtl-mode', isAr());
      panel.classList.toggle('ltr-mode', !isAr());
    }
    renderTabs();
    renderPlayerHeader();
    renderBody();
  }

  function renderTabs() {
    var tabsEl = document.getElementById('swst-tabs');
    if (!tabsEl) return;
    var ar = isAr();
    tabsEl.innerHTML = TABS.map(function (t) {
      var cls = 'swst-tab' + (t.key === activeTab ? ' active' : '');
      return '<button type="button" class="' + cls + '" data-tab="' + t.key + '">' +
        '<svg viewBox="0 0 24 24"><path d="' + t.icon + '"/></svg>' +
        '<span>' + (ar ? t.ar : (isRu() ? t.ru : t.en)) + '</span></button>';
    }).join('');
    tabsEl.querySelectorAll('.swst-tab').forEach(function (btn) {
      btn.addEventListener('click', function () {
        queueViewOpen = false;
        activeTab = btn.getAttribute('data-tab');
        if (typeof window.setShineWaveSource === 'function') {
          window.setShineWaveSource(activeTab);
        }
        renderTabs();
        renderPlayerHeader();
        renderBody();
        loadTabData(activeTab);
      });
    });
  }

  function renderPlayerHeader() {
    var ar = isAr();
    var card = window.ShineWaveCardState || {};
    var titleEl = document.getElementById('swst-title');
    var artistEl = document.getElementById('swst-artist');
    var coverWrap = document.getElementById('swst-cover-wrap');
    var coverImg = document.getElementById('swst-cover-img');
    var playPath = document.getElementById('swst-play-path');
    var shufBtn = document.getElementById('swst-btn-shuffle');
    var repBtn = document.getElementById('swst-btn-repeat');
    var qBtn = document.getElementById('swst-btn-queue');

    if (titleEl) titleEl.textContent = card.title || (ar ? 'مشغل الصوتيات' : 'ShineWave Audio');
    if (artistEl) artistEl.textContent = card.artist || '';
    if (playPath) playPath.setAttribute('d', card.playing ? 'M6 19h4V5H6v14zm8-14v14h4V5h-4z' : 'M8 5v14l11-7z');

    if (coverWrap && coverImg) {
      if (card.albumArt && card.albumArt.length > 30) {
        coverImg.src = card.albumArt;
        coverWrap.classList.add('has-art');
      } else {
        coverImg.removeAttribute('src');
        coverWrap.classList.remove('has-art');
      }
    }

    if (shufBtn) {
      shufBtn.classList.toggle('shuffle-active', !!card.shuffle);
      shufBtn.innerHTML = '<svg viewBox="0 0 24 24"><path d="M10.59 9.17L5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 17.96 7.46 20 9.5V4h-5.5zm.33 9.41l-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z"/></svg>' +
        '<span>' + (ar ? 'عشوائي' : 'Shuffle') + '</span>';
    }

    if (repBtn) {
      var mode = card.repeatMode || 'OFF';
      repBtn.classList.remove('repeat-all', 'repeat-one');
      if (mode === 'ALL') repBtn.classList.add('repeat-all');
      else if (mode === 'ONE') repBtn.classList.add('repeat-one');
      var repLbl = mode === 'ONE'
        ? (ar ? 'تكرار المقطع' : 'Repeat 1')
        : (mode === 'ALL' ? (ar ? 'تكرار الكل' : 'Repeat All') : (ar ? 'بدون تكرار' : 'Repeat Off'));
      repBtn.innerHTML = '<svg viewBox="0 0 24 24"><path d="M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z"/></svg>' +
        '<span>' + repLbl + '</span>';
    }

    if (qBtn) {
      qBtn.classList.toggle('queue-active', queueViewOpen);
      qBtn.innerHTML = '<svg viewBox="0 0 24 24"><path d="M15 6H3v2h12V6zm0 4H3v2h12v-2zM3 16h8v-2H3v2zM17 6v8.18c-.31-.11-.65-.18-1-.18-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3V8h3V6h-5z"/></svg>' +
        '<span>' + (ar ? 'القائمة' : 'Queue') + '</span>';
    }

    var mascotBtn = document.getElementById('swst-btn-mascot');
    var mascotWrap = document.getElementById('swst-mascot-wrap');
    if (mascotWrap) {
      mascotWrap.style.display = mascotEnabled ? 'flex' : 'none';
    }
    if (mascotBtn) {
      mascotBtn.classList.toggle('mascot-active', mascotEnabled);
      var mSvg = '<svg viewBox="0 0 24 24"><path d="M12 2a2 2 0 0 1 2 2c0 .74-.4 1.39-1 1.73V7h1a7 7 0 0 1 7 7h1a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-1v1a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-1H2a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1h1a7 7 0 0 1 7-7h1V5.73c-.6-.34-1-.99-1-1.73a2 2 0 0 1 2-2M7.5 13A2.5 2.5 0 0 0 5 15.5 2.5 2.5 0 0 0 7.5 18a2.5 2.5 0 0 0 2.5-2.5A2.5 2.5 0 0 0 7.5 13m9 0a2.5 2.5 0 0 0-2.5 2.5 2.5 2.5 0 0 0 2.5 2.5 2.5 2.5 0 0 0 2.5-2.5 2.5 2.5 0 0 0-2.5-2.5"/></svg>';
      var mLbl = mascotEnabled
        ? (ar ? 'الدمية: تشغيل' : (isRu() ? 'Маскот: Вкл' : 'Mascot: On'))
        : (ar ? 'الدمية: إيقاف' : (isRu() ? 'Маскот: Выкл' : 'Mascot: Off'));
      mascotBtn.innerHTML = mSvg + '<span>' + mLbl + '</span>';
    }

    window.syncShineWaveStudioTime(card.pos || 0, card.dur || 0);
  }

  function renderBody() {
    var body = document.getElementById('swst-body');
    if (!body) return;
    if (queueViewOpen) { renderQueueBody(body); return; }
    switch (activeTab) {
      case 'LOCAL_MUSIC': renderLocalBody(body); break;
      case 'BLUETOOTH': renderBluetoothBody(body); break;
      case 'CAR_RADIO': renderRadioBody(body); break;
      case 'INTERNET_RADIO': renderInternetBody(body); break;
      case 'EXTERNAL_APP': renderAppsBody(body); break;
    }
  }

  function renderQueueBody(body) {
    var ar = isAr();
    var items = (queueData && queueData.items) || [];
    if (!items.length) {
      body.innerHTML = '<div class="swst-empty">' + (ar ? 'قائمة التشغيل الحالية فارغة' : 'Active queue is empty') + '</div>';
      return;
    }
    var html = '<div class="swst-section-label">' + (ar ? 'قائمة التشغيل الحالية (' + items.length + ')' : 'Active Queue (' + items.length + ')') + '</div>';
    items.forEach(function (t) {
      html += '<div class="swst-row' + (t.active ? ' active' : '') + '" data-qidx="' + t.index + '">' +
        '<div class="swst-row-icon">' + (t.active ? '▶' : '♪') + '</div>' +
        '<div class="swst-row-info"><div class="swst-row-title">' + escHtml(t.title) + '</div>' +
        '<div class="swst-row-sub">' + escHtml(t.artist) + '</div></div>' +
        '<div class="swst-row-badge">' + fmtTime(t.durationSec) + '</div></div>';
    });
    body.innerHTML = html;
    body.querySelectorAll('[data-qidx]').forEach(function (r) {
      r.addEventListener('click', function () {
        var idx = parseInt(r.getAttribute('data-qidx'), 10);
        if (bridge()) bridge().playShineWaveQueueIndex(idx);
        else { simDb.localIdx = idx; window.simSwitchShineWaveSource('LOCAL_MUSIC'); queueViewOpen = false; renderPlayerHeader(); renderBody(); }
      });
    });
  }

  function renderLocalBody(body) {
    var ar = isAr();
    var d = localData || { currentFolder: '', parentFolder: '', totalTracks: 0, hasSavedQueue: false, folders: [], tracks: [] };
    var html =
      '<div class="swst-toolbar">' +
        '<input type="text" class="swst-search-input" id="swst-local-search" placeholder="' + (ar ? 'ابحث في الملفات والفلاشة...' : 'Search tracks or artists...') + '" value="' + escHtml(localQuery) + '">' +
        (d.hasSavedQueue ? '<button type="button" class="swst-pill-action mint" id="swst-local-resume">▶ ' + (ar ? 'استئناف' : 'Resume') + '</button>' : '') +
        '<button type="button" class="swst-pill-action" id="swst-local-rescan">' + (ar ? 'تحديث' : 'Rescan') + '</button>' +
      '</div>';

    if (d.currentFolder && !localQuery) {
      html += '<div class="swst-row" id="swst-local-up"><div class="swst-row-icon folder">‹</div>' +
        '<div class="swst-row-info"><div class="swst-row-title">' + (ar ? 'المجلد السابق' : 'Parent Folder') + '</div>' +
        '<div class="swst-row-sub">' + escHtml(d.currentFolderName || d.currentFolder) + '</div></div></div>';
    }

    (d.folders || []).forEach(function (f) {
      html += '<div class="swst-row" data-folder="' + escHtml(f.path) + '">' +
        '<div class="swst-row-icon folder">📁</div>' +
        '<div class="swst-row-info"><div class="swst-row-title">' + escHtml(f.name) + '</div>' +
        '<div class="swst-row-sub">' + escHtml(f.count + (ar ? ' ملف صوتي' : ' audio tracks')) + '</div></div>' +
        '<div class="swst-row-badge">›</div></div>';
    });

    (d.tracks || []).forEach(function (t) {
      var curTitle = (window.ShineWaveCardState && window.ShineWaveCardState.title) || '';
      html += '<div class="swst-row' + (curTitle === t.title ? ' active' : '') + '" data-track="' + escHtml(t.id) + '">' +
        '<div class="swst-row-icon">♪</div>' +
        '<div class="swst-row-info"><div class="swst-row-title">' + escHtml(t.title) + '</div>' +
        '<div class="swst-row-sub">' + escHtml(t.artist) + '</div></div>' +
        '<div class="swst-row-badge">' + fmtTime(t.durationSec) + '</div></div>';
    });

    if ((!d.folders || !d.folders.length) && (!d.tracks || !d.tracks.length)) {
      html += '<div class="swst-empty">' + (ar ? 'جاري فحص الذاكرة والفلاشة...' : 'Scanning internal storage & USB...') + '</div>';
    }
    body.innerHTML = html;

    var searchEl = document.getElementById('swst-local-search');
    searchEl.addEventListener('input', function () {
      localQuery = searchEl.value || '';
      if (bridge()) bridge().browseShineWaveLocal(localFolder, localQuery, false);
      else simBrowseLocal(localFolder, localQuery);
    });
    var rescanBtn = document.getElementById('swst-local-rescan');
    if (rescanBtn) rescanBtn.addEventListener('click', function () {
      if (bridge()) bridge().browseShineWaveLocal(localFolder, localQuery, true);
      else simBrowseLocal(localFolder, localQuery);
    });
    var resumeBtn = document.getElementById('swst-local-resume');
    if (resumeBtn) resumeBtn.addEventListener('click', function () {
      if (bridge()) bridge().resumeShineWaveQueue('LOCAL_MUSIC');
      else window.simSwitchShineWaveSource('LOCAL_MUSIC');
    });
    var upBtn = document.getElementById('swst-local-up');
    if (upBtn) upBtn.addEventListener('click', function () {
      localFolder = d.parentFolder || '';
      if (bridge()) bridge().browseShineWaveLocal(localFolder, localQuery, false);
      else simBrowseLocal(localFolder, localQuery);
    });
    body.querySelectorAll('[data-folder]').forEach(function (row) {
      row.addEventListener('click', function () {
        localFolder = row.getAttribute('data-folder');
        if (bridge()) bridge().browseShineWaveLocal(localFolder, localQuery, false);
        else simBrowseLocal(localFolder, localQuery);
      });
    });
    body.querySelectorAll('[data-track]').forEach(function (row) {
      row.addEventListener('click', function () {
        var tid = row.getAttribute('data-track');
        if (bridge()) bridge().playShineWaveLocalTrack(localFolder, localQuery, tid);
        else {
          for (var i = 0; i < simDb.localTracks.length; i++) {
            if (simDb.localTracks[i].id === tid) { simDb.localIdx = i; break; }
          }
          window.simSwitchShineWaveSource('LOCAL_MUSIC');
          renderBody();
        }
      });
    });
  }

  function renderBluetoothBody(body) {
    var ar = isAr();
    var card = window.ShineWaveCardState || {};
    body.innerHTML =
      '<div class="swst-tuner-hero" style="margin-top:16px;">' +
        '<div style="font-size:26px;font-weight:800;color:#bae6fd;">' + (ar ? 'صوت البلوتوث التلقائي' : 'Bluetooth Audio Stream') + '</div>' +
        '<div style="font-size:18px;color:#cbd5e1;text-align:center;">' + escHtml(card.title || (ar ? 'قم بتوصيل هاتفك بالبلوتوث للاستماع والمزامنة' : 'Connect your phone via Bluetooth to play')) + '</div>' +
        '<button type="button" class="swst-pill-action mint" id="swst-bt-activate">▶ ' + (ar ? 'تفعيل وتشغيل البلوتوث' : 'Activate & Play BT') + '</button>' +
      '</div>';
    document.getElementById('swst-bt-activate').addEventListener('click', function () {
      if (bridge()) bridge().sendShineWaveCommand('source_bluetooth');
      else window.simSwitchShineWaveSource('BLUETOOTH');
    });
  }

  function renderRadioBody(body) {
    var ar = isAr();
    var r = radioData || simDb.radio;
    var isDab = r.radioType === 'DAB';
    var isAm = r.band === 'AM' && !isDab;
    var freqDisplay = isDab ? 'DAB+' : (isAm ? String(r.freqKhz) : (r.freqKhz / 1000).toFixed(1));
    var freqUnit = isDab ? 'Digital' : (isAm ? 'kHz' : 'MHz');
    var favs = (r.stations || []).filter(function (s) { return s.favorite; });

    var html = '';
    if (!isDab && favs.length) {
      html += '<div class="swst-presets-strip">' + favs.map(function (st) {
        var fShort = isAm ? st.freq : (st.freq / 1000).toFixed(1);
        return '<button type="button" class="swst-preset-pill' + (st.freq === r.freqKhz ? ' active' : '') + '" data-rfreq="' + st.freq + '">' +
          '★ ' + escHtml(st.name || fShort) + '</button>';
      }).join('') + '</div>';
    }

    html +=
      '<div class="swst-tuner-hero">' +
        '<div class="swst-band-pills" dir="ltr">' +
          '<button type="button" class="swst-subpill' + (!isDab && !isAm ? ' active' : '') + '" data-rband="FM">FM</button>' +
          '<button type="button" class="swst-subpill' + (!isDab && isAm ? ' active' : '') + '" data-rband="AM">AM</button>' +
          '<button type="button" class="swst-subpill' + (isDab ? ' active' : '') + '" data-rband="DAB" dir="ltr">DAB+</button>' +
        '</div>' +
        '<div class="swst-freq-readout"><span class="swst-freq-num">' + freqDisplay + '</span><span class="swst-freq-unit">' + freqUnit + '</span></div>';

    if (!isDab) {
      var min = isAm ? 531 : 87500, max = isAm ? 1629 : 108000, step = isAm ? 9 : 100;
      html +=
        '<input type="range" class="swst-slider" dir="ltr" id="swst-radio-slider" min="' + min + '" max="' + max + '" step="' + step + '" value="' + r.freqKhz + '">' +
        '<div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap;justify-content:center;direction:ltr;">' +
          '<button type="button" class="swst-pill-action lavender" id="swst-r-seek-dn">◀ ' + (ar ? 'بحث' : 'Seek -') + '</button>' +
          '<button type="button" class="swst-pill-action" id="swst-r-step-dn" dir="ltr">' + (isAm ? '-9' : '-0.1') + '</button>' +
          '<button type="button" class="swst-pill-action" id="swst-r-step-up" dir="ltr">' + (isAm ? '+9' : '+0.1') + '</button>' +
          '<button type="button" class="swst-pill-action lavender" id="swst-r-seek-up">' + (ar ? 'بحث' : 'Seek +') + ' ▶</button>' +
        '</div>' +
        '<div class="swst-name-save-row">' +
          '<input type="text" class="swst-search-input" id="swst-r-name-input" placeholder="' + (ar ? 'اكتب اسم المحطة لحفظها (مثلاً: نجوم FM)...' : 'Enter station name to save (e.g. Nile FM)...') + '" value="' + escHtml(editingRadioName) + '">' +
          '<button type="button" class="swst-pill-action peach" id="swst-r-fav">★ ' + (ar ? 'حفظ باسم' : 'Save Name') + '</button>' +
        '</div>';
    }
    html += '</div>';

    if (isDab) {
      html += '<div class="swst-section-label">' + (ar ? 'محطات الراديو الرقمي DAB+' : 'DAB+ Digital Stations') + '</div><div class="swst-stations-grid">';
      (r.dabItems || []).forEach(function (it) {
        html += '<div class="swst-row" data-dab="' + escHtml(it.mediaId) + '"><div class="swst-row-info"><div class="swst-row-title">' + escHtml(it.title) + '</div></div></div>';
      });
      html += '</div>';
    } else {
      var sts = r.stations || [];
      html += '<div class="swst-section-label">' + (ar ? 'المحطات المحفوظة (اضغط ✎ لتسمية أي محطة)' : 'Saved & OEM Stations (Tap ✎ to rename)') + '</div><div class="swst-stations-grid">';
      sts.forEach(function (st) {
        var fText = isAm ? (st.freq + ' kHz') : ((st.freq / 1000).toFixed(1) + ' MHz');
        html += '<div class="swst-row' + (st.freq === r.freqKhz ? ' active' : '') + '" data-rfreq="' + st.freq + '">' +
          '<div class="swst-row-info"><div class="swst-row-title">' + escHtml(st.name || fText) + '</div>' +
          '<div class="swst-row-sub">' + escHtml(fText) + (st.favorite ? ' · ★' : '') + '</div></div>' +
          '<button type="button" class="swst-edit-btn" data-redit-freq="' + st.freq + '" data-redit-name="' + escHtml(st.name) + '" title="Rename">✎</button>' +
        '</div>';
      });
      html += '</div>';
    }
    body.innerHTML = html;

    function simTune(bnd, fk) {
      if (bnd === 'DAB') { simDb.radio.radioType = 'DAB'; }
      else { simDb.radio.radioType = bnd; simDb.radio.band = bnd; simDb.radio.freqKhz = fk; }
      editingRadioName = '';
      window.simSwitchShineWaveSource('CAR_RADIO');
      renderBody();
    }

    body.querySelectorAll('[data-rband]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var bnd = btn.getAttribute('data-rband');
        var defF = (bnd === 'AM') ? 1000 : 100600;
        editingRadioName = '';
        if (bridge()) bridge().tuneShineWaveRadio(bnd, defF);
        else simTune(bnd, defF);
      });
    });
    var sl = document.getElementById('swst-radio-slider');
    if (sl) sl.addEventListener('change', function () {
      var f = parseInt(sl.value, 10);
      if (bridge()) bridge().tuneShineWaveRadio(r.band, f);
      else simTune(r.band, f);
    });
    var sDn = document.getElementById('swst-r-seek-dn');
    if (sDn) sDn.addEventListener('click', function () { if (bridge()) bridge().seekShineWaveRadio(false); else simTune(r.band, Math.max(87500, r.freqKhz - 400)); });
    var sUp = document.getElementById('swst-r-seek-up');
    if (sUp) sUp.addEventListener('click', function () { if (bridge()) bridge().seekShineWaveRadio(true); else simTune(r.band, Math.min(108000, r.freqKhz + 400)); });
    var stDn = document.getElementById('swst-r-step-dn');
    if (stDn) stDn.addEventListener('click', function () { if (bridge()) bridge().stepShineWaveRadio(isAm ? -9 : -100); else simTune(r.band, r.freqKhz - (isAm ? 9 : 100)); });
    var stUp = document.getElementById('swst-r-step-up');
    if (stUp) stUp.addEventListener('click', function () { if (bridge()) bridge().stepShineWaveRadio(isAm ? 9 : 100); else simTune(r.band, r.freqKhz + (isAm ? 9 : 100)); });

    var nameInput = document.getElementById('swst-r-name-input');
    if (nameInput) nameInput.addEventListener('input', function () { editingRadioName = nameInput.value || ''; });

    var favBtn = document.getElementById('swst-r-fav');
    if (favBtn) favBtn.addEventListener('click', function () {
      var customName = (nameInput && nameInput.value.trim()) ? nameInput.value.trim() : (freqDisplay + ' ' + freqUnit);
      editingRadioName = '';
      if (bridge() && bridge().saveShineWaveRadioFavorite) {
        bridge().saveShineWaveRadioFavorite(r.freqKhz, customName);
      } else {
        var found = false;
        for (var i = 0; i < simDb.radio.stations.length; i++) {
          if (simDb.radio.stations[i].freq === r.freqKhz) {
            simDb.radio.stations[i].name = customName;
            simDb.radio.stations[i].favorite = true;
            found = true;
            break;
          }
        }
        if (!found) simDb.radio.stations.unshift({ name: customName, freq: r.freqKhz, favorite: true });
        renderBody();
      }
    });

    body.querySelectorAll('[data-rfreq]').forEach(function (cardEl) {
      cardEl.addEventListener('click', function (e) {
        if (e.target && e.target.getAttribute('data-redit-freq')) return;
        var f = parseInt(cardEl.getAttribute('data-rfreq'), 10);
        if (bridge()) bridge().tuneShineWaveRadio(r.band, f);
        else simTune(r.band, f);
      });
    });

    body.querySelectorAll('[data-redit-freq]').forEach(function (edBtn) {
      edBtn.addEventListener('click', function (e) {
        e.stopPropagation();
        var f = parseInt(edBtn.getAttribute('data-redit-freq'), 10);
        editingRadioName = edBtn.getAttribute('data-redit-name') || '';
        if (bridge()) bridge().tuneShineWaveRadio(r.band, f);
        else simTune(r.band, f);
        setTimeout(function () {
          var box = document.getElementById('swst-r-name-input');
          if (box) { box.value = editingRadioName; box.focus(); }
        }, 40);
      });
    });

    body.querySelectorAll('[data-dab]').forEach(function (cardEl) {
      cardEl.addEventListener('click', function () {
        if (bridge()) bridge().playShineWaveDab(cardEl.getAttribute('data-dab'));
        else window.simSwitchShineWaveSource('CAR_RADIO');
      });
    });
  }

  function renderInternetBody(body) {
    var ar = isAr();
    var subTabs = [
      { id: 'popular', en: 'Popular', ar: 'شائعة' },
      { id: 'arabic', en: 'Arabic', ar: 'عربية' },
      { id: 'favorites', en: 'Favorites ★', ar: 'المفضلة ★' },
      { id: 'recent', en: 'Recent', ar: 'الأخيرة' },
      { id: 'custom', en: '+ Custom URL', ar: '+ إضافة محطة' }
    ];
    var html = '<div class="swst-band-pills" style="flex-wrap:wrap;margin-bottom:4px;direction:inherit;">' +
      subTabs.map(function (st) {
        return '<button type="button" class="swst-subpill' + (internetTab === st.id ? ' active' : '') + '" data-itab="' + st.id + '">' + (ar ? st.ar : st.en) + '</button>';
      }).join('') + '</div>';

    if (internetTab === 'custom') {
      html +=
        '<div class="swst-toolbar" style="margin-top:6px;">' +
          '<input type="text" class="swst-search-input" id="swst-c-name" placeholder="' + (ar ? 'اسم المحطة' : 'Station Name') + '">' +
          '<input type="text" class="swst-search-input" id="swst-c-url" placeholder="' + (ar ? 'رابط البث http://...' : 'Stream URL http://...') + '">' +
          '<button type="button" class="swst-pill-action peach" id="swst-c-add">' + (ar ? 'حفظ' : 'Add') + '</button>' +
        '</div>';
    } else if (internetTab === 'popular' || internetTab === 'arabic') {
      html +=
        '<div class="swst-toolbar">' +
          '<input type="text" class="swst-search-input" id="swst-inet-search" placeholder="' + (ar ? 'ابحث باسم الإذاعة...' : 'Search station name...') + '" value="' + escHtml(internetQuery) + '">' +
          '<button type="button" class="swst-pill-action peach" id="swst-inet-go">' + (ar ? 'بحث' : 'Search') + '</button>' +
        '</div>';
    }

    var list = (internetData && internetData.stations) || [];
    if (!list.length) {
      html += '<div class="swst-empty">' + (ar ? 'لا توجد محطات في هذا القسم حالياً' : 'No stations found') + '</div>';
    } else {
      list.forEach(function (st, idx) {
        html += '<div class="swst-row" data-ist="' + idx + '">' +
          '<div class="swst-row-icon" style="color:#fde68a;background:rgba(253,230,138,0.16);">📡</div>' +
          '<div class="swst-row-info"><div class="swst-row-title">' + escHtml(st.name) + '</div>' +
          '<div class="swst-row-sub">' + escHtml(st.country || 'Internet Radio') + '</div></div>' +
          '<button type="button" class="swst-star-btn' + (st.favorite ? ' fav' : '') + '" data-ifav="' + idx + '">' + (st.favorite ? '★' : '☆') + '</button></div>';
      });
    }
    body.innerHTML = html;

    body.querySelectorAll('[data-itab]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        internetTab = btn.getAttribute('data-itab');
        loadTabData('INTERNET_RADIO');
      });
    });
    var searchGo = document.getElementById('swst-inet-go');
    if (searchGo) searchGo.addEventListener('click', function () {
      var box = document.getElementById('swst-inet-search');
      internetQuery = box ? box.value : '';
      loadTabData('INTERNET_RADIO');
    });
    var cAdd = document.getElementById('swst-c-add');
    if (cAdd) cAdd.addEventListener('click', function () {
      var n = (document.getElementById('swst-c-name') || {}).value || '';
      var u = (document.getElementById('swst-c-url') || {}).value || '';
      if (!n || !u) return;
      if (bridge()) bridge().addShineWaveCustomStation(n, u);
      else { simDb.internet.unshift({ uuid: 'c_' + Date.now(), name: n, url: u, country: 'CUSTOM', favorite: true }); loadTabData('INTERNET_RADIO'); }
    });
    body.querySelectorAll('[data-ist]').forEach(function (row) {
      row.addEventListener('click', function (e) {
        if (e.target && e.target.getAttribute('data-ifav') !== null) return;
        var st = list[parseInt(row.getAttribute('data-ist'), 10)];
        if (!st) return;
        if (bridge()) bridge().playShineWaveInternetStation(st.uuid, st.name, st.url, st.country);
        else { window.ShineWaveCardState.title = st.name; window.ShineWaveCardState.artist = 'Internet Radio'; window.ShineWaveCardState.playing = true; window.refreshShineWaveCardUI(); renderPlayerHeader(); }
      });
    });
    body.querySelectorAll('[data-ifav]').forEach(function (star) {
      star.addEventListener('click', function (e) {
        e.stopPropagation();
        var st = list[parseInt(star.getAttribute('data-ifav'), 10)];
        if (!st) return;
        if (bridge()) bridge().toggleShineWaveInternetFav(st.uuid, st.name, st.url, st.country, internetTab, internetQuery);
        else { st.favorite = !st.favorite; loadTabData('INTERNET_RADIO'); }
      });
    });
  }

  function renderAppsBody(body) {
    var ar = isAr();
    var apps = (appsData && appsData.apps) || simDb.apps;
    var html = '<div class="swst-section-label">' + (ar ? 'تطبيقات الميديا المنزّلة + مشغل الموسيقى' : 'Downloaded Media Apps + Stock Music') + '</div>';
    if (!apps.length) {
      html += '<div class="swst-empty">' + (ar ? 'لم يتم العثور على تطبيقات وسائط مثبتة' : 'No installed media apps found') + '</div>';
    } else {
      html += '<div class="swst-apps-grid">';
      apps.forEach(function (app, idx) {
        html += '<div class="swst-app-card' + (app.selected ? ' selected' : '') + '" data-appidx="' + idx + '">' +
          (app.icon ? '<img class="swst-app-icon" src="' + app.icon + '" alt="">' : '<div class="swst-row-icon">🎵</div>') +
          '<div class="swst-app-name">' + escHtml(app.label) + '</div>' +
          '<div class="swst-app-actions">' +
            '<button type="button" class="swst-mini-btn" data-appsel="' + idx + '">' + (app.selected ? (ar ? '✓ مختار' : '✓ Active') : (ar ? 'اختيار' : 'Select')) + '</button>' +
            '<button type="button" class="swst-mini-btn" style="background:rgba(167,243,208,0.2);color:#a7f3d0;" data-appopen="' + idx + '">' + (ar ? 'فتح' : 'Open') + '</button>' +
          '</div></div>';
      });
      html += '</div>';
    }
    body.innerHTML = html;

    function simSelectApp(a) {
      simDb.apps.forEach(function (item) { item.selected = (item.pkg === a.pkg); });
      window.simSwitchShineWaveSource('EXTERNAL_APP');
      renderBody();
    }

    body.querySelectorAll('[data-appsel]').forEach(function (b) {
      b.addEventListener('click', function (e) {
        e.stopPropagation();
        var a = apps[parseInt(b.getAttribute('data-appsel'), 10)];
        if (!a) return;
        if (bridge()) bridge().selectShineWaveExternalApp(a.pkg, a.label, false);
        else simSelectApp(a);
      });
    });
    body.querySelectorAll('[data-appopen]').forEach(function (b) {
      b.addEventListener('click', function (e) {
        e.stopPropagation();
        var a = apps[parseInt(b.getAttribute('data-appopen'), 10)];
        if (!a) return;
        if (bridge()) {
          bridge().selectShineWaveExternalApp(a.pkg, a.label, true);
          window.closeShineWaveStudio();
        } else {
          simSelectApp(a);
          window.closeShineWaveStudio();
        }
      });
    });
    body.querySelectorAll('[data-appidx]').forEach(function (cardEl) {
      cardEl.addEventListener('click', function () {
        var a = apps[parseInt(cardEl.getAttribute('data-appidx'), 10)];
        if (!a) return;
        if (bridge()) bridge().selectShineWaveExternalApp(a.pkg, a.label, true);
        else simSelectApp(a);
      });
    });
  }
})();
