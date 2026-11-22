/**
 * ============================================================================
 * COCKPIT BOOT — Asynchronous Non-Blocking 2-Phase 3D Pipeline Loader
 * ============================================================================
 * Waits for the Phase-1 HUD frame to paint first (~30-80ms), then sequentially
 * injects Three.js libraries, the embedded 3D model bundle, and the 3D engine
 * without stalling HUD interactivity.
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

  function startPhase23D() {
    loadScript('js/three.min.js')
      .then(function () { return loadScript('js/OrbitControls.js'); })
      .then(function () { return loadScript('js/GLTFLoader.js'); })
      .then(function () { return loadScript('js/car-model-data.js'); })
      .then(function () { return loadScript('js/cockpit-3d.js'); })
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
      setTimeout(startPhase23D, 16);
    });
  } else {
    setTimeout(startPhase23D, 32);
  }
})();
