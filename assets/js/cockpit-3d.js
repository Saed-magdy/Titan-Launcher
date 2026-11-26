/**
 * ============================================================================
 * COCKPIT 3D ENGINE — Deferred WebGL Scene, Car Model & 35-FPS Loop (Phase 2)
 * ============================================================================
 * Loaded asynchronously after Phase-1 HUD first paint via cockpit-boot.js.
 */

// Lazy-initialize Three.js value objects now that three.min.js is loaded
clock = new THREE.Clock();
HERO_CAM_POS = new THREE.Vector3(4500, 2100, 5200);
CAM_TARGET = new THREE.Vector3(0, 16, 0);
var seatPaintCanvas = null;
var seatPaintCtx = null;
var seatWorkCanvas = null;
var seatWorkCtx = null;
var seatPaintTexture = null;
var seatImages = { sport: null };
var seatMaterials = [];
var _paintQueued = false;

    function getCurrentCamAzimuth() {
      if (!camera) return HERO_CAM_ANGLE;
      return Math.atan2(camera.position.x, camera.position.z);
    }

    function shortestAngleDiff(targetAngle, currentAngle) {
      let deltaAngle = (targetAngle - currentAngle) % (Math.PI * 2);
      if (deltaAngle > Math.PI) deltaAngle -= Math.PI * 2;
      if (deltaAngle < -Math.PI) deltaAngle += Math.PI * 2;
      return deltaAngle;
    }

    function clearAutoReturnTimer() {
      autoReturnDeadlineMs = 0;
      if (autoReturnTimer) {
        clearTimeout(autoReturnTimer);
        autoReturnTimer = null;
      }
    }

    function resetAutoReturnTimer(delayMs) {
      const waitMs = (delayMs !== undefined) ? delayMs : 10000; // Always 10 seconds per user request
      if (autoReturnTimer) clearTimeout(autoReturnTimer);
      autoReturnDeadlineMs = Date.now() + waitMs;
      autoReturnTimer = setTimeout(() => {
        autoReturnDeadlineMs = 0;
        // Clear stale interaction locks (>10s) in case WebView dropped an up/cancel event
        if ((isUserInteracting || isCanvasTouched) && touchStartedAtMs > 0 && (Date.now() - touchStartedAtMs) >= 10000) {
          isUserInteracting = false;
          isCanvasTouched = false;
          touchStartedAtMs = 0;
        }
        if (!isUserInteracting && !isCanvasTouched) {
          smoothReturnToHeroAngle();
        } else {
          // User is still actively dragging at the 10s mark; check again once released
          resetAutoReturnTimer(1000);
        }
      }, waitMs);
    }

    function cancelCameraTween() {
      camTween = null;
      if (controls) controls.enableDamping = true;
    }

    function smoothReturnToHeroAngle() {
      if (isUserInteracting || isCanvasTouched) return;
      tweenCameraToAngle(HERO_CAM_ANGLE, 1600);
    }

    function tweenCameraToAngle(targetAngle, durationMs) {
      if (!camera || !controls) return;
      const startAngle = getCurrentCamAzimuth();
      const deltaAngle = shortestAngleDiff(targetAngle, startAngle);
      if (Math.abs(deltaAngle) < 0.002 && !camTween) {
        camera.position.x = CAM_ORBIT_RADIUS * Math.sin(targetAngle);
        camera.position.z = CAM_ORBIT_RADIUS * Math.cos(targetAngle);
        camera.position.y = CAM_ORBIT_Y;
        controls.target.copy(CAM_TARGET);
        camera.lookAt(CAM_TARGET);
        return;
      }

      controls.enableDamping = false; // Zero residual orbit inertia before starting tween
      controls.update();

      camTween = {
        startAngle: startAngle,
        deltaAngle: deltaAngle,
        targetAngle: targetAngle,
        startTime: 0,
        duration: durationMs || 1500
      };
      idleRenderCountdown = 90;
      requestRender();
    }

    function snapCameraToDoor(doorKey) {
      let targetAngle = null;
      if (doorKey === 'fl' || doorKey === 'rl' || doorKey === 'left') {
        targetAngle = 1.25;  // Look at Left (driver side) doors
      } else if (doorKey === 'fr' || doorKey === 'rr' || doorKey === 'right') {
        targetAngle = -1.25; // Look at Right (passenger side) doors
      } else if (doorKey === 'trunk') {
        targetAngle = 2.75;  // Look at Rear / Trunk
      } else if (doorKey === 'hero') {
        targetAngle = HERO_CAM_ANGLE;
      }
      if (targetAngle !== null) {
        if (!isUserInteracting && !isCanvasTouched) {
          tweenCameraToAngle(targetAngle, 1400);
        }
        resetAutoReturnTimer(10000); // Always return to hero 10 seconds after last door/trunk change
      }
    }
    window.snapCameraToDoor = snapCameraToDoor;

    function init3DScene() {
      autoFitViewport();
      const container = document.getElementById('canvas-container');
      const w = container.clientWidth || 864; const h = container.clientHeight || 460;

      scene = new THREE.Scene();
      scene.background = null; // Transparent background so petroleum gradient & ground glow show through
      scene.fog = null;

      // Full-width canvas with centered car with comfortable margins
      camera = new THREE.PerspectiveCamera(23.5, w / h, 10, 50000);
      camera.setViewOffset(w, h, 0, 52, w, h);
      camera.position.set(4500, 2100, 5200);

      try {
        renderer = new THREE.WebGLRenderer({ antialias: false, alpha: true, powerPreference: 'high-performance' });
      } catch (e1) {
        console.warn('WebGL init attempt 1 failed, trying fallback', e1);
        try {
          renderer = new THREE.WebGLRenderer({ antialias: false, alpha: true });
        } catch (e2) {
          console.error('Fatal WebGL initialization failure:', e2);
        }
      }

      if (!renderer) {
        return false;
      }
      if (renderer) {
        renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 0.92));
        renderer.setSize(w, h);
        renderer.toneMapping = THREE.NoToneMapping;
        renderer.shadowMap.enabled = false;
        renderer.domElement.style.willChange = 'transform';
        renderer.domElement.style.transform = 'translateZ(0)';
        container.appendChild(renderer.domElement);

        renderer.domElement.addEventListener('webglcontextlost', function(e) {
          e.preventDefault();
          console.warn('WebGL context lost, waiting for restore...');
        }, false);
        renderer.domElement.addEventListener('webglcontextrestored', function(e) {
          console.log('WebGL context restored');
          requestRender();
        }, false);
      }

      // OrbitControls: Full 360-degree smooth swipe turntable
      controls = new THREE.OrbitControls(camera, renderer.domElement);
      controls.enableDamping = true;
      controls.dampingFactor = 0.28;
      controls.rotateSpeed = 1.55;
      controls.enableZoom = false;  // LOCKED ZOOM
      controls.enablePan = false;   // LOCKED PAN
      controls.target.set(0, 16, 0);

      const fixedPolar = Math.atan2(Math.sqrt(5600 * 5600 + 6150 * 6150), 2550 - 16);
      controls.minPolarAngle = fixedPolar;
      controls.maxPolarAngle = fixedPolar;

      const onCanvasTouchStart = () => {
        isCanvasTouched = true;
        touchStartedAtMs = Date.now();
        cancelCameraTween();
        clearAutoReturnTimer();
        requestRender();
      };
      const onCanvasTouchMove = () => {
        if (isCanvasTouched || isUserInteracting) {
          touchStartedAtMs = Date.now();
          requestRender();
        }
      };
      const onCanvasTouchEnd = () => {
        if (!isCanvasTouched && !isUserInteracting) return;
        isCanvasTouched = false;
        isUserInteracting = false;
        touchStartedAtMs = 0;
        idleRenderCountdown = 60;
        requestRender();
        resetAutoReturnTimer(10000); // Always return to hero 10 seconds after last touch on 3D car
      };

      renderer.domElement.addEventListener('pointerdown', onCanvasTouchStart, { passive: true });
      renderer.domElement.addEventListener('pointermove', onCanvasTouchMove, { passive: true });
      window.addEventListener('pointerup', onCanvasTouchEnd, { passive: true });
      window.addEventListener('pointercancel', onCanvasTouchEnd, { passive: true });
      window.addEventListener('blur', onCanvasTouchEnd, { passive: true });

      controls.addEventListener('start', () => {
        isUserInteracting = true;
        isCanvasTouched = true;
        touchStartedAtMs = Date.now();
        cancelCameraTween();
        clearAutoReturnTimer();
        requestRender();
      });
      controls.addEventListener('end', () => {
        isUserInteracting = false;
        isCanvasTouched = false;
        touchStartedAtMs = 0;
        idleRenderCountdown = 36;
        requestRender();
        resetAutoReturnTimer(10000); // Auto-return to hero angle after 10 seconds of inactivity
      });
      controls.addEventListener('change', () => {
        requestRender();
      });

      // High-efficiency Adreno 506 Studio Lighting (2 sculpted directional lights + hemisphere + ambient)
      const ambLight = new THREE.AmbientLight(0xffffff, 0.58);
      scene.add(ambLight);

      const hemiLight = new THREE.HemisphereLight(0xffffff, 0xbae6fd, 0.95);
      hemiLight.position.set(0, 4000, 0);
      scene.add(hemiLight);

      const dirLightFront = new THREE.DirectionalLight(0xffffff, 1.25);
      dirLightFront.position.set(4200, 1550, 4200);
      scene.add(dirLightFront);

      const dirLightRear = new THREE.DirectionalLight(0xe0f2fe, 0.95);
      dirLightRear.position.set(-4200, 1450, -3800);
      scene.add(dirLightRear);

      // Initialize System Clock
      return true;
    }

    function loadSingleLightTexture(key) {
      if (lightTextures[key]) return;
      const img = new Image();
      img.onload = function() {
        const tex = new THREE.Texture(img);
        tex.flipY = false;
        tex.generateMipmaps = false;
        tex.minFilter = THREE.LinearFilter;
        tex.magFilter = THREE.LinearFilter;
        tex.needsUpdate = true;
        lightTextures[key] = tex;
        if (key === 'C65_zuodeng.png') {
          if (leftLightMaterials.length) applyLightState(leftLightMaterials, tex, false);
          applyTrunkState();
        } else if (key === 'C65_zuodeng_jinguang.png') {
          applyTrunkState();
        } else if (key === 'C65_youdeng.png' && rightLightMaterials.length) {
          applyLightState(rightLightMaterials, tex, false);
        }
        if (carModel && (state.lights.dipped || state.lights.main || state.lights.position || state.lights.fog || state.flashers.left || state.flashers.right || state.flashers.hazard)) {
          updateLights();
        }
        requestRender();
      };
      img.src = 'textures/lights/' + key;
    }

    function preloadHeadlightTextures() {
      // Base resting textures (C65_zuodeng.png / C65_youdeng.png) are extracted
      // directly from the embedded GLB in setupLoadedModel(). Only lazy-load
      // active/flasher state textures after first car paint & render.
      const deferredKeys = [
        'C65_zuodeng_jinguang.png', 'C65_zuodeng_yuanguang.png',
        'C65_zuodeng_01.png', 'C65_zuodeng_jinguang_01.png', 'C65_zuodeng_yuanguang_01.png',
        'C65_youdeng_jinguang.png', 'C65_youdeng_yuanguang.png',
        'C65_youdeng_01.png', 'C65_youdeng_jinguang_01.png', 'C65_youdeng_yuanguang_01.png'
      ];
      setTimeout(function() {
        for (let i = 0; i < deferredKeys.length; i++) {
          loadSingleLightTexture(deferredKeys[i]);
        }
      }, 180);
    }

    function initPlateTexture() {
      const canvas = document.createElement('canvas');
      canvas.width = 1024; canvas.height = 1024;
      plateTexture = new THREE.CanvasTexture(canvas);
      plateTexture.flipY = false;
      plateTexture.generateMipmaps = false;
      plateTexture.minFilter = THREE.LinearFilter;
      plateTexture.magFilter = THREE.LinearFilter;
      if (renderer && renderer.capabilities && typeof renderer.capabilities.getMaxAnisotropy === 'function') {
        plateTexture.anisotropy = renderer.capabilities.getMaxAnisotropy();
      }
      updateLicensePlateCanvas(state.plateText);
    }

    function updateLicensePlateCanvas(text) {
      if (!plateTexture) return;
      const canvas = plateTexture.image;
      const ctx = canvas.getContext('2d');
      ctx.clearRect(0, 0, 1024, 1024);
      const px = 96, py = 640, pw = 832, ph = 260;
      const headerH = 74;
      // Outer dark frame
      ctx.fillStyle = '#05080f';
      ctx.fillRect(px - 16, py - 16, pw + 32, ph + 32);
      // Anti-glare soft matte off-white plate body (prevents blooming/glare)
      ctx.fillStyle = '#d0d8e2';
      ctx.fillRect(px, py, pw, ph);
      // Top Egyptian plate pastel sky blue header strip
      ctx.fillStyle = '#7dd3fc';
      ctx.fillRect(px, py, pw, headerH);
      // Crisp dark divider line under blue strip & outer black border
      ctx.fillStyle = '#090d16';
      ctx.fillRect(px, py + headerH, pw, 8);
      ctx.strokeStyle = '#090d16';
      ctx.lineWidth = 12;
      ctx.strokeRect(px + 4, py + 4, pw - 8, ph - 8);
      // Pitch-black bold plate characters centered in the lower white area
      const textCenterY = py + headerH + (ph - headerH) / 2 + 6;
      ctx.font = '900 96px "Plus Jakarta Sans", "Noto Sans Arabic", sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.lineJoin = 'round';
      ctx.lineWidth = 4;
      ctx.strokeStyle = '#000000';
      ctx.fillStyle = '#000000';
      ctx.strokeText(text, px + pw / 2, textCenterY);
      ctx.fillText(text, px + pw / 2, textCenterY);
      plateTexture.needsUpdate = true;
      idleRenderCountdown = 120;
      requestRender();
    }

    function initDynamicPaintEngine() {
      carPaintCanvas = document.createElement('canvas');
      carPaintCanvas.width = 512;
      carPaintCanvas.height = 512;
      carPaintCtx = carPaintCanvas.getContext('2d');

      carPaintTexture = new THREE.CanvasTexture(carPaintCanvas);
      carPaintTexture.flipY = false;
      carPaintTexture.generateMipmaps = false;
      carPaintTexture.minFilter = THREE.LinearFilter;
      carPaintTexture.magFilter = THREE.LinearFilter;

      seatWorkCanvas = document.createElement('canvas');
      seatWorkCanvas.width = 512;
      seatWorkCanvas.height = 512;
      seatWorkCtx = seatWorkCanvas.getContext('2d');

      seatPaintCanvas = document.createElement('canvas');
      seatPaintCanvas.width = 2048;
      seatPaintCanvas.height = 2048;
      seatPaintCtx = seatPaintCanvas.getContext('2d');
      seatPaintTexture = new THREE.CanvasTexture(seatPaintCanvas);
      seatPaintTexture.flipY = false;
      seatPaintTexture.generateMipmaps = false;
      seatPaintTexture.minFilter = THREE.LinearFilter;
      seatPaintTexture.magFilter = THREE.LinearFilter;
      if (renderer && renderer.capabilities && typeof renderer.capabilities.getMaxAnisotropy === 'function') {
        seatPaintTexture.anisotropy = renderer.capabilities.getMaxAnisotropy();
      }
    }

    function isTexImgReady(img) {
      return !!(img && (img.complete === true || (img.complete === undefined && img.width > 0 && img.height > 0)));
    }

    function updateSeatUpholstery() {
      if (!seatPaintCtx || !seatPaintTexture || !seatWorkCtx) return;
      const sOpt = state.seatOption || 'sport';
      const baseImg = seatImages.sport || seatImages[sOpt];
      if (!isTexImgReady(baseImg)) return;

      const W = seatWorkCanvas.width;   // 512
      const HW = seatPaintCanvas.width; // 2048
      const scaleF = HW / W;            // 4
      const totalPx = W * W;

      seatWorkCtx.fillStyle = '#060607';
      seatWorkCtx.fillRect(0, 0, W, W);
      seatWorkCtx.imageSmoothingEnabled = true;
      seatWorkCtx.drawImage(baseImg, 0, 0, W, W);

      const imgData = seatWorkCtx.getImageData(0, 0, W, W);
      const d = imgData.data;

      if (sOpt === 'black') {
        // 1. Extract smooth floating-point stitch trajectories from raw 512x512 pixels BEFORE darkening
        const traceSmoothBand = function (bx0, bx1, by0, by1, mode) {
          const pts = [];
          const rawY = new Float32Array(W);
          for (let x = 0; x < W; x++) rawY[x] = -1;

          for (let x = bx0; x <= bx1; x++) {
            let chosenY = -1;
            if (mode === 'seam') {
              let orangeMinY = -1, orangeMaxY = -1;
              let greyMinY = -1, greyMaxY = -1;
              for (let y = by0; y <= by1; y++) {
                const i = (y * W + x) * 4;
                const r = d[i], g = d[i + 1], b = d[i + 2];
                if (r >= 110 && r > g * 1.55 && r > b * 1.8 && b < 95) {
                  if (orangeMinY < 0) orangeMinY = y;
                  orangeMaxY = y;
                } else if (r >= 95 && g >= 95 && b >= 95 && Math.abs(r - g) < 32 && Math.abs(g - b) < 32) {
                  if (greyMinY < 0) greyMinY = y;
                  greyMaxY = y;
                }
              }
              if (orangeMinY >= 0 && greyMinY >= 0) {
                chosenY = (orangeMaxY <= greyMinY + 2) ? (orangeMaxY + greyMinY) * 0.5
                  : ((greyMaxY <= orangeMinY + 2) ? (greyMaxY + orangeMinY) * 0.5 : (orangeMinY + orangeMaxY) * 0.5);
              }
            } else {
              let bestChroma = -1;
              for (let y = by0; y <= by1; y++) {
                const i = (y * W + x) * 4;
                const r = d[i], g = d[i + 1], b = d[i + 2];
                if (r >= 115 && r > g * 1.65 && r > b * 2.0 && b < 95) {
                  const chroma = (r - g) + (r - b) * 0.5;
                  if (chroma > bestChroma) {
                    bestChroma = chroma;
                    chosenY = y;
                  }
                }
              }
            }
            rawY[x] = chosenY;
          }

          // 11-point moving average for silky-smooth hairline trajectory
          for (let x = bx0; x <= bx1; x++) {
            if (rawY[x] < 0) continue;
            let sum = 0, cnt = 0;
            for (let dx = -5; dx <= 5; dx++) {
              const nx = x + dx;
              if (nx >= bx0 && nx <= bx1 && rawY[nx] >= 0) {
                sum += rawY[nx];
                cnt++;
              }
            }
            if (cnt > 0) {
              pts.push([x * scaleF, (sum / cnt) * scaleF]);
            }
          }
          return pts;
        };

        const stitchPaths = [
          // 4 piping welt strips (trimmed slightly from pinched UV tips)
          traceSmoothBand(312, 436, 48, 66, 'peak'),
          traceSmoothBand(400, 496, 72, 92, 'peak'),
          traceSmoothBand(374, 482, 322, 350, 'peak'),
          traceSmoothBand(344, 458, 413, 441, 'peak'),
          // 2 front-seat shoulder bolster interior seams
          traceSmoothBand(254, 330, 160, 194, 'seam'),
          traceSmoothBand(328, 406, 240, 270, 'seam')
        ];

        // 2. Convert every pixel first to unified true deep black leather shading
        for (let p = 0; p < totalPx; p++) {
          const i = p * 4;
          const r = d[i], g = d[i + 1], b = d[i + 2];
          const lumByte = (r * 77 + g * 150 + b * 29) >> 8;
          let shade;
          if (lumByte <= 45) {
            shade = Math.max(0.0, Math.min(1.0, (lumByte - 10) / 35.0));
          } else if (lumByte >= 125) {
            shade = Math.max(0.0, Math.min(1.0, (lumByte - 135) / 85.0));
          } else {
            shade = 0.28;
          }
          const v = Math.round(5 + shade * 6);
          d[i]     = v;
          d[i + 1] = v;
          d[i + 2] = v + 1;
          d[i + 3] = 255;
        }
        seatWorkCtx.putImageData(imgData, 0, 0);

        seatPaintCtx.imageSmoothingEnabled = true;
        seatPaintCtx.drawImage(seatWorkCanvas, 0, 0, HW, HW);

        // 3. Draw ultra-fine 0.9px orange hairline threads onto 2048x2048 texture
        seatPaintCtx.save();
        seatPaintCtx.strokeStyle = 'rgba(182, 56, 12, 0.85)';
        seatPaintCtx.lineWidth = 0.9;
        seatPaintCtx.lineCap = 'round';
        seatPaintCtx.lineJoin = 'round';
        for (let pIdx = 0; pIdx < stitchPaths.length; pIdx++) {
          const pts = stitchPaths[pIdx];
          if (pts.length < 2) continue;
          seatPaintCtx.beginPath();
          seatPaintCtx.moveTo(pts[0][0], pts[0][1]);
          for (let k = 1; k < pts.length; k++) {
            if (Math.abs(pts[k][0] - pts[k - 1][0]) > 10 * scaleF || Math.abs(pts[k][1] - pts[k - 1][1]) > 10 * scaleF) {
              seatPaintCtx.moveTo(pts[k][0], pts[k][1]);
            } else {
              seatPaintCtx.lineTo(pts[k][0], pts[k][1]);
            }
          }
          seatPaintCtx.stroke();
        }
        seatPaintCtx.restore();
      } else {
        for (let p = 0; p < totalPx; p++) {
          const i = p * 4;
          const r = d[i], g = d[i + 1], b = d[i + 2];
          const isOrange = (r > 38 && r > g * 1.22 && r > b * 1.45 && b < 115);
          const isLightGrey = (!isOrange && r > 105 && g > 105 && b > 105 && Math.abs(r - g) < 30 && Math.abs(g - b) < 30);

          if (sOpt === 'yellow') {
            const lumByte = (r * 77 + g * 150 + b * 29) >> 8;
            let shade;
            if (lumByte <= 45) {
              shade = Math.max(0.0, Math.min(1.0, (lumByte - 10) / 35.0));
            } else if (lumByte >= 125) {
              shade = Math.max(0.0, Math.min(1.0, (lumByte - 135) / 85.0));
            } else {
              shade = 0.28;
            }
            if (isOrange) {
              const maxC = r > g ? (r > b ? r : b) : (g > b ? g : b);
              const yScale = 72 + ((maxC * 184) >> 8);
              d[i]     = Math.min(255, (230 * yScale) >> 8);
              d[i + 1] = Math.min(255, (192 * yScale) >> 8);
              d[i + 2] = (6 * maxC) >> 8;
            } else {
              // Dark charcoal grey (not black, calibrated for ~2.5x scene lighting) for Sport Yellow leather bolsters & panels
              const gBase = 24 + shade * 12;
              d[i]     = Math.round(gBase);
              d[i + 1] = Math.round(gBase * 1.02);
              d[i + 2] = Math.round(gBase * 1.06);
            }
          } else {
            // 'sport' (رياضي ملون): keep orange & light grey, darken black leather 4 shades
            if (!isOrange && !isLightGrey) {
              d[i]     = (r * 36) >> 8;
              d[i + 1] = (g * 36) >> 8;
              d[i + 2] = (b * 40) >> 8;
            }
          }
          d[i + 3] = 255;
        }
        seatWorkCtx.putImageData(imgData, 0, 0);
        seatPaintCtx.imageSmoothingEnabled = true;
        seatPaintCtx.drawImage(seatWorkCanvas, 0, 0, HW, HW);
      }

      seatPaintTexture.needsUpdate = true;

      const softCabinEmissive = (sOpt === 'black') ? 0x050608 : 0x050709;
      seatMaterials.forEach(function(mat) {
        mat.map = seatPaintTexture;
        mat.color.set('#ffffff');
        if (mat.emissive) mat.emissive.setHex(softCabinEmissive);
        mat.needsUpdate = true;
      });
      if (nodes['C65_zuoyi'] && nodes['C65_zuoyi'].material) {
        nodes['C65_zuoyi'].material.map = seatPaintTexture;
        nodes['C65_zuoyi'].material.color.set('#ffffff');
        if (nodes['C65_zuoyi'].material.emissive) nodes['C65_zuoyi'].material.emissive.setHex(softCabinEmissive);
        nodes['C65_zuoyi'].material.needsUpdate = true;
      }
      if (nodes['C65_zhongkong'] && nodes['C65_zhongkong'].material && nodes['C65_zhongkong'].material.emissive) {
        nodes['C65_zhongkong'].material.emissive.setHex(0x020203);
        nodes['C65_zhongkong'].material.needsUpdate = true;
      }
      idleRenderCountdown = 120;
      requestRender();
      if (window.isCar3DFrozen && typeof window.render3DCarOnce === 'function') {
        window.render3DCarOnce();
      }
    }
    window.updateSeatUpholstery = updateSeatUpholstery;

    function hexToRgb(hex) {
      hex = hex.replace('#', '');
      if (hex.length === 3) hex = hex[0]+hex[0]+hex[1]+hex[1]+hex[2]+hex[2];
      const num = parseInt(hex, 16);
      return { r: (num >> 16) & 255, g: (num >> 8) & 255, b: num & 255 };
    }

    function updateCarPaint() {
      if (!isTexImgReady(rawCheqiImg) || !carPaintCtx) return;

      carPaintCtx.clearRect(0, 0, 512, 512);
      carPaintCtx.drawImage(rawCheqiImg, 0, 0);

      const imgData = carPaintCtx.getImageData(0, 0, 512, 512);
      const data = imgData.data;
      let bodyRgb = { r: 96, g: 106, b: 120 };
      const colLower = (state.carColor || '#ffffff').toLowerCase();
      const isPureGlossyBlack = (colLower === '#0a0a0a' || colLower === '#050505' || colLower === '#000000');
      const isDarkerGrey = (colLower === '#4b5563' || colLower === '#343a42' || colLower === '#181c22');
      const isDarkGrey = (colLower === '#9ca3af' || colLower === '#5f6b7c' || colLower === '#6c7684');
      const isPearlWhite = (colLower === '#ffffff' || colLower === '#e2e8f0');

      if (isPureGlossyBlack) {
        bodyRgb = { r: 6, g: 7, b: 8 };
      } else if (isDarkerGrey) {
        bodyRgb = { r: 22, g: 25, b: 30 };
      } else if (isDarkGrey) {
        bodyRgb = { r: 55, g: 61, b: 70 };
      } else if (isPearlWhite) {
        bodyRgb = { r: 176, g: 173, b: 166 };
      }
      const roofOpt = state.roofOption || 'black';
      const lipOpt = state.lipOption || 'orange';

      // Precompute 256-entry RGB Lookup Tables (eliminates 262k Math.pow calls per repaint)
      const bodyLutR = new Uint8Array(256), bodyLutG = new Uint8Array(256), bodyLutB = new Uint8Array(256);
      const darkLutR = new Uint8Array(256), darkLutG = new Uint8Array(256), darkLutB = new Uint8Array(256);
      const lipLutR  = new Uint8Array(256), lipLutG  = new Uint8Array(256), lipLutB  = new Uint8Array(256);
      for (let l = 0; l < 256; l++) {
        const lum = l / 255.0;
        const cLum = 0.98 * Math.pow(Math.min(lum, 0.96), 1.06);
        const effLum = isPearlWhite ? (0.24 + 0.76 * cLum) : cLum;
        bodyLutR[l] = Math.min(255, Math.round(bodyRgb.r * effLum));
        bodyLutG[l] = Math.min(255, Math.round(bodyRgb.g * effLum));
        bodyLutB[l] = Math.min(255, Math.round(bodyRgb.b * effLum));
        darkLutR[l] = Math.round(9 * lum);
        darkLutG[l] = Math.round(10 * lum);
        darkLutB[l] = Math.round(12 * lum);
        if (lipOpt === 'orange') {
          lipLutR[l] = Math.min(255, Math.round(145 * (0.24 + 0.76 * lum)));
          lipLutG[l] = Math.min(255, Math.round(24 * (0.24 + 0.76 * lum)));
          lipLutB[l] = Math.min(255, Math.round(1 * lum));
        } else if (lipOpt === 'yellow') {
          lipLutR[l] = Math.min(255, Math.round(170 * (0.28 + 0.72 * lum)));
          lipLutG[l] = Math.min(255, Math.round(142 * (0.28 + 0.72 * lum)));
          lipLutB[l] = 0;
        } else {
          lipLutR[l] = bodyLutR[l];
          lipLutG[l] = bodyLutG[l];
          lipLutB[l] = bodyLutB[l];
        }
      }
      const useDarkRoof = (roofOpt === 'black');

      for (let y = 0; y < 512; y++) {
        const rowOffset = y * 512 * 4;
        const inRoofBand = (y >= 260 && y <= 383);
        const inAntennaY = (y >= 304 && y <= 339);
        const inLipBand = (y >= 262 && y <= 291);
        for (let x = 0; x < 512; x++) {
          const idx = rowOffset + x * 4;
          if (data[idx + 3] < 15) continue;

          const l = (data[idx] * 77 + data[idx + 1] * 150 + data[idx + 2] * 29) >> 8;

          if (inAntennaY && x >= 235 && x <= 286) {
            data[idx] = darkLutR[l];
            data[idx + 1] = darkLutG[l];
            data[idx + 2] = darkLutB[l];
          } else if (inRoofBand && x <= 185) {
            if (useDarkRoof) {
              data[idx] = darkLutR[l];
              data[idx + 1] = darkLutG[l];
              data[idx + 2] = darkLutB[l];
            } else {
              data[idx] = bodyLutR[l];
              data[idx + 1] = bodyLutG[l];
              data[idx + 2] = bodyLutB[l];
            }
          } else if (inLipBand && x >= 194 && x <= 380) {
            data[idx] = lipLutR[l];
            data[idx + 1] = lipLutG[l];
            data[idx + 2] = lipLutB[l];
          } else {
            data[idx] = bodyLutR[l];
            data[idx + 1] = bodyLutG[l];
            data[idx + 2] = bodyLutB[l];
          }
        }
      }

      carPaintCtx.putImageData(imgData, 0, 0);
      carPaintTexture.needsUpdate = true;

      if (nodes['C65_cheqi'] && nodes['C65_cheqi'].material) {
        nodes['C65_cheqi'].material.map = carPaintTexture;
        nodes['C65_cheqi'].material.color.set('#ffffff');
        if (nodes['C65_cheqi'].material.reflectivity !== undefined) {
          nodes['C65_cheqi'].material.reflectivity = isPureGlossyBlack ? 0.24 : (isPearlWhite ? 0.21 : (isDarkerGrey ? 0.19 : 0.16));
        }
        if (nodes['C65_cheqi'].material.userData && nodes['C65_cheqi'].material.userData.pearlUniform) {
          nodes['C65_cheqi'].material.userData.pearlUniform.value = isPearlWhite ? 1.0 : 0.0;
        }
        nodes['C65_cheqi'].material.needsUpdate = true;
      }
      if (nodes['C65_chejia'] && nodes['C65_chejia'].material) {
        let frameHex = isPearlWhite ? '#cfd5de' : '#6c7684';
        if (isPureGlossyBlack) frameHex = '#080808';
        else if (isDarkerGrey) frameHex = '#16191e';
        else if (isDarkGrey) frameHex = '#373d46';
        nodes['C65_chejia'].material.color.set(frameHex);
        if (nodes['C65_chejia'].material.reflectivity !== undefined) {
          nodes['C65_chejia'].material.reflectivity = isPureGlossyBlack ? 0.22 : (isPearlWhite ? 0.20 : (isDarkerGrey ? 0.16 : 0.12));
        }
        if (nodes['C65_chejia'].material.userData && nodes['C65_chejia'].material.userData.pearlUniform) {
          nodes['C65_chejia'].material.userData.pearlUniform.value = isPearlWhite ? 1.0 : 0.0;
        }
        nodes['C65_chejia'].material.needsUpdate = true;
      }

      // Dynamic Seat Upholstery Texture
      updateSeatUpholstery();

      idleRenderCountdown = 120;
      requestRender();
      if (window.isCar3DFrozen && typeof window.render3DCarOnce === 'function') {
        window.render3DCarOnce();
      }
    }

    window.updateCarPaint = updateCarPaint;

    function loadCarModel() {
      const fetchGlbFallback = function() {
        return new Promise(function(resolve, reject) {
          const xhr = new XMLHttpRequest();
          xhr.open('GET', 'models/c65_car.glb', true);
          xhr.responseType = 'arraybuffer';
          xhr.onload = function() {
            if ((xhr.status === 200 || xhr.status === 0) && xhr.response && xhr.response.byteLength > 0) {
              resolve(xhr.response);
            } else {
              reject(new Error('GLB XHR failed: ' + xhr.status));
            }
          };
          xhr.onerror = reject;
          xhr.send();
        });
      };

      const bufferPromise = window.__carGlbBufferPromise || fetchGlbFallback();

      bufferPromise.then(function(arrayBuffer) {
        const loader = new THREE.GLTFLoader();
        loader.parse(arrayBuffer, '', function(gltf) {
          console.log('[Cockpit3D] GLTF car model parsed and mounted!');
          setupLoadedModel(gltf);
          if (typeof applySunroofVisualState === 'function') applySunroofVisualState(state.sunroof, false);
          if (typeof updateLights === 'function') updateLights();
          window.__cockpit3DReady = true;
          window.__carGlbBufferPromise = null;
          idleRenderCountdown = 120;
          renderRequested = true;
          ensureAnimLoop();
          if (renderer && scene && camera) {
            renderer.render(scene, camera);
          }
        }, function(err) {
          console.error('[Cockpit3D] GLTF parser error:', err);
        });
      }).catch(function(e) {
        console.error('[Cockpit3D] GLTF load exception:', e);
      });
    }

    function freezeStaticCarMatrices() {
      if (!carModel) return;
      carModel.updateMatrixWorld(true);
      const dynamicNodes = new Set([
        carModel,
        hinges.fl, hinges.fr, hinges.rl, hinges.rr, hinges.trunk,
        sunroofPivot,
        wheelPivots.flSteer, wheelPivots.flRoll,
        wheelPivots.frSteer, wheelPivots.frRoll,
        wheelPivots.rlRoll, wheelPivots.rrRoll,
        windowMeshes.fl, windowMeshes.fr, windowMeshes.rl, windowMeshes.rr
      ]);
      carModel.traverse(function(obj) {
        if (dynamicNodes.has(obj)) return;
        let p = obj;
        while (p) {
          if (p === headlightBeamsGroup) return;
          p = p.parent;
        }
        obj.updateMatrix();
        obj.matrixAutoUpdate = false;
      });
    }

    function setupLoadedModel(gltf) {
      carModel = gltf.scene;

      // Adjusted proportions: widened along X (0.785) so car is not squeezed from sides
      carModel.scale.set(0.785, 0.736, 0.736);
      carModel.position.set(0, 16, 0);
      scene.add(carModel);

      leftLightMaterials = [];
      rightLightMaterials = [];
      seatMaterials = [];
      trunkMaterial = null;

      // Procedural Studio Reflection EnvMap (256x128) — delivers luxury showroom gloss at ~4x the speed of PBR on Adreno 506
      const envCanvas = document.createElement('canvas');
      envCanvas.width = 256; envCanvas.height = 128;
      const envCtx = envCanvas.getContext('2d');
      const envGrad = envCtx.createLinearGradient(0, 0, 0, 128);
      envGrad.addColorStop(0.00, '#ffffff');
      envGrad.addColorStop(0.18, '#e0f2fe');
      envGrad.addColorStop(0.35, '#64748b');
      envGrad.addColorStop(0.49, '#0f172a');
      envGrad.addColorStop(0.54, '#020617');
      envGrad.addColorStop(1.00, '#000000');
      envCtx.fillStyle = envGrad;
      envCtx.fillRect(0, 0, 256, 128);
      envCtx.fillStyle = '#ffffff';
      envCtx.fillRect(16, 14, 96, 16);
      envCtx.fillRect(144, 16, 96, 16);
      const studioEnvTex = new THREE.CanvasTexture(envCanvas);
      studioEnvTex.mapping = THREE.EquirectangularReflectionMapping;

      // Convert GLTF MeshStandardMaterials (preserving exact material sharing across body & doors!) to ultra-fast MeshLambertMaterial
      const lambertByUuid = {};
      function toFastLambert(stdMat) {
        if (!stdMat || stdMat.isMeshLambertMaterial || stdMat.isMeshBasicMaterial) return stdMat;
        if (lambertByUuid[stdMat.uuid]) return lambertByUuid[stdMat.uuid];
        const mName = stdMat.name || '';
        const isPaintOrFrame = (mName === 'C65_cheqi' || mName === 'C65_chejia');
        const isCriticalDoubleSide = (
          mName === 'C65_chejia' ||
          mName === 'C65_dipan' ||
          mName === 'C65_heikaoqi' ||
          mName === 'C65_youqianmenneishi' ||
          mName === 'C65_youhoumenneishi'
        );
        const lam = new THREE.MeshLambertMaterial({
          name: mName,
          color: stdMat.color ? stdMat.color.clone() : new THREE.Color(0xffffff),
          map: stdMat.map || null,
          envMap: isPaintOrFrame ? studioEnvTex : null,
          combine: THREE.AddOperation,
          reflectivity: isPaintOrFrame ? 0.18 : 0.0,
          side: isCriticalDoubleSide ? THREE.DoubleSide : THREE.FrontSide
        });
        if (isPaintOrFrame) {
          lam.userData.pearlUniform = { value: 0.0 };
          lam.onBeforeCompile = function (shader) {
            shader.uniforms.uIsPearlWhite = lam.userData.pearlUniform;
            shader.vertexShader = 'varying vec3 vPearlNormal;\nvarying vec3 vPearlViewPos;\n' + shader.vertexShader;
            shader.vertexShader = shader.vertexShader.replace(
              '#include <project_vertex>',
              '#include <project_vertex>\n  vPearlNormal = normalize(transformedNormal);\n  vPearlViewPos = -mvPosition.xyz;\n'
            );
            shader.fragmentShader = 'uniform float uIsPearlWhite;\nvarying vec3 vPearlNormal;\nvarying vec3 vPearlViewPos;\n' + shader.fragmentShader;
            shader.fragmentShader = shader.fragmentShader.replace(
              '#include <dithering_fragment>',
              `#include <dithering_fragment>
               if (uIsPearlWhite > 0.5) {
                 float pLuma = dot(gl_FragColor.rgb, vec3(0.299, 0.587, 0.114));
                 float pChroma = max(gl_FragColor.r, gl_FragColor.g) - gl_FragColor.b;
                 float pMask = smoothstep(0.18, 0.44, pLuma) * (1.0 - smoothstep(0.14, 0.28, pChroma));
                 vec3 c = (gl_FragColor.rgb * (1.0 + gl_FragColor.rgb * 0.20)) / (1.0 + gl_FragColor.rgb * 0.56);
                 float ndv = clamp(abs(dot(normalize(vPearlNormal), normalize(vPearlViewPos))), 0.0, 1.0);
                 c += vec3(0.068, 0.055, 0.028) * pow(ndv, 2.5);
                 c += vec3(0.038, 0.056, 0.085) * pow(1.0 - ndv, 2.2);
                 gl_FragColor.rgb = mix(gl_FragColor.rgb, clamp(c, 0.0, 1.0), pMask);
               }
              `
            );
          };
        }
        lambertByUuid[stdMat.uuid] = lam;
        return lam;
      }

      // Shared singleton materials for antenna and license plate
      const sharedAntennaMat = new THREE.MeshLambertMaterial({
        color: 0x060708,
        envMap: studioEnvTex,
        combine: THREE.AddOperation,
        reflectivity: 0.14,
        side: THREE.FrontSide
      });
      const sharedPlateMat = new THREE.MeshBasicMaterial({
        map: plateTexture,
        color: 0xd8e0ea,
        side: THREE.FrontSide,
        toneMapped: false
      });

      carModel.traverse((child) => {
        if (child.name) nodes[child.name] = child;
        if (child.isMesh) {
          child.frustumCulled = true;

          // Keep shark-fin roof antenna always glossy black regardless of body/roof color
          const cNameLower = (child.name || '').toLowerCase();
          if (cNameLower.includes('tianxian') || cNameLower.includes('shark') || cNameLower.includes('antenna') || cNameLower.includes('yuqi')) {
            child.material = sharedAntennaMat;
            return;
          }
          if (child.name === 'C65_qianchepai' || child.name === 'C65_qianchepai_1' || child.name === 'C65_houchepai') {
            child.material = sharedPlateMat;
            return;
          }

          if (child.name === 'C65_weixiang' || child.name === 'C65_weixiang_1') {
            if (Array.isArray(child.material)) {
              child.material = child.material.map(toFastLambert);
              child.material[0] = child.material[0].clone();
              child.material[0].name = 'C65_weixiang_light';
              trunkMaterial = child.material[0];
            } else {
              child.material = toFastLambert(child.material).clone();
              child.material.name = 'C65_weixiang_light';
              trunkMaterial = child.material;
            }
            return;
          }

          if (Array.isArray(child.material)) {
            child.material = child.material.map(toFastLambert);
          } else {
            child.material = toFastLambert(child.material);
          }

          const mats = Array.isArray(child.material) ? child.material : [child.material];
          mats.forEach((m) => {
            if (!m) return;
            if (m.name === 'C65_zuodeng' || child.name === 'C65_zuodeng_1' || child.name === 'C65_zuodeng') {
              if (!leftLightMaterials.includes(m)) leftLightMaterials.push(m);
            }
            if (m.name === 'C65_youdeng' || child.name === 'C65_youdeng_1' || child.name === 'C65_youdeng') {
              if (!rightLightMaterials.includes(m)) rightLightMaterials.push(m);
            }
            if (m.name === 'C65_zuoyi' || child.name === 'C65_zuoyi' || child.name === 'C65_zuoyi_1') {
              if (!seatMaterials.includes(m)) seatMaterials.push(m);
            }
          });
        }
      });

      if (nodes['C65_dipan']) {
        nodes['C65_dipan'].visible = true;
      }

      // Convert white/light-beige/grey trim on dashboard & center console (C65_zhongkong) into deep off-black anthracite (closer to black than grey)
      if (nodes['C65_zhongkong'] && nodes['C65_zhongkong'].material && nodes['C65_zhongkong'].material.map && nodes['C65_zhongkong'].material.map.image) {
        const dashMat = nodes['C65_zhongkong'].material;
        const dashCanvas = document.createElement('canvas');
        dashCanvas.width = 512;
        dashCanvas.height = 512;
        const dashCtx = dashCanvas.getContext('2d');
        dashCtx.drawImage(dashMat.map.image, 0, 0, 512, 512);
        const dashData = dashCtx.getImageData(0, 0, 512, 512);
        const dd = dashData.data;
        for (let y = 0; y < 512; y++) {
          for (let x = 0; x < 512; x++) {
            // Preserve the center infotainment screen artwork UV rect
            if (x >= 270 && x <= 336 && y >= 80 && y <= 140) continue;
            const i = (y * 512 + x) * 4;
            const r = dd[i], g = dd[i + 1], b = dd[i + 2];
            const lum = (r * 77 + g * 150 + b * 29) >> 8;
            const maxC = r > g ? (r > b ? r : b) : (g > b ? g : b);
            const minC = r < g ? (r < b ? r : b) : (g < b ? g : b);
            const sat = maxC - minC;
            if (lum >= 18 && sat < 68) {
              const t = (lum - 18) / 237.0;
              const dg = 8 + t * 7;
              dd[i]     = Math.round(dg);
              dd[i + 1] = Math.round(dg * 1.02);
              dd[i + 2] = Math.round(dg * 1.05);
            }
          }
        }
        dashCtx.putImageData(dashData, 0, 0);
        const dashTex = new THREE.CanvasTexture(dashCanvas);
        dashTex.flipY = false;
        dashTex.generateMipmaps = false;
        dashTex.minFilter = THREE.LinearFilter;
        dashTex.magFilter = THREE.LinearFilter;
        dashMat.map = dashTex;
        if (dashMat.emissive) dashMat.emissive.setHex(0x020203);
        dashMat.needsUpdate = true;
      }

      // 4-Wheel Axle Pivots Setup (Front Steering + 4-Wheel Rolling around intrinsic centers)
      const wCenters = {
        fl: new THREE.Vector3(777.4, -409.1, 1397.8),
        fr: new THREE.Vector3(-777.4, -409.1, 1397.8),
        rl: new THREE.Vector3(777.4, -409.1, -1265.3),
        rr: new THREE.Vector3(-777.4, -409.1, -1265.3)
      };

      // Front Left Wheel (Steer Group around Y + Roll Group around X)
      if (nodes['C65_zuoqianluntai']) {
        const m = nodes['C65_zuoqianluntai'];
        const c = wCenters.fl;
        const steerG = new THREE.Group();
        steerG.position.copy(c);
        const rollG = new THREE.Group();
        steerG.add(rollG);
        carModel.add(steerG);
        m.position.set(-c.x, -c.y, -c.z);
        rollG.add(m);
        wheelPivots.flSteer = steerG;
        wheelPivots.flRoll = rollG;
      }

      // Front Right Wheel (Steer Group around Y + Roll Group around X)
      if (nodes['C65_youqianluntai']) {
        const m = nodes['C65_youqianluntai'];
        const c = wCenters.fr;
        const steerG = new THREE.Group();
        steerG.position.copy(c);
        const rollG = new THREE.Group();
        steerG.add(rollG);
        carModel.add(steerG);
        m.position.set(-c.x, -c.y, -c.z);
        rollG.add(m);
        wheelPivots.frSteer = steerG;
        wheelPivots.frRoll = rollG;
      }

      // Rear Left Wheel (Roll Group around X)
      if (nodes['C65_zuohouluntai']) {
        const m = nodes['C65_zuohouluntai'];
        const c = wCenters.rl;
        const rollG = new THREE.Group();
        rollG.position.copy(c);
        carModel.add(rollG);
        m.position.set(-c.x, -c.y, -c.z);
        rollG.add(m);
        wheelPivots.rlRoll = rollG;
      }

      // Rear Right Wheel (Roll Group around X)
      if (nodes['C65_youhouluntai']) {
        const m = nodes['C65_youhouluntai'];
        const c = wCenters.rr;
        const rollG = new THREE.Group();
        rollG.position.copy(c);
        carModel.add(rollG);
        m.position.set(-c.x, -c.y, -c.z);
        rollG.add(m);
        wheelPivots.rrRoll = rollG;
      }

      // Initialize Ground Shadow immediately; defer Headlight Beams unless lights are already active
      if (state.lights.main || state.lights.dipped) {
        initHeadlightBeams();
      } else {
        setTimeout(initHeadlightBeams, 150);
      }
      initCarGroundShadow();

      // Symmetric Door Glass EnvMap (64x64) so DoubleSide looks identical from both inside & outside
      const dgCanvas = document.createElement('canvas');
      dgCanvas.width = 64; dgCanvas.height = 64;
      const dgCtx = dgCanvas.getContext('2d');
      const dgGrad = dgCtx.createLinearGradient(0, 0, 0, 64);
      dgGrad.addColorStop(0.00, '#dbeafe');
      dgGrad.addColorStop(0.25, '#64748b');
      dgGrad.addColorStop(0.50, '#1e293b');
      dgGrad.addColorStop(0.75, '#64748b');
      dgGrad.addColorStop(1.00, '#dbeafe');
      dgCtx.fillStyle = dgGrad;
      dgCtx.fillRect(0, 0, 64, 64);
      const doorGlassEnvTex = new THREE.CanvasTexture(dgCanvas);
      doorGlassEnvTex.mapping = THREE.EquirectangularReflectionMapping;

      // Setup specialized automotive glass materials (ultra-fast MeshLambertMaterial + Studio Reflection):
      // 1. Front windshield: slightly transparent with bottom ceramic frit band
      const frontGlassMaterial = new THREE.MeshLambertMaterial({
        color: 0x030508,
        envMap: studioEnvTex,
        combine: THREE.AddOperation,
        reflectivity: 0.16,
        transparent: true,
        opacity: 0.75,
        side: THREE.DoubleSide,
        depthWrite: false
      });

      // 2. Rear tailgate windshield ONLY: deep black privacy tint (فاميه)
      const rearDarkGlassMaterial = new THREE.MeshLambertMaterial({
        color: 0x010203,
        envMap: studioEnvTex,
        combine: THREE.AddOperation,
        reflectivity: 0.06,
        transparent: false,
        opacity: 1.0,
        side: THREE.DoubleSide
      });

      // 3. All 4 door windows + C-pillar quarter glass: unified semi-transparent from BOTH inside & outside
      const doorGlassMaterial = new THREE.MeshLambertMaterial({
        color: 0x070b10,
        envMap: doorGlassEnvTex,
        combine: THREE.AddOperation,
        reflectivity: 0.11,
        transparent: true,
        opacity: 0.52,
        side: THREE.DoubleSide,
        depthWrite: false
      });

      // 4. Sunroof: completely dark opaque glossy glass (non-transparent per user request)
      const sunroofOpaqueDarkMaterial = new THREE.MeshLambertMaterial({
        color: 0x010203,
        envMap: studioEnvTex,
        combine: THREE.AddOperation,
        reflectivity: 0.06,
        transparent: false,
        opacity: 1.0,
        side: THREE.FrontSide
      });

      // Split C65_boli: front windshield, rear tailgate glass, C-pillar quarter glass, and sunroofMesh
      if (nodes['C65_boli'] && nodes['C65_boli'].geometry) {
        const origGeom = nodes['C65_boli'].geometry;
        const posAttr = origGeom.attributes.position;
        const origPos = posAttr.array;
        const origIndices = origGeom.index ? origGeom.index.array : null;

        if (origIndices) {
          const frontIndices = [];
          const rearIndices = [];
          const quarterIndices = [];
          const sunroofIndices = [];

          for (let i = 0; i < origIndices.length; i += 3) {
            const i0 = origIndices[i];
            const i1 = origIndices[i + 1];
            const i2 = origIndices[i + 2];

            const y0 = origPos[i0 * 3 + 1], z0 = origPos[i0 * 3 + 2], x0 = origPos[i0 * 3];
            const y1 = origPos[i1 * 3 + 1], z1 = origPos[i1 * 3 + 2], x1 = origPos[i1 * 3];
            const y2 = origPos[i2 * 3 + 1], z2 = origPos[i2 * 3 + 2], x2 = origPos[i2 * 3];

            const avgY = (y0 + y1 + y2) / 3;
            const avgZ = (z0 + z1 + z2) / 3;
            const avgX = (x0 + x1 + x2) / 3;

            if (avgY >= 680 && avgZ >= -290 && avgZ <= 210 && Math.abs(avgX) <= 460) {
              sunroofIndices.push(i0, i1, i2);
            } else if (avgZ < -900 && avgZ > -1500 && Math.abs(avgX) > 595 && avgY < 570) {
              quarterIndices.push(i0, i1, i2);
            } else if (avgZ < -200) {
              rearIndices.push(i0, i1, i2);
            } else {
              frontIndices.push(i0, i1, i2);
            }
          }

          // Dynamic calculation of front windshield Y bounds for realistic bottom-to-top opacity gradient:
          let frontMinY = Infinity, frontMaxY = -Infinity;
          for (let i = 0; i < frontIndices.length; i++) {
            const vy = origPos[frontIndices[i] * 3 + 1];
            if (vy < frontMinY) frontMinY = vy;
            if (vy > frontMaxY) frontMaxY = vy;
          }
          if (frontMinY === Infinity) { frontMinY = 500; frontMaxY = 800; }

          // Front windshield shader: balanced midpoint lower 16% ceramic frit black edge, smooth transition 16%..28%, upper part 0.73 transparent
          frontGlassMaterial.onBeforeCompile = function(shader) {
            shader.uniforms.uGlassMinY = { value: frontMinY };
            shader.uniforms.uGlassMaxY = { value: frontMaxY };

            shader.vertexShader = 'varying float vCustomGlassY;\n' + shader.vertexShader;
            shader.vertexShader = shader.vertexShader.replace(
              '#include <begin_vertex>',
              '#include <begin_vertex>\n  vCustomGlassY = position.y;\n'
            );

            shader.fragmentShader = 'uniform float uGlassMinY;\nuniform float uGlassMaxY;\nvarying float vCustomGlassY;\n' + shader.fragmentShader;
            shader.fragmentShader = shader.fragmentShader.replace(
              '#include <dithering_fragment>',
              `#include <dithering_fragment>
               float hNorm = clamp((vCustomGlassY - uGlassMinY) / max(uGlassMaxY - uGlassMinY, 0.001), 0.0, 1.0);
               float glassAlpha = 0.73;
               if (hNorm <= 0.16) {
                 glassAlpha = 1.0;
                 gl_FragColor.rgb = mix(gl_FragColor.rgb, vec3(0.005, 0.008, 0.012), 0.96);
               } else if (hNorm < 0.28) {
                 float t = smoothstep(0.16, 0.28, hNorm);
                 glassAlpha = mix(1.0, 0.73, t);
                 gl_FragColor.rgb = mix(gl_FragColor.rgb, vec3(0.005, 0.008, 0.012), (1.0 - t) * 0.96);
               } else {
                 glassAlpha = 0.73;
               }
               gl_FragColor.a = glassAlpha;
              `
            );
          };

          // Front windshield in origGeom
          origGeom.setIndex(new THREE.BufferAttribute(new Uint16Array(frontIndices), 1));
          origGeom.index.needsUpdate = true;
          nodes['C65_boli'].material = frontGlassMaterial;

          // Rear tailgate windshield mesh (100% opaque deep black privacy tint)
          if (rearIndices.length > 0) {
            const rearGeom = new THREE.BufferGeometry();
            rearGeom.setAttribute('position', origGeom.attributes.position);
            if (origGeom.attributes.normal) rearGeom.setAttribute('normal', origGeom.attributes.normal);
            if (origGeom.attributes.uv) rearGeom.setAttribute('uv', origGeom.attributes.uv);
            rearGeom.setIndex(new THREE.BufferAttribute(new Uint16Array(rearIndices), 1));

            const rearGlassMesh = new THREE.Mesh(rearGeom, rearDarkGlassMaterial);
            rearGlassMesh.name = 'C65_rear_glass';
            nodes['C65_boli'].parent.add(rearGlassMesh);
          }

          // C-pillar side quarter glass mesh (semi-transparent from inside & outside matching door glass)
          if (quarterIndices.length > 0) {
            const quarterGeom = new THREE.BufferGeometry();
            quarterGeom.setAttribute('position', origGeom.attributes.position);
            if (origGeom.attributes.normal) quarterGeom.setAttribute('normal', origGeom.attributes.normal);
            if (origGeom.attributes.uv) quarterGeom.setAttribute('uv', origGeom.attributes.uv);
            quarterGeom.setIndex(new THREE.BufferAttribute(new Uint16Array(quarterIndices), 1));

            const quarterGlassMesh = new THREE.Mesh(quarterGeom, doorGlassMaterial);
            quarterGlassMesh.name = 'C65_quarter_glass';
            nodes['C65_boli'].parent.add(quarterGlassMesh);
          }

          // Sunroof glass mesh (100% opaque glossy black)
          const sunroofGeom = new THREE.BufferGeometry();
          sunroofGeom.setAttribute('position', origGeom.attributes.position);
          if (origGeom.attributes.normal) sunroofGeom.setAttribute('normal', origGeom.attributes.normal);
          if (origGeom.attributes.uv) sunroofGeom.setAttribute('uv', origGeom.attributes.uv);
          sunroofGeom.setIndex(new THREE.BufferAttribute(new Uint16Array(sunroofIndices), 1));

          sunroofMesh = new THREE.Mesh(sunroofGeom, sunroofOpaqueDarkMaterial);
          sunroofMesh.name = 'C65_sunroof_glass';
          sunroofMesh.userData = { isSunroof: true };

          sunroofPivot = new THREE.Group();
          sunroofPivot.name = 'sunroof_pivot';
          sunroofPivot.position.set(SUNROOF_PIVOT_X, SUNROOF_PIVOT_Y, SUNROOF_PIVOT_Z);

          sunroofMesh.position.set(-SUNROOF_PIVOT_X, -SUNROOF_PIVOT_Y, -SUNROOF_PIVOT_Z);
          sunroofPivot.add(sunroofMesh);

          nodes['C65_boli'].parent.add(sunroofPivot);
        }
      }

      // Setup all 4 door windows with unified semi-transparent glass (inside & outside)
      function setupDoorWindow(doorNode, doorKey) {
        if (!doorNode) return null;
        let win = null;
        doorNode.traverse((child) => {
          if (child.isMesh && child.material && child.material.name === 'C65_boli') {
            win = child;
            child.material = doorGlassMaterial;
            child.userData = { isWindow: true, doorKey: doorKey };
          }
        });
        return win;
      }

      windowMeshes.fl = setupDoorWindow(nodes['C65_zuoqianmen'], 'fl');
      windowMeshes.fr = setupDoorWindow(nodes['C65_youqianmen'], 'fr');
      windowMeshes.rl = setupDoorWindow(nodes['C65_zuohoumen'], 'rl');
      windowMeshes.rr = setupDoorWindow(nodes['C65_youhoumen'], 'rr');

      setupHinges();
      freezeStaticCarMatrices();

      // Extract base paint, seat, and resting light textures directly from embedded GLB materials
      // so the first rendered frame is 100% textured with zero extra HTTP/file fetches.
      function makeGlbBaseTex(mat) {
        if (!mat || !mat.map || !mat.map.image) return null;
        const tex = new THREE.Texture(mat.map.image);
        tex.flipY = false;
        tex.generateMipmaps = false;
        tex.minFilter = THREE.LinearFilter;
        tex.magFilter = THREE.LinearFilter;
        tex.needsUpdate = true;
        return tex;
      }

      if (!rawCheqiImg && nodes['C65_cheqi'] && nodes['C65_cheqi'].material && nodes['C65_cheqi'].material.map) {
        rawCheqiImg = nodes['C65_cheqi'].material.map.image;
      }
      if (!seatImages.sport) {
        const seatMat = seatMaterials[0] || (nodes['C65_zuoyi'] && nodes['C65_zuoyi'].material);
        if (seatMat && seatMat.map && seatMat.map.image) {
          seatImages.sport = seatMat.map.image;
        }
      }
      if (!lightTextures['C65_zuodeng.png']) {
        const leftTex = makeGlbBaseTex(leftLightMaterials[0] || trunkMaterial);
        if (leftTex) lightTextures['C65_zuodeng.png'] = leftTex;
      }
      if (!lightTextures['C65_youdeng.png']) {
        const rightTex = makeGlbBaseTex(rightLightMaterials[0]);
        if (rightTex) lightTextures['C65_youdeng.png'] = rightTex;
      }

      if (isTexImgReady(rawCheqiImg)) {
        updateCarPaint();
      } else if (isTexImgReady(seatImages.sport)) {
        updateSeatUpholstery();
      }

      if (lightTextures['C65_zuodeng.png']) {
        applyLightState(leftLightMaterials, lightTextures['C65_zuodeng.png'], false);
      }
      applyTrunkState();
      if (lightTextures['C65_youdeng.png']) {
        applyLightState(rightLightMaterials, lightTextures['C65_youdeng.png'], false);
      }

      idleRenderCountdown = 120;
      if (renderer && scene && camera) {
        renderer.render(scene, camera);
      }
      requestRender();
    }

    function setupHinges() {
      hinges.fl = createHinge(nodes['C65_zuoqianmen'], new THREE.Vector3(730, 350, 730));
      hinges.fr = createHinge(nodes['C65_youqianmen'], new THREE.Vector3(-730, 350, 730));
      hinges.rl = createHinge(nodes['C65_zuohoumen'], new THREE.Vector3(730, 350, -221));
      hinges.rr = createHinge(nodes['C65_youhoumen'], new THREE.Vector3(-730, 350, -221));
      hinges.trunk = createHinge(nodes['C65_weixiang'], new THREE.Vector3(0, 380, -1890));
    }

    function createHinge(targetMesh, pivotPoint) {
      if (!targetMesh) return null;
      const parent = targetMesh.parent;
      const hingeGroup = new THREE.Group();
      hingeGroup.position.copy(pivotPoint);
      parent.add(hingeGroup);
      targetMesh.position.sub(pivotPoint);
      hingeGroup.add(targetMesh);
      return hingeGroup;
    }

    function resolveTexture(side, isFlasherActive) {
      const prefix = (side === 'left') ? 'C65_zuodeng' : 'C65_youdeng';
      let mode = '';
      if (state.lights.main) mode = '_yuanguang';
      else if (state.lights.dipped || state.lights.position || state.lights.fog) mode = '_jinguang';
      const flash = isFlasherActive ? '_01' : '';
      let targetKey = prefix + mode + flash + '.png';
      if (mode === '' && flash === '') targetKey = prefix + '.png';
      return lightTextures[targetKey] || null;
    }

    function applyLightState(materialsList, tex, isActive) {
      if (!tex) return;
      materialsList.forEach((mat) => {
        mat.map = tex;
        if (isActive) {
          mat.emissiveMap = tex;
          mat.emissive = new THREE.Color(0xffffff);
          if (state.lights.main) {
            mat.emissiveIntensity = 2.05; // Full high beam radiant flood
          } else if (state.lights.dipped || state.lights.fog) {
            mat.emissiveIntensity = 1.35; // Standard solid low beam
          } else if (state.lights.position) {
            mat.emissiveIntensity = 0.45; // Soft/dim dashboard/position light (gentler than low beam)
          } else {
            mat.emissiveIntensity = 1.25; // Turn signal active
          }
        } else {
          mat.emissiveMap = null;
          mat.emissive = new THREE.Color(0x000000);
          mat.emissiveIntensity = 0;
        }
        mat.needsUpdate = true;
      });
      idleRenderCountdown = 120;
      requestRender();
    }

    function applyTrunkState() {
      if (!trunkMaterial) return;
      const isActive = state.lights.dipped || state.lights.main || state.lights.position || state.lights.fog;
      const baseKey = isActive ? 'C65_zuodeng_jinguang.png' : 'C65_zuodeng.png';
      const targetTex = lightTextures[baseKey] || lightTextures['C65_zuodeng.png'] || trunkMaterial.map;
      if (targetTex) {
        trunkMaterial.map = targetTex;
        if (isActive) {
          trunkMaterial.emissiveMap = targetTex;
          trunkMaterial.emissive = new THREE.Color(0xffffff);
          if (state.lights.main) {
            trunkMaterial.emissiveIntensity = 1.35;
          } else if (state.lights.dipped || state.lights.fog) {
            trunkMaterial.emissiveIntensity = 1.15;
          } else if (state.lights.position) {
            trunkMaterial.emissiveIntensity = 0.42; // Subtle soft glow for position light
          } else {
            trunkMaterial.emissiveIntensity = 1.0;
          }
        } else {
          trunkMaterial.emissiveMap = null;
          trunkMaterial.emissive = new THREE.Color(0x000000);
          trunkMaterial.emissiveIntensity = 0;
        }
        trunkMaterial.needsUpdate = true;
      }
    }

    function updateLights() {
      const isLeftFlashing = (state.flashers.left || state.flashers.hazard) && flasherPhase;
      const isRightFlashing = (state.flashers.right || state.flashers.hazard) && flasherPhase;

      const texL = resolveTexture('left', isLeftFlashing);
      const texR = resolveTexture('right', isRightFlashing);

      const isLightOn = state.lights.dipped || state.lights.main || state.lights.position || state.lights.fog;
      applyLightState(leftLightMaterials, texL, isLightOn || isLeftFlashing);
      applyLightState(rightLightMaterials, texR, isLightOn || isRightFlashing);
      applyTrunkState();
      updateHeadlightBeamsVisual();

      idleRenderCountdown = 120;
      requestRender();
    }

    // --- True 3D Automotive Ground Contact & Ambient Occlusion Shadow (Zero GPU Lag: Single 2-Tri Baked Plane) ---
    function initCarGroundShadow() {
      if (!carModel) return;
      const sCanvas = document.createElement('canvas');
      sCanvas.width = 64;
      sCanvas.height = 128;
      const sCtx = sCanvas.getContext('2d');
      const sImg = sCtx.createImageData(64, 128);
      const sd = sImg.data;

      const planeW = 2600.0;
      const planeL = 5400.0;
      const tires = [
        [ 777.4,  1397.8],
        [-777.4,  1397.8],
        [ 777.4, -1265.3],
        [-777.4, -1265.3]
      ];

      for (let py = 0; py < 128; py++) {
        const vNorm = py / 127.0;
        const wz = (vNorm - 0.5) * planeL;
        const zRatio = Math.min(1.5, Math.abs(wz) / 2200.0);
        const halfW = 815.0 * Math.max(0.52, 1.0 - 0.22 * Math.pow(zRatio, 2.5));
        const nz = Math.abs(wz) / 2180.0;
        const nzPow = Math.pow(nz, 3.8);
        const edgeV = Math.sin(vNorm * Math.PI);

        for (let px = 0; px < 64; px++) {
          const uNorm = px / 63.0;
          const wx = (uNorm - 0.5) * planeW;
          const idx = (py * 64 + px) * 4;

          // 1. Chassis silhouette Ambient Occlusion core + soft penumbra
          const nx = Math.abs(wx) / halfW;
          const bodyDist = Math.pow(Math.pow(nx, 2.8) + nzPow, 1.0 / 3.2);
          let chassisAO = 0.0;
          if (bodyDist < 0.65) {
            chassisAO = 0.90;
          } else if (bodyDist < 1.38) {
            const t = (bodyDist - 0.65) / 0.73;
            chassisAO = 0.90 * Math.exp(-3.6 * t * t);
          }

          // 2. Four Tire Ground Contact Patches (Anchors tires firmly to the floor)
          let tireAO = 0.0;
          for (let t = 0; t < 4; t++) {
            const dx = (wx - tires[t][0]) / 185.0;
            const dz = (wz - tires[t][1]) / 320.0;
            const d2 = dx * dx + dz * dz;
            if (d2 < 6.0) {
              const patch = 0.95 * Math.exp(-0.65 * d2);
              if (patch > tireAO) tireAO = patch;
            }
          }

          // 3. Soft outer ambient floor diffusion
          const hDist2 = (wx / 1060.0) * (wx / 1060.0) + (wz / 2480.0) * (wz / 2480.0);
          const haloAO = (hDist2 < 2.5) ? 0.28 * Math.exp(-1.9 * hDist2) : 0.0;

          const edgeU = Math.sin(uNorm * Math.PI);
          const borderFade = Math.min(1.0, edgeU * edgeV * 4.5);

          let alpha = Math.min(0.95, (Math.max(chassisAO, tireAO) + haloAO * 0.45) * borderFade);
          if (alpha <= 0.004) continue;

          sd[idx]     = 1;
          sd[idx + 1] = 5;
          sd[idx + 2] = 6;
          sd[idx + 3] = Math.round(alpha * 255);
        }
      }
      sCtx.putImageData(sImg, 0, 0);

      const shadowTex = new THREE.CanvasTexture(sCanvas);
      shadowTex.generateMipmaps = false;
      shadowTex.minFilter = THREE.LinearFilter;
      shadowTex.magFilter = THREE.LinearFilter;

      const sGeom = new THREE.PlaneGeometry(planeW, planeL);
      sGeom.rotateX(-Math.PI / 2);

      const sMat = new THREE.MeshBasicMaterial({
        map: shadowTex,
        transparent: true,
        opacity: 0.94,
        depthTest: true,
        depthWrite: false,
        side: THREE.DoubleSide
      });

      const shadowMesh = new THREE.Mesh(sGeom, sMat);
      // Exact bottom contact plane of the 4 rubber tires (minY = -728.4)
      shadowMesh.position.set(0, -725, 25);
      shadowMesh.renderOrder = -1;
      shadowMesh.name = 'Car3DGroundShadow';
      carModel.add(shadowMesh);
    }

    // --- Headlight Beam Functions (Upper Volumetric Beam Only: 2 Levels for Low & High Beam) ---
    var lensGlowMaterial = null;
    function initHeadlightBeams() {
      if (!carModel || headlightBeamsGroup) return;

      headlightBeamsGroup = new THREE.Group();
      headlightBeamsGroup.name = 'HeadlightBeamsGroup';

      // 1. 2D-Feathered Volumetric Light Cone Texture (Exact previous effect with wider spread)
      const vCanvas = document.createElement('canvas');
      vCanvas.width = 64;
      vCanvas.height = 128;
      const vCtx = vCanvas.getContext('2d');
      const vImg = vCtx.createImageData(64, 128);
      const vd = vImg.data;

      for (let py = 0; py < 128; py++) {
        const v = py / 127.0; // 0 = at projector lens, 1 = far end of beam
        const startFade = Math.min(1.0, v / 0.045);
        const endFade = Math.pow(1.0 - v, 2.0);
        // Starts at lens (0.052) and spreads out wider as it travels forward (up to 0.38)
        const sigmaU = 0.052 + v * 0.33;

        for (let px = 0; px < 64; px++) {
          const idx = (py * 64 + px) * 4;
          const u = px / 63.0;
          const du = u - 0.5;
          const latGauss = Math.exp(-0.5 * (du * du) / (sigmaU * sigmaU));
          const latWindow = Math.pow(Math.sin(u * Math.PI), 1.4);
          let alpha = latGauss * latWindow * startFade * endFade;
          if (alpha <= 0.002) continue;

          const core = Math.pow(alpha, 0.55);
          vd[idx]     = Math.round(180 + 75 * core);
          vd[idx + 1] = Math.round(228 + 27 * core);
          vd[idx + 2] = 255;
          vd[idx + 3] = Math.round(Math.min(1.0, alpha) * 255);
        }
      }
      vCtx.putImageData(vImg, 0, 0);

      const volTex = new THREE.CanvasTexture(vCanvas);
      volTex.generateMipmaps = false;
      volTex.minFilter = THREE.LinearFilter;
      volTex.magFilter = THREE.LinearFilter;

      volumetricBeamMaterial = new THREE.MeshBasicMaterial({
        map: volTex,
        transparent: true,
        opacity: 0,
        blending: THREE.AdditiveBlending,
        depthTest: true,
        depthWrite: false,
        side: THREE.DoubleSide
      });

      // 2. Soft Projector Lens Optical Bloom Sprite
      const hCanvas = document.createElement('canvas');
      hCanvas.width = 128;
      hCanvas.height = 128;
      const hCtx = hCanvas.getContext('2d');
      const hGrad = hCtx.createRadialGradient(64, 64, 0, 64, 64, 62);
      hGrad.addColorStop(0.0, 'rgba(255, 255, 255, 0.95)');
      hGrad.addColorStop(0.22, 'rgba(220, 245, 255, 0.58)');
      hGrad.addColorStop(0.55, 'rgba(150, 215, 255, 0.18)');
      hGrad.addColorStop(1.0, 'rgba(80, 170, 255, 0.0)');
      hCtx.fillStyle = hGrad;
      hCtx.fillRect(0, 0, 128, 128);

      const haloTex = new THREE.CanvasTexture(hCanvas);
      lensGlowMaterial = new THREE.SpriteMaterial({
        map: haloTex,
        transparent: true,
        opacity: 0,
        blending: THREE.AdditiveBlending,
        depthTest: true,
        depthWrite: false
      });

      const createVolCone = (xPos) => {
        const vGrp = new THREE.Group();
        // Exact outer lens surface of C65 front headlights
        vGrp.position.set(xPos, -12, 2135);

        const shaftHolder = new THREE.Group();
        shaftHolder.rotation.x = -Math.PI / 2 + 0.185;
        shaftHolder.rotation.z = (xPos > 0 ? -0.045 : 0.045);

        const angles = [0, Math.PI / 3, -Math.PI / 3];
        angles.forEach((ang) => {
          // Widened from 820x2400 to 1480x2650 for broader horizontal & ground spread
          const pGeom = new THREE.PlaneGeometry(1480, 2650);
          pGeom.translate(0, -1325, 0);
          pGeom.rotateY(ang);
          const pMesh = new THREE.Mesh(pGeom, volumetricBeamMaterial);
          shaftHolder.add(pMesh);
        });
        vGrp.add(shaftHolder);

        const haloSprite = new THREE.Sprite(lensGlowMaterial);
        haloSprite.position.set(0, 4, 25);
        haloSprite.scale.set(340, 200, 1);
        vGrp.add(haloSprite);

        return vGrp;
      };

      const leftBeam = createVolCone(585);
      const rightBeam = createVolCone(-585);
      headlightBeamsGroup.add(leftBeam);
      headlightBeamsGroup.add(rightBeam);
      volumetricBeamMeshes = [leftBeam, rightBeam];

      carModel.add(headlightBeamsGroup);
      headlightBeamsGroup.visible = false;
    }

    function updateHeadlightBeamsVisual() {
      const isMain = !!state.lights.main;
      const isDipped = !!state.lights.dipped;

      if (!headlightBeamsGroup) {
        if (!isMain && !isDipped) return;
        initHeadlightBeams();
        if (!headlightBeamsGroup) return;
      }

      // Beam is ONLY for Low Beam and High Beam.
      // Position / Dashboard light uses the headlight housing emissive glow only.

      if (!isMain && !isDipped) {
        headlightBeamsGroup.visible = false;
        if (volumetricBeamMaterial) volumetricBeamMaterial.opacity = 0;
        if (lensGlowMaterial) lensGlowMaterial.opacity = 0;
        return;
      }

      headlightBeamsGroup.visible = true;

      if (isMain) {
        // Level 2 — High Beam (النور العالي): stronger, wider & longer throw
        if (volumetricBeamMaterial) volumetricBeamMaterial.opacity = 0.45;
        if (lensGlowMaterial) lensGlowMaterial.opacity = 0.90;
        volumetricBeamMeshes.forEach(b => {
          b.scale.set(1.38, 1.35, 1.30);
          if (b.children[0]) b.children[0].rotation.x = -Math.PI / 2 + 0.135;
        });
      } else {
        // Level 1 — Low Beam (النور الواطي): wide spread angled down toward ground
        if (volumetricBeamMaterial) volumetricBeamMaterial.opacity = 0.26;
        if (lensGlowMaterial) lensGlowMaterial.opacity = 0.55;
        volumetricBeamMeshes.forEach(b => {
          b.scale.set(1.12, 1.05, 1.08);
          if (b.children[0]) b.children[0].rotation.x = -Math.PI / 2 + 0.195;
        });
      }
    }

    // Direct Door Tap Interaction on 3D Model (Correctly mapped to #canvas-container bounds)
    const raycaster = new THREE.Raycaster();
    const mouse = new THREE.Vector2();
    let pointerDownPos = { x: 0, y: 0 };

    window.addEventListener('pointerdown', (e) => {
      pointerDownPos.x = e.clientX;
      pointerDownPos.y = e.clientY;
    });

    window.addEventListener('pointerup', (e) => {
      const dist = Math.hypot(e.clientX - pointerDownPos.x, e.clientY - pointerDownPos.y);
      if (dist > 8) return;

      if (e.target.closest('.hud') || e.target.closest('.modal-overlay')) return;

      const container = document.getElementById('canvas-container');
      if (!container || !carModel) return;
      const rect = container.getBoundingClientRect();
      if (e.clientX < rect.left || e.clientX > rect.right || e.clientY < rect.top || e.clientY > rect.bottom) return;

      mouse.x = ((e.clientX - rect.left) / rect.width) * 2 - 1;
      mouse.y = -((e.clientY - rect.top) / rect.height) * 2 + 1;
      raycaster.setFromCamera(mouse, camera);

      const intersects = raycaster.intersectObjects(carModel.children, true);
      if (intersects.length > 0) {
        // Ignore taps directly on window glass per user explicit request
        if (intersects[0].object.userData && intersects[0].object.userData.isWindow) {
          return;
        }

        // 1. Direct hit check on tagged interactive meshes (sunroof glass and headlights/blinkers)
        for (let i = 0; i < intersects.length; i++) {
          const hitObj = intersects[i].object;
          if (hitObj.userData && hitObj.userData.isWindow) {
            return;
          }
          if (hitObj.userData && hitObj.userData.isSunroof) {
            openSunroofModal();
            return;
          }
          const hName = (hitObj.name || '').toLowerCase();
          if (hName.includes('zuodeng') || hName.includes('deng_zuo')) {
            toggleFlasher('left');
            return;
          }
          if (hName.includes('youdeng') || hName.includes('deng_you')) {
            toggleFlasher('right');
            return;
          }
        }

        // 2. Traversal check for door structures, trunk, and light groups
        let obj = intersects[0].object;
        while (obj && obj !== carModel) {
          const name = (obj.name || '').toLowerCase();
          if (name.includes('weixiang')) { toggleTrunk(); return; }
          if (name.includes('zuoqianmen')) { toggleWindow('fl'); return; }
          if (name.includes('youqianmen')) { toggleWindow('fr'); return; }
          if (name.includes('zuohoumen')) { toggleWindow('rl'); return; }
          if (name.includes('youhoumen')) { toggleWindow('rr'); return; }
          if (name.includes('sunroof')) { openSunroofModal(); return; }
          if (name.includes('zuodeng') || name.includes('deng_zuo')) { toggleFlasher('left'); return; }
          if (name.includes('youdeng') || name.includes('deng_you')) { toggleFlasher('right'); return; }
          obj = obj.parent;
        }
      }
    });

    window.isCar3DFrozen = false;
    window.__cockpitPaused = !!window.__cockpitPaused;
    window.__avmOverlayActive = !!window.__avmOverlayActive;
    window.__uiRequested3DFreeze = false;
    let animLoopScheduled = false;
    let wasIdleStopped = true;
    function applyCar3DFrozen(frozen) {
      const wasFrozen = window.isCar3DFrozen;
      window.isCar3DFrozen = !!frozen;
      if (!window.isCar3DFrozen) {
        idleRenderCountdown = Math.max(idleRenderCountdown, 36);
        renderRequested = true;
        ensureAnimLoop();
        if (wasFrozen && renderer && scene && camera) {
          renderer.render(scene, camera);
        }
      }
    }
    window.forceWakeAndRender3D = function() {
      if (window.isCar3DFrozen) return;
      idleRenderCountdown = Math.max(idleRenderCountdown, 36);
      renderRequested = true;
      ensureAnimLoop();
      if (renderer && scene && camera) {
        renderer.render(scene, camera);
        setTimeout(function() {
          if (!window.isCar3DFrozen && renderer && scene && camera) {
            renderer.render(scene, camera);
          }
        }, 120);
      }
    };
    document.addEventListener('visibilitychange', function() {
      if (!document.hidden) {
        window.__cockpitPaused = false;
        syncCar3DPauseState();
        window.forceWakeAndRender3D();
      }
    });
    window.addEventListener('pageshow', function() {
      window.__cockpitPaused = false;
      syncCar3DPauseState();
      window.forceWakeAndRender3D();
    });
    function syncCar3DPauseState() {
      const pagePaused = document.body.classList.contains('journey-pip-active')
          || document.body.classList.contains('widgets-page-active');
      const shouldFreeze = window.__cockpitPaused || window.__avmOverlayActive
          || window.__uiRequested3DFreeze || pagePaused;
      applyCar3DFrozen(shouldFreeze);
      if (!shouldFreeze && typeof window.resumeShineWaveCardIfActive === 'function') {
        window.resumeShineWaveCardIfActive();
      }
    }
    window.setCar3DFrozen = function(frozen) {
      window.__uiRequested3DFreeze = !!frozen;
      syncCar3DPauseState();
    };
    window.render3DCarOnce = function() {
      if (renderer && scene && camera) {
        renderer.render(scene, camera);
      }
    };
    window.setCockpitPaused = function(paused) {
      window.__cockpitPaused = !!paused;
      syncCar3DPauseState();
    };
    window.setCameraOverlayActive = function(active) {
      window.__avmOverlayActive = !!active;
      syncCar3DPauseState();
    };
    syncCar3DPauseState();
    function ensureAnimLoop() {
      if (!window.isCar3DFrozen && !animLoopScheduled) {
        animLoopScheduled = true;
        if (wasIdleStopped && clock) {
          clock.getDelta();
        }
        wasIdleStopped = false;
        requestAnimationFrame(animate);
      }
    }
    window._wake3DLoop = ensureAnimLoop;
    function animate(now) {
      animLoopScheduled = false;
      if (window.isCar3DFrozen) {
        wasIdleStopped = true;
        return;
      }
      if (!now) now = performance.now();

      const dtRaw = clock ? clock.getDelta() : 0.016;
      const delta = Math.min(dtRaw > 0 ? dtRaw : 0.016, 0.05);

      flasherTimer += delta;
      if (flasherTimer > 0.45) {
        flasherTimer = 0;
        flasherPhase = !flasherPhase;
        if (state.flashers.left || state.flashers.right || state.flashers.hazard) {
          updateLightBarVisuals();
          updateLights();
          renderRequested = true;
        }
      }

      const speed = 4.5 * delta;
      for (let k in state.doors) {
        const diff = Math.abs(state.doors[k] - state.targetDoors[k]);
        if (diff > 0.005) {
          state.doors[k] = THREE.MathUtils.lerp(state.doors[k], state.targetDoors[k], speed);
        }
      }

      if (hinges.fl) hinges.fl.rotation.y = -state.doors.fl * 1.05;
      if (hinges.fr) hinges.fr.rotation.y = state.doors.fr * 1.05;
      if (hinges.rl) hinges.rl.rotation.y = -state.doors.rl * 0.95;
      if (hinges.rr) hinges.rr.rotation.y = state.doors.rr * 0.95;
            if (hinges.trunk) hinges.trunk.rotation.x = state.doors.trunk * 1.0;

      // 4-Wheel Rolling & Direction according to Gear (D/M=forward, R=reverse, P/N=stationary)
      const spdKmh = state.telemetry.speed || 0;
      let rollDir = 0;
      const g = (state.gear || '').toUpperCase();
      if (g === 'R') {
        rollDir = -1; // Reverse gear -> roll backward
      } else if (spdKmh > 0.5) {
        // Forward motion (D, M, S, or any speed > 0.5 km/h) -> roll forward!
        rollDir = 1;
      }

      if (rollDir !== 0 && spdKmh > 0.5) {
        // Correct physical angular velocity: omega = v (m/s) / R (m)
        // Wheel radius = 0.3195 m. Speed in m/s = spdKmh / 3.6
        const omega = (spdKmh / 3.6) / 0.3195; // radians per second
        const rDelta = rollDir * omega * delta;
        wheelRollAngle += rDelta;
        if (wheelPivots.flRoll) wheelPivots.flRoll.rotation.x = wheelRollAngle;
        if (wheelPivots.frRoll) wheelPivots.frRoll.rotation.x = wheelRollAngle;
        if (wheelPivots.rlRoll) wheelPivots.rlRoll.rotation.x = wheelRollAngle;
        if (wheelPivots.rrRoll) wheelPivots.rrRoll.rotation.x = wheelRollAngle;
      }

      // Front Wheels Steering Yaw (Left / Right according to steering column angle from CAN)
      let rawSwc = Number(state.swcAngle) || 0;
      if (rawSwc > 32767) {
        rawSwc -= 65536;
      }
      // CAN bus steering wheel angle is in 0.1 deg
      const swcDeg = rawSwc * 0.1;
      const absDeg = Math.abs(swcDeg);

      let targetSteerRad = 0;
      if (absDeg > 1.0) {
        // Road wheel steering ratio ~14.5:1, clamped to max 32 degrees (0.56 rad)
        const roadWheelDeg = Math.min(absDeg / 14.5, 32.0);

        // Direction mapping:
        // Left turn: positive swcDeg (> 0) / swcDirection === 1 -> Positive Y rotation (+1, Left in Three.js)
        // Right turn: negative swcDeg (< 0) / swcDirection === 2 -> Negative Y rotation (-1, Right in Three.js)
        let steerSign = 0;
        if (state.swcDirection === 2 || swcDeg < -0.5) {
          steerSign = -1; // Right turn
        } else if (state.swcDirection === 1 || swcDeg > 0.5) {
          steerSign = 1;  // Left turn
        }

        targetSteerRad = steerSign * roadWheelDeg * (Math.PI / 180.0);
      }

      window.__hasActiveSteer = Math.abs(currentSteerRad - targetSteerRad) > 0.002;
      currentSteerRad = THREE.MathUtils.lerp(currentSteerRad, targetSteerRad, Math.min(1.0, 22.0 * delta));
      if (wheelPivots.flSteer) wheelPivots.flSteer.rotation.y = currentSteerRad;
      if (wheelPivots.frSteer) wheelPivots.frSteer.rotation.y = currentSteerRad;

      // Window vertical sliding animation (smooth linear glide for realistic speed and easy midway tap)
      ['fl', 'fr', 'rl', 'rr'].forEach((key) => {
        const mesh = windowMeshes[key];
        if (!mesh) return;
        const target = targetWindowOffsets[key];
        const cur = windowOffsets[key];
        const diff = target - cur;
        if (Math.abs(diff) > 0.5) {
          const step = Math.sign(diff) * Math.min(Math.abs(diff), 110 * delta);
          windowOffsets[key] += step;
          mesh.position.y = windowOffsets[key];
        } else if (cur !== target) {
          windowOffsets[key] = target;
          mesh.position.y = target;
        }
      });

      // Sunroof tilt & slide animation (pivots upward 12 deg or slides back into roof)
      if (sunroofPivot) {
        const dRot = targetSunroofRotX - sunroofPivot.rotation.x;
        const dZ = targetSunroofPosZ - sunroofPivot.position.z;
        const dY = targetSunroofPosY - sunroofPivot.position.y;

        if (Math.abs(dRot) > 0.002) {
          sunroofPivot.rotation.x += dRot * Math.min(1.0, 6.0 * delta);
        } else if (sunroofPivot.rotation.x !== targetSunroofRotX) {
          sunroofPivot.rotation.x = targetSunroofRotX;
        }

        if (Math.abs(dZ) > 0.5) {
          sunroofPivot.position.z += dZ * Math.min(1.0, 6.0 * delta);
        } else if (sunroofPivot.position.z !== targetSunroofPosZ) {
          sunroofPivot.position.z = targetSunroofPosZ;
        }

        if (Math.abs(dY) > 0.2) {
          sunroofPivot.position.y += dY * Math.min(1.0, 6.0 * delta);
        } else if (sunroofPivot.position.y !== targetSunroofPosY) {
          sunroofPivot.position.y = targetSunroofPosY;
        }
      }

      // Deadline watchdog: guarantees auto-return after 10s even if setTimeout was delayed/throttled
      if (!isUserInteracting && !isCanvasTouched && !camTween && autoReturnDeadlineMs > 0 && Date.now() >= autoReturnDeadlineMs) {
        autoReturnDeadlineMs = 0;
        smoothReturnToHeroAngle();
      }

      // Smooth Camera Turntable Tween Interpolation (60fps Cosine Ease-In-Out Orbital Arc)
      if (camTween) {
        if (!camTween.startTime) camTween.startTime = now;
        const p = Math.max(0.0, Math.min(1.0, (now - camTween.startTime) / camTween.duration));
        const ease = 0.5 - 0.5 * Math.cos(p * Math.PI);
        const curAngle = camTween.startAngle + camTween.deltaAngle * ease;

        camera.position.x = CAM_ORBIT_RADIUS * Math.sin(curAngle);
        camera.position.z = CAM_ORBIT_RADIUS * Math.cos(curAngle);
        camera.position.y = CAM_ORBIT_Y;

        controls.target.copy(CAM_TARGET);
        camera.lookAt(CAM_TARGET);

        idleRenderCountdown = 60;
        renderRequested = true;
        if (p >= 1.0) {
          const endAngle = camTween.targetAngle;
          camera.position.x = CAM_ORBIT_RADIUS * Math.sin(endAngle);
          camera.position.z = CAM_ORBIT_RADIUS * Math.cos(endAngle);
          camera.position.y = CAM_ORBIT_Y;
          camera.lookAt(CAM_TARGET);
          camTween = null;
          if (controls) {
            controls.enableDamping = true;
            controls.update();
          }
        }
      } else if (controls) {
        controls.dampingFactor = Math.min(0.65, Math.max(0.14, 1.0 - Math.pow(1.0 - 0.24, delta * 60.0)));
        controls.update();
      }

      const hasActiveDoor = Math.abs(state.doors.fl - state.targetDoors.fl) > 0.005 || Math.abs(state.doors.fr - state.targetDoors.fr) > 0.005 || Math.abs(state.doors.rl - state.targetDoors.rl) > 0.005 || Math.abs(state.doors.rr - state.targetDoors.rr) > 0.005 || Math.abs(state.doors.trunk - state.targetDoors.trunk) > 0.005;
      const hasActiveSunroof = !!sunroofPivot && (Math.abs(sunroofPivot.rotation.x - targetSunroofRotX) > 0.002 || Math.abs(sunroofPivot.position.z - targetSunroofPosZ) > 0.5 || Math.abs(sunroofPivot.position.y - targetSunroofPosY) > 0.2);
      const hasFlashers = !!(state.flashers.left || state.flashers.right || state.flashers.hazard);
      const isMovingOrInteracting = isUserInteracting || isCanvasTouched || !!camTween || hasActiveDoor || hasActiveSunroof || !!window.__hasActiveSteer || spdKmh > 0.5;
      if (isMovingOrInteracting || renderRequested) {
        idleRenderCountdown = Math.max(idleRenderCountdown, 24);
        renderRequested = false;
      }
      if (isMovingOrInteracting || idleRenderCountdown > 0) {
        if (idleRenderCountdown > 0) idleRenderCountdown--;
        if (renderer && scene && camera) {
          renderer.render(scene, camera);
        }
      }
      if (isMovingOrInteracting || hasFlashers || idleRenderCountdown > 0 || renderRequested) {
        ensureAnimLoop();
      } else {
        wasIdleStopped = true;
      }
    }

    window.onResize3D = function() {
            const container = document.getElementById('canvas-container');
      if (!container || !renderer || !camera) return;
      const w = container.clientWidth || 960; const h = container.clientHeight || 680;
      camera.aspect = w / h;
      camera.setViewOffset(w, h, 0, 52, w, h);
      camera.updateProjectionMatrix();
      renderer.setSize(w, h);
    }

window.startCockpit3DEngine = function() {
  if (window.__cockpit3DEngineStarted) return;
  window.__cockpit3DEngineStarted = true;
  console.log('[Cockpit3D] Starting 3D WebGL Vehicle Scene...');
  if (!init3DScene()) {
    window.__cockpit3DEngineStarted = false;
    window.__webglRetryCount = (window.__webglRetryCount || 0) + 1;
    if (window.__webglRetryCount <= 6) {
      setTimeout(window.startCockpit3DEngine, 300);
    }
    return;
  }
  initPlateTexture();
  preloadHeadlightTextures();
  initDynamicPaintEngine();
  loadCarModel();
  idleRenderCountdown = 45;
  ensureAnimLoop();
  if (typeof window.onResize3D === 'function') window.onResize3D();
};
