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
  function isAr() {
    try {
      if (typeof state !== 'undefined' && state && state.lang) return state.lang === 'ar';
      if (window.state && window.state.lang) return window.state.lang === 'ar';
      return (localStorage.getItem('shine_cockpit_lang') || '') === 'ar';
    } catch (e) { return false; }
  }
  function srcByKey(k) { for (var i = 0; i < SOURCES.length; i++) if (SOURCES[i].key === k) return SOURCES[i]; return SOURCES[1]; }
  function svg(path, cls) { return '<svg viewBox="0 0 24 24"' + (cls ? ' class="' + cls + '"' : '') + '><path d="' + path + '"/></svg>'; }

  function formatTime(sec) {
    if (!sec || isNaN(sec) || sec < 0) sec = 0;
    var mm = Math.floor(sec / 60);
    var ss = Math.floor(sec % 60);
    var res = (mm < 10 ? '0' + mm : mm) + ':' + (ss < 10 ? '0' + ss : ss);
    if (isAr() && typeof window.toLangDigits === 'function') return window.toLangDigits(res);
    return res;
  }

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
    m.shuffle = !m.shuffle;
    render();
    var b = bridge();
    if (b && typeof b.toggleShineWaveShuffle === 'function') b.toggleShineWaveShuffle();
    if (typeof window.syncShineWaveStudioState === 'function') window.syncShineWaveStudioState(m);
  }

  function cycleRepeat() {
    m.repeatMode = (m.repeatMode === 'OFF') ? 'ALL' : (m.repeatMode === 'ALL' ? 'ONE' : 'OFF');
    render();
    var b = bridge();
    if (b && typeof b.cycleShineWaveRepeat === 'function') b.cycleShineWaveRepeat();
    if (typeof window.syncShineWaveStudioState === 'function') window.syncShineWaveStudioState(m);
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

  function bindTrackBtnLongPress(btn, cmd) {
    var timer = null;
    var repeatTimer = null;
    var isLong = false;
    var deltaSec = (cmd === 'next') ? 10 : -10;

    function doSeek() {
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
    applySourceSelection(key);
    toggleSources(false);
  }

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
    var effDur = m.dur > 0 ? m.dur : 225;
    var frac = Math.max(0, Math.min(1, m.pos / effDur));
    var xp = x0 + (x1 - x0) * frac;
    ctx.lineCap = 'round';

    ctx.beginPath();
    ctx.moveTo(xp, mid);
    ctx.lineTo(x1, mid);
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.18)';
    ctx.lineWidth = 4;
    ctx.stroke();

    var A = amp * 4.0, k = (Math.PI * 2) / 28;
    ctx.beginPath();
    for (var x = x0; x <= xp; x += 1.5) {
      var edge = Math.min(1, (xp - x) / 16);
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

    ctx.shadowBlur = 12;
    ctx.shadowColor = '#bae6fd';
    ctx.fillStyle = '#f8fafc';
    ctx.beginPath();
    ctx.arc(xp, mid, 6.8, 0, Math.PI * 2);
    ctx.fill();
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

  function frame(t) {
    rafId = 0;
    if (isAudioCardInactive()) {
      lastFrame = 0;
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
    el.wave.addEventListener('touchstart', function (e) { dragging = true; move(e); }, { passive: false });
    el.wave.addEventListener('touchmove', move, { passive: false });
    el.wave.addEventListener('touchend', end);
    el.wave.addEventListener('mousedown', function (e) { dragging = true; move(e); });
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', end);
  }

  function stripMediaExtension(str) {
    if (!str || typeof str !== 'string') return str || '';
    return str.replace(/\.(mp3|m4a|flac|wav|aac|ogg|wma|ape|alac|opus|mp4)$/i, '').trim();
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

    if (el.shuffle) {
      el.shuffle.classList.toggle('shuffle-active', !!m.shuffle);
      el.shuffle.title = ar ? (m.shuffle ? 'التشغيل العشوائي: مفعل' : 'تشغيل عشوائي') : 'Shuffle';
    }
    if (el.repeat) {
      el.repeat.classList.remove('repeat-all', 'repeat-one');
      if (m.repeatMode === 'ALL') el.repeat.classList.add('repeat-all');
      else if (m.repeatMode === 'ONE') el.repeat.classList.add('repeat-one');
      el.repeat.title = ar
        ? (m.repeatMode === 'ONE' ? 'إعادة نفس المقطع' : (m.repeatMode === 'ALL' ? 'إعادة القائمة كاملة' : 'إلغاء الإعادة'))
        : ('Repeat: ' + m.repeatMode);
    }

    if (el.time) {
      var effDur = m.dur > 0 ? m.dur : 0;
      el.time.textContent = formatTime(m.pos) + (effDur > 0 ? ' / ' + formatTime(effDur) : '');
    }

    el.sources.querySelectorAll('.swcard-src').forEach(function (n) {
      var sc = srcByKey(n.getAttribute('data-key'));
      n.classList.toggle('active', n.getAttribute('data-key') === m.source);
      n.textContent = ar ? sc.ar : sc.en;
    });
    el.wave.style.visibility = 'visible';
    kick();
  }

  function setArt(url) {
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
      setArt(m.albumArt);
      render();
      if (typeof window.syncShineWaveStudioState === 'function') {
        window.syncShineWaveStudioState(m, j);
      }
    } catch (e) { console.error('swcard state error', e); }
  };

  window.onSwcMediaKey = function (cmd) {
    try {
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
    if (m.playing && !isAudioCardInactive() && m.dur > 0) {
      m.pos = (m.pos + 0.5) % m.dur;
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
