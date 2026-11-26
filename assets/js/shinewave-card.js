/* ShineWave Media Card - Integrated Zero-Latency Controller inside Titan Launcher.
   Pastel-Only Palette, Zero-Duplicate Dispatch, Shuffle & Repeat Support, Direct Studio Opening.
*/
(function () {
  'use strict';
  var PLAY = 'M8 5v14l11-7z';
  var PAUSE = 'M6 19h4V5H6v14zm8-14v14h4V5h-4z';
  var CARET = 'M7 10l5 5 5-5z';
  var SHUFFLE_ICO = 'M10.59 9.17L5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 17.96 7.46 20 9.5V4h-5.5zm.33 9.41l-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z';
  var REPEAT_ICO = 'M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z';

  var SOURCES = [
    { key: 'LOCAL_MUSIC',    cmd: 'source_local',    en: 'USB',   ar: 'فلاشة',  icon: 'M15 7v4h1v2h-3V5h2l-3-4-3 4h2v8H8v-2.07c.7-.37 1.2-1.08 1.2-1.93 0-1.21-.99-2.2-2.2-2.2-1.21 0-2.2.99-2.2 2.2 0 .85.5 1.56 1.2 1.93V13c0 1.11.89 2 2 2h3v3.05c-.71.37-1.2 1.1-1.2 1.95 0 1.22.99 2.2 2.2 2.2 1.21 0 2.2-.98 2.2-2.2 0-.85-.49-1.58-1.2-1.95V15h3c1.11 0 2-.89 2-2v-2h1V7h-4z' },
    { key: 'BLUETOOTH',      cmd: 'source_bluetooth',en: 'BT',    ar: 'بلوتوث', icon: 'M17.71 7.71L12 2h-1v7.59L6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 11 14.41V22h1l5.71-5.71-4.3-4.29 4.3-4.29zM13 5.83l1.88 1.88L13 9.59V5.83zm1.88 10.46L13 18.17v-3.76l1.88 1.88z' },
    { key: 'CAR_RADIO',      cmd: 'source_car',      en: 'RADIO', ar: 'راديو',  icon: 'M3.24 6.15C2.51 6.43 2 7.17 2 8v12c0 1.1.89 2 2 2h16c1.11 0 2-.9 2-2V8c0-1.11-.89-2-2-2H8.3l8.26-3.34L15.88 1 3.24 6.15zM7 20c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3zm13-8h-2v-2h-2v2H4V8h16v4z' },
    { key: 'INTERNET_RADIO', cmd: 'source_internet', en: 'WEB',   ar: 'إنترنت', icon: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z' },
    { key: 'EXTERNAL_APP',   cmd: 'source_apps',     en: 'APPS',  ar: 'تطبيقات',icon: 'M4 8h4V4H4v4zm6 12h4v-4h-4v4zm-6 0h4v-4H4v4zm0-6h4v-4H4v4zm6 0h4v-4h-4v4zm6-10v4h4V4h-4zm-6 4h4V4h-4v4zm6 6h4v-4h-4v4zm0 6h4v-4h-4v4z' }
  ];

  var savedInitSource = 'BLUETOOTH';
  try {
    var storedSrc = localStorage.getItem('shine_wave_active_source');
    if (storedSrc) savedInitSource = storedSrc;
  } catch (e) {}

  var m = {
    title: '', artist: '', album: '', pos: 0, dur: 0,
    playing: false, source: savedInitSource, repeatMode: 'OFF', shuffle: false, albumArt: null
  };
  var el = {};
  var closeTimer = null;
  var phase = 0, rafId = 0, lastFrame = 0, amp = 0.85;
  var pulseWaves = [];
  var prevAudioBeat = 0, prevAudioEnergy = 0, avgAudioBeat = 0;
  var lastPulseSpawnAt = 0;

  window.ShineWaveCardState = m;

  function bridge() { return window.AndroidBridge || null; }
  function isAr() { return window.isArabicLang ? window.isArabicLang() : false; }
  function srcByKey(k) { for (var i = 0; i < SOURCES.length; i++) if (SOURCES[i].key === k) return SOURCES[i]; return SOURCES[1]; }
  function svg(path, cls) { return '<svg viewBox="0 0 24 24"' + (cls ? ' class="' + cls + '"' : '') + '><path d="' + path + '"/></svg>'; }
  function formatTime(sec) { return window.formatTimeSec ? window.formatTimeSec(sec, true) : '00:00'; }

  function build() {
    if (document.getElementById('swcard')) return;
    var root = document.createElement('div');
    root.id = 'swcard';
    root.className = 'hud interactive';

    var srcHtml = SOURCES.map(function (s) {
      return '<button type="button" class="swcard-src" data-key="' + s.key + '"></button>';
    }).join('');

    root.innerHTML =
      '<div class="swcard-top-info" id="swcard-open-top">' +
        '<div class="swcard-meta-top">' +
          '<div class="swcard-chip" id="swcard-chip" style="visibility: visible !important; display: inline-flex !important;" title="Select Source"><span id="swcard-chip-ico"></span><span class="swcard-brand" id="swcard-chip-lbl"></span>' + svg(CARET, 'caret') + '</div>' +
          '<div class="swcard-top-title-wrap"><div class="swcard-title" id="swcard-title">ShineWave Audio</div></div>' +
        '</div>' +
        '<div class="swcard-artist" id="swcard-artist" style="display:none !important;"></div>' +
      '</div>' +
      '<div class="swcard-bottom-row">' +
        '<div class="swcard-disc-wrap" id="swcard-open" title="Open Shine Wave Studio">' +
          '<canvas class="swcard-eq-canvas" id="swcard-eq-canvas"></canvas>' +
          '<div class="swcard-aura"></div>' +
          '<div class="swcard-disc" id="swcard-disc">' +
            '<div class="swcard-disc-center">' + svg('M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z') + '</div>' +
            '<img class="swcard-art" id="swcard-art" alt="">' +
          '</div>' +
        '</div>' +
        '<div class="swcard-mid-title-wrap">' +
          '<div class="swcard-time-display" id="swcard-time">00:00</div>' +
          '<div class="swcard-mode-row">' +
            '<button type="button" class="swcard-mode-btn" id="swcard-shuffle" title="Shuffle">' + svg(SHUFFLE_ICO) + '</button>' +
            '<button type="button" class="swcard-mode-btn" id="swcard-repeat" title="Repeat">' + svg(REPEAT_ICO) + '<span class="swcard-repeat-badge">1</span></button>' +
          '</div>' +
        '</div>' +
        '<div class="swcard-linear-eq-wrap" id="swcard-linear-wrap" style="display:none !important;">' +
          '<canvas class="swcard-linear-eq-canvas" id="swcard-linear-canvas"></canvas>' +
        '</div>' +
        '<div class="swcard-ctrls">' +
          '<button class="swcard-btn" data-cmd="previous" title="Previous Track">' + svg('M6 6h2v12H6zm3.5 6l8.5 6V6z') + '</button>' +
          '<button class="swcard-btn main" data-cmd="toggle" title="Play/Pause"><svg viewBox="0 0 24 24"><path id="swcard-play" d="' + PLAY + '"/></svg></button>' +
          '<button class="swcard-btn" data-cmd="next" title="Next Track">' + svg('M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z') + '</button>' +
        '</div>' +
      '</div>' +
      '<div class="swcard-wave" id="swcard-wave" title="Drag to Seek"><canvas id="swcard-canvas"></canvas></div>' +
      '<div class="swcard-sources" id="swcard-sources">' + srcHtml + '</div>';

    var host = document.querySelector('.hud');
    (host && host.parentElement ? host.parentElement : document.body).appendChild(root);

    el.root = root;
    el['mode-row'] = root.querySelector('.swcard-mode-row');
    ['disc', 'art', 'title', 'artist', 'play', 'chip', 'chip-ico', 'chip-lbl', 'time',
     'shuffle', 'repeat', 'wave', 'canvas', 'eq-canvas', 'linear-canvas', 'sources'].forEach(function (k) {
      el[k] = document.getElementById('swcard-' + k);
    });

    root.querySelectorAll('.swcard-btn').forEach(function (b) {
      var cmd = b.getAttribute('data-cmd');
      if (cmd === 'previous' || cmd === 'next') {
        bindTrackBtnLongPress(b, cmd);
      } else {
        b.addEventListener('click', function (e) { e.stopPropagation(); transport(cmd); });
      }
    });
    root.querySelectorAll('.swcard-src').forEach(function (n) {
      n.addEventListener('click', function (e) { e.stopPropagation(); pickSource(n.getAttribute('data-key')); });
    });
    el.shuffle.addEventListener('click', function (e) { e.stopPropagation(); toggleShuffle(); });
    el.repeat.addEventListener('click', function (e) { e.stopPropagation(); cycleRepeat(); });
    el.chip.addEventListener('click', function (e) { e.stopPropagation(); toggleSources(); });
    document.getElementById('swcard-open').addEventListener('click', function (e) { e.stopPropagation(); openStudio(m.source); });
    root.querySelector('.swcard-top-title-wrap').addEventListener('click', function (e) { e.stopPropagation(); openStudio(m.source); });
    el.art.addEventListener('error', function () { el.disc.classList.remove('has-art'); });

    bindSeek();
    sizeCanvas();
    render();

    var b = bridge();
    if (b && typeof b.requestShineWaveState === 'function') b.requestShineWaveState();
    document.addEventListener('visibilitychange', function () {
      if (!document.hidden && bridge() && bridge().requestShineWaveState) bridge().requestShineWaveState();
    });
  }

  /* ---------- 0ms Single-Path Commands ---------- */
  function transport(cmd) {
    if (cmd === 'toggle') {
      m.playing = !m.playing;
      render();
    }
    var b = bridge();
    if (b && typeof b.sendShineWaveCommand === 'function') {
      b.sendShineWaveCommand(cmd);
    } else if ((cmd === 'next' || cmd === 'previous') && typeof window.simStepShineWaveTrack === 'function') {
      window.simStepShineWaveTrack(cmd === 'next' ? 1 : -1);
    }
    if (typeof window.syncShineWaveStudioState === 'function') {
      window.syncShineWaveStudioState(m);
    }
  }

  function toggleShuffle() {
    if (!supportsSeekAndModes(m.source)) return;
    m.shuffle = !m.shuffle;
    render();
    var b = bridge();
    if (b && typeof b.toggleShineWaveShuffle === 'function') b.toggleShineWaveShuffle();
    if (typeof window.syncShineWaveStudioState === 'function') window.syncShineWaveStudioState(m);
  }

  function cycleRepeat() {
    if (!supportsSeekAndModes(m.source)) return;
    m.repeatMode = (m.repeatMode === 'OFF') ? 'ALL' : (m.repeatMode === 'ALL' ? 'ONE' : 'OFF');
    render();
    var b = bridge();
    if (b && typeof b.cycleShineWaveRepeat === 'function') b.cycleShineWaveRepeat();
    if (typeof window.syncShineWaveStudioState === 'function') window.syncShineWaveStudioState(m);
  }

  function supportsSeekAndModes(src) {
    return src === 'LOCAL_MUSIC' || src === 'EXTERNAL_APP';
  }

  function showsPlaybackProgress(src) {
    return src === 'LOCAL_MUSIC' || src === 'EXTERNAL_APP' || src === 'BLUETOOTH';
  }

  var toastTimer = null;
  function showCardToast(text) {
    var toast = document.getElementById('swcard-seek-toast');
    if (!toast) {
      toast = document.createElement('div');
      toast.id = 'swcard-seek-toast';
      toast.style.cssText = 'position:absolute;bottom:78px;left:50%;transform:translateX(-50%);background:rgba(12,29,40,0.92);color:#7dd3fc;padding:4px 14px;border-radius:14px;font-size:14px;font-weight:800;border:1px solid rgba(125,211,252,0.4);box-shadow:0 0 16px rgba(125,211,252,0.3);pointer-events:none;z-index:99;opacity:0;transition:opacity 0.18s,transform 0.18s;';
      if (el.root) el.root.appendChild(toast);
    }
    toast.textContent = text;
    toast.style.opacity = '1';
    toast.style.transform = 'translateX(-50%) translateY(-4px)';
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      toast.style.opacity = '0';
      toast.style.transform = 'translateX(-50%) translateY(0)';
    }, 700);
  }
  window.showShineWaveCardToast = showCardToast;

  function bindTrackBtnLongPress(btn, cmd) {
    var timer = null;
    var repeatTimer = null;
    var isLong = false;
    var lastTouchAt = 0;
    var HOLD_DELAY_MS = 750;
    var deltaSec = (cmd === 'next') ? 10 : -10;

    function doSeek() {
      if (!supportsSeekAndModes(m.source)) return;
      var b = bridge();
      if (b && typeof b.seekMediaBy === 'function') {
        b.seekMediaBy(deltaSec);
      } else if (b && typeof b.sendShineWaveCommand === 'function') {
        b.sendShineWaveCommand(cmd === 'next' ? 'seek_forward' : 'seek_back');
      } else {
        m.pos = Math.max(0, Math.min(m.dur || 225, (m.pos || 0) + deltaSec));
        render();
        kick();
      }
      showCardToast(deltaSec > 0 ? '+10s ▶▶' : '◀◀ -10s');
    }

    function start(e) {
      isLong = false;
      if (timer) clearTimeout(timer);
      if (repeatTimer) clearInterval(repeatTimer);
      if (!supportsSeekAndModes(m.source)) return;
      timer = setTimeout(function () {
        isLong = true;
        doSeek();
        repeatTimer = setInterval(doSeek, 450);
      }, HOLD_DELAY_MS);
    }

    function cancel() {
      if (timer) { clearTimeout(timer); timer = null; }
      if (repeatTimer) { clearInterval(repeatTimer); repeatTimer = null; }
    }

    btn.addEventListener('touchstart', function (e) {
      lastTouchAt = Date.now();
      start(e);
    }, { passive: true });
    btn.addEventListener('touchend', function (e) {
      lastTouchAt = Date.now();
      cancel();
      if (isLong) {
        if (e.cancelable) e.preventDefault();
        e.stopPropagation();
      }
    });
    btn.addEventListener('touchcancel', function () {
      lastTouchAt = Date.now();
      cancel();
      isLong = false;
    });
    btn.addEventListener('mousedown', function (e) {
      if (Date.now() - lastTouchAt < 900) return;
      if (e.button === 0) start(e);
    });
    btn.addEventListener('mouseup', function (e) {
      if (Date.now() - lastTouchAt < 900) return;
      cancel();
      if (isLong) {
        if (e.cancelable) e.preventDefault();
        e.stopPropagation();
      }
    });
    btn.addEventListener('mouseleave', function () {
      if (Date.now() - lastTouchAt < 900) return;
      cancel();
    });

    btn.addEventListener('click', function (e) {
      e.stopPropagation();
      cancel();
      if (isLong) {
        isLong = false;
        return;
      }
      transport(cmd);
    });
  }

  window.refreshShineWaveCardUI = render;

  function openStudio(tabKey) {
    toggleSources(false);
    if (typeof window.openShineWaveStudio === 'function') {
      window.openShineWaveStudio(tabKey || m.source || 'LOCAL_MUSIC');
    }
  }

  function applySourceSelection(key) {
    var s = srcByKey(key), b = bridge();
    if (!s) return;
    m.source = key;
    try { localStorage.setItem('shine_wave_active_source', key); } catch (e) {}
    if (b && typeof b.sendShineWaveCommand === 'function') {
      b.sendShineWaveCommand(s.cmd);
    } else if (typeof window.simSwitchShineWaveSource === 'function') {
      window.simSwitchShineWaveSource(key);
    }
    render();
    if (typeof window.syncShineWaveStudioState === 'function') {
      window.syncShineWaveStudioState(m);
    }
  }

  window.setShineWaveSource = function (key) {
    applySourceSelection(key);
  };

  function pickSource(key) {
    toggleSources(false);
    openQuickPicker(key);
  }

  /* ---------- Launcher-Style Mini-Player Source Quick-Picker Popup ---------- */
  var qpSource = '';
  var qpLocalFolder = '';
  var qpLocalQuery = '';
  var qpInetTab = 'arabic';
  var qpInetQuery = '';
  var qpCache = { LOCAL_MUSIC: null, CAR_RADIO: null, INTERNET_RADIO: null, EXTERNAL_APP: null };

  function esc(str) {
    return window.escapeHtml ? window.escapeHtml(str) : String(str || '');
  }

  function ensureQuickPickerDom() {
    var existing = document.getElementById('swcard-picker-overlay');
    if (existing) return existing;
    var ov = document.createElement('div');
    ov.id = 'swcard-picker-overlay';
    ov.className = 'swqp-overlay interactive';
    ov.innerHTML =
      '<div class="swqp-box" id="swqp-box">' +
        '<div class="swqp-header">' +
          '<div class="swqp-title-wrap">' +
            '<span class="swqp-title-ico" id="swqp-ico"></span>' +
            '<div class="swqp-title" id="swqp-title"></div>' +
          '</div>' +
          '<div class="swqp-header-actions">' +
            '<button type="button" class="swqp-studio-btn" id="swqp-open-studio"></button>' +
            '<button type="button" class="swqp-close" id="swqp-close">&times;</button>' +
          '</div>' +
        '</div>' +
        '<div class="swqp-controls" id="swqp-controls"></div>' +
        '<div class="swqp-list" id="swqp-list"></div>' +
      '</div>';
    var host = document.getElementById('cockpit-viewport') || document.body;
    host.appendChild(ov);

    ov.addEventListener('click', function () { closeQuickPicker(); });
    document.getElementById('swqp-box').addEventListener('click', function (e) { e.stopPropagation(); });
    document.getElementById('swqp-close').addEventListener('click', function (e) { e.stopPropagation(); closeQuickPicker(); });
    document.getElementById('swqp-open-studio').addEventListener('click', function (e) {
      e.stopPropagation();
      var targetSrc = qpSource || m.source;
      closeQuickPicker();
      if (typeof window.setShineWaveSource === 'function') window.setShineWaveSource(targetSrc);
      openStudio(targetSrc);
    });
    return ov;
  }

  function closeQuickPicker() {
    var ov = document.getElementById('swcard-picker-overlay');
    if (ov) ov.classList.remove('active');
  }
  window.closeShineWaveQuickPicker = closeQuickPicker;

  function fetchQuickPickerData(key) {
    var b = bridge();
    if (b) {
      if (key === 'LOCAL_MUSIC' && b.browseShineWaveLocal) b.browseShineWaveLocal(qpLocalFolder, qpLocalQuery, false);
      else if (key === 'CAR_RADIO' && b.getShineWaveRadioState) b.getShineWaveRadioState();
      else if (key === 'INTERNET_RADIO' && b.searchShineWaveInternetRadio) b.searchShineWaveInternetRadio(qpInetTab, qpInetQuery);
      else if (key === 'EXTERNAL_APP' && b.getShineWaveExternalApps) b.getShineWaveExternalApps();
      return;
    }
    var db = window.ShineWaveSimDb;
    if (!db) return;
    if (key === 'LOCAL_MUSIC') {
      var q = (qpLocalQuery || '').trim().toLowerCase();
      var folders = [], tracks = [];
      if (q) {
        tracks = db.localTracks.filter(function (t) {
          return t.title.toLowerCase().indexOf(q) >= 0 || t.artist.toLowerCase().indexOf(q) >= 0;
        });
      } else if (!qpLocalFolder) {
        folders = [{ path: '/USB/Arabic Hits', name: 'Arabic Hits', count: 3 }, { path: '/USB/Chill Night', name: 'Chill Night', count: 2 }];
      } else {
        tracks = db.localTracks.filter(function (t) { return t.folder === qpLocalFolder; });
      }
      qpCache.LOCAL_MUSIC = { currentFolder: qpLocalFolder, currentFolderName: qpLocalFolder.split('/').pop(), parentFolder: '', hasSavedQueue: true, folders: folders, tracks: tracks };
      renderQuickPickerList();
    } else if (key === 'CAR_RADIO') {
      qpCache.CAR_RADIO = db.radio;
      renderQuickPickerList();
    } else if (key === 'INTERNET_RADIO') {
      var iq = (qpInetQuery || '').trim().toLowerCase();
      qpCache.INTERNET_RADIO = { stations: db.internet.filter(function (s) {
        if (qpInetTab === 'favorites' && !s.favorite) return false;
        if (iq) return s.name.toLowerCase().indexOf(iq) >= 0;
        return true;
      }) };
      renderQuickPickerList();
    } else if (key === 'EXTERNAL_APP') {
      qpCache.EXTERNAL_APP = { apps: db.apps };
      renderQuickPickerList();
    }
  }

  function openQuickPicker(key) {
    qpSource = key;
    var ov = ensureQuickPickerDom();
    var box = document.getElementById('swqp-box');
    var ar = isAr();
    box.classList.toggle('rtl-mode', ar);
    box.classList.toggle('ltr-mode', !ar);

    var s = srcByKey(key);
    var titles = {
      LOCAL_MUSIC: ar ? 'الملفات والفلاشة' : 'USB & Local Files',
      BLUETOOTH: ar ? 'صوت البلوتوث' : 'Bluetooth Audio',
      CAR_RADIO: ar ? 'راديو السيارة FM / AM' : 'Car Radio FM / AM',
      INTERNET_RADIO: ar ? 'راديو الإنترنت' : 'Internet Radio',
      EXTERNAL_APP: ar ? 'تطبيقات الميديا' : 'Media Apps'
    };
    document.getElementById('swqp-ico').innerHTML = s ? svg(s.icon) : '';
    document.getElementById('swqp-title').textContent = titles[key] || '';
    document.getElementById('swqp-open-studio').textContent = ar ? 'المشغل الكامل' : 'Full Studio';

    ov.classList.add('active');
    renderQuickPickerControls();
    renderQuickPickerList();
    fetchQuickPickerData(key);
  }

  function renderQuickPickerControls() {
    var ctrl = document.getElementById('swqp-controls');
    if (!ctrl) return;
    var ar = isAr();

    if (qpSource === 'INTERNET_RADIO') {
      var tabs = [
        { id: 'arabic', en: 'Arabic', ar: 'عربية' },
        { id: 'popular', en: 'Popular', ar: 'شائعة' },
        { id: 'favorites', en: 'Favorites ★', ar: 'المفضلة ★' },
        { id: 'recent', en: 'Recent', ar: 'الأخيرة' }
      ];
      ctrl.innerHTML =
        '<div class="swqp-pills">' + tabs.map(function (t) {
          return '<button type="button" class="swqp-pill' + (qpInetTab === t.id ? ' active' : '') + '" data-qp-itab="' + t.id + '">' + (ar ? t.ar : t.en) + '</button>';
        }).join('') + '</div>' +
        '<div class="swqp-search-row">' +
          '<input type="text" class="swqp-search" id="swqp-inet-q" placeholder="' + (ar ? 'ابحث باسم الإذاعة...' : 'Search station...') + '" value="' + esc(qpInetQuery) + '">' +
          '<button type="button" class="swqp-pill peach" id="swqp-inet-go">' + (ar ? 'بحث' : 'Search') + '</button>' +
        '</div>';
      ctrl.querySelectorAll('[data-qp-itab]').forEach(function (b) {
        b.addEventListener('click', function () {
          qpInetTab = b.getAttribute('data-qp-itab');
          renderQuickPickerControls();
          fetchQuickPickerData('INTERNET_RADIO');
        });
      });
      var goBtn = document.getElementById('swqp-inet-go');
      var qIn = document.getElementById('swqp-inet-q');
      if (goBtn) goBtn.addEventListener('click', function () { qpInetQuery = qIn ? qIn.value : ''; fetchQuickPickerData('INTERNET_RADIO'); });
      if (qIn) qIn.addEventListener('keydown', function (e) { if (e.key === 'Enter') { qpInetQuery = qIn.value; fetchQuickPickerData('INTERNET_RADIO'); } });
    } else if (qpSource === 'LOCAL_MUSIC') {
      ctrl.innerHTML =
        '<div class="swqp-search-row">' +
          '<input type="text" class="swqp-search" id="swqp-local-q" placeholder="' + (ar ? 'ابحث في الأغاني والملفات...' : 'Search tracks or artists...') + '" value="' + esc(qpLocalQuery) + '">' +
        '</div>';
      var lIn = document.getElementById('swqp-local-q');
      if (lIn) lIn.addEventListener('input', function () { qpLocalQuery = lIn.value || ''; fetchQuickPickerData('LOCAL_MUSIC'); });
    } else if (qpSource === 'CAR_RADIO') {
      var r = qpCache.CAR_RADIO || (window.ShineWaveSimDb && window.ShineWaveSimDb.radio) || { radioType: 'FM', band: 'FM', freqKhz: 100600 };
      var isDab = r.radioType === 'DAB';
      var isAm = r.band === 'AM' && !isDab;
      var freqDisp = isDab ? 'DAB+' : (isAm ? (r.freqKhz + ' kHz') : ((r.freqKhz / 1000).toFixed(1) + ' MHz'));
      ctrl.innerHTML =
        '<div class="swqp-pills" style="justify-content:space-between;">' +
          '<div style="display:flex;gap:6px;direction:ltr;">' +
            '<button type="button" class="swqp-pill' + (!isDab && !isAm ? ' active' : '') + '" data-qp-rband="FM">FM</button>' +
            '<button type="button" class="swqp-pill' + (!isDab && isAm ? ' active' : '') + '" data-qp-rband="AM">AM</button>' +
            '<button type="button" class="swqp-pill' + (isDab ? ' active' : '') + '" data-qp-rband="DAB">DAB+</button>' +
          '</div>' +
          '<span style="font-size:17px;font-weight:800;color:#bae6fd;direction:ltr;">' + esc(freqDisp) + '</span>' +
          (!isDab ? ('<div style="display:flex;gap:6px;direction:ltr;">' +
            '<button type="button" class="swqp-pill" id="swqp-r-dn">◀ ' + (ar ? 'بحث' : 'Seek') + '</button>' +
            '<button type="button" class="swqp-pill" id="swqp-r-up">' + (ar ? 'بحث' : 'Seek') + ' ▶</button>' +
          '</div>') : '') +
        '</div>';
      ctrl.querySelectorAll('[data-qp-rband]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          var bnd = btn.getAttribute('data-qp-rband');
          if (bridge() && bridge().selectShineWaveRadioBand) { bridge().selectShineWaveRadioBand(bnd); }
          else if (window.ShineWaveSimDb) {
            var sr = window.ShineWaveSimDb.radio;
            sr.radioType = bnd;
            if (bnd !== 'DAB') { sr.band = bnd; sr.freqKhz = (bnd === 'AM') ? sr.lastAmFreq : sr.lastFmFreq; sr.stations = (bnd === 'AM') ? sr.amStations : sr.fmStations; }
            qpCache.CAR_RADIO = sr;
            renderQuickPickerControls(); renderQuickPickerList();
          }
        });
      });
      var dn = document.getElementById('swqp-r-dn');
      var up = document.getElementById('swqp-r-up');
      if (dn) dn.addEventListener('click', function () { if (bridge()) bridge().seekShineWaveRadio(false); else if (window.simStepShineWaveTrack) { window.simSwitchShineWaveSource('CAR_RADIO'); window.simStepShineWaveTrack(-1); renderQuickPickerControls(); } });
      if (up) up.addEventListener('click', function () { if (bridge()) bridge().seekShineWaveRadio(true); else if (window.simStepShineWaveTrack) { window.simSwitchShineWaveSource('CAR_RADIO'); window.simStepShineWaveTrack(1); renderQuickPickerControls(); } });
    } else {
      ctrl.innerHTML = '';
    }
  }

  function renderQuickPickerList() {
    var listEl = document.getElementById('swqp-list');
    if (!listEl) return;
    var ar = isAr();

    if (qpSource === 'INTERNET_RADIO') {
      var d = qpCache.INTERNET_RADIO;
      var sts = (d && d.stations) || [];
      var html = '<div class="swqp-row active" id="swqp-inet-resume">' +
        '<div class="swqp-row-ico">▶</div>' +
        '<div class="swqp-row-info"><div class="swqp-row-title">' + (ar ? 'استئناف آخر محطة' : 'Resume Last Station') + '</div>' +
        '<div class="swqp-row-sub">' + (ar ? 'تشغيل آخر إذاعة إنترنت محفوظة' : 'Play last saved internet radio station') + '</div></div></div>';
      if (!sts.length) {
        html += '<div class="swqp-empty">' + (d ? (ar ? 'لا توجد محطات مطابقة' : 'No matching stations') : (ar ? 'جاري تحميل المحطات...' : 'Loading stations...')) + '</div>';
      } else {
        sts.forEach(function (st, idx) {
          html += '<div class="swqp-row" data-qp-ist="' + idx + '">' +
            '<div class="swqp-row-ico">📡</div>' +
            '<div class="swqp-row-info"><div class="swqp-row-title">' + esc(st.name) + '</div>' +
            '<div class="swqp-row-sub">' + esc(st.country || 'Internet Radio') + '</div></div>' +
            (st.favorite ? '<div class="swqp-row-badge" style="color:#fde68a;background:rgba(253,230,138,0.16);">★</div>' : '') +
            '</div>';
        });
      }
      listEl.innerHTML = html;
      document.getElementById('swqp-inet-resume').addEventListener('click', function () {
        if (bridge() && bridge().resumeShineWaveQueue) bridge().resumeShineWaveQueue('INTERNET_RADIO');
        else applySourceSelection('INTERNET_RADIO');
        closeQuickPicker();
      });
      listEl.querySelectorAll('[data-qp-ist]').forEach(function (row) {
        row.addEventListener('click', function () {
          var st = sts[parseInt(row.getAttribute('data-qp-ist'), 10)];
          if (!st) return;
          m.source = 'INTERNET_RADIO';
          try { localStorage.setItem('shine_wave_active_source', 'INTERNET_RADIO'); } catch (e) {}
          if (bridge()) bridge().playShineWaveInternetStation(st.uuid, st.name, st.url, st.country);
          else {
            m.title = st.name; m.artist = 'Internet Radio'; m.dur = 0; m.pos = 0; m.playing = true;
            render();
            if (typeof window.syncShineWaveStudioState === 'function') window.syncShineWaveStudioState(m);
          }
          closeQuickPicker();
        });
      });
    } else if (qpSource === 'LOCAL_MUSIC') {
      var ld = qpCache.LOCAL_MUSIC;
      if (!ld) {
        listEl.innerHTML = '<div class="swqp-empty">' + (ar ? 'جاري فحص الذاكرة والفلاشة...' : 'Scanning USB & local files...') + '</div>';
        return;
      }
      var lHtml = '';
      if (!qpLocalFolder && !qpLocalQuery && ld.hasSavedQueue) {
        lHtml += '<div class="swqp-row active" id="swqp-loc-resume">' +
          '<div class="swqp-row-ico">▶</div>' +
          '<div class="swqp-row-info"><div class="swqp-row-title">' + (ar ? 'استئناف آخر تشغيل' : 'Resume Last Track') + '</div>' +
          '<div class="swqp-row-sub">' + (ar ? 'متابعة آخر قائمة تشغيل من الفلاشة' : 'Continue last local playback queue') + '</div></div></div>';
      }
      if (qpLocalFolder && !qpLocalQuery) {
        lHtml += '<div class="swqp-row" id="swqp-loc-up">' +
          '<div class="swqp-row-ico folder">‹</div>' +
          '<div class="swqp-row-info"><div class="swqp-row-title">' + (ar ? 'المجلد السابق' : 'Parent Folder') + '</div>' +
          '<div class="swqp-row-sub">' + esc(ld.currentFolderName || qpLocalFolder) + '</div></div></div>';
      }
      (ld.folders || []).forEach(function (f) {
        lHtml += '<div class="swqp-row" data-qp-lfolder="' + esc(f.path) + '">' +
          '<div class="swqp-row-ico folder">📁</div>' +
          '<div class="swqp-row-info"><div class="swqp-row-title">' + esc(f.name) + '</div>' +
          '<div class="swqp-row-sub">' + esc(f.count + (ar ? ' ملف صوتي' : ' tracks')) + '</div></div>' +
          '<div class="swqp-row-badge">›</div></div>';
      });
      (ld.tracks || []).forEach(function (t, tidx) {
        lHtml += '<div class="swqp-row" data-qp-ltrack="' + esc(t.id) + '" data-qp-tidx="' + tidx + '">' +
          '<div class="swqp-row-ico">♪</div>' +
          '<div class="swqp-row-info"><div class="swqp-row-title">' + esc(t.title) + '</div>' +
          '<div class="swqp-row-sub">' + esc(t.artist) + '</div></div>' +
          (t.durationSec > 0 ? '<div class="swqp-row-badge">' + esc(formatTime(t.durationSec)) + '</div>' : '') +
          '</div>';
      });
      if (!lHtml) lHtml = '<div class="swqp-empty">' + (ar ? 'لا توجد ملفات مطابقة' : 'No matching tracks') + '</div>';
      listEl.innerHTML = lHtml;

      var rBtn = document.getElementById('swqp-loc-resume');
      if (rBtn) rBtn.addEventListener('click', function () {
        if (bridge() && bridge().resumeShineWaveQueue) bridge().resumeShineWaveQueue('LOCAL_MUSIC');
        else applySourceSelection('LOCAL_MUSIC');
        closeQuickPicker();
      });
      var upBtn = document.getElementById('swqp-loc-up');
      if (upBtn) upBtn.addEventListener('click', function () {
        qpLocalFolder = ld.parentFolder || '';
        fetchQuickPickerData('LOCAL_MUSIC');
      });
      listEl.querySelectorAll('[data-qp-lfolder]').forEach(function (row) {
        row.addEventListener('click', function () {
          qpLocalFolder = row.getAttribute('data-qp-lfolder');
          fetchQuickPickerData('LOCAL_MUSIC');
        });
      });
      listEl.querySelectorAll('[data-qp-ltrack]').forEach(function (row) {
        row.addEventListener('click', function () {
          var tid = row.getAttribute('data-qp-ltrack');
          m.source = 'LOCAL_MUSIC';
          try { localStorage.setItem('shine_wave_active_source', 'LOCAL_MUSIC'); } catch (e) {}
          if (bridge()) bridge().playShineWaveLocalTrack(qpLocalFolder, qpLocalQuery, tid);
          else {
            var db = window.ShineWaveSimDb;
            if (db && db.localTracks) {
              for (var i = 0; i < db.localTracks.length; i++) {
                if (db.localTracks[i].id === tid) { db.localIdx = i; break; }
              }
            }
            applySourceSelection('LOCAL_MUSIC');
          }
          closeQuickPicker();
        });
      });
    } else if (qpSource === 'CAR_RADIO') {
      var r = qpCache.CAR_RADIO || (window.ShineWaveSimDb && window.ShineWaveSimDb.radio) || { radioType: 'FM', band: 'FM', freqKhz: 100600, stations: [], dabItems: [] };
      var isDab = r.radioType === 'DAB';
      var isAm = r.band === 'AM' && !isDab;
      var curLabel = isDab ? 'DAB+' : (isAm ? ('AM ' + r.freqKhz + ' kHz') : ('FM ' + (r.freqKhz / 1000).toFixed(1) + ' MHz'));
      var rHtml = '<div class="swqp-row active" id="swqp-r-current">' +
        '<div class="swqp-row-ico">▶</div>' +
        '<div class="swqp-row-info"><div class="swqp-row-title">' + (ar ? 'تشغيل التردد الحالي: ' : 'Play Current: ') + esc(curLabel) + '</div>' +
        '<div class="swqp-row-sub">' + (ar ? 'تفعيل صوت راديو السيارة مباشرة' : 'Activate Car Radio immediately') + '</div></div></div>';
      if (isDab) {
        (r.dabItems || []).forEach(function (it) {
          rHtml += '<div class="swqp-row" data-qp-dab="' + esc(it.mediaId) + '" data-qp-dtitle="' + esc(it.title) + '">' +
            '<div class="swqp-row-ico">📻</div>' +
            '<div class="swqp-row-info"><div class="swqp-row-title">' + esc(it.title) + '</div>' +
            '<div class="swqp-row-sub">DAB+ Digital Radio</div></div></div>';
        });
      } else {
        var sortedSts = (r.stations || []).slice().sort(function (a, b) {
          return (b.favorite ? 1 : 0) - (a.favorite ? 1 : 0);
        });
        if (!sortedSts.length) {
          rHtml += '<div class="swqp-empty">' + (ar ? 'لا توجد محطات محفوظة حالياً — افتح المشغل الكامل للمسح التلقائي' : 'No saved stations yet') + '</div>';
        } else {
          sortedSts.forEach(function (st) {
            var fText = isAm ? (st.freq + ' kHz') : ((st.freq / 1000).toFixed(1) + ' MHz');
            rHtml += '<div class="swqp-row' + (st.freq === r.freqKhz ? ' active' : '') + '" data-qp-rfreq="' + st.freq + '" data-qp-rname="' + esc(st.name || fText) + '">' +
              '<div class="swqp-row-ico">' + (st.favorite ? '★' : '📻') + '</div>' +
              '<div class="swqp-row-info"><div class="swqp-row-title">' + esc(st.name || fText) + '</div>' +
              '<div class="swqp-row-sub">' + esc(fText) + '</div></div></div>';
          });
        }
      }
      listEl.innerHTML = rHtml;

      document.getElementById('swqp-r-current').addEventListener('click', function () {
        if (bridge() && !isDab && bridge().tuneShineWaveRadio) {
          m.source = 'CAR_RADIO';
          try { localStorage.setItem('shine_wave_active_source', 'CAR_RADIO'); } catch (e) {}
          bridge().tuneShineWaveRadio(r.band || 'FM', r.freqKhz);
          render();
        } else {
          applySourceSelection('CAR_RADIO');
        }
        closeQuickPicker();
      });
      listEl.querySelectorAll('[data-qp-rfreq]').forEach(function (row) {
        row.addEventListener('click', function () {
          var fk = parseInt(row.getAttribute('data-qp-rfreq'), 10);
          m.source = 'CAR_RADIO';
          try { localStorage.setItem('shine_wave_active_source', 'CAR_RADIO'); } catch (e) {}
          if (bridge() && bridge().tuneShineWaveRadio) {
            bridge().tuneShineWaveRadio(r.band || 'FM', fk);
            render();
          } else {
            if (window.ShineWaveSimDb) window.ShineWaveSimDb.radio.freqKhz = fk;
            applySourceSelection('CAR_RADIO');
          }
          closeQuickPicker();
        });
      });
      listEl.querySelectorAll('[data-qp-dab]').forEach(function (row) {
        row.addEventListener('click', function () {
          var mid = row.getAttribute('data-qp-dab');
          m.source = 'CAR_RADIO';
          try { localStorage.setItem('shine_wave_active_source', 'CAR_RADIO'); } catch (e) {}
          if (bridge() && bridge().playShineWaveDab) bridge().playShineWaveDab(mid);
          else applySourceSelection('CAR_RADIO');
          closeQuickPicker();
        });
      });
    } else if (qpSource === 'EXTERNAL_APP') {
      var ad = qpCache.EXTERNAL_APP || (window.ShineWaveSimDb ? { apps: window.ShineWaveSimDb.apps } : null);
      var apps = (ad && ad.apps) || [];
      if (!apps.length) {
        listEl.innerHTML = '<div class="swqp-empty">' + (ad ? (ar ? 'لا توجد تطبيقات ميديا مثبتة' : 'No installed media apps found') : (ar ? 'جاري تحميل التطبيقات...' : 'Loading media apps...')) + '</div>';
        return;
      }
      listEl.innerHTML = apps.map(function (a, idx) {
        return '<div class="swqp-row' + (a.selected ? ' active' : '') + '" data-qp-app="' + idx + '">' +
          '<div class="swqp-row-ico">' + (a.icon ? '<img src="' + esc(a.icon) + '" alt="">' : '🎵') + '</div>' +
          '<div class="swqp-row-info"><div class="swqp-row-title">' + esc(a.label) + (a.selected ? ' ✓' : '') + '</div>' +
          '<div class="swqp-row-sub">' + esc(a.pkg) + '</div></div>' +
          '<button type="button" class="swqp-pill mint" data-qp-appopen="' + idx + '">' + (ar ? 'فتح' : 'Open') + '</button>' +
          '</div>';
      }).join('');

      listEl.querySelectorAll('[data-qp-app]').forEach(function (row) {
        row.addEventListener('click', function (e) {
          var launchNow = !!(e.target && e.target.getAttribute('data-qp-appopen') !== null);
          var a = apps[parseInt(row.getAttribute('data-qp-app'), 10)];
          if (!a) return;
          m.source = 'EXTERNAL_APP';
          try { localStorage.setItem('shine_wave_active_source', 'EXTERNAL_APP'); } catch (e2) {}
          if (bridge() && bridge().selectShineWaveExternalApp) {
            bridge().selectShineWaveExternalApp(a.pkg, a.label, launchNow);
            render();
          } else {
            apps.forEach(function (x) { x.selected = (x.pkg === a.pkg); });
            applySourceSelection('EXTERNAL_APP');
          }
          closeQuickPicker();
        });
      });
    } else if (qpSource === 'BLUETOOTH') {
      var liveTrack = m.title && m.source === 'BLUETOOTH' ? m.title : (ar ? 'صوت البلوتوث التلقائي' : 'Bluetooth Audio Stream');
      listEl.innerHTML =
        '<div class="swqp-row active" id="swqp-bt-play">' +
          '<div class="swqp-row-ico">▶</div>' +
          '<div class="swqp-row-info"><div class="swqp-row-title">' + (ar ? 'تفعيل وتشغيل البلوتوث' : 'Activate & Play Bluetooth') + '</div>' +
          '<div class="swqp-row-sub">' + esc(liveTrack) + '</div></div></div>' +
        '<div class="swqp-row" id="swqp-bt-settings">' +
          '<div class="swqp-row-ico">⚙</div>' +
          '<div class="swqp-row-info"><div class="swqp-row-title">' + (ar ? 'إعدادات اتصال البلوتوث' : 'Bluetooth Connection Settings') + '</div>' +
          '<div class="swqp-row-sub">' + (ar ? 'فحص أو إقران الهاتف بالسيارة' : 'Manage paired phone & connection') + '</div></div></div>';
      document.getElementById('swqp-bt-play').addEventListener('click', function () {
        applySourceSelection('BLUETOOTH');
        closeQuickPicker();
      });
      document.getElementById('swqp-bt-settings').addEventListener('click', function () {
        closeQuickPicker();
        if (typeof window.openConnectivityModal === 'function') window.openConnectivityModal('bluetooth');
      });
    }
  }

  window.__onSwQuickPickerData = function (key, data) {
    qpCache[key] = data;
    var ov = document.getElementById('swcard-picker-overlay');
    if (ov && ov.classList.contains('active') && qpSource === key) {
      if (key === 'CAR_RADIO') renderQuickPickerControls();
      renderQuickPickerList();
    }
  };

  function toggleSources(force) {
    var open = typeof force === 'boolean' ? force : !el.root.classList.contains('src-open');
    el.root.classList.toggle('src-open', open);
    clearTimeout(closeTimer);
    if (open) closeTimer = setTimeout(function () { toggleSources(false); }, 6000);
  }

  /* ---------- Wavy Progress & Pastel Aura Equalizer ---------- */
  function sizeCanvas() {
    var dpr = window.devicePixelRatio || 1, w = el.wave.clientWidth || 600, h = 20;
    el.canvas.width = Math.round(w * dpr); el.canvas.height = Math.round(h * dpr);
    el.canvas.style.width = w + 'px'; el.canvas.style.height = h + 'px';
    el.canvas._dpr = dpr; el.canvas._w = w; el.canvas._h = h;

    if (el['eq-canvas']) {
      var eqSize = 136;
      el['eq-canvas'].width = Math.round(eqSize * dpr);
      el['eq-canvas'].height = Math.round(eqSize * dpr);
      el['eq-canvas'].style.width = eqSize + 'px';
      el['eq-canvas'].style.height = eqSize + 'px';
      el['eq-canvas']._dpr = dpr;
      el['eq-canvas']._w = eqSize;
      el['eq-canvas']._h = eqSize;
    }
  }

  function drawPulsingAura(beat, energy, dt, nowMs) {
    if (!el['eq-canvas']) return;
    var c = el['eq-canvas'], ctx = c.getContext('2d'), dpr = c._dpr || 1;
    var eqW = c._w || 136, eqH = c._h || 136;
    var cx = eqW / 2, cy = eqH / 2;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, eqW, eqH);

    var safeDt = (dt > 0 && dt < 0.1) ? dt : 0.016;
    if (!m.playing && pulseWaves.length === 0 && amp <= 0.02) {
      prevAudioBeat = 0; prevAudioEnergy = 0; avgAudioBeat = 0; lastPulseSpawnAt = 0;
      return;
    }

    var baseR = 37;
    var maxReach = Math.max(baseR + 12, cx - 2.5);
    var dBeat = beat - prevAudioBeat;
    var dEnergy = energy - prevAudioEnergy;
    avgAudioBeat += (beat - avgAudioBeat) * Math.min(1, safeDt * 4.5);

    if (m.playing && amp > 0.05) {
      var ts = nowMs || performance.now();
      var sinceLast = lastPulseSpawnAt ? (ts - lastPulseSpawnAt) : 999;
      var transientHit = dBeat > 0.035 || dEnergy > 0.025 || (beat - avgAudioBeat > 0.055 && beat > 0.18);
      var sustainPunch = sinceLast >= 240 && beat > 0.32 && energy > 0.35;

      if ((transientHit && sinceLast >= 145) || sustainPunch) {
        var strength = Math.min(1.0, Math.max(0.28,
          beat * 0.78 + Math.max(0, dBeat) * 2.4 + Math.max(0, dEnergy) * 1.8 + energy * 0.22
        ));
        pulseWaves.push({
          r: baseR + 1.5,
          maxR: Math.min(maxReach, baseR + 14 + strength * 17),
          speed: 42 + strength * 24,
          strength: strength,
          lineW: 1.6 + strength * 1.9
        });
        if (pulseWaves.length > 5) pulseWaves.shift();
        lastPulseSpawnAt = ts;
      }
    }

    prevAudioBeat = beat;
    prevAudioEnergy = energy;
    if (pulseWaves.length === 0) return;

    var remaining = [];
    for (var i = 0; i < pulseWaves.length; i++) {
      var w = pulseWaves[i];
      w.r += w.speed * safeDt;
      var span = Math.max(8, w.maxR - (baseR + 1.5));
      var progress = (w.r - (baseR + 1.5)) / span;
      if (progress >= 1) continue;
      remaining.push(w);

      var fade = Math.pow(1 - progress, 1.3);
      var alpha = (0.36 + 0.56 * w.strength) * fade * (m.playing ? Math.max(0.25, amp) : Math.max(0.15, amp));
      if (alpha <= 0.01) continue;

      ctx.beginPath();
      ctx.arc(cx, cy, w.r, 0, Math.PI * 2);
      ctx.strokeStyle = 'rgba(147, 197, 253, ' + (alpha * 0.28).toFixed(3) + ')';
      ctx.lineWidth = w.lineW * 2.8;
      ctx.shadowBlur = 0;
      ctx.stroke();

      ctx.beginPath();
      ctx.arc(cx, cy, w.r, 0, Math.PI * 2);
      ctx.strokeStyle = 'rgba(186, 230, 253, ' + Math.min(0.95, alpha).toFixed(3) + ')';
      ctx.lineWidth = w.lineW;
      ctx.shadowBlur = 8 + w.strength * 10;
      ctx.shadowColor = '#93c5fd';
      ctx.stroke();
    }
    pulseWaves = remaining;
    ctx.shadowBlur = 0;
  }

  function drawWave() {
    var c = el.canvas, ctx = c.getContext('2d'), dpr = c._dpr || 1, w = c._w, h = c._h, mid = h / 2;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    var pad = 10, x0 = pad, x1 = w - pad;
    var showProg = showsPlaybackProgress(m.source);
    var frac = 1.0;
    if (showProg) {
      if (m.dur > 0) frac = Math.max(0, Math.min(1, m.pos / m.dur));
      else if (m.pos > 0) frac = Math.max(0, Math.min(1, (m.pos % 240) / 240));
      else frac = 0;
    }
    var xp = x0 + (x1 - x0) * frac;
    ctx.lineCap = 'round';

    if (showProg) {
      ctx.beginPath();
      ctx.moveTo(xp, mid);
      ctx.lineTo(x1, mid);
      ctx.strokeStyle = 'rgba(255, 255, 255, 0.18)';
      ctx.lineWidth = 4;
      ctx.stroke();
    }

    var A = amp * 4.0, k = (Math.PI * 2) / 28;
    ctx.beginPath();
    for (var x = x0; x <= xp; x += 1.5) {
      var edge = showProg ? Math.min(1, (xp - x) / 16) : 1;
      var y = mid + Math.sin(x * k - phase) * A * edge;
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

    if (showProg) {
      ctx.shadowBlur = 12;
      ctx.shadowColor = '#bae6fd';
      ctx.fillStyle = '#f8fafc';
      ctx.beginPath();
      ctx.arc(xp, mid, 6.8, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.shadowBlur = 0;
  }

  function isAudioCardInactive() {
    return !!window.__cockpitPaused;
  }

  window.getShineWaveAudioSpectrum = function () {
    var beat = 0, energy = 0, bands = null;
    var b = bridge();
    if (b && typeof b.getAudioVisualizerData === 'function') {
      try {
        var rawData = b.getAudioVisualizerData();
        if (rawData && rawData.length > 5) {
          var parts = rawData.split(',');
          if (parts.length >= 2) {
            beat = parseFloat(parts[0]) || 0;
            energy = parseFloat(parts[1]) || 0;
            bands = [];
            for (var i = 2; i < parts.length; i++) bands.push(parseFloat(parts[i]) || 0);
          }
        }
      } catch (err) {}
    }
    if ((!bands || energy <= 0.02) && m.playing) {
      var beatPeak = Math.pow(0.5 + 0.5 * Math.sin(phase * 1.5), 3);
      beat = beatPeak * 0.85 * amp;
      energy = (0.28 + 0.62 * beatPeak) * amp;
      bands = [];
      for (var k = 0; k < 24; k++) {
        var v = (Math.sin(k * 0.65 + phase * 3.2) * 0.42 + Math.cos(k * 1.15 - phase * 2.1) * 0.34 + 0.52) * amp;
        bands.push(Math.max(0.10, Math.min(1.0, v)));
      }
    }
    return { beat: beat, energy: energy, bands: bands || [] };
  };

  var _cardFrameSkip = 0;
  function frame(t) {
    rafId = 0;
    if (isAudioCardInactive()) {
      lastFrame = 0;
      return;
    }
    // These three are declared with `let` in cockpit-core.js. `let` values are shared between
    // the page's scripts by name, but they are NOT copies on `window`, so `window.isUserInteracting`
    // was always undefined and this frame skip never ran. Read them by name instead; `typeof`
    // keeps it safe if cockpit-core.js has not loaded yet.
    var carActive = !!((typeof isUserInteracting !== 'undefined' && isUserInteracting) ||
                       (typeof isCanvasTouched !== 'undefined' && isCanvasTouched) ||
                       (typeof camTween !== 'undefined' && camTween));
    if (carActive && (++_cardFrameSkip & 1)) {
      rafId = requestAnimationFrame(frame);
      return;
    }
    var dt = lastFrame ? Math.min(0.05, (t - lastFrame) / 1000) : 0;
    lastFrame = t;
    var target = m.playing ? 1.0 : 0.0;
    amp += (target - amp) * Math.min(1, dt * 6.0);
    if (m.playing) phase += dt * 5.4;

    var spec = window.getShineWaveAudioSpectrum();
    drawWave();
    drawPulsingAura(spec.beat, spec.energy, dt, t);

    if (el.time) {
      var effDur = m.dur > 0 ? m.dur : 0;
      el.time.textContent = formatTime(m.pos) + (effDur > 0 ? ' / ' + formatTime(effDur) : '');
    }
    if (!isAudioCardInactive() && (m.playing || amp > 0.01)) {
      rafId = requestAnimationFrame(frame);
    } else {
      lastFrame = 0;
    }
  }

  function kick() {
    if (isAudioCardInactive()) return;
    if (!rafId && el.canvas) rafId = requestAnimationFrame(frame);
  }

  window.resumeShineWaveCardIfActive = function () {
    if (!isAudioCardInactive() && el.canvas) {
      lastFrame = 0;
      kick();
    }
  };

  function bindSeek() {
    var dragging = false;
    function posFromEvent(e) {
      var t = e.touches && e.touches[0] ? e.touches[0] : e;
      var r = el.wave.getBoundingClientRect();
      return Math.max(0, Math.min(1, (t.clientX - r.left - 10) / (r.width - 20)));
    }
    function move(e) {
      var effDur = m.dur > 0 ? m.dur : 225;
      if (!dragging) return;
      m.pos = posFromEvent(e) * effDur;
      kick();
      e.preventDefault();
    }
    function end() {
      if (!dragging) return;
      dragging = false;
      var b = bridge();
      if (b && typeof b.seekMediaTo === 'function') b.seekMediaTo(Math.round(m.pos));
    }
    el.wave.addEventListener('touchstart', function (e) { if (!supportsSeekAndModes(m.source)) return; dragging = true; move(e); }, { passive: false });
    el.wave.addEventListener('touchmove', move, { passive: false });
    el.wave.addEventListener('touchend', end);
    el.wave.addEventListener('mousedown', function (e) { if (!supportsSeekAndModes(m.source)) return; dragging = true; move(e); });
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', end);
  }

  function stripMediaExtension(str) {
    return window.stripMediaExtension ? window.stripMediaExtension(str) : (str || '');
  }

  function render() {
    if (!el.title) return;
    var ar = isAr(), s = srcByKey(m.source);
    var rawTitle = m.title && m.title !== 'Audio Player' && m.title !== 'ShineWave Audio'
      ? stripMediaExtension(m.title)
      : (ar ? 'مشغل الصوتيات' : ((typeof state !== 'undefined' && state.lang === 'ru') ? 'Аудио плеер' : 'ShineWave Audio'));
    el.title.textContent = (ar && typeof window.toLangDigits === 'function') ? window.toLangDigits(rawTitle) : rawTitle;
    el.title.setAttribute('dir', ar ? 'rtl' : 'auto');
    el.play.setAttribute('d', m.playing ? PAUSE : PLAY);
    el.disc.classList.toggle('playing', m.playing);

    el.chip.style.visibility = 'visible';
    el.chip.style.display = 'inline-flex';
    el['chip-ico'].innerHTML = s ? svg(s.icon) : '';
    el['chip-lbl'].textContent = s ? (ar ? s.ar : s.en) : (ar ? 'المصدر' : 'SOURCE');

    var adv = supportsSeekAndModes(m.source);
    var showProg = showsPlaybackProgress(m.source);
    if (el['mode-row']) {
      el['mode-row'].style.display = adv ? 'flex' : 'none';
    }
    if (el.shuffle) {
      el.shuffle.style.display = adv ? 'inline-flex' : 'none';
      el.shuffle.classList.toggle('shuffle-active', adv && !!m.shuffle);
      el.shuffle.title = ar ? (m.shuffle ? 'التشغيل العشوائي: مفعل' : 'تشغيل عشوائي') : 'Shuffle';
    }
    if (el.repeat) {
      el.repeat.style.display = adv ? 'inline-flex' : 'none';
      el.repeat.classList.remove('repeat-all', 'repeat-one');
      if (adv && m.repeatMode === 'ALL') el.repeat.classList.add('repeat-all');
      else if (adv && m.repeatMode === 'ONE') el.repeat.classList.add('repeat-one');
      el.repeat.title = ar
        ? (m.repeatMode === 'ONE' ? 'إعادة نفس المقطع' : (m.repeatMode === 'ALL' ? 'إعادة القائمة كاملة' : 'إلغاء الإعادة'))
        : ('Repeat: ' + m.repeatMode);
    }

    if (el.time) {
      el.time.style.display = showProg ? '' : 'none';
      var effDur = m.dur > 0 ? m.dur : 0;
      el.time.textContent = formatTime(m.pos) + (effDur > 0 ? ' / ' + formatTime(effDur) : '');
    }

    el.sources.querySelectorAll('.swcard-src').forEach(function (n) {
      var sc = srcByKey(n.getAttribute('data-key'));
      n.classList.toggle('active', n.getAttribute('data-key') === m.source);
      n.textContent = ar ? sc.ar : sc.en;
    });
    el.wave.style.visibility = 'visible';
    el.wave.style.cursor = adv ? 'pointer' : 'default';
    el.wave.style.pointerEvents = adv ? 'auto' : 'none';
    el.wave.title = adv ? (ar ? 'اسحب للتقديم أو التأخير' : 'Drag to Seek') : '';
    kick();
  }

  function setArt(url) {
    if (!el.art || !el.disc) return;
    if (url && typeof url === 'string' && url.length > 30) {
      if (el.art.getAttribute('src') !== url) el.art.src = url;
      el.disc.classList.add('has-art');
    } else {
      el.art.removeAttribute('src');
      el.disc.classList.remove('has-art');
    }
  }

  window.refreshShineWaveLanguage = function () {
    try {
      if (el.root) render();
      if (typeof window.refreshShineWaveStudioLanguage === 'function') {
        window.refreshShineWaveStudioLanguage();
      }
    } catch (e) {}
  };

  window.onShineWaveState = function (j) {
    try {
      if (!j || !j.available) return;
      if (j.source) m.source = j.source;
      if (j.title !== undefined) m.title = stripMediaExtension(j.title);
      if (j.artist !== undefined) m.artist = j.artist;
      if (j.album !== undefined) m.album = j.album;
      if (typeof j.duration === 'number') m.dur = Math.max(0, Math.floor(j.duration));
      if (typeof j.position === 'number') m.pos = Math.max(0, Math.floor(j.position));
      if (typeof j.playing === 'boolean') m.playing = j.playing;
      if (j.repeatMode) m.repeatMode = j.repeatMode;
      if (typeof j.shuffle === 'boolean') m.shuffle = j.shuffle;
      m.albumArt = j.albumArt || null;
      if (!el.root) return;
      setArt(m.albumArt);
      render();
      if (typeof window.syncShineWaveStudioState === 'function') {
        window.syncShineWaveStudioState(m, j);
      }
    } catch (e) { console.error('swcard state error', e); }
  };

  window.onSwcSeek = function (dir, deltaMs) {
    try {
      if (!supportsSeekAndModes(m.source)) return;
      var stepSec = Math.max(1, Math.round((deltaMs || 10000) / 1000));
      var effDur = m.dur > 0 ? m.dur : 225;
      if (dir === 'forward') {
        m.pos = Math.min(effDur, (m.pos || 0) + stepSec);
      } else {
        m.pos = Math.max(0, (m.pos || 0) - stepSec);
      }
      render();
      kick();
      var badgeText = (dir === 'forward' ? ('+' + stepSec + 's ▶▶') : ('◀◀ -' + stepSec + 's'));
      showCardToast(badgeText);
      if (typeof window.showShineWaveStudioToast === 'function') {
        var ov = document.getElementById('sw-studio-overlay');
        if (ov && ov.classList.contains('active')) {
          window.showShineWaveStudioToast(badgeText);
        }
      }
      if (typeof window.syncShineWaveStudioTime === 'function') {
        window.syncShineWaveStudioTime(m.pos, effDur);
      }
    } catch (e) {}
  };

  window.onSwcSeekEnd = function () {};

  window.onSwcMediaKey = function (cmd) {
    try {
      // Only optimistically toggle in browser preview; on the car, ShineWaveEngine emits authoritative onShineWaveState.
      if (window.AndroidBridge) return;
      if (cmd === 'play_pause') {
        m.playing = !m.playing;
        render();
      } else if (cmd === 'next' || cmd === 'prev') {
        m.pos = 0;
        render();
      }
    } catch (e) {}
  };

  setInterval(function () {
    if (m.playing && !isAudioCardInactive() && showsPlaybackProgress(m.source)) {
      if (m.dur > 0) {
        m.pos = (m.source === 'BLUETOOTH') ? Math.min(m.dur, m.pos + 0.5) : ((m.pos + 0.5) % m.dur);
      } else if (m.source === 'BLUETOOTH') {
        m.pos = m.pos + 0.5;
      } else {
        return;
      }
      kick();
      if (typeof window.syncShineWaveStudioTime === 'function') {
        window.syncShineWaveStudioTime(m.pos, m.dur);
      }
    }
  }, 500);

  window.addEventListener('resize', function () { if (el.canvas) { sizeCanvas(); kick(); } });
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', build);
  else build();
})();
