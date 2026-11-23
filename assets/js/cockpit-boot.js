/**
 * ============================================================================
 * COCKPIT BOOT — Parallel Non-Blocking 2-Phase 3D Pipeline Loader
 * ============================================================================
 * Waits for the Phase-1 HUD frame to paint first (<40ms), then fetches the raw
 * binary GLB car model in parallel with Three.js / OrbitControls / GLTFLoader
 * so the 3D vehicle mounts with minimal delay and zero Base64 heap overhead.
 */
(function () {
  'use strict';

  const bootStartMs = performance.now();

  function loadScript(src) {
    return new Promise(function (resolve, reject) {
      const s = document.createElement('script');
      s.src = src;
      s.async = false;
      s.onload = function () { resolve(src); };
      s.onerror = function (err) {
        console.error('[CockpitBoot] Failed to load:', src, err);
        reject(err);
      };
      document.body.appendChild(s);
    });
  }

  function prefetchCarGlbBinary() {
    if (window.__carGlbBufferPromise) return window.__carGlbBufferPromise;
    window.__carGlbBufferPromise = new Promise(function (resolve, reject) {
      var xhr = new XMLHttpRequest();
      xhr.open('GET', 'models/c65_car.glb', true);
      xhr.responseType = 'arraybuffer';
      xhr.onload = function () {
        if ((xhr.status === 200 || xhr.status === 0) && xhr.response && xhr.response.byteLength > 0) {
          resolve(xhr.response);
        } else {
          reject(new Error('GLB load failed: status=' + xhr.status));
        }
      };
      xhr.onerror = function (e) {
        reject(e || new Error('GLB XHR network error'));
      };
      xhr.send();
    });
    return window.__carGlbBufferPromise;
  }

  function startPhase23D() {
    // Begin streaming binary GLB from APK assets/ immediately in parallel with JS parsing
    prefetchCarGlbBinary();

    var pThree = (typeof window.THREE !== 'undefined')
      ? Promise.resolve()
      : loadScript('js/three.min.js');

    pThree
      .then(function () {
        return Promise.all([
          loadScript('js/OrbitControls.js'),
          loadScript('js/GLTFLoader.js')
        ]);
      })
      .then(function () {
        return loadScript('js/cockpit-3d.js');
      })
      .then(function () {
        if (typeof window.startCockpit3DEngine === 'function') {
          window.startCockpit3DEngine();
          window.__cockpitPhase2Ms = Math.round(performance.now() - bootStartMs);
          console.log('[CockpitBoot] Phase 2 3D Engine started in ' + window.__cockpitPhase2Ms + 'ms');
        }
      })
      .catch(function (err) {
        console.error('[CockpitBoot] Phase 2 3D pipeline aborted:', err);
      });
  }

  // Schedule immediately after the first browser paint frame of Phase 1 HUD
  if (typeof requestAnimationFrame === 'function') {
    requestAnimationFrame(function () {
      setTimeout(startPhase23D, 8);
    });
  } else {
    setTimeout(startPhase23D, 16);
  }
})();
