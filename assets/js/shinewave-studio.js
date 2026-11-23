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
  var internetTab = 'arabic';
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
  function isAr() { return window.isArabicLang ? window.isArabicLang() : false; }
  function isRu() { return window.isRussianLang ? window.isRussianLang() : false; }
  function fmtTime(sec) { return window.formatTimeSec ? window.formatTimeSec(sec, true) : '00:00'; }
  function escHtml(str) { return window.escapeHtml ? window.escapeHtml(str) : String(str || ''); }

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

    var studioToastTimer = null;
    function showStudioToast(text) {
      var toast = document.getElementById('swst-seek-toast');
      if (!toast) {
        toast = document.createElement('div');
        toast.id = 'swst-seek-toast';
        toast.style.cssText = 'position:absolute;top:20px;left:50%;transform:translateX(-50%);background:rgba(12,29,40,0.95);color:#7dd3fc;padding:6px 18px;border-radius:18px;font-size:16px;font-weight:800;border:1px solid rgba(125,211,252,0.45);box-shadow:0 0 22px rgba(125,211,252,0.4);pointer-events:none;z-index:999;opacity:0;transition:opacity 0.18s,transform 0.18s;';
        var panel = document.getElementById('swst-panel');
        if (panel) panel.appendChild(toast);
      }
      toast.textContent = text;
      toast.style.opacity = '1';
      toast.style.transform = 'translateX(-50%) translateY(4px)';
      if (studioToastTimer) clearTimeout(studioToastTimer);
      studioToastTimer = setTimeout(function () {
        toast.style.opacity = '0';
        toast.style.transform = 'translateX(-50%) translateY(0)';
      }, 700);
    }
    window.showShineWaveStudioToast = showStudioToast;

    function bindStudioTrackBtnLongPress(btn, isNext) {
      if (!btn) return;
      var timer = null;
      var repeatTimer = null;
      var isLong = false;
      var deltaSec = isNext ? 10 : -10;

      function doSeek() {
        var m = window.ShineWaveCardState || {};
        var b = bridge();
        if (b && typeof b.seekMediaBy === 'function') {
          b.seekMediaBy(deltaSec);
        } else if (b && typeof b.sendShineWaveCommand === 'function') {
          b.sendShineWaveCommand(isNext ? 'seek_forward' : 'seek_back');
        } else {
          var effDur = m.dur > 0 ? m.dur : 225;
          m.pos = Math.max(0, Math.min(effDur, (m.pos || 0) + deltaSec));
          window.syncShineWaveStudioTime(m.pos, effDur);
        }
        showStudioToast(deltaSec > 0 ? '+10s ▶▶' : '◀◀ -10s');
      }

      function start(e) {
        isLong = false;
        if (timer) clearTimeout(timer);
        if (repeatTimer) clearInterval(repeatTimer);
        timer = setTimeout(function () {
          isLong = true;
          doSeek();
          repeatTimer = setInterval(doSeek, 450);
        }, 500);
      }

      function cancel() {
        if (timer) { clearTimeout(timer); timer = null; }
        if (repeatTimer) { clearInterval(repeatTimer); repeatTimer = null; }
      }

      btn.addEventListener('touchstart', function (e) { start(e); }, { passive: true });
      btn.addEventListener('touchend', function (e) {
        cancel();
        if (isLong) {
          if (e.cancelable) e.preventDefault();
          e.stopPropagation();
        }
      });
      btn.addEventListener('mousedown', function (e) { if (e.button === 0) start(e); });
      btn.addEventListener('mouseup', function (e) {
        cancel();
        if (isLong) {
          if (e.cancelable) e.preventDefault();
          e.stopPropagation();
        }
      });
      btn.addEventListener('mouseleave', cancel);

      btn.addEventListener('click', function (e) {
        e.stopPropagation();
        if (isLong) {
          isLong = false;
          return;
        }
        var b = bridge();
        if (b) b.sendShineWaveCommand(isNext ? 'next' : 'previous');
        else if (window.simStepShineWaveTrack) window.simStepShineWaveTrack(isNext ? 1 : -1);
      });
    }

    bindStudioTrackBtnLongPress(document.getElementById('swst-btn-prev'), false);
    bindStudioTrackBtnLongPress(document.getElementById('swst-btn-next'), true);

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

  /* ---------- Live Fluid Silk Wave Equalizer (أمواج الحرير والنيون الانسيابية) ---------- */
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
    var isPlaying = !!m.playing;
    var spec = (window.getShineWaveAudioSpectrum) ? window.getShineWaveAudioSpectrum() : { beat: 0, energy: 0, bands: [] };
    var effDur = m.dur > 0 ? m.dur : 225;
    var frac = Math.max(0, Math.min(1, (m.pos || 0) / effDur));
    var midY = h / 2;

    if (isPlaying) {
      wavePhase += 0.055 + (spec.beat || 0) * 0.035;
    } else {
      wavePhase += 0.012;
    }

    var beatAmp = isPlaying ? (0.35 + (spec.beat || 0) * 0.45 + (spec.energy || 0) * 0.35) : 0.15;
    var bassDrop = isPlaying ? Math.max(0, (spec.beat || 0) * 1.2) : 0;

    // Draw 3 harmonic fluid silk layers
    var layers = [
      {
        color: '#c084fc',
        glow: 'rgba(192, 132, 252, 0.45)',
        width: 2.0,
        alpha: 0.60,
        speed: 1.4,
        freq: 0.032,
        amp: (h * 0.28) * beatAmp + bassDrop * 3.5
      },
      {
        color: '#34d399',
        glow: 'rgba(52, 211, 153, 0.55)',
        width: 2.4,
        alpha: 0.75,
        speed: 1.9,
        freq: 0.040,
        amp: (h * 0.34) * beatAmp + bassDrop * 4.5
      },
      {
        color: '#38bdf8',
        glow: '#38bdf8',
        width: 3.2,
        alpha: 0.95,
        speed: 2.4,
        freq: 0.048,
        amp: (h * 0.42) * beatAmp + bassDrop * 5.5
      }
    ];

    layers.forEach(function (l, lIdx) {
      ctx.save();
      ctx.beginPath();
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';

      var step = 4;
      var first = true;
      for (var x = 0; x <= w; x += step) {
        var normX = x / w;
        var env = Math.sin(normX * Math.PI);
        var specMod = 1.0;
        if (spec.bands && spec.bands.length) {
          var bIdx = Math.floor(normX * (spec.bands.length - 1));
          specMod = 0.5 + (spec.bands[bIdx] || 0.3) * 0.9;
        }

        var y = midY + Math.sin(x * l.freq - wavePhase * l.speed + lIdx * 1.2) * l.amp * env * specMod;
        if (first) { ctx.moveTo(x, y); first = false; }
        else { ctx.lineTo(x, y); }
      }

      ctx.strokeStyle = l.color;
      ctx.lineWidth = l.width;
      ctx.shadowColor = l.glow;
      ctx.shadowBlur = 8;
      ctx.globalAlpha = l.alpha;
      ctx.stroke();
      ctx.restore();
    });

    // Active playback progress head on the wave
    var px = w * frac;
    var pEnv = Math.sin(frac * Math.PI);
    var py = midY + Math.sin(px * 0.048 - wavePhase * 2.4 + 2.4) * (h * 0.42) * beatAmp * pEnv;

    ctx.save();
    ctx.shadowColor = '#38bdf8';
    ctx.shadowBlur = 12;
    ctx.fillStyle = '#ffffff';
    ctx.beginPath();
    ctx.arc(px, py, 4.2, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();
  }

  /* ---------- Interactive 3D WebGL Musical Clown Mascot (مجسم المهرج الكرتوني 3D حقيقي) ---------- */
  var threeMascot = null;
  var threeMascotCanvas = null;
  var threeMascotBassHit = 0;
  var threePointerTarget = { x: 0, y: 0 };
  var threePointerCurrent = { x: 0, y: 0 };

  function initThreeMascot(canvas, w, h) {
    if (!window.THREE || !canvas) return false;
    try {
      if (!canvas.__isWebgl && canvas.parentNode) {
        var fresh = canvas.cloneNode(false);
        canvas.parentNode.replaceChild(fresh, canvas);
        canvas = fresh;
      }
      var THREE = window.THREE;
      var renderer = new THREE.WebGLRenderer({
        canvas: canvas,
        alpha: true,
        antialias: true,
        powerPreference: 'low-power'
      });
      canvas.__isWebgl = true;
      renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
      renderer.setSize(w, h, false);
      if (renderer.toneMapping !== undefined) {
        renderer.toneMapping = THREE.ACESFilmicToneMapping || THREE.LinearToneMapping;
        renderer.toneMappingExposure = 1.15;
      }

      var scene = new THREE.Scene();
      var camera = new THREE.PerspectiveCamera(36, w / h, 0.1, 50);
      camera.position.set(0, 0.15, 6.8);

      // Studio 3-Point Lighting
      var ambLight = new THREE.AmbientLight(0xffffff, 0.75);
      scene.add(ambLight);

      var keyLight = new THREE.DirectionalLight(0xfff7ed, 1.3);
      keyLight.position.set(3.5, 4.5, 4.0);
      scene.add(keyLight);

      var fillLight = new THREE.DirectionalLight(0x93c5fd, 0.55);
      fillLight.position.set(-3.5, 1.0, 3.0);
      scene.add(fillLight);

      var rimLight = new THREE.DirectionalLight(0xfef08a, 0.85);
      rimLight.position.set(0, 4.0, -3.5);
      scene.add(rimLight);

      // Materials (Standard PBR with fallback-safe properties)
      var MatClass = THREE.MeshStandardMaterial || THREE.MeshPhongMaterial;
      var skinMat = new MatClass({ color: 0xffdfd0, roughness: 0.38, metalness: 0.05 });
      var hairMat = new MatClass({ color: 0xea580c, roughness: 0.75, metalness: 0.0 });
      var noseMat = new MatClass({ color: 0xdc2626, roughness: 0.15, metalness: 0.1 });
      var hatMat = new MatClass({ color: 0xdc2626, roughness: 0.35 });
      var hatBrimMat = new MatClass({ color: 0x2563eb, roughness: 0.4 });
      var eyeWhiteMat = new MatClass({ color: 0xffffff, roughness: 0.2 });
      var irisMat = new MatClass({ color: 0x0284c7, roughness: 0.25 });
      var pupilMat = new THREE.MeshBasicMaterial({ color: 0x080808 });
      var browMat = new MatClass({ color: 0x1e1e24, roughness: 0.6 });
      var cheekMat = new THREE.MeshBasicMaterial({ color: 0xf87171, transparent: true, opacity: 0.6 });
      var lipMat = new MatClass({ color: 0xb91c1c, roughness: 0.3 });
      var teethMat = new MatClass({ color: 0xffffff, roughness: 0.2 });
      var collarMat = new MatClass({ color: 0xfacc15, roughness: 0.45 });
      var bowTieMat = new MatClass({ color: 0xfbbf24, roughness: 0.4 });
      var bowDotMat = new THREE.MeshBasicMaterial({ color: 0xdc2626 });
      var shirtMat = new MatClass({ color: 0x1d4ed8, roughness: 0.55 });
      var overallsMat = new MatClass({ color: 0xdc2626, roughness: 0.5 });
      var buttonMat = new MatClass({ color: 0xfacc15, roughness: 0.25, metalness: 0.2 });
      var gloveMat = new MatClass({ color: 0xf8fafc, roughness: 0.35 });
      var shoeYellowMat = new MatClass({ color: 0xeab308, roughness: 0.4 });
      var shoeRedMat = new MatClass({ color: 0xdc2626, roughness: 0.35 });

      // Root Hierarchy
      var mascotRoot = new THREE.Group();
      scene.add(mascotRoot);

      // Ground Shadow
      var shadowGeo = new THREE.CircleGeometry(0.85, 24);
      var shadowCanvas = document.createElement('canvas');
      shadowCanvas.width = 64; shadowCanvas.height = 64;
      var sctx = shadowCanvas.getContext('2d');
      var grad = sctx.createRadialGradient(32, 32, 4, 32, 32, 30);
      grad.addColorStop(0, 'rgba(0, 0, 0, 0.6)');
      grad.addColorStop(0.5, 'rgba(0, 0, 0, 0.25)');
      grad.addColorStop(1, 'rgba(0, 0, 0, 0)');
      sctx.fillStyle = grad;
      sctx.fillRect(0, 0, 64, 64);
      var shadowTex = new THREE.CanvasTexture(shadowCanvas);
      var shadowMat = new THREE.MeshBasicMaterial({ map: shadowTex, transparent: true, opacity: 0.8 });
      var groundShadow = new THREE.Mesh(shadowGeo, shadowMat);
      groundShadow.rotation.x = -Math.PI / 2;
      groundShadow.position.y = -2.15;
      scene.add(groundShadow);

      var charGroup = new THREE.Group();
      mascotRoot.add(charGroup);
      charGroup.position.y = 0.05;

      var bodyGroup = new THREE.Group();
      charGroup.add(bodyGroup);

      // Torso - Blue Shirt
      var shirtGeo = new THREE.CylinderGeometry(0.55, 0.58, 0.95, 20);
      var shirtMesh = new THREE.Mesh(shirtGeo, shirtMat);
      shirtMesh.position.y = -0.35;
      bodyGroup.add(shirtMesh);

      // Overalls (Red Dungarees)
      var overallsGeo = new THREE.CylinderGeometry(0.58, 0.62, 0.82, 20);
      var overallsMesh = new THREE.Mesh(overallsGeo, overallsMat);
      overallsMesh.position.y = -0.45;
      bodyGroup.add(overallsMesh);

      // Shoulder Straps
      var strapGeo = new THREE.BoxGeometry(0.14, 0.82, 0.08);
      var leftStrap = new THREE.Mesh(strapGeo, overallsMat);
      leftStrap.position.set(-0.32, -0.28, 0.48);
      leftStrap.rotation.z = -0.08;
      bodyGroup.add(leftStrap);

      var rightStrap = new THREE.Mesh(strapGeo, overallsMat);
      rightStrap.position.set(0.32, -0.28, 0.48);
      rightStrap.rotation.z = 0.08;
      bodyGroup.add(rightStrap);

      // Golden Buttons
      var buttonGeo = new THREE.CylinderGeometry(0.095, 0.095, 0.05, 16);
      var leftBtn = new THREE.Mesh(buttonGeo, buttonMat);
      leftBtn.rotation.x = Math.PI / 2;
      leftBtn.position.set(-0.32, -0.38, 0.53);
      bodyGroup.add(leftBtn);

      var rightBtn = new THREE.Mesh(buttonGeo, buttonMat);
      rightBtn.rotation.x = Math.PI / 2;
      rightBtn.position.set(0.32, -0.38, 0.53);
      bodyGroup.add(rightBtn);

      // Legs
      var legGeo = new THREE.CylinderGeometry(0.18, 0.20, 0.55, 14);
      var leftLeg = new THREE.Mesh(legGeo, overallsMat);
      leftLeg.position.set(-0.28, -1.02, 0);
      bodyGroup.add(leftLeg);

      var rightLeg = new THREE.Mesh(legGeo, overallsMat);
      rightLeg.position.set(0.28, -1.02, 0);
      bodyGroup.add(rightLeg);

      // Yellow Clown Shoes with Red Toe Caps
      function createClownShoe(isRight) {
        var shoeGroup = new THREE.Group();
        var baseGeo = new THREE.SphereGeometry(0.24, 14, 14);
        baseGeo.scale(1.1, 0.75, 1.6);
        var baseMesh = new THREE.Mesh(baseGeo, shoeYellowMat);
        baseMesh.position.set(0, 0, 0.12);
        shoeGroup.add(baseMesh);

        var toeGeo = new THREE.SphereGeometry(0.21, 14, 14);
        toeGeo.scale(1.05, 0.9, 1.15);
        var toeMesh = new THREE.Mesh(toeGeo, shoeRedMat);
        toeMesh.position.set(0, -0.02, 0.32);
        shoeGroup.add(toeMesh);

        shoeGroup.position.set(isRight ? 0.28 : -0.28, -1.34, 0);
        return shoeGroup;
      }
      bodyGroup.add(createClownShoe(false));
      bodyGroup.add(createClownShoe(true));

      // Left Arm (Relaxed)
      var leftArmGroup = new THREE.Group();
      leftArmGroup.position.set(-0.62, -0.15, 0);
      var leftArmGeo = new THREE.CylinderGeometry(0.15, 0.14, 0.65, 14);
      var leftArmMesh = new THREE.Mesh(leftArmGeo, shirtMat);
      leftArmMesh.position.set(-0.12, -0.28, 0.08);
      leftArmMesh.rotation.z = 0.22;
      leftArmMesh.rotation.x = 0.15;
      leftArmGroup.add(leftArmMesh);

      var leftHandGeo = new THREE.SphereGeometry(0.18, 14, 14);
      leftHandGeo.scale(1.1, 1.3, 0.85);
      var leftHandMesh = new THREE.Mesh(leftHandGeo, gloveMat);
      leftHandMesh.position.set(-0.22, -0.68, 0.18);
      leftArmGroup.add(leftHandMesh);
      bodyGroup.add(leftArmGroup);

      // Right Arm (Waving!)
      var rightArmGroup = new THREE.Group();
      rightArmGroup.position.set(0.62, -0.12, 0);
      var rightUpperGeo = new THREE.CylinderGeometry(0.15, 0.14, 0.52, 14);
      var rightUpperMesh = new THREE.Mesh(rightUpperGeo, shirtMat);
      rightUpperMesh.position.set(0.18, 0.18, 0.12);
      rightUpperMesh.rotation.z = -0.75;
      rightArmGroup.add(rightUpperMesh);

      var handGroup = new THREE.Group();
      handGroup.position.set(0.38, 0.42, 0.22);
      var palmGeo = new THREE.SphereGeometry(0.17, 14, 14);
      palmGeo.scale(1.1, 1.25, 0.7);
      var palmMesh = new THREE.Mesh(palmGeo, gloveMat);
      handGroup.add(palmMesh);

      for (var fi = 0; fi < 4; fi++) {
        var fingerGeo = new THREE.CylinderGeometry(0.045, 0.04, 0.22, 10);
        var finger = new THREE.Mesh(fingerGeo, gloveMat);
        finger.position.set(-0.12 + fi * 0.08, 0.18, 0);
        finger.rotation.z = -0.3 + fi * 0.22;
        handGroup.add(finger);
      }
      var thumbGeo = new THREE.CylinderGeometry(0.05, 0.045, 0.18, 10);
      var thumb = new THREE.Mesh(thumbGeo, gloveMat);
      thumb.position.set(-0.18, 0.02, 0.05);
      thumb.rotation.z = 0.85;
      handGroup.add(thumb);

      rightArmGroup.add(handGroup);
      bodyGroup.add(rightArmGroup);

      // Neck Group
      var neckGroup = new THREE.Group();
      neckGroup.position.set(0, 0.18, 0);
      bodyGroup.add(neckGroup);

      // Ruffled Collar
      var collarGeo = new THREE.CylinderGeometry(0.58, 0.68, 0.16, 24);
      var collarMesh = new THREE.Mesh(collarGeo, collarMat);
      collarMesh.position.set(0, 0, 0.05);
      neckGroup.add(collarMesh);

      // Bow Tie
      var bowGroup = new THREE.Group();
      bowGroup.position.set(0, 0.02, 0.52);
      var knotGeo = new THREE.CylinderGeometry(0.10, 0.10, 0.14, 14);
      var knot = new THREE.Mesh(knotGeo, bowTieMat);
      knot.rotation.z = Math.PI / 2;
      bowGroup.add(knot);

      var loopGeo = new THREE.ConeGeometry(0.25, 0.42, 14);
      var leftLoop = new THREE.Mesh(loopGeo, bowTieMat);
      leftLoop.rotation.z = Math.PI / 2;
      leftLoop.position.set(-0.25, 0, 0);
      bowGroup.add(leftLoop);

      var rightLoop = new THREE.Mesh(loopGeo, bowTieMat);
      rightLoop.rotation.z = -Math.PI / 2;
      rightLoop.position.set(0.25, 0, 0);
      bowGroup.add(rightLoop);

      var dotGeo = new THREE.SphereGeometry(0.045, 10, 10);
      var dotCoords = [
        [-0.22, 0.08, 0.08], [-0.32, -0.06, 0.08], [-0.16, -0.08, 0.08],
        [0.22, 0.08, 0.08], [0.32, -0.06, 0.08], [0.16, -0.08, 0.08], [0, 0, 0.09]
      ];
      dotCoords.forEach(function(crd) {
        var dot = new THREE.Mesh(dotGeo, bowDotMat);
        dot.position.set(crd[0], crd[1], crd[2]);
        bowGroup.add(dot);
      });
      neckGroup.add(bowGroup);

      // Head Group
      var headGroup = new THREE.Group();
      headGroup.position.set(0, 0.85, 0);
      bodyGroup.add(headGroup);

      var headGeo = new THREE.SphereGeometry(0.72, 24, 24);
      headGeo.scale(1.0, 1.1, 0.96);
      var headMesh = new THREE.Mesh(headGeo, skinMat);
      headGroup.add(headMesh);

      // Ears
      var earGeo = new THREE.SphereGeometry(0.18, 14, 14);
      earGeo.scale(0.6, 1.1, 0.8);
      var leftEar = new THREE.Mesh(earGeo, skinMat);
      leftEar.position.set(-0.72, 0, -0.05);
      headGroup.add(leftEar);
      var rightEar = new THREE.Mesh(earGeo, skinMat);
      rightEar.position.set(0.72, 0, -0.05);
      headGroup.add(rightEar);

      // Afro Curls
      var curlGeo = new THREE.SphereGeometry(0.24, 12, 12);
      var curlPositions = [
        [-0.52, 0.65, -0.1], [0, 0.78, -0.15], [0.52, 0.65, -0.1],
        [-0.32, 0.76, 0.05], [0.32, 0.76, 0.05],
        [-0.45, 0.58, 0.42], [-0.22, 0.68, 0.48], [0, 0.72, 0.52], [0.22, 0.68, 0.48], [0.45, 0.58, 0.42],
        [-0.68, 0.42, 0.12], [-0.75, 0.18, 0.05], [-0.68, -0.08, -0.05],
        [0.68, 0.42, 0.12], [0.75, 0.18, 0.05], [0.68, -0.08, -0.05],
        [-0.45, 0.35, -0.45], [0, 0.45, -0.52], [0.45, 0.35, -0.45]
      ];
      curlPositions.forEach(function(pos) {
        var curl = new THREE.Mesh(curlGeo, hairMat);
        curl.position.set(pos[0], pos[1], pos[2]);
        headGroup.add(curl);
      });

      // Party Hat
      var hatGroup = new THREE.Group();
      hatGroup.position.set(0.04, 0.92, 0.05);
      hatGroup.rotation.z = -0.12;
      hatGroup.rotation.x = -0.05;
      var coneGeo = new THREE.ConeGeometry(0.24, 0.56, 18);
      var coneMesh = new THREE.Mesh(coneGeo, hatMat);
      coneMesh.position.y = 0.28;
      hatGroup.add(coneMesh);
      var brimGeo = new THREE.TorusGeometry(0.24, 0.05, 12, 24);
      var brimMesh = new THREE.Mesh(brimGeo, hatBrimMat);
      brimMesh.rotation.x = Math.PI / 2;
      hatGroup.add(brimMesh);
      headGroup.add(hatGroup);

      // Nose
      var noseGeo = new THREE.SphereGeometry(0.21, 18, 18);
      var noseMesh = new THREE.Mesh(noseGeo, noseMat);
      noseMesh.position.set(0, 0.02, 0.72);
      headGroup.add(noseMesh);

      // Eyes
      function createEye3D(isRight) {
        var eyeGrp = new THREE.Group();
        var scleraGeo = new THREE.SphereGeometry(0.16, 16, 16);
        scleraGeo.scale(0.85, 1.15, 0.75);
        var sclera = new THREE.Mesh(scleraGeo, eyeWhiteMat);
        eyeGrp.add(sclera);

        var irisGeo = new THREE.CircleGeometry(0.075, 16);
        var iris = new THREE.Mesh(irisGeo, irisMat);
        iris.position.set(0, 0, 0.122);
        eyeGrp.add(iris);

        var pupilGeo = new THREE.CircleGeometry(0.042, 14);
        var pupil = new THREE.Mesh(pupilGeo, pupilMat);
        pupil.position.set(0, 0, 0.124);
        eyeGrp.add(pupil);

        var dotGeo = new THREE.CircleGeometry(0.022, 10);
        var dot = new THREE.Mesh(dotGeo, new THREE.MeshBasicMaterial({ color: 0xffffff }));
        dot.position.set(0.025, 0.025, 0.126);
        eyeGrp.add(dot);

        eyeGrp.position.set(isRight ? 0.20 : -0.20, 0.18, 0.62);
        return eyeGrp;
      }
      headGroup.add(createEye3D(false));
      headGroup.add(createEye3D(true));

      // Cheeks
      var cheekGeo = new THREE.CircleGeometry(0.12, 14);
      var leftCheek = new THREE.Mesh(cheekGeo, cheekMat);
      leftCheek.position.set(-0.35, 0, 0.64);
      leftCheek.rotation.y = -0.35;
      headGroup.add(leftCheek);
      var rightCheek = new THREE.Mesh(cheekGeo, cheekMat);
      rightCheek.position.set(0.35, 0, 0.64);
      rightCheek.rotation.y = 0.35;
      headGroup.add(rightCheek);

      // Smile
      var mouthGroup = new THREE.Group();
      mouthGroup.position.set(0, -0.22, 0.66);
      mouthGroup.rotation.x = 0.18;
      var lipCurve = new THREE.QuadraticBezierCurve3(
        new THREE.Vector3(-0.32, 0.06, 0),
        new THREE.Vector3(0, -0.16, 0),
        new THREE.Vector3(0.32, 0.06, 0)
      );
      var lipGeo = new THREE.TubeGeometry(lipCurve, 16, 0.04, 8, false);
      var lipMesh = new THREE.Mesh(lipGeo, lipMat);
      mouthGroup.add(lipMesh);
      var teethGeo = new THREE.BoxGeometry(0.34, 0.06, 0.03);
      var teethMesh = new THREE.Mesh(teethGeo, teethMat);
      teethMesh.position.set(0, 0.01, -0.01);
      mouthGroup.add(teethMesh);
      headGroup.add(mouthGroup);

      // Touch / pointer interaction
      function onPointerMove(e) {
        var rect = canvas.getBoundingClientRect();
        var clientX = e.clientX !== undefined ? e.clientX : (e.touches && e.touches[0] ? e.touches[0].clientX : rect.left + rect.width / 2);
        var clientY = e.clientY !== undefined ? e.clientY : (e.touches && e.touches[0] ? e.touches[0].clientY : rect.top + rect.height / 2);
        var normX = ((clientX - rect.left) / rect.width - 0.5) * 2;
        var normY = ((clientY - rect.top) / rect.height - 0.5) * 2;
        threePointerTarget.x = normX * 0.45;
        threePointerTarget.y = normY * 0.25;
      }
      function onPointerLeave() {
        threePointerTarget.x = 0;
        threePointerTarget.y = 0;
      }
      canvas.addEventListener('mousemove', onPointerMove);
      canvas.addEventListener('touchmove', onPointerMove, { passive: true });
      canvas.addEventListener('mouseleave', onPointerLeave);
      canvas.addEventListener('touchend', onPointerLeave);

      threeMascot = {
        renderer: renderer,
        scene: scene,
        camera: camera,
        charGroup: charGroup,
        bodyGroup: bodyGroup,
        headGroup: headGroup,
        rightArmGroup: rightArmGroup,
        handGroup: handGroup,
        groundShadow: groundShadow
      };
      threeMascotCanvas = canvas;
      return true;
    } catch (err) {
      console.warn('[ShineWave] Failed to init Three.js Mascot:', err);
      return false;
    }
  }

  function drawStudioMascot() {
    var c = document.getElementById('swst-mascot-canvas');
    var wrap = document.getElementById('swst-mascot-wrap');
    if (!c || !wrap || !mascotEnabled) return;

    var w = wrap.clientWidth || 104;
    var h = wrap.clientHeight || 104;

    if (!threeMascot || threeMascotCanvas !== c) {
      if (window.THREE) {
        initThreeMascot(c, w, h);
      }
    }

    if (threeMascot) {
      var m = window.ShineWaveCardState || {};
      var isPlaying = !!m.playing;
      var spec = (window.getShineWaveAudioSpectrum) ? window.getShineWaveAudioSpectrum() : { beat: 0, energy: 0, bands: [] };
      var beatVal = spec.beat || 0;
      var energyVal = spec.energy || 0;

      // Detect bass hit
      var bassBand = (spec.bands && spec.bands.length > 2) ? ((spec.bands[0] || 0) + (spec.bands[1] || 0)) * 0.5 : energyVal;
      if (bassBand > 0.65 || beatVal > 0.7) {
        threeMascotBassHit = Math.max(threeMascotBassHit, Math.max(bassBand, beatVal));
      }
      threeMascotBassHit *= 0.88;

      if (isPlaying) {
        mascotPhase += 0.045 + energyVal * 0.05;
      }

      // Smooth pointer tracking
      threePointerCurrent.x += (threePointerTarget.x - threePointerCurrent.x) * 0.12;
      threePointerCurrent.y += (threePointerTarget.y - threePointerCurrent.y) * 0.12;

      var charGroup = threeMascot.charGroup;
      var bodyGroup = threeMascot.bodyGroup;
      var headGroup = threeMascot.headGroup;
      var rightArmGroup = threeMascot.rightArmGroup;
      var handGroup = threeMascot.handGroup;
      var groundShadow = threeMascot.groundShadow;

      if (isPlaying) {
        // Sway left & right in 3D
        charGroup.position.x = Math.sin(mascotPhase) * 0.16 + threePointerCurrent.x * 0.3;
        charGroup.rotation.z = Math.sin(mascotPhase) * 0.06;
        charGroup.rotation.y = Math.sin(mascotPhase * 0.5) * 0.14 + threePointerCurrent.x;
        charGroup.rotation.x = -threePointerCurrent.y * 0.6;

        // Bass jump off the ground
        var jump = Math.pow(threeMascotBassHit, 1.4) * 0.45;
        charGroup.position.y = 0.05 + jump;

        // Shadow scales and fades when character leaps
        var shadowScale = Math.max(0.4, 1.0 - jump * 0.9);
        groundShadow.scale.set(shadowScale, shadowScale, 1.0);
        groundShadow.material.opacity = Math.max(0.15, 0.8 - jump * 1.2);

        // Head bobbing with music
        headGroup.rotation.z = Math.sin(mascotPhase + 0.3) * 0.06;
        headGroup.rotation.x = Math.sin(mascotPhase * 0.5) * 0.04;

        // Enthusiastic hand wave
        handGroup.rotation.z = Math.sin(mascotPhase * 4.0) * 0.35;
        rightArmGroup.rotation.z = Math.sin(mascotPhase * 2.0) * 0.12;
      } else {
        // Calm breathing stance when paused
        var breathe = Math.sin(Date.now() * 0.0025);
        bodyGroup.scale.y = 1.0 + breathe * 0.015;
        headGroup.rotation.x = breathe * 0.02 - threePointerCurrent.y * 0.4;
        charGroup.position.x = charGroup.position.x * 0.92 + threePointerCurrent.x * 0.3;
        charGroup.rotation.y = charGroup.rotation.y * 0.92 + threePointerCurrent.x;
        charGroup.rotation.z *= 0.92;
        charGroup.position.y = 0.05;
        groundShadow.scale.set(1, 1, 1);
        groundShadow.material.opacity = 0.8;
        handGroup.rotation.z = Math.sin(Date.now() * 0.003) * 0.12;
      }

      threeMascot.renderer.render(threeMascot.scene, threeMascot.camera);
      return;
    }

    // Fallback 2D canvas if THREE not yet loaded - renders complete 3D cartoon clown
    var dpr = window.devicePixelRatio || 1;
    if (c.width !== Math.round(w * dpr) || c.height !== Math.round(h * dpr)) {
      c.width = Math.round(w * dpr);
      c.height = Math.round(h * dpr);
    }
    var ctx = c.getContext('2d');
    if (!ctx) return;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);

    var mState = window.ShineWaveCardState || {};
    var isPl = !!mState.playing;
    mascotPhase += isPl ? 0.05 : 0.02;
    var swayX = isPl ? Math.sin(mascotPhase * 1.8) * 6.0 : Math.sin(mascotPhase * 0.8) * 1.5;
    var tilt = isPl ? Math.sin(mascotPhase * 1.8) * 0.06 : 0;
    var jump = isPl ? Math.pow(threeMascotBassHit, 1.4) * 12.0 : 0;

    ctx.save();
    var cx = w / 2;
    var cy = h / 2 + 12;

    // Ground shadow
    var sScale = Math.max(0.3, 1.0 - jump / 16.0);
    ctx.save();
    ctx.translate(cx + swayX * 0.3, cy + 30);
    ctx.scale(sScale, sScale * 0.35);
    var sGrad = ctx.createRadialGradient(0, 0, 2, 0, 0, 26);
    sGrad.addColorStop(0, 'rgba(0,0,0,0.45)');
    sGrad.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.fillStyle = sGrad;
    ctx.beginPath(); ctx.arc(0, 0, 26, 0, Math.PI * 2); ctx.fill();
    ctx.restore();

    ctx.translate(cx + swayX, cy - jump);
    ctx.rotate(tilt);

    // Legs & Shoes
    ctx.fillStyle = '#dc2626';
    ctx.fillRect(-14, 10, 9, 14);
    ctx.fillRect(5, 10, 9, 14);
    // Yellow Clown Shoes with Red Caps
    ctx.fillStyle = '#eab308';
    ctx.beginPath(); ctx.ellipse(-9, 25, 10, 6, 0, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.ellipse(9, 25, 10, 6, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#dc2626';
    ctx.beginPath(); ctx.arc(-16, 25, 4.5, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.arc(16, 25, 4.5, 0, Math.PI * 2); ctx.fill();

    // Torso - Blue Shirt & Red Dungarees
    ctx.fillStyle = '#1d4ed8';
    ctx.beginPath(); ctx.ellipse(0, -6, 20, 14, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#dc2626';
    ctx.beginPath(); ctx.ellipse(0, 2, 17, 12, 0, 0, Math.PI * 2); ctx.fill();
    // Straps & Yellow Buttons
    ctx.fillRect(-11, -12, 5, 14);
    ctx.fillRect(6, -12, 5, 14);
    ctx.fillStyle = '#facc15';
    ctx.beginPath(); ctx.arc(-8.5, -2, 3.5, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.arc(8.5, -2, 3.5, 0, Math.PI * 2); ctx.fill();

    // Left Arm & Waving Right Arm
    ctx.fillStyle = '#f8fafc';
    ctx.beginPath(); ctx.ellipse(-19, -2, 5, 4, 0, 0, Math.PI * 2); ctx.fill();
    // Waving right hand
    var waveA = isPl ? Math.sin(mascotPhase * 3.5) * 0.3 - 0.4 : -0.3;
    ctx.save();
    ctx.translate(18, -6);
    ctx.rotate(waveA);
    ctx.fillStyle = '#1d4ed8';
    ctx.beginPath(); ctx.ellipse(2, -4, 4, 7, 0.4, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#ffffff';
    ctx.beginPath(); ctx.ellipse(6, -11, 5.5, 5, 0, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.arc(4, -16, 1.8, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.arc(7, -17, 1.8, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.arc(10, -15, 1.8, 0, Math.PI * 2); ctx.fill();
    ctx.restore();

    // Yellow Ruffled Collar & Bow Tie with Red Dots
    ctx.fillStyle = '#facc15';
    ctx.beginPath(); ctx.ellipse(0, -17, 18, 5, 0, 0, Math.PI * 2); ctx.fill();
    // Bow Tie
    ctx.beginPath();
    ctx.moveTo(0, -15);
    ctx.bezierCurveTo(-5, -20, -12, -19, -13, -15);
    ctx.bezierCurveTo(-12, -11, -5, -10, 0, -15);
    ctx.bezierCurveTo(5, -20, 12, -19, 13, -15);
    ctx.bezierCurveTo(12, -11, 5, -10, 0, -15);
    ctx.fill();
    ctx.fillStyle = '#eab308';
    ctx.beginPath(); ctx.ellipse(0, -15, 3, 3.8, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#dc2626';
    ctx.beginPath(); ctx.arc(-8, -16, 1.5, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.arc(-6, -13, 1.4, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.arc(8, -16, 1.5, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.arc(6, -13, 1.4, 0, Math.PI * 2); ctx.fill();

    // Head
    var headY = -34;
    // Peach Ears
    ctx.fillStyle = '#fed7aa';
    ctx.beginPath(); ctx.ellipse(-16, headY + 1, 4, 5, 0, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.ellipse(16, headY + 1, 4, 5, 0, 0, Math.PI * 2); ctx.fill();
    // Head base
    var hGrad = ctx.createRadialGradient(-3, headY - 3, 2, 0, headY, 17);
    hGrad.addColorStop(0, '#ffffff');
    hGrad.addColorStop(0.4, '#fed7aa');
    hGrad.addColorStop(1, '#fdba74');
    ctx.fillStyle = hGrad;
    ctx.beginPath(); ctx.ellipse(0, headY, 16, 17, 0, 0, Math.PI * 2); ctx.fill();

    // Rosy cheeks & smile
    ctx.fillStyle = 'rgba(248, 113, 113, 0.75)';
    ctx.beginPath(); ctx.arc(-8, headY + 4, 4, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.arc(8, headY + 4, 4, 0, Math.PI * 2); ctx.fill();
    // Smile with teeth
    ctx.fillStyle = '#b91c1c';
    ctx.beginPath();
    ctx.moveTo(-10, headY + 6.5);
    ctx.quadraticCurveTo(0, headY + 16, 10, headY + 6.5);
    ctx.quadraticCurveTo(0, headY + 10, -10, headY + 6.5);
    ctx.fill();
    ctx.fillStyle = '#ffffff';
    ctx.beginPath();
    ctx.moveTo(-6.5, headY + 7.5);
    ctx.quadraticCurveTo(0, headY + 11, 6.5, headY + 7.5);
    ctx.lineTo(5.5, headY + 9.5);
    ctx.quadraticCurveTo(0, headY + 12.5, -5.5, headY + 9.5);
    ctx.fill();

    // Eyes & Eyebrows
    ctx.strokeStyle = '#18181b'; ctx.lineWidth = 1.6;
    ctx.beginPath(); ctx.arc(-5.5, headY - 6.5, 4, Math.PI * 1.15, Math.PI * 1.85); ctx.stroke();
    ctx.beginPath(); ctx.arc(5.5, headY - 6.5, 4, Math.PI * 1.15, Math.PI * 1.85); ctx.stroke();
    ctx.fillStyle = '#ffffff';
    ctx.beginPath(); ctx.ellipse(-5.5, headY - 3, 3.5, 4.8, 0, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.ellipse(5.5, headY - 3, 3.5, 4.8, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#0284c7';
    ctx.beginPath(); ctx.arc(-5.2, headY - 2.8, 2.3, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.arc(5.2, headY - 2.8, 2.3, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#0f172a';
    ctx.beginPath(); ctx.arc(-5.2, headY - 2.8, 1.2, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.arc(5.2, headY - 2.8, 1.2, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#ffffff';
    ctx.beginPath(); ctx.arc(-4.5, headY - 3.5, 0.8, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.arc(5.9, headY - 3.5, 0.8, 0, Math.PI * 2); ctx.fill();

    // 3D Glossy Red Nose
    var nGrad = ctx.createRadialGradient(-1.5, headY - 0.5, 0.5, 0, headY + 1.2, 5.8);
    nGrad.addColorStop(0, '#fca5a5'); nGrad.addColorStop(0.4, '#ef4444'); nGrad.addColorStop(1, '#991b1b');
    ctx.fillStyle = nGrad;
    ctx.beginPath(); ctx.arc(0, headY + 1.2, 5.6, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#ffffff';
    ctx.beginPath(); ctx.arc(-1.5, headY - 0.5, 1.1, 0, Math.PI * 2); ctx.fill();

    // Orange Afro Curls
    ctx.fillStyle = '#ea580c';
    var curls = [
      [-13, headY - 2, 4.5], [-14, headY - 7, 4.5], [-12, headY - 12, 4.5], [-8, headY - 15, 4.5],
      [-3, headY - 16, 4.4], [3, headY - 16, 4.4], [8, headY - 15, 4.5], [12, headY - 12, 4.5],
      [14, headY - 7, 4.5], [13, headY - 2, 4.5], [-11, headY + 2, 3.6], [11, headY + 2, 3.6]
    ];
    curls.forEach(function(crl) {
      ctx.beginPath(); ctx.arc(crl[0], crl[1], crl[2], 0, Math.PI * 2); ctx.fill();
    });

    // Red Party Cone Hat with Blue Brim
    var hatBaseY = headY - 16;
    ctx.fillStyle = '#2563eb';
    ctx.beginPath(); ctx.ellipse(0, hatBaseY, 7.5, 2.5, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#dc2626';
    ctx.beginPath();
    ctx.moveTo(-5.5, hatBaseY); ctx.lineTo(0, hatBaseY - 13); ctx.lineTo(5.5, hatBaseY);
    ctx.closePath(); ctx.fill();
    ctx.fillStyle = '#f87171';
    ctx.beginPath(); ctx.arc(0, hatBaseY - 13, 1.6, 0, Math.PI * 2); ctx.fill();

    ctx.restore();
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
      var mSvg = '<svg viewBox="0 0 24 24"><path d="M12 2a2.5 2.5 0 0 0-2.5 2.5c0 .35.08.68.21.98L4.2 8.65A2 2 0 0 0 3 10.5v1.2a2 2 0 0 0 1.1 1.77L5 20a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2l.9-6.53A2 2 0 0 0 21 11.7v-1.2a2 2 0 0 0-1.2-1.85l-5.51-3.17c.13-.3.21-.63.21-.98A2.5 2.5 0 0 0 12 2zm0 4.2 4.5 2.6-2.5 4.7h-4L7.5 8.8 12 6.2zM7 16h10l-.6 4H7.6L7 16z"/></svg>';
      var mLbl = mascotEnabled
        ? (ar ? 'المهرج: تشغيل' : (isRu() ? 'Клоун: Вкл' : 'Clown: On'))
        : (ar ? 'المهرج: إيقاف' : (isRu() ? 'Клоун: Выкл' : 'Clown: Off'));
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
        '<div class="swst-row-badge">' + (t.durationSec > 0 ? fmtTime(t.durationSec) : '') + '</div></div>';
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
        '<div class="swst-row-badge">' + (t.durationSec > 0 ? fmtTime(t.durationSec) : '') + '</div></div>';
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
          '<button type="button" class="swst-pill-action mint" id="swst-r-autoscan">⚡ ' + (ar ? 'مسح وحفظ تلقائي' : 'Auto Scan & Save') + '</button>' +
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
    var aScan = document.getElementById('swst-r-autoscan');
    if (aScan) aScan.addEventListener('click', function () {
      if (bridge() && bridge().startShineWaveRadioAutoScan) {
        bridge().startShineWaveRadioAutoScan();
        aScan.innerText = ar ? '⏳ جاري المسح...' : '⏳ Scanning...';
        aScan.disabled = true;
        setTimeout(function () {
          if (bridge() && bridge().getShineWaveRadioState) bridge().getShineWaveRadioState();
          aScan.disabled = false;
          aScan.innerText = '⚡ ' + (ar ? 'مسح وحفظ تلقائي' : 'Auto Scan & Save');
        }, 12000);
      }
    });

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

  // Auto-open studio in browser simulation if #studio or ?studio is present
  try {
    if (window.location && (window.location.hash.indexOf('studio') !== -1 || window.location.search.indexOf('studio') !== -1)) {
      window.addEventListener('DOMContentLoaded', function () {
        setTimeout(function () {
          if (typeof window.openShineWaveStudio === 'function') {
            window.openShineWaveStudio('LOCAL_MUSIC');
          }
        }, 400);
      });
    }
  } catch (e) {}
})();
