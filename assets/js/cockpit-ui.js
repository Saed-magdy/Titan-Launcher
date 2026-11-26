/**
 * ============================================================================
 * COCKPIT UI — Instant 0ms HUD, Controls, Modals, Apps & CAN Telemetry (Phase 1)
 * ============================================================================
 * Executes immediately on first load without waiting for the 3D WebGL engine.
 */
    /* ========================================================
       INSTANT 0MS BUTTON TAP HANDLER (Zero delay)
       ======================================================== */
    function setupInstantButtons() {
      const buttonActions = [
        { id: 'btn-header-settings', fn: openSettings },
                { id: 'btn-lgt-turn-l', fn: () => toggleFlasher('left') },
        { id: 'btn-lgt-hazard', fn: () => toggleFlasher('hazard') },
        { id: 'btn-lgt-fog', fn: () => toggleLight('fog') },
        { id: 'btn-lgt-dipped', fn: () => toggleLight('dipped') },
        { id: 'btn-lgt-pos', fn: () => toggleLight('position') },
        { id: 'btn-lgt-main', fn: () => toggleLight('main') },
        { id: 'btn-lgt-turn-r', fn: () => toggleFlasher('right') },
                        { id: 'btn-win-toggle', fn: toggleAllWindows },
        { id: 'btn-side-trunk', fn: toggleTrunk },
        { id: 'btn-side-sunroof', fn: openSunroofModal },
        { id: 'btn-save-settings', fn: saveAllSettings },
        { id: 'tpms-badge', fn: openTpmsModal },
        { id: 'nav-radar-widget', fn: cycleNavRadarDemo }
      ];

      buttonActions.forEach(item => {
        const el = document.getElementById(item.id);
        if (!el) return;
        let lastPointerTime = 0;
        let lastTriggerTime = 0;

        const trigger = (e) => {
          if (e && e.type === 'click' && (Date.now() - lastPointerTime) < 600) {
            if (e.cancelable) e.preventDefault();
            e.stopPropagation();
            return;
          }
          if (e && (e.type === 'pointerdown' || e.type === 'touchstart')) {
            lastPointerTime = Date.now();
          }
          if (e && e.cancelable) e.preventDefault();
          if (e) e.stopPropagation();
          const now = Date.now();
          if (now - lastTriggerTime < 180) return;
          lastTriggerTime = now;
          el.classList.add('instant-active');
          setTimeout(() => el.classList.remove('instant-active'), 110);
          item.fn();
        };

        el.addEventListener('pointerdown', trigger, { passive: false });
        el.addEventListener('touchstart', trigger, { passive: false });
        el.onclick = trigger;
      });
    }

    /* ========================================================
       DIGITAL CLOCK & DATE (Top-Left HUD Column)
       ======================================================== */
    function startDigitalClock() {
      function updateClock() {
        const now = new Date();
        let hours = now.getHours();
        const minutes = String(now.getMinutes()).padStart(2, '0');
        const isPM = hours >= 12;
        hours = hours % 12;
        hours = hours ? hours : 12;
        const timeDigits = String(hours).padStart(2, '0') + ':' + minutes;

        const monthsEn = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'];
        const monthsFullEn = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
        const monthsAr = [
          '\u064A\u0646\u0627\u064A\u0631', '\u0641\u0628\u0631\u0627\u064A\u0631', '\u0645\u0627\u0631\u0633', '\u0623\u0628\u0631\u064A\u0644',
          '\u0645\u0627\u064A\u0648', '\u064A\u0648\u0646\u064A\u0648', '\u064A\u0648\u0644\u064A\u0648', '\u0623\u063A\u0633\u0637\u0633',
          '\u0633\u0628\u062A\u0645\u0628\u0631', '\u0623\u0643\u062A\u0648\u0628\u0631', '\u0646\u0648\u0641\u0645\u0628\u0631', '\u062F\u064A\u0633\u0645\u0628\u0631'
        ];
        const daysEn = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'];
        const daysFullEn = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
        const daysAr = [
          '\u0627\u0644\u0623\u062D\u062F', '\u0627\u0644\u0625\u062B\u0646\u064A\u0646', '\u0627\u0644\u062B\u0644\u0627\u062B\u0627\u0621', '\u0627\u0644\u0623\u0631\u0628\u0639\u0627\u0621',
          '\u0627\u0644\u062E\u0645\u064A\u0633', '\u0627\u0644\u062C\u0645\u0639\u0629', '\u0627\u0644\u0633\u0628\u062A'
        ];
        const isArabic = (state.lang === 'ar');
        const fmtDigits = (v) => (typeof window.toLangDigits === 'function' ? window.toLangDigits(v) : String(v));
        const dayStr = isArabic ? daysAr[now.getDay()] : daysEn[now.getDay()];
        const ampmStr = isArabic ? (isPM ? '\u0645' : '\u0635') : (isPM ? 'PM' : 'AM');
        const monthDayStr = isArabic
          ? fmtDigits(now.getDate() + ' ' + monthsAr[now.getMonth()])
          : (monthsEn[now.getMonth()] + ' ' + String(now.getDate()).padStart(2, '0'));

        const fullDateStr = isArabic
          ? fmtDigits(daysAr[now.getDay()] + '\u060C ' + now.getDate() + ' ' + monthsAr[now.getMonth()] + ' ' + now.getFullYear())
          : (daysFullEn[now.getDay()] + ', ' + now.getDate() + ' ' + monthsFullEn[now.getMonth()] + ' ' + now.getFullYear());

        const displayTimeDigits = fmtDigits(timeDigits);

        const elDate = document.getElementById('sb-date-text');
        if (elDate) elDate.innerText = fullDateStr;

        const elSbTime = document.getElementById('sb-clock-digits');
        const elSbAmpm = document.getElementById('sb-clock-ampm');
        if (elSbTime) elSbTime.innerText = displayTimeDigits;
        if (elSbAmpm) elSbAmpm.innerText = ampmStr;

        const elTime = document.getElementById('clock-digits');
        const elAmpm = document.getElementById('clock-ampm');
        const elMonthDay = document.getElementById('clock-month-day');
        const elWeekday = document.getElementById('clock-weekday');
        if (elTime) elTime.innerText = displayTimeDigits;
        if (elAmpm) elAmpm.innerText = ampmStr;
        if (elMonthDay) elMonthDay.innerText = monthDayStr;
        if (elWeekday) elWeekday.innerText = dayStr;

        const sbCenterClock = document.getElementById('sb-center-clock');
        if (sbCenterClock) {
          const isClockHidden = document.body.classList.contains('launcher-clock-hidden');
          const isLauncherClockVisible = !isClockHidden && elTime && (elTime.offsetParent !== null) && (window.getComputedStyle(elTime).display !== 'none');
          if (isLauncherClockVisible) {
            sbCenterClock.style.opacity = '0';
            sbCenterClock.style.visibility = 'hidden';
            sbCenterClock.style.pointerEvents = 'none';
          } else {
            sbCenterClock.style.opacity = '1';
            sbCenterClock.style.visibility = 'visible';
            sbCenterClock.style.pointerEvents = 'auto';
          }
        }
      }
      window.updateClock = updateClock;
      updateClock();
      setInterval(updateClock, 1000);
    }

    function updateLicensePlateText(text) {
      state.plateText = text;
      if (typeof updateLicensePlateCanvas === 'function') updateLicensePlateCanvas(text);
    }

    function setRoofOption(opt) {
      state.roofOption = opt;
      const bBlack = document.getElementById('roof-btn-black');
      const bBody = document.getElementById('roof-btn-body');
      if (opt === 'black') {
        if (bBlack) bBlack.classList.add('active');
        if (bBody) bBody.classList.remove('active');
      } else {
        if (bBody) bBody.classList.add('active');
        if (bBlack) bBlack.classList.remove('active');
      }
      if (typeof updateCarPaint === 'function') updateCarPaint();
    }

    function setLipOption(opt) {
      state.lipOption = opt;
      const bOrange = document.getElementById('lip-btn-orange');
      const bYellow = document.getElementById('lip-btn-yellow');
      if (bOrange) bOrange.classList.toggle('active', opt === 'orange');
      if (bYellow) bYellow.classList.toggle('active', opt === 'yellow');
      if (typeof updateCarPaint === 'function') updateCarPaint();
    }

        function setSeatOption(opt) {
      state.seatOption = opt || 'sport';
      const bSport = document.getElementById('seat-btn-sport');
      const bBlack = document.getElementById('seat-btn-black');
      const bYellow = document.getElementById('seat-btn-yellow');
      if (bSport) bSport.classList.toggle('active', state.seatOption === 'sport');
      if (bBlack) bBlack.classList.toggle('active', state.seatOption === 'black');
      if (bYellow) bYellow.classList.toggle('active', state.seatOption === 'yellow');
      if (typeof updateCarPaint === 'function') updateCarPaint();
    }
    window.setSeatOption = setSeatOption;

    function toggleTrunk() {
      // SAFETY GUARD: match CanBusClient.TRUNK_MAX_SPEED_KMH (5 km/h) so the 3D trunk NEVER animates open while moving!
      const liveSpeed = Number((state.telemetry && state.telemetry.speed) || 0);
      if (liveSpeed > 5) {
        sendCanCommand('VEHICLE_TUNK_CONTROL', 2); // Let Java trigger the bilingual safety toast
        return;
      }
      // Trunk opens; button triggers momentary pulse glow
      manualDoorLockUntil.trunk = Date.now() + 15000;
      state.targetDoors.trunk = 1;
      const btn = document.getElementById('btn-side-trunk');
      if (btn) {
        btn.classList.add('pulse-active');
        setTimeout(() => btn.classList.remove('pulse-active'), 1200);
      }
      if (typeof snapCameraToDoor === 'function') snapCameraToDoor('trunk'); // Smoothly rotate 3D camera to focus on trunk
      idleRenderCountdown = 120;
      requestRender();
      sendCanCommand('VEHICLE_TUNK_CONTROL', 2);
    }

    window.__onTrunkRefused = function () {
      manualDoorLockUntil.trunk = 0;
      state.targetDoors.trunk = (lastCanDoors && lastCanDoors.trunk) ? 1 : 0;
      const btn = document.getElementById('btn-side-trunk');
      if (btn) btn.classList.remove('pulse-active', 'active');
      if (typeof smoothReturnToHeroAngle === 'function') smoothReturnToHeroAngle();
      requestRender();
    };

                
    function resetSunroofInactivityTimer() {
      clearTimeout(sunroofInactivityTimer);
      sunroofInactivityTimer = setTimeout(() => {
        closeSunroofModal();
      }, 10000); // 10 seconds of inactivity auto-dismiss per user specification
    }

    function toggleSunroof() {
      openSunroofModal();
    }

    function openSunroofModal() {
      const modal = document.getElementById('sunroof-modal');
      const btn = document.getElementById('btn-side-sunroof');
      if (btn) {
        btn.classList.add('pulse-active');
        setTimeout(() => btn.classList.remove('pulse-active'), 1200);
      }
      if (modal) {
        const isClosed = (modal.style.display === 'none' || modal.style.display === '');
        if (isClosed) {
          modal.style.display = 'block';
          resetSunroofInactivityTimer();
          ['open', 'close', 'tilt', 'stop'].forEach(k => {
            const b = document.getElementById('btn-sr-' + k);
            if (b) b.classList.toggle('active', k === state.sunroof);
          });
        } else {
          closeSunroofModal();
        }
      }
      idleRenderCountdown = 60;
      requestRender();
    }

    function closeSunroofModal(e) {
      clearTimeout(sunroofInactivityTimer);
      const modal = document.getElementById('sunroof-modal');
      if (modal) modal.style.display = 'none';
      idleRenderCountdown = 60;
      requestRender();
    }

    function selectSunroofAction(cmd) {
      resetSunroofInactivityTimer();
      manualSunroofCooldown = Date.now() + 5000;
      controlSunroof(cmd);
      const b = document.getElementById('btn-sr-' + cmd);
      if (b) {
        b.classList.add('pulse-active');
        setTimeout(() => b.classList.remove('pulse-active'), 1200);
      }
    }

    function applySunroofVisualState(cmd, persist) {
      if (!cmd) return;
      const norm = String(cmd).toLowerCase();
      state.sunroof = norm;
      if (persist !== false) {
        try { localStorage.setItem('shine_sunroof_user_state', norm); } catch (e) {}
      }
      ['open', 'close', 'tilt', 'stop'].forEach(k => {
        const b = document.getElementById('btn-sr-' + k);
        if (b) b.classList.toggle('active', k === norm);
      });

      if (norm === 'close') {
        targetSunroofRotX = 0;
        targetSunroofPosZ = SUNROOF_PIVOT_Z;
        targetSunroofPosY = SUNROOF_PIVOT_Y;
      } else if (norm === 'open') {
        targetSunroofRotX = -0.015;
        targetSunroofPosZ = SUNROOF_PIVOT_Z - 480;
        targetSunroofPosY = SUNROOF_PIVOT_Y - 35;
      } else if (norm === 'tilt') {
        targetSunroofRotX = 12 * Math.PI / 180; // 12 degrees upward angle pivoting on front edge
        targetSunroofPosZ = SUNROOF_PIVOT_Z;
        targetSunroofPosY = SUNROOF_PIVOT_Y;
      }

      idleRenderCountdown = 90;
      requestRender();
    }

    function controlSunroof(cmd) {
      applySunroofVisualState(cmd, true);

      if (cmd === 'close') {
        sendCanCommand('SUNROOF_CONTROL', 3); // 3 = Close on Dongfeng C65
      } else if (cmd === 'open') {
        sendCanCommand('SUNROOF_CONTROL', 2); // 2 = Open on Dongfeng C65
      } else if (cmd === 'tilt') {
        sendCanCommand('SUNROOF_CONTROL', 1); // 1 = Tilt on Dongfeng C65
      } else if (cmd === 'stop') {
        if (sunroofPivot) {
          targetSunroofRotX = sunroofPivot.rotation.x;
          targetSunroofPosZ = sunroofPivot.position.z;
          targetSunroofPosY = sunroofPivot.position.y;
        }
        sendCanCommand('SUNROOF_CONTROL', 4); // 4 = Stop on Dongfeng C65
      }
    }

    // Instant tap opens sunroof menu directly

    function toggleWindow(doorKey) {
      if (!doorKey || windowStates[doorKey] === undefined) return;

      const curPos = windowOffsets[doorKey];
      const targetPos = targetWindowOffsets[doorKey];
      const isMoving = Math.abs(targetPos - curPos) > 6;

      const canCmd = (doorKey === 'fl') ? 'DRIVER_WINDOW_CONTROL' :
                     (doorKey === 'fr') ? 'PAS_WIDOW_CONTROL' :
                     (doorKey === 'rl') ? 'LEFT_BACK_WINDOW_CONTROL' : 'RIGHT_BACK_WINDOW_CONTROL';

      if (isMoving) {
        // Window is currently in motion -> TAP STOPS IT MIDWAY!
        targetWindowOffsets[doorKey] = curPos;
        sendCanCommand(canCmd, 3);
        windowStates[doorKey] = (curPos < -165);
      } else {
        // Window is stationary
        if (curPos < -20 && curPos > -310) {
          // Stopped midway -> TAP RESUMES IN SAME DIRECTION!
          if (windowLastDirection[doorKey] === 'down') {
            targetWindowOffsets[doorKey] = -330;
            windowStates[doorKey] = true;
            sendCanCommand(canCmd, 2);
          } else {
            targetWindowOffsets[doorKey] = 0;
            windowStates[doorKey] = false;
            sendCanCommand(canCmd, 1);
          }
        } else if (curPos >= -20) {
          // Fully closed / at top -> Roll down (Open)
          targetWindowOffsets[doorKey] = -330;
          windowLastDirection[doorKey] = 'down';
          windowStates[doorKey] = true;
          sendCanCommand(canCmd, 2);
        } else {
          // Fully open / at bottom -> Roll up (Close)
          targetWindowOffsets[doorKey] = 0;
          windowLastDirection[doorKey] = 'up';
          windowStates[doorKey] = false;
          sendCanCommand(canCmd, 1);
        }
      }

      updateAllWindowsButtonState();
      idleRenderCountdown = 120;
      requestRender();
    }

    function toggleAllWindows() {
      const btn = document.getElementById('btn-win-toggle');
      if (btn) {
        btn.classList.add('pulse-active');
        setTimeout(() => btn.classList.remove('pulse-active'), 1200);
      }
      const anyMoving = ['fl', 'fr', 'rl', 'rr'].some(k => Math.abs(targetWindowOffsets[k] - windowOffsets[k]) > 6);
      if (anyMoving) {
        // Stop all moving windows
        ['fl', 'fr', 'rl', 'rr'].forEach(k => {
          targetWindowOffsets[k] = windowOffsets[k];
          windowStates[k] = (windowOffsets[k] < -165);
        });
        sendCanCommand('ALL_WINDOW_CONTROL', 3);
      } else {
        const anyOpen = ['fl', 'fr', 'rl', 'rr'].some(k => windowOffsets[k] < -20);
        const newState = !anyOpen;
        ['fl', 'fr', 'rl', 'rr'].forEach((key) => {
          targetWindowOffsets[key] = newState ? -330 : 0;
          windowLastDirection[key] = newState ? 'down' : 'up';
          windowStates[key] = newState;
        });
        sendCanCommand('ALL_WINDOW_CONTROL', newState ? 2 : 1);
      }
      idleRenderCountdown = 120;
      requestRender();
    }

    function updateAllWindowsButtonState() {
      // Buttons do not retain permanent active glow per user specification
    }

    function controlWindows(cmd) {
      if (cmd === 'all_down') {
        ['fl', 'fr', 'rl', 'rr'].forEach(k => {
          targetWindowOffsets[k] = -330;
          windowLastDirection[k] = 'down';
          windowStates[k] = true;
        });
        sendCanCommand('ALL_WINDOW_CONTROL', 2);
      } else if (cmd === 'all_up') {
        ['fl', 'fr', 'rl', 'rr'].forEach(k => {
          targetWindowOffsets[k] = 0;
          windowLastDirection[k] = 'up';
          windowStates[k] = false;
        });
        sendCanCommand('ALL_WINDOW_CONTROL', 1);
      } else if (cmd === 'all_stop') {
        ['fl', 'fr', 'rl', 'rr'].forEach(k => {
          targetWindowOffsets[k] = windowOffsets[k];
          windowStates[k] = (windowOffsets[k] < -165);
        });
        sendCanCommand('ALL_WINDOW_CONTROL', 3);
      }
      updateAllWindowsButtonState();
      idleRenderCountdown = 120;
      requestRender();
    }

    
    function toggleLight(type) {
      manualLightCooldown = Date.now() + 3000;
      if (type === 'fog') {
        state.lights.fog = !state.lights.fog;
        sendCanCommand('FOG_LIGHT', state.lights.fog ? 2 : 1);
      } else if (type === 'dipped') {
        state.lights.dipped = !state.lights.dipped;
        if (state.lights.dipped) state.lights.main = false;
        sendCanCommand('LOW_BEAM', state.lights.dipped ? 2 : 1);
      } else if (type === 'position') {
        state.lights.position = !state.lights.position;
        sendCanCommand('PositionLamp', state.lights.position ? 2 : 1);
      } else if (type === 'main') {
        state.lights.main = !state.lights.main;
        if (state.lights.main) state.lights.dipped = false;
        sendCanCommand('HIGH_BEAM', state.lights.main ? 2 : 1);
      }
      updateLightBarVisuals();
      if (typeof updateLights === 'function') updateLights();
      idleRenderCountdown = 120;
      requestRender();
    }

    function toggleFlasher(side) {
      manualLightCooldown = Date.now() + 3000;
      if (side === 'left') {
        state.flashers.left = !state.flashers.left;
        if (state.flashers.left) {
          state.flashers.right = false;
          state.flashers.hazard = false;
        }
        sendCanCommand('LEFT_DIRECTION_LIGHT', state.flashers.left ? 2 : 1);
      } else if (side === 'right') {
        state.flashers.right = !state.flashers.right;
        if (state.flashers.right) {
          state.flashers.left = false;
          state.flashers.hazard = false;
        }
        sendCanCommand('RIGHT_DIRECTION_LIGHT', state.flashers.right ? 2 : 1);
      } else if (side === 'hazard') {
        state.flashers.hazard = !state.flashers.hazard;
        if (state.flashers.hazard) {
          state.flashers.left = false;
          state.flashers.right = false;
        }
        sendCanCommand('WARNING_LIGHT', state.flashers.hazard ? 2 : 1);
      }
      flasherPhase = true;
      flasherTimer = 0;
      updateLightBarVisuals();
      if (typeof updateLights === 'function') updateLights();
      idleRenderCountdown = 120;
      requestRender();
    }

    function updateLightBarVisuals() {
      const isLeftFlashing = (state.flashers.left || state.flashers.hazard) && flasherPhase;
      const isRightFlashing = (state.flashers.right || state.flashers.hazard) && flasherPhase;
      const isHazardFlashing = state.flashers.hazard && flasherPhase;

      const btnTurnL = document.getElementById('btn-lgt-turn-l');
      const btnHazard = document.getElementById('btn-lgt-hazard');
      const btnFog = document.getElementById('btn-lgt-fog');
      const btnDipped = document.getElementById('btn-lgt-dipped');
      const btnPos = document.getElementById('btn-lgt-pos');
      const btnMain = document.getElementById('btn-lgt-main');
      const btnTurnR = document.getElementById('btn-lgt-turn-r');

      if (btnTurnL) btnTurnL.classList.toggle('active', isLeftFlashing);
      if (btnTurnR) btnTurnR.classList.toggle('active', isRightFlashing);
      if (btnHazard) btnHazard.classList.toggle('active', isHazardFlashing);
      if (btnFog) btnFog.classList.toggle('active', state.lights.fog);
      if (btnDipped) btnDipped.classList.toggle('active', state.lights.dipped);
      if (btnPos) btnPos.classList.toggle('active', state.lights.position);
      if (btnMain) btnMain.classList.toggle('active', state.lights.main);
    }

    let tpmsCloseTimer = null;

    function openTpmsModal() {
      const modal = document.getElementById('tpms-modal');
      if (!modal) return;
      const box = modal.querySelector('.modal-box');
      if (tpmsCloseTimer) {
        clearTimeout(tpmsCloseTimer);
        tpmsCloseTimer = null;
      }
      if (typeof window.setCar3DFrozen === 'function') {
        window.setCar3DFrozen(true);
      }
      modal.style.display = 'flex';
      if (box) {
        box.classList.remove('twist-out');
        box.classList.remove('twist-in');
        void box.offsetWidth;
        box.classList.add('twist-in');
      }
    }

    function closeTpmsModal(e) {
      if (!e || e.target.id === 'tpms-modal' || (e.target.classList && e.target.classList.contains('modal-close'))) {
        const modal = document.getElementById('tpms-modal');
        if (!modal || modal.style.display === 'none') return;
        const box = modal.querySelector('.modal-box');
        if (box) {
          box.classList.remove('twist-in');
          box.classList.add('twist-out');
          if (tpmsCloseTimer) clearTimeout(tpmsCloseTimer);
          tpmsCloseTimer = setTimeout(function() {
            modal.style.display = 'none';
            box.classList.remove('twist-out');
            tpmsCloseTimer = null;
            if (typeof window.setCar3DFrozen === 'function') {
              window.setCar3DFrozen(false);
            }
          }, 230);
        } else {
          modal.style.display = 'none';
          if (typeof window.setCar3DFrozen === 'function') {
            window.setCar3DFrozen(false);
          }
        }
      }
    }

    function openTpmsResetConfirm() {
      const modal = document.getElementById('tpms-confirm-modal');
      if (modal) modal.style.display = 'flex';
    }

    function closeTpmsResetConfirm(e) {
      if (!e || e.target.id === 'tpms-confirm-modal' || e.target.classList.contains('modal-close')) {
        const modal = document.getElementById('tpms-confirm-modal');
        if (modal) modal.style.display = 'none';
      }
    }

    function confirmTpmsReset() {
      closeTpmsResetConfirm();
      sendCanCommand('TIRE_PRESSURE_RESET', 2);
      state.tpmsWarnings = { fl: false, fr: false, rl: false, rr: false };
      state.tpmsResetJustDone = true;
      updateTpmsOverallBadge();

      const overlayMsg = document.getElementById('tpms-card-overlay-msg');
      if (overlayMsg) {
        overlayMsg.style.display = 'block';
      }
      const cardStat = document.getElementById('tpms-card-status-text');
      if (cardStat) {
        cardStat.style.color = '#93c5fd';
        cardStat.innerText = state.lang === 'ar'
          ? '\u2713 \u062A\u0645 \u0625\u0631\u0633\u0627\u0644 \u0623\u0645\u0631 \u0625\u0639\u0627\u062F\u0629 \u0636\u0628\u0637 \u0627\u0644\u0625\u0637\u0627\u0631\u0627\u062A'
          : '\u2713 TPMS Reset Completed (CAN Bus Sent)';
      }
      showLauncherToast(
        state.lang === 'ar'
          ? '\u2713 \u062A\u0645 \u0625\u0631\u0633\u0627\u0644 \u0623\u0645\u0631 \u0625\u0639\u0627\u062F\u0629 \u0636\u0628\u0637 \u0627\u0644\u0625\u0637\u0627\u0631\u0627\u062A'
          : '\u2713 TPMS Reset Command Sent to CAN Bus',
        2400
      );

      setTimeout(() => {
        state.tpmsResetJustDone = false;
        if (overlayMsg) overlayMsg.style.display = 'none';
        updateTpmsOverallBadge();
      }, 6000);
    }

    function updateTpmsTire(tire, info) {
      if (!info) return;
      const isLow = (info.pressure !== undefined && info.pressure < 1.9) || !!info.warn;
      state.tpmsWarnings[tire] = isLow;
      updateTpmsOverallBadge();
    }

    function updateTpmsOverallBadge() {
      const hasWarn = state.tpmsWarnings.fl || state.tpmsWarnings.fr || state.tpmsWarnings.rl || state.tpmsWarnings.rr;
      const badge = document.getElementById('tpms-badge');
      if (badge) {
        badge.classList.toggle('tpms-warn-state', !!hasWarn);
      }
      const tpmsBox = document.querySelector('#tpms-modal .modal-box');
      if (tpmsBox) tpmsBox.classList.toggle('tpms-warn-state', !!hasWarn);
      const cardStat = document.getElementById('tpms-card-status-text');
      const cardDot = document.getElementById('tpms-status-dot');
      if (cardStat && !state.tpmsResetJustDone) {
        if (hasWarn) {
          cardStat.style.color = '#fca5a5';
          if (cardDot) { cardDot.style.background = '#fca5a5'; cardDot.style.boxShadow = '0 0 10px rgba(252,165,165,0.9)'; }
          cardStat.innerText = state.lang === 'ar' ? '\u26A0 \u062A\u062D\u0630\u064A\u0631 \u0627\u0644\u0636\u063A\u0637!' : 'WARNING!';
        } else {
          cardStat.style.color = '#86efac';
          if (cardDot) { cardDot.style.background = '#86efac'; cardDot.style.boxShadow = '0 0 10px rgba(134,239,172,0.85)'; }
          cardStat.innerText = state.lang === 'ar' ? '\u0627\u0644\u062D\u0627\u0644\u0629 \u0633\u0644\u064A\u0645\u0629' : 'NORMAL';
        }
      }
    }

    /* ========================================================
       6b. SMART NAV & RADAR COMPANION WIDGET (Google Maps + Radarbot)
       ======================================================== */
    const NAV_RADAR_PRESETS = [
      {
        mode: 'radar',
        brand: 'RADARBOT',
        dotColor: '#fca5a5',
        brandColor: '#fca5a5',
        speed: '90',
        title: '\u0631\u0627\u062F\u0627\u0631 \u0645\u062A\u062D\u0631\u0643 \u0669\u0660',
        dist: '100m',
        tag: '\u062A\u0647\u062F\u0626\u0629',
        badgeType: 'speed',
        arrowPath: ''
      },
      {
        mode: 'nav',
        brand: 'MAPS',
        dotColor: '#93c5fd',
        brandColor: '#93c5fd',
        speed: '',
        title: '\u064A\u0645\u064A\u0646 \u0628\u0639\u062F \u0662\u0660\u0660\u0645',
        dist: '200m',
        tag: '\u0645\u062D\u0648\u0631 \u0662\u0666',
        badgeType: 'arrow_right',
        arrowPath: 'M9 4h6a3 3 0 0 1 3 3v13M14 9l4-5 4 5'
      },
      {
        mode: 'standby',
        brand: 'ROAD ASSIST',
        dotColor: '#86efac',
        brandColor: '#86efac',
        speed: '',
        title: '\u0627\u0644\u0637\u0631\u064A\u0642 \u0633\u0627\u0644\u0643',
        dist: 'OK',
        tag: 'STANDBY',
        badgeType: 'standby',
        arrowPath: 'M12 2L4.5 20.29l.71.71L12 18l6.79 3 .71-.71z'
      }
    ];

    let currentNavRadarIdx = 0;

    function applyNavRadarPreset(preset) {
      const speedSign = document.getElementById('nr-speed-sign');
      const arrowSign = document.getElementById('nr-nav-arrow-sign');
      const speedVal = document.getElementById('nr-speed-val');
      const liveDot = document.getElementById('nr-live-dot');
      const brandLbl = document.getElementById('nr-brand-label');
      const titleEl = document.getElementById('nr-title');
      const distEl = document.getElementById('nr-dist-val');
      const tagEl = document.getElementById('nr-tag-end');
      const gaugeRow = document.getElementById('nr-gauge-row');

      if (brandLbl) {
        brandLbl.innerText = preset.brand;
        brandLbl.style.color = preset.brandColor;
      }
      if (liveDot) {
        liveDot.style.background = preset.dotColor;
        liveDot.style.boxShadow = '0 0 8px ' + preset.dotColor;
      }
      if (titleEl) titleEl.innerText = preset.title;
      if (distEl) {
        distEl.innerText = window.toLangDigits(preset.dist);
        distEl.style.color = preset.dotColor;
      }
      if (tagEl) tagEl.innerText = preset.tag;

      if (preset.badgeType === 'speed') {
        if (speedSign) speedSign.style.display = 'flex';
        if (arrowSign) arrowSign.style.display = 'none';
        if (speedVal) speedVal.innerText = window.toLangDigits(preset.speed);
        if (gaugeRow) {
          gaugeRow.style.background = 'rgba(252, 165, 165, 0.14)';
          gaugeRow.style.borderColor = 'rgba(252, 165, 165, 0.35)';
        }
      } else {
        if (speedSign) speedSign.style.display = 'none';
        if (arrowSign) {
          arrowSign.style.display = 'flex';
          const p = arrowSign.querySelector('path');
          if (p && preset.arrowPath) p.setAttribute('d', preset.arrowPath);
        }
        if (gaugeRow) {
          gaugeRow.style.background = 'rgba(0, 240, 255, 0.12)';
          gaugeRow.style.borderColor = 'rgba(0, 240, 255, 0.3)';
        }
      }
    }

    function cycleNavRadarDemo() {
      currentNavRadarIdx = (currentNavRadarIdx + 1) % NAV_RADAR_PRESETS.length;
      applyNavRadarPreset(NAV_RADAR_PRESETS[currentNavRadarIdx]);
      const p = NAV_RADAR_PRESETS[currentNavRadarIdx];
      showLauncherToast('Mode: ' + p.brand + ' - ' + p.title, 1200);
    }

    /* ========================================================
       8 & 9. QUICK APP DOCK, APP DRAWER & RAM CLEANER
       ======================================================== */
    function launchDockApp(appKey) {
      const isAr = (typeof state !== 'undefined' && state.lang === 'ar');
      if (appKey === 'vehicle') {
        openSettings();
        switchSettingsTab('locks');
        return;
      }
      if (appKey === 'cam360') {
        showLauncherToast(isAr ? '\u062C\u0627\u0631\u064A \u0641\u062A\u062D \u0643\u0627\u0645\u064A\u0631\u0627 \u0663\u0666\u0660\u00B0...' : 'Opening 360\u00B0 Surround Camera...', 1800);
        if (window.AndroidBridge && typeof window.AndroidBridge.openCamera360 === 'function') {
          window.AndroidBridge.openCamera360();
        }
        return;
      }

      if (window.AndroidBridge && typeof window.AndroidBridge.launchApp === 'function') {
        const ok = window.AndroidBridge.launchApp(appKey);
        if (!ok) {
          const dispName = window.localizeAppName ? window.localizeAppName(appKey, appKey, isAr) : appKey;
          const msg = isAr
            ? ('\u0627\u0644\u062A\u0637\u0628\u064A\u0642 \u063A\u064A\u0631 \u0645\u062B\u0628\u062A: ' + dispName)
            : ('App not found on device: ' + appKey.toUpperCase());
          showLauncherToast(msg, 2200);
        }
      } else {
        const dispName = window.localizeAppName ? window.localizeAppName(appKey, appKey, isAr) : appKey;
        showLauncherToast(isAr ? ('\u062C\u0627\u0631\u064A \u062A\u0634\u063A\u064A\u0644: ' + dispName) : ('Launching: ' + appKey.toUpperCase()), 1600);
      }
    }

    /* ========================================================
       CUSTOM QUICK APP SLOTS & SYSTEM INTENTS CONTROLLER
       ======================================================== */


    let _cachedAvailableApps = null;
    function getAllAvailableApps() {
      if (_cachedAvailableApps) return _cachedAvailableApps;
      let apps = [];
      if (window.AndroidBridge && typeof window.AndroidBridge.getInstalledAppsJson === 'function') {
        try {
          const raw = window.AndroidBridge.getInstalledAppsJson();
          if (raw) apps = JSON.parse(raw);
        } catch (e) {
          console.error('Failed to parse installed apps:', e);
        }
      }
      if (Array.isArray(apps)) {
        apps = apps.filter(a => a && a.pkg && a.pkg !== 'org.codeaurora.snapcam' && !a.pkg.toLowerCase().includes('snapcam'));
      }
      if (!apps || apps.length === 0) {
        apps = [
          { name: 'Vehicle Controls', nameAr: '\u0623\u0646\u0638\u0645\u0629 \u0648\u062A\u062D\u0643\u0645 \u0627\u0644\u0633\u064A\u0627\u0631\u0629', pkg: 'vehicle', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><defs><linearGradient id="g_vc" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="#5eead4"/><stop offset="100%" stop-color="#6ee7b7"/></linearGradient></defs><rect width="100" height="100" rx="22" fill="url(#g_vc)"/><path d="M28 40h44M28 60h44" stroke="#1e293b" stroke-width="5.5" stroke-linecap="round"/><circle cx="42" cy="40" r="7.5" fill="#fff"/><circle cx="60" cy="60" r="7.5" fill="#fff"/></svg>' },
          { name: '360\u00B0 Camera', nameAr: '\u0643\u0627\u0645\u064A\u0631\u0627 \u0663\u0666\u0660\u00B0 \u0627\u0644\u0645\u062D\u064A\u0637\u064A\u0629', pkg: 'cam360', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><rect width="100" height="100" rx="22" fill="#334155"/><circle cx="50" cy="50" r="28" fill="#7dd3fc"/><circle cx="50" cy="50" r="18" fill="#1e293b"/><circle cx="50" cy="50" r="8" fill="#bae6fd"/><path d="M24 38a34 34 0 0 1 52 0m0 24a34 34 0 0 1-52 0" fill="none" stroke="#bae6fd" stroke-width="4.5" stroke-linecap="round"/></svg>' },
          { name: 'Music Player', nameAr: '\u0645\u0634\u063A\u0644 \u0627\u0644\u0645\u0648\u0633\u064A\u0642\u0649', pkg: 'music', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><defs><linearGradient id="g_mus" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="#f9a8d4"/><stop offset="100%" stop-color="#c4b5fd"/></linearGradient></defs><rect width="100" height="100" rx="22" fill="url(#g_mus)"/><circle cx="40" cy="64" r="9" fill="#fff"/><circle cx="66" cy="54" r="9" fill="#fff"/><path d="M49 64V32l26-7v29" fill="none" stroke="#fff" stroke-width="7" stroke-linecap="round"/></svg>' },
          { name: 'Google Maps', nameAr: '\u062E\u0631\u0627\u0626\u0637 \u062C\u0648\u062C\u0644', pkg: 'maps', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><rect width="100" height="100" rx="22" fill="#f8fafc"/><path d="M28 22l22-7 22 7v56l-22-7-22 7z" fill="#e8eaed"/><path d="M50 15v56l22 7V22z" fill="#d2e3fc"/><path d="M50 30c-7.5 0-13.5 6-13.5 13.5 0 10.5 13.5 24.5 13.5 24.5s13.5-14 13.5-24.5C63.5 36 57.5 30 50 30z" fill="#fca5a5"/><circle cx="50" cy="43.5" r="5" fill="#93c5fd"/></svg>' },
          { name: 'Waze Navigation', nameAr: '\u0627\u0644\u0645\u0644\u0627\u062D\u0629 \u0648\u064A\u0632', pkg: 'com.waze', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><rect width="100" height="100" rx="22" fill="#93c5fd"/><ellipse cx="50" cy="50" rx="30" ry="24" fill="#fff"/><circle cx="40" cy="46" r="4.5" fill="#334155"/><circle cx="60" cy="46" r="4.5" fill="#334155"/><path d="M44 55q6 6 12 0" fill="none" stroke="#334155" stroke-width="3.5" stroke-linecap="round"/><circle cx="34" cy="74" r="7" fill="#334155"/><circle cx="66" cy="74" r="7" fill="#334155"/></svg>' },
          { name: 'Spotify Music', nameAr: '\u0633\u0628\u0648\u062A\u064A\u0641\u0627\u064A', pkg: 'com.spotify.music', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><rect width="100" height="100" rx="22" fill="#1e293b"/><circle cx="50" cy="50" r="38" fill="#86efac"/><path d="M30 42c13-3 27-2 40 4m-37 12c11-2 23-2 33 4m-30 11c9-2 18-1 26 3" fill="none" stroke="#1e293b" stroke-width="5.5" stroke-linecap="round"/></svg>' },
          { name: 'YouTube', nameAr: '\u064A\u0648\u062A\u064A\u0648\u0628', pkg: 'com.google.android.youtube', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><rect width="100" height="100" rx="22" fill="#fca5a5"/><path d="M40 34l26 16-26 16z" fill="#ffffff"/></svg>' },
          { name: 'Radarbot', nameAr: '\u0643\u0627\u0634\u0641 \u0627\u0644\u0631\u0627\u062F\u0627\u0631', pkg: 'radar', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><rect width="100" height="100" rx="22" fill="#7dd3fc"/><circle cx="50" cy="50" r="34" fill="none" stroke="#f0f9ff" stroke-width="4"/><path d="M54 24l-14 26h14l-6 26 18-30h-14z" fill="#fde68a"/></svg>' },
          { name: 'Video Player', nameAr: '\u0645\u0634\u063A\u0644 \u0627\u0644\u0641\u064A\u062F\u064A\u0648', pkg: 'video', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><defs><linearGradient id="g_vid" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="#fdba74"/><stop offset="100%" stop-color="#fca5a5"/></linearGradient></defs><rect width="100" height="100" rx="22" fill="url(#g_vid)"/><circle cx="50" cy="50" r="32" fill="none" stroke="#fff" stroke-width="4.5"/><path d="M43 36l22 14-22 14z" fill="#fff"/></svg>' },
          { name: 'FM Radio', nameAr: '\u0631\u0627\u062F\u064A\u0648 \u0625\u0641 \u0625\u0645', pkg: 'radio', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><defs><linearGradient id="g_rd" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="#fde68a"/><stop offset="100%" stop-color="#fdba74"/></linearGradient></defs><rect width="100" height="100" rx="22" fill="url(#g_rd)"/><circle cx="38" cy="56" r="15" fill="#334155"/><circle cx="68" cy="46" r="6.5" fill="#334155"/><circle cx="68" cy="64" r="6.5" fill="#334155"/><path d="M24 34l28-18" stroke="#fff" stroke-width="4.5" stroke-linecap="round"/></svg>' },
          { name: 'Bluetooth Phone', nameAr: '\u0647\u0627\u062A\u0641 \u0627\u0644\u0628\u0644\u0648\u062A\u0648\u062B', pkg: 'com.android.phone', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><rect width="100" height="100" rx="22" fill="#86efac"/><path d="M33 30c2-2 5-2 7 0l5 7c2 2 1 5-1 7l-3 3c3 7 9 13 16 16l3-3c2-2 5-3 7-1l7 5c2 2 2 5 0 7-3 4-9 7-14 5-15-5-26-16-31-31-2-5 1-11 4-13z" fill="#fff"/></svg>' },
          { name: 'Carplay Link', nameAr: '\u0622\u0628\u0644 \u0643\u0627\u0631\u0628\u0644\u0627\u064A', pkg: 'com.zjinnova.zlink', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><rect width="100" height="100" rx="22" fill="#1e293b"/><circle cx="34" cy="34" r="11" fill="#93c5fd"/><circle cx="66" cy="34" r="11" fill="#86efac"/><circle cx="34" cy="66" r="11" fill="#fca5a5"/><circle cx="66" cy="66" r="11" fill="#fde68a"/></svg>' },
          { name: 'Android Auto', nameAr: '\u0623\u0646\u062F\u0631\u0648\u064A\u062F \u0623\u0648\u062A\u0648', pkg: 'com.google.android.projection.gearhead', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><defs><linearGradient id="g_aa" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="#93c5fd"/><stop offset="100%" stop-color="#7dd3fc"/></linearGradient></defs><rect width="100" height="100" rx="22" fill="url(#g_aa)"/><path d="M50 22L24 70l26-9 26 9z" fill="#ffffff"/></svg>' },
          { name: 'Dashcam Recorder', nameAr: '\u0643\u0627\u0645\u064A\u0631\u0627 \u0627\u0644\u062A\u0633\u062C\u064A\u0644', pkg: 'com.dongfeng.dashcam', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><rect width="100" height="100" rx="22" fill="#334155"/><circle cx="50" cy="52" r="24" fill="#1e293b"/><circle cx="50" cy="52" r="15" fill="#475569"/><circle cx="50" cy="52" r="7.5" fill="#bae6fd"/><circle cx="74" cy="30" r="4.5" fill="#fca5a5"/></svg>' },
          { name: 'Tyre Pressure TPMS', nameAr: '\u0645\u0631\u0627\u0642\u0628\u0629 \u0636\u063A\u0637 \u0627\u0644\u0625\u0637\u0627\u0631\u0627\u062A', pkg: 'com.dongfeng.tpms', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><defs><linearGradient id="g_tp" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="#86efac"/><stop offset="100%" stop-color="#6ee7b7"/></linearGradient></defs><rect width="100" height="100" rx="22" fill="url(#g_tp)"/><path d="M26 34c4-9 40-9 48 0 6 9 6 32 0 42-8 9-40 9-48 0" fill="none" stroke="#fff" stroke-width="5.5"/><circle cx="50" cy="64" r="3.5" fill="#fff"/><path d="M50 42v15" stroke="#fff" stroke-width="5.5" stroke-linecap="round"/></svg>' },
          { name: 'Voice Assistant', nameAr: '\u0627\u0644\u0645\u0633\u0627\u0639\u062F \u0627\u0644\u0635\u0648\u062A\u064A', pkg: 'com.dongfeng.voice', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><defs><linearGradient id="g_va" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="#a5b4fc"/><stop offset="100%" stop-color="#d8b4fe"/></linearGradient></defs><rect width="100" height="100" rx="22" fill="url(#g_va)"/><circle cx="50" cy="50" r="32" fill="none" stroke="#fff" stroke-width="3"/><path d="M32 50h4m4-14v28m8-20v40m8-26v12m8-6h4" stroke="#fff" stroke-width="4.5" stroke-linecap="round"/></svg>' },
          { name: 'Sound Equalizer', nameAr: '\u0645\u0648\u0632\u0639 \u0627\u0644\u0635\u0648\u062A', pkg: 'com.dongfeng.eq', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><defs><linearGradient id="g_eq" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="#7dd3fc"/><stop offset="100%" stop-color="#a5b4fc"/></linearGradient></defs><rect width="100" height="100" rx="22" fill="url(#g_eq)"/><path d="M28 70V40m22 30V26m22 44V48" stroke="#fff" stroke-width="7.5" stroke-linecap="round"/></svg>' },
          { name: 'Ambient Lighting', nameAr: '\u0627\u0644\u0625\u0636\u0627\u0621\u0629 \u0627\u0644\u0645\u062D\u064A\u0637\u064A\u0629', pkg: 'com.dongfeng.ambient', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><defs><linearGradient id="g_amb" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="#fde68a"/><stop offset="100%" stop-color="#fdba74"/></linearGradient></defs><rect width="100" height="100" rx="22" fill="url(#g_amb)"/><circle cx="50" cy="44" r="17" fill="#fff"/><path d="M41 62h18m-14 6h10" stroke="#fff" stroke-width="4.5" stroke-linecap="round"/></svg>' },
          { name: 'File Manager', nameAr: '\u0645\u062F\u064A\u0631 \u0627\u0644\u0645\u0644\u0641\u0627\u062A', pkg: 'com.android.documentsui', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><rect width="100" height="100" rx="22" fill="#93c5fd"/><path d="M26 36h20l6 8h22v26H26z" fill="#fff"/></svg>' },
          { name: 'Chrome Browser', nameAr: '\u0645\u062A\u0635\u0641\u062D \u0643\u0631\u0648\u0645', pkg: 'com.android.chrome', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><rect width="100" height="100" rx="22" fill="#fff"/><circle cx="50" cy="50" r="32" fill="#fca5a5"/><circle cx="50" cy="50" r="18" fill="#93c5fd"/></svg>' },
          { name: 'Weather Forecast', nameAr: '\u0627\u0644\u0637\u0642\u0633 \u0648\u0627\u0644\u0645\u0646\u0627\u062E', pkg: 'com.dongfeng.weather', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><rect width="100" height="100" rx="22" fill="#7dd3fc"/><circle cx="45" cy="45" r="14" fill="#fde68a"/><path d="M36 62h28a12 12 0 0 0 0-24 16 16 0 0 0-28 6z" fill="#fff"/></svg>' },
          { name: 'Clock & Alarms', nameAr: '\u0627\u0644\u0633\u0627\u0639\u0629 \u0648\u0627\u0644\u0645\u0646\u0628\u0647', pkg: 'com.android.deskclock', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><rect width="100" height="100" rx="22" fill="#475569"/><circle cx="50" cy="50" r="30" fill="none" stroke="#fff" stroke-width="4"/><path d="M50 30v20l12 10" stroke="#fff" stroke-width="4" stroke-linecap="round"/></svg>' },
          { name: 'Gallery & Media', nameAr: '\u0645\u0639\u0631\u0636 \u0627\u0644\u0635\u0648\u0631', pkg: 'com.android.gallery3d', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><rect width="100" height="100" rx="22" fill="#f9a8d4"/><circle cx="42" cy="42" r="8" fill="#fff"/><path d="M28 70l18-20 12 12 10-10 14 18z" fill="#fff"/></svg>' },
          { name: 'Calculator', nameAr: '\u0627\u0644\u0622\u0644\u0629 \u0627\u0644\u062D\u0627\u0633\u0628\u0629', pkg: 'com.android.calculator2', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><rect width="100" height="100" rx="22" fill="#5eead4"/><path d="M35 35h10M35 65h10m0-5h-10M65 30v10m-5-5h10M65 65l6-6m-6 0l6 6" stroke="#1e293b" stroke-width="4" stroke-linecap="round"/></svg>' },
          { name: 'App Store', nameAr: '\u0645\u062A\u062C\u0631 \u0627\u0644\u062A\u0637\u0628\u064A\u0642\u0627\u062A', pkg: 'com.android.vending', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><rect width="100" height="100" rx="22" fill="#fff"/><path d="M30 26l40 24-40 24z" fill="#7dd3fc"/><path d="M30 26l22 34-22 14z" fill="#86efac"/><path d="M52 60l18-10-18-10z" fill="#fde68a"/><path d="M30 26l22 22 18-10z" fill="#fca5a5"/></svg>' },
          { name: 'System Settings', nameAr: '\u0625\u0639\u062F\u0627\u062F\u0627\u062A \u0627\u0644\u0646\u0638\u0627\u0645', pkg: 'com.android.settings', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><rect width="100" height="100" rx="22" fill="#475569"/><circle cx="50" cy="50" r="16" fill="#334155"/><path d="M50 18v10m0 44v10m-32-32h10m44 0h10m-13-23l-7 7m-30 30l-7 7m0-44l7 7m30 30l7 7" stroke="#cbd5e1" stroke-width="8" stroke-linecap="round"/></svg>' },
          { name: 'Device Care & RAM', nameAr: '\u062A\u0633\u0631\u064A\u0639 \u0648\u062A\u0646\u0638\u064A\u0641 \u0627\u0644\u0630\u0627\u0643\u0631\u0629', pkg: 'clean', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><defs><linearGradient id="g_cln" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="#86efac"/><stop offset="100%" stop-color="#6ee7b7"/></linearGradient></defs><rect width="100" height="100" rx="22" fill="url(#g_cln)"/><path d="M50 28v20m0 24v-8m-20-16h20m24 0h-8" stroke="#fff" stroke-width="6" stroke-linecap="round"/></svg>' }
        ];
      }
      if (Array.isArray(apps) && !apps.some(a => a && a.pkg === 'com.titan.pip')) {
        apps.unshift({ name: 'Titan PiP', nameAr: '\u062A\u0642\u0633\u064A\u0645 \u0627\u0644\u0634\u0627\u0634\u0629 PiP', pkg: 'com.titan.pip', svg: '<svg viewBox="0 0 100 100" width="76" height="76"><rect width="100" height="100" rx="22" fill="#07171d"/><rect x="18" y="18" width="64" height="30" rx="8" fill="#00f0ff"/><rect x="18" y="54" width="29" height="28" rx="7" fill="#e0f2fe"/><rect x="53" y="54" width="29" height="28" rx="7" fill="#93c5fd"/></svg>' });
      }
      _cachedAvailableApps = apps;
      return apps;
    }

    function bindScrollSafeTap(element, callback) {
      let startX = 0;
      let startY = 0;
      let isScrolling = false;
      let touchStartTime = 0;

      element.addEventListener('touchstart', (e) => {
        if (e.touches && e.touches.length === 1) {
          startX = e.touches[0].clientX;
          startY = e.touches[0].clientY;
          isScrolling = false;
          touchStartTime = Date.now();
        }
      }, { passive: true });

      element.addEventListener('touchmove', (e) => {
        if (e.touches && e.touches.length === 1) {
          const dx = Math.abs(e.touches[0].clientX - startX);
          const dy = Math.abs(e.touches[0].clientY - startY);
          if (dx > 10 || dy > 10) {
            isScrolling = true;
          }
        }
      }, { passive: true });

      element.addEventListener('touchend', (e) => {
        if (!isScrolling && (Date.now() - touchStartTime < 500)) {
          callback();
        }
      });

      element.addEventListener('click', (e) => {
        e.stopPropagation();
        if (Date.now() - touchStartTime < 650) {
          return;
        }
        callback();
      });
    }

    function triggerNavBack() {
      const appDrawer = document.getElementById('app-drawer-overlay');
      if (appDrawer && (appDrawer.classList.contains('active') || appDrawer.style.display === 'flex')) {
        closeAppDrawer();
        return;
      }
      const settingsModal = document.getElementById('settings-modal');
      if (settingsModal && settingsModal.style.display === 'flex') {
        closeSettings();
        return;
      }
      const sunroofModal = document.getElementById('sunroof-modal');
      if (sunroofModal && sunroofModal.style.display === 'block') {
        closeSunroofModal();
        return;
      }
      const tpmsModal = document.getElementById('tpms-modal');
      if (tpmsModal && tpmsModal.style.display === 'flex') {
        closeTpmsModal();
        return;
      }
      if (window.AndroidBridge && typeof window.AndroidBridge.dispatchSystemNav === 'function') {
        window.AndroidBridge.dispatchSystemNav('back');
      }
    }

    function triggerNavHome() {
      closeAppDrawer();
      closeSettings();
      closeSunroofModal();
      closeTpmsModal();
      if (window.AndroidBridge && typeof window.AndroidBridge.dispatchSystemNav === 'function') {
        window.AndroidBridge.dispatchSystemNav('home');
      }
    }

    function toggleOverview() {
      if (window.AndroidBridge && typeof window.AndroidBridge.dispatchSystemNav === 'function') {
        window.AndroidBridge.dispatchSystemNav('recents');
      } else {
        showLauncherToast(state.lang === 'ar' ? '\u0627\u0644\u062A\u0637\u0628\u064A\u0642\u0627\u062A \u0627\u0644\u0623\u062E\u064A\u0631\u0629' : 'Recent Apps Overview', 1500);
      }
    }

    function openAppDrawer() {
      const overlay = document.getElementById('app-drawer-overlay');
      const grid = document.getElementById('app-drawer-grid');
      const countEl = document.getElementById('app-drawer-count');
      if (!overlay || !grid) return;

      const apps = getAllAvailableApps();
      const isAr = (typeof state !== 'undefined' && state.lang === 'ar') || (document.documentElement.lang === 'ar');

      if (countEl) {
        countEl.textContent = window.toLangDigits(apps.length);
      }

      grid.innerHTML = '';
      apps.forEach(app => {
        const item = document.createElement('div');
        item.className = 'app-drawer-item';

        let iconHtml = '';
        if (app.icon && app.icon.startsWith('data:image')) {
          // escapeHtml(): app names come from installed apps, so treat them as plain text.
          iconHtml = `<img class="app-drawer-icon-img" src="${app.icon}" alt="${escapeHtml(app.name)}" />`;
        } else if (app.svg) {
          iconHtml = app.svg;
        } else if (app.iconGlyph) {
          iconHtml = `<span class="app-drawer-icon-fallback">${app.iconGlyph}</span>`;
        } else {
          const letter = (app.name || 'A').charAt(0).toUpperCase();
          iconHtml = `<span class="app-drawer-icon-fallback">${escapeHtml(letter)}</span>`;
        }

        const rawLabel = (isAr && app.nameAr) ? app.nameAr : app.name;
        const labelText = window.localizeAppName(app.pkg, rawLabel, isAr);
        item.innerHTML = `
          <div class="app-drawer-icon-wrap">${iconHtml}</div>
          <div class="app-drawer-label">${escapeHtml(labelText)}</div>
        `;
        bindScrollSafeTap(item, () => {
          closeAppDrawer();
          launchDockApp(app.pkg);
        });
        grid.appendChild(item);
      });

      overlay.style.display = 'flex';
      requestAnimationFrame(() => {
        overlay.classList.add('active');
      });
      requestRender();
    }

    function closeAppDrawer(e) {
      const overlay = document.getElementById('app-drawer-overlay');
      if (!overlay) return;
      if (!e || e.target === overlay || e.target.classList.contains('app-drawer-close-btn')) {
        overlay.classList.remove('active');
        setTimeout(() => {
          overlay.style.display = 'none';
          requestRender();
        }, 220);
      }
    }

    function cleanAllAppsMemory() {
      // Shows only what really happened: the real amount freed, no number when it is 0,
      // and an honest message when the cleanup could not run. Music and maps apps are kept open.
      const ar = state.lang === 'ar';
      let ok = false;
      let freedMb = 0;
      if (window.AndroidBridge && typeof window.AndroidBridge.cleanMemory === 'function') {
        try {
          const res = JSON.parse(window.AndroidBridge.cleanMemory());
          ok = !!(res && res.ok);
          freedMb = (res && res.freedMb > 0) ? Math.round(res.freedMb) : 0;
        } catch (e) {}
      }
      let msg;
      if (!window.AndroidBridge) {
        msg = ar ? '\u2713 \u062A\u0645 \u0625\u063A\u0644\u0627\u0642 \u062A\u0637\u0628\u064A\u0642\u0627\u062A \u0627\u0644\u062E\u0644\u0641\u064A\u0629 (\u0645\u0639\u0627\u064A\u0646\u0629)' : '\u2713 Background Apps Closed (preview)';
      } else if (!ok) {
        msg = ar ? '\u062A\u0639\u0630\u0651\u0631 \u062A\u0646\u0638\u064A\u0641 \u0627\u0644\u0630\u0627\u0643\u0631\u0629 \u0627\u0644\u0622\u0646' : "Couldn't free memory right now";
      } else if (freedMb > 0) {
        msg = ar
          ? ('\u2713 \u062A\u0645 \u0625\u063A\u0644\u0627\u0642 \u062A\u0637\u0628\u064A\u0642\u0627\u062A \u0627\u0644\u062E\u0644\u0641\u064A\u0629 \u0648\u062A\u062D\u0631\u064A\u0631 ' + window.toLangDigits(freedMb) + ' \u0645\u064A\u062C\u0627\u0628\u0627\u064A\u062A')
          : ('\u2713 Background Apps Closed \u2022 Freed ' + freedMb + ' MB RAM');
      } else {
        msg = ar ? '\u2713 \u062A\u0645 \u0625\u063A\u0644\u0627\u0642 \u062A\u0637\u0628\u064A\u0642\u0627\u062A \u0627\u0644\u062E\u0644\u0641\u064A\u0629' : '\u2713 Background Apps Closed';
      }
      showLauncherToast(msg, 2600);
    }

    // Back button handler for Android Home Launcher
    window.onLauncherBackPressed = function() {
      closeQuickAppModal();
      closeCreditsModal();
      closeSettings();
      closeTpmsResetConfirm();
      closeTpmsModal();
      closeSunroofModal();
      closeAppDrawer();
      if (typeof window.closeConnectivityModal === 'function') window.closeConnectivityModal(); if (typeof window.closeShineWaveStudio === 'function') window.closeShineWaveStudio();
    };

    
    /* ========================================================
       7. CUSTOMIZABLE QUICK APPS DECK (5 Slots + Real Logos)
       ======================================================== */
    const DEFAULT_QUICK_SLOTS = [
      { empty: true },
      { empty: true },
      { empty: true },
      { empty: true },
      { empty: true }
    ];

    let currentEditingSlot = -1;

    function getQuickAppSlots() {
      try {
        const saved = localStorage.getItem('shine_quick_apps_slots_v3');
        if (saved) {
          const parsed = JSON.parse(saved);
          if (Array.isArray(parsed) && parsed.length === 5) return parsed;
        }
      } catch (e) {}
      return DEFAULT_QUICK_SLOTS.slice();
    }

    function saveQuickAppSlots(slots) {
      try {
        localStorage.setItem('shine_quick_apps_slots_v3', JSON.stringify(slots));
      } catch (e) {}
    }

    function renderQuickAppsDeck() {
      const deck = document.getElementById('quick-apps-deck');
      if (!deck) return;
      const slots = getQuickAppSlots();
      const allApps = getAllAvailableApps();
      const isAr = (typeof state !== 'undefined' && state.lang === 'ar') || (document.documentElement.lang === 'ar');

      deck.innerHTML = '';
      slots.forEach((slot, index) => {
        const tile = document.createElement('div');
        tile.className = 'quick-app-tile';
        tile.dataset.slot = index;

        const isEmpty = !slot || slot.empty || !slot.pkg;

        if (isEmpty) {
          tile.innerHTML = `
            <div class="quick-app-plus-wrap">
              <svg viewBox="0 0 24 24" fill="none" stroke="#ffffff" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round">
                <line x1="12" y1="5" x2="12" y2="19"></line>
                <line x1="5" y1="12" x2="19" y2="12"></line>
              </svg>
            </div>
          `;

          const onSlotTap = (e) => {
            e.stopPropagation();
            if (e.cancelable) e.preventDefault();
            openQuickAppPicker(index);
          };

          tile.addEventListener('pointerdown', onSlotTap);
          tile.addEventListener('touchstart', onSlotTap, { passive: false });
          tile.onclick = onSlotTap;
        } else {
          let matched = allApps.find(a => a && a.pkg && (
            a.pkg.toLowerCase() === (slot.pkg || '').toLowerCase() ||
            a.pkg.toLowerCase().includes((slot.key || '').toLowerCase())
          ));

          let iconHtml = '';
          const rawLabel = (isAr && (slot.nameAr || (matched && matched.nameAr))) ? (slot.nameAr || matched.nameAr) : (slot.name || (matched && matched.name) || 'App');
          let label = window.localizeAppName(slot.pkg || slot.key, rawLabel, isAr);

          if (matched && matched.icon && matched.icon.startsWith('data:image')) {
            iconHtml = `<img class="quick-app-icon-img" src="${matched.icon}" alt="${escapeHtml(label)}" />`;
          } else if (matched && matched.svg) {
            iconHtml = matched.svg;
          } else {
            iconHtml = getOfficialAppSvg(slot.key || slot.pkg || '');
          }

          tile.innerHTML = `
            <div class="quick-app-icon-wrap">${iconHtml}</div>
            <span class="quick-app-label">${escapeHtml(label)}</span>
          `;

          let pressTimer = null;
          let isLongPress = false;
          let startX = 0, startY = 0;

          tile.addEventListener('touchstart', (e) => {
            isLongPress = false;
            if (e.touches && e.touches[0]) {
              startX = e.touches[0].clientX;
              startY = e.touches[0].clientY;
            }
            pressTimer = setTimeout(() => {
              isLongPress = true;
              openQuickAppPicker(index);
            }, 600);
          }, { passive: true });

          tile.addEventListener('touchmove', (e) => {
            if (!pressTimer) return;
            if (e.touches && e.touches[0]) {
              const dx = Math.abs(e.touches[0].clientX - startX);
              const dy = Math.abs(e.touches[0].clientY - startY);
              if (dx > 12 || dy > 12) {
                clearTimeout(pressTimer);
                pressTimer = null;
              }
            }
          }, { passive: true });

          tile.addEventListener('touchend', (e) => {
            if (pressTimer) {
              clearTimeout(pressTimer);
              pressTimer = null;
            }
            if (!isLongPress) {
              launchDockApp(slot.pkg || slot.key);
            }
          });

          tile.addEventListener('click', (e) => {
            if (!isLongPress) {
              launchDockApp(slot.pkg || slot.key);
            }
          });

          tile.addEventListener('contextmenu', (e) => {
            e.preventDefault();
            openQuickAppPicker(index);
          });
        }

        deck.appendChild(tile);
      });
    }

    function getOfficialAppSvg(key) {
      const k = (key || '').toLowerCase();
      if (k.includes('pip')) {
        return '<svg viewBox="0 0 100 100" width="56" height="56"><rect width="100" height="100" rx="22" fill="#07171d"/><rect x="18" y="18" width="64" height="30" rx="8" fill="#00f0ff"/><rect x="18" y="54" width="29" height="28" rx="7" fill="#e0f2fe"/><rect x="53" y="54" width="29" height="28" rx="7" fill="#93c5fd"/></svg>';
      }
      if (k.includes('maps') || k.includes('map')) {
        return '<svg viewBox="0 0 100 100" width="56" height="56"><path d="M50 10C33.4 10 20 23.4 20 40c0 22.5 30 50 30 50s30-27.5 30-50c0-16.6-13.4-30-30-30z" fill="#fca5a5"/><path d="M50 26c-7.7 0-14 6.3-14 14s6.3 14 14 14 14-6.3 14-14-6.3-14-14-14z" fill="#ffffff"/><circle cx="50" cy="40" r="8" fill="#93c5fd"/></svg>';
      }
      if (k.includes('radar')) {
        return '<svg viewBox="0 0 100 100" width="56" height="56"><circle cx="50" cy="50" r="44" fill="none" stroke="#a5f3fc" stroke-width="3" opacity="0.45"/><circle cx="50" cy="50" r="32" fill="none" stroke="#a5f3fc" stroke-width="3.5" opacity="0.75"/><circle cx="50" cy="50" r="19" fill="none" stroke="#a5f3fc" stroke-width="4"/><circle cx="50" cy="50" r="5" fill="#a5f3fc"/><path d="M57 14l-22 36h19l-9 36 28-42H53z" fill="#fde68a" filter="drop-shadow(0 0 6px rgba(253, 230, 138, 0.7))"/></svg>';
      }
      if (k.includes('vlc') || k.includes('video')) {
        return '<svg viewBox="0 0 100 100" width="56" height="56"><ellipse cx="50" cy="85" rx="38" ry="9" fill="#fdba74"/><ellipse cx="50" cy="82" rx="32" ry="7" fill="#fed7aa"/><path d="M26 82 L33 66 L67 66 L74 82 Z" fill="#fdba74"/><path d="M33 66 L38 52 L62 52 L67 66 Z" fill="#ffffff"/><path d="M38 52 L42 38 L58 38 L62 52 Z" fill="#fdba74"/><path d="M42 38 L45 26 L55 26 L58 38 Z" fill="#ffffff"/><path d="M45 26 L48 14 C48.8 11.5 51.2 11.5 52 14 L55 26 Z" fill="#fdba74"/><ellipse cx="50" cy="14" rx="3.5" ry="2" fill="#fed7aa"/></svg>';
      }
      if (k.includes('file') || k.includes('document')) {
        return '<svg viewBox="0 0 100 100" width="56" height="56"><path d="M14 26c0-4.5 3.5-8 8-8h22l8 8h34c4.5 0 8 3.5 8 8v38c0 4.5-3.5 8-8 8H22c-4.5 0-8-3.5-8-8V26z" fill="#93c5fd"/><rect x="28" y="28" width="44" height="28" rx="4" fill="#ffffff" opacity="0.95"/><line x1="36" y1="36" x2="64" y2="36" stroke="#7dd3fc" stroke-width="3.5" stroke-linecap="round"/><line x1="36" y1="44" x2="56" y2="44" stroke="#7dd3fc" stroke-width="3.5" stroke-linecap="round"/><path d="M10 42c0-3.5 3-6.5 6.5-6.5h67c3.5 0 6.5 3 6.5 6.5v24c0 4.5-3.5 8-8 8H18c-4.5 0-8-3.5-8-8V42z" fill="#60a5fa"/></svg>';
      }
      if (k.includes('youtube')) {
        return '<svg viewBox="0 0 100 100" width="56" height="56"><path d="M92 33c-1-4-4.2-7.2-8.2-8.2C76.5 22.5 50 22.5 50 22.5s-26.5 0-33.8 2.3c-4 1-7.2 4.2-8.2 8.2C5.7 40.3 5.7 50 5.7 50s0 9.7 2.3 17c1 4 4.2 7.2 8.2 8.2C23.5 77.5 50 77.5 50 77.5s26.5 0 33.8-2.3c4-1 7.2-4.2 8.2-8.2 2.3-7.3 2.3-17 2.3-17s0-9.7-2.3-17z" fill="#fca5a5"/><polygon points="42,37 66,50 42,63" fill="#ffffff"/></svg>';
      }
      return '<svg viewBox="0 0 100 100" width="56" height="56"><circle cx="50" cy="50" r="38" fill="none" stroke="#bae6fd" stroke-width="4" opacity="0.65"/><rect x="36" y="36" width="28" height="28" rx="6" fill="#bae6fd"/></svg>';
    }

    function openQuickAppPicker(slotIndex) {
      currentEditingSlot = slotIndex;
      const modal = document.getElementById('quick-app-select-modal');
      const grid = document.getElementById('quick-app-picker-grid');
      const titleEl = document.getElementById('quick-app-modal-title');
      if (!modal || !grid) return;

      const isAr = (typeof state !== 'undefined' && state.lang === 'ar') || (document.documentElement.lang === 'ar');
      if (titleEl) {
        titleEl.textContent = isAr ? 'اختر التطبيق' : 'Choose Application';
      }

      const allApps = getAllAvailableApps();

      grid.innerHTML = '';
      allApps.forEach(app => {
        const item = document.createElement('div');
        item.className = 'picker-app-item';

        let iconHtml = '';
        if (app.icon && app.icon.startsWith('data:image')) {
          iconHtml = `<img class="picker-app-icon" src="${app.icon}" alt="${escapeHtml(app.name)}" />`;
        } else if (app.svg) {
          iconHtml = app.svg;
        } else {
          iconHtml = getOfficialAppSvg(app.pkg || app.name);
        }

        const rawLabel = (isAr && app.nameAr) ? app.nameAr : app.name;
        const appLabel = window.localizeAppName(app.pkg, rawLabel, isAr);
        item.innerHTML = `
          ${iconHtml}
          <span class="picker-app-name">${escapeHtml(appLabel)}</span>
        `;

        item.addEventListener('click', () => {
          selectAppForSlot(currentEditingSlot, app);
        });

        grid.appendChild(item);
      });

      modal.style.display = 'flex';
      requestRender();
    }

    function selectAppForSlot(slotIndex, app) {
      if (slotIndex < 0 || slotIndex >= 5) return;
      const slots = getQuickAppSlots();
      const isAr = (typeof state !== 'undefined' && state.lang === 'ar') || (document.documentElement.lang === 'ar');
      const cleanNameEn = window.localizeAppName(app.pkg, app.name, false);
      const cleanNameAr = window.localizeAppName(app.pkg, app.nameAr || app.name, true);
      slots[slotIndex] = {
        empty: false,
        id: app.pkg || app.name,
        name: cleanNameEn,
        nameAr: cleanNameAr,
        pkg: app.pkg,
        key: app.pkg
      };
      saveQuickAppSlots(slots);
      closeQuickAppModal();
      renderQuickAppsDeck();
      const msg = isAr
        ? ('\u2713 \u062A\u0645 \u062A\u062E\u0635\u064A\u0635 \u0627\u0644\u062E\u0627\u0646\u0629 ' + window.toLangDigits(slotIndex + 1) + ': ' + cleanNameAr)
        : ('\u2713 Slot ' + (slotIndex + 1) + ' updated: ' + cleanNameEn);
      showLauncherToast(msg, 2400);
    }

    function closeQuickAppModal(e) {
      const modal = document.getElementById('quick-app-select-modal');
      if (!modal) return;
      if (!e || e.target === modal || e.target.classList.contains('modal-close')) {
        modal.style.display = 'none';
        currentEditingSlot = -1;
        requestRender();
      }
    }
    window.closeQuickAppModal = closeQuickAppModal;
    window.openQuickAppPicker = openQuickAppPicker;
    window.renderQuickAppsDeck = renderQuickAppsDeck;
/* ========================================================
       SETTINGS MODAL & PERSISTENCE
       ======================================================== */
    function openSettings() {
      const modal = document.getElementById('settings-modal');
      if (modal) modal.style.display = 'flex';
      // Halt 3D render completely while settings modal is open to ensure fluid touch response & zero GPU stutter
      if (typeof window.setCar3DFrozen === 'function') {
        window.setCar3DFrozen(true);
      }
    }

    function closeSettings(e) {
      if (!e || e.target.id === 'settings-modal' || e.target.classList.contains('modal-close')) {
        closeCreditsModal();
        const modal = document.getElementById('settings-modal');
        if (modal) modal.style.display = 'none';
        if (typeof window.setCar3DFrozen === 'function') {
          window.setCar3DFrozen(false);
          idleRenderCountdown = 60;
          requestRender();
        }
      }
    }

    function switchSettingsTab(tabKey) {
      const tabs = ['locks', 'launcher', 'vehicle'];
      tabs.forEach(t => {
        const btn = document.getElementById('tab-btn-' + t);
        const pane = document.getElementById('tab-pane-' + t);
        if (btn) btn.classList.toggle('active', t === tabKey);
        if (pane) pane.classList.toggle('active', t === tabKey);
      });
      idleRenderCountdown = 90;
      requestRender();
    }
    window.switchSettingsTab = switchSettingsTab;

    function setCarColor(hex, el) {
      if (hex === '#ffffff') hex = '#e2e8f0';
      state.carColor = hex;
      document.querySelectorAll('.color-dot').forEach(d => {
        d.classList.remove('active');
        const bg = (d.getAttribute('style') || '').toLowerCase();
        const oc = (d.getAttribute('onclick') || '').toLowerCase();
        if (bg.includes(hex.toLowerCase()) || oc.includes(hex.toLowerCase())) {
          d.classList.add('active');
        }
      });
      if (el) el.classList.add('active');
      if (typeof updateCarPaint === 'function') updateCarPaint();
    }
    window.setCarColor = setCarColor;
    window.openSettings = openSettings;
    window.closeSettings = closeSettings;

    function openCreditsModal(e) {
      if (e && e.stopPropagation) e.stopPropagation();
      const modal = document.getElementById('credits-modal');
      if (modal) modal.style.display = 'flex';
    }
    function closeCreditsModal(e) {
      if (!e || e.target.id === 'credits-modal' || e.target.classList.contains('credits-close-btn')) {
        const modal = document.getElementById('credits-modal');
        if (modal) modal.style.display = 'none';
      }
    }
    function triggerManualUpdateCheck() {
      const isAr = state.lang === 'ar';
      const isRu = state.lang === 'ru';
      const msg = isAr ? 'جاري فحص التحديثات من GitHub...' : (isRu ? 'Проверка обновлений GitHub...' : 'Checking GitHub for updates...');
      showLauncherToast(msg, 2500);
      if (window.AndroidBridge && typeof window.AndroidBridge.checkForUpdates === 'function') {
        window.AndroidBridge.checkForUpdates(false);
      } else {
        // Desktop preview only (no car connected, so no real update check happens).
        // No version number here on purpose: the real number lives only in AndroidManifest.xml.
        setTimeout(() => {
          showLauncherToast(isAr ? '✓ أنت تستخدم أحدث إصدار (معاينة)' : (isRu ? '✓ У вас актуальная версия (предпросмотр)' : '✓ You are on the latest version (preview)'), 2500);
        }, 1200);
      }
    }
    window.triggerManualUpdateCheck = triggerManualUpdateCheck;

    window.openCreditsModal = openCreditsModal;
    window.closeCreditsModal = closeCreditsModal;

    function setDefaultPageOption(page) {
      const normalized = (page === 'widgets') ? 'widgets' : 'main';
      state.defaultPage = normalized;
      try { localStorage.setItem('shine_default_page', normalized); } catch (e) {}
      const btnMain = document.getElementById('btn-def-page-main');
      const btnWidgets = document.getElementById('btn-def-page-widgets');
      if (btnMain) btnMain.classList.toggle('active', normalized === 'main');
      if (btnWidgets) btnWidgets.classList.toggle('active', normalized === 'widgets');
    }
    window.setDefaultPageOption = setDefaultPageOption;

    function setDefaultLauncher() {
      if (window.AndroidBridge && typeof window.AndroidBridge.setDefaultLauncher === 'function') {
        window.AndroidBridge.setDefaultLauncher();
        showLauncherToast(state.lang === 'ar' ? '\u062C\u0627\u0631\u064A \u0641\u062A\u062D \u062E\u064A\u0627\u0631\u0627\u062A \u062A\u0639\u064A\u064A\u0646 \u0627\u0644\u0645\u0634\u063A\u0644 \u0627\u0644\u0627\u0641\u062A\u0631\u0627\u0636\u064A...' : 'Opening Default Launcher settings...');
      } else {
        showLauncherToast(state.lang === 'ar' ? '\u062A\u0639\u064A\u064A\u0646 \u0627\u0644\u0645\u0634\u063A\u0644 \u0627\u0644\u0627\u0641\u062A\u0631\u0627\u0636\u064A \u0645\u062A\u0627\u062D \u0639\u0644\u0649 \u0627\u0644\u062C\u0647\u0627\u0632 \u0641\u0642\u0637' : 'Default launcher intent simulated (AndroidBridge required)');
      }
    }

    function onAutoBootChanged(enabled) {
      state.autoBoot = enabled;
      if (window.AndroidBridge && typeof window.AndroidBridge.setAutoBoot === 'function') {
        window.AndroidBridge.setAutoBoot(enabled);
      }
      showLauncherToast((state.lang === 'ar' ? '\u0627\u0644\u062A\u0634\u063A\u064A\u0644 \u0627\u0644\u062A\u0644\u0642\u0627\u0626\u064A: ' : 'Auto-Start: ') + (enabled ? (state.lang === 'ar' ? '\u0645\u0641\u0639\u0644' : 'ON') : (state.lang === 'ar' ? '\u0645\u0639\u0637\u0644' : 'OFF')));
    }

    function onWatchdogChanged(enabled) {
      if (window.AndroidBridge && typeof window.AndroidBridge.setWatchdogEnabled === 'function') {
        window.AndroidBridge.setWatchdogEnabled(enabled);
      }
      showLauncherToast((state.lang === 'ar' ? '\u062D\u0645\u0627\u064A\u0629 \u0625\u0639\u0627\u062F\u0629 \u0627\u0644\u062A\u0634\u063A\u064A\u0644: ' : 'Crash Loop Breaker: ') + (enabled ? (state.lang === 'ar' ? '\u0645\u0641\u0639\u0644' : 'ENABLED') : (state.lang === 'ar' ? '\u0645\u0639\u0637\u0644' : 'DISABLED')));
    }

    function saveAllSettings() {
      const chkSpeed = document.getElementById('chk-speed-lock');
      const chkWalk = document.getElementById('chk-walk-lock');
      const chkOff = document.getElementById('chk-off-unlock');
      const chkAutoBoot = document.getElementById('chk-auto-boot');
      const chkWatchdog = document.getElementById('chk-watchdog');

      const settings = {
        lang: state.lang,
        numFormat: state.numFormat || 'western',
        defaultPage: state.defaultPage || 'main',
        carColor: state.carColor,
        roofOption: state.roofOption,
        lipOption: state.lipOption,
        seatOption: state.seatOption,
        plateText: state.plateText,
        drivingTipEnabled: (function () { const c = document.getElementById('chk-drive-tip'); return c ? c.checked : true; })(),
        speedLock: chkSpeed ? chkSpeed.checked : false,
        walkLock: chkWalk ? chkWalk.checked : false,
        offUnlock: chkOff ? chkOff.checked : false,
        autoBoot: chkAutoBoot ? chkAutoBoot.checked : true,
        watchdogEnabled: chkWatchdog ? chkWatchdog.checked : true
      };

      const jsonStr = JSON.stringify(settings);
      try {
        localStorage.setItem('shine_cockpit_settings', jsonStr);
        localStorage.setItem('shine_cockpit_lang', state.lang);
      } catch(e) {}

      if (window.AndroidBridge && window.AndroidBridge.saveSettings) {
        window.AndroidBridge.saveSettings(jsonStr);
      }
      if (window.AndroidBridge && window.AndroidBridge.setAutoBoot) {
        window.AndroidBridge.setAutoBoot(settings.autoBoot);
      }
      if (window.AndroidBridge && window.AndroidBridge.setWatchdogEnabled) {
        window.AndroidBridge.setWatchdogEnabled(settings.watchdogEnabled);
      }

      toggleVehicleConfig('speed_lock', settings.speedLock);
      toggleVehicleConfig('walk_lock', settings.walkLock);
      toggleVehicleConfig('off_unlock', settings.offUnlock);

      const saveBtn = document.getElementById('btn-save-settings');
      const saveLbl = document.getElementById('lbl-save-settings');
      const toast = document.getElementById('save-toast-msg');
      const dict = I18N[state.lang] || I18N['ar'];

      if (saveBtn) saveBtn.classList.add('saved-success');
      if (saveLbl) saveLbl.innerText = dict['save-success-btn'] || '\u2713 Saved';
      if (toast) {
        toast.innerText = dict['save-toast-txt'] || '\u2713 Settings saved';
        toast.style.display = 'block';
      }

      setTimeout(() => {
        if (saveBtn) saveBtn.classList.remove('saved-success');
        if (saveLbl) saveLbl.innerText = dict['lbl-save-settings'] || 'Save Settings';
        if (toast) toast.style.display = 'none';
        closeSettings();
      }, 1000);
    }

    function onLockToggleChanged(key, isChecked) {
      updateLockPillUi(key, isChecked);
      toggleVehicleConfig(key, isChecked);
    }

    function updateLockPillUi(key, isChecked) {
      let pillId = '', statId = '';
      if (key === 'speed_lock') { pillId = 'pill-lock-speed'; statId = 'stat-lock-speed'; }
      else if (key === 'walk_lock') { pillId = 'pill-lock-walk'; statId = 'stat-lock-walk'; }
      else if (key === 'off_unlock') { pillId = 'pill-lock-off'; statId = 'stat-lock-off'; }

      const isAr = (typeof state !== 'undefined' && state.lang === 'ar');
      const pill = document.getElementById(pillId);
      const stat = document.getElementById(statId);
      if (pill) {
        if (isChecked) {
          pill.innerText = isAr ? '\u0645\u0641\u0639\u0644' : 'ON';
          pill.className = 'lock-pill-badge active-on';
        } else {
          pill.innerText = isAr ? '\u0645\u0639\u0637\u0644' : 'OFF';
          pill.className = 'lock-pill-badge';
        }
      }
      if (stat) {
        stat.innerText = isChecked
          ? (isAr ? '\u0627\u0644\u0634\u0628\u0643\u0629: \u0645\u0641\u0639\u0644' : 'CAN: ACTIVE')
          : (isAr ? '\u0627\u0644\u0634\u0628\u0643\u0629: \u0645\u0639\u0637\u0644' : 'CAN: OFF');
      }
    }

    let cachedCanConnected = false;
    function updateCanStatusBadge(isConnected) {
      cachedCanConnected = !!isConnected;
      const badge = document.getElementById('canbus-status-badge');
      const txt = document.getElementById('canbus-status-text');
      if (!badge || !txt) return;
      const isAr = (typeof state !== 'undefined' && state.lang === 'ar');
      if (isConnected) {
        badge.className = 'canbus-status-badge connected';
        txt.innerText = isAr ? '\u0645\u062A\u0635\u0644 \u0628\u0627\u0644\u0633\u064A\u0627\u0631\u0629' : 'CANBUS CONNECTED';
      } else {
        badge.className = 'canbus-status-badge disconnected';
        txt.innerText = isAr ? '\u063A\u064A\u0631 \u0645\u062A\u0635\u0644' : 'NOT CONNECTED';
      }
    }

    function applySavedSettingsJson(jsonStr) {
      if (!jsonStr) return;
      try {
        const s = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;
        if (s.lang) setLanguage(s.lang);
        if (s.numFormat) {
          if (typeof setNumberFormat === 'function') setNumberFormat(s.numFormat);
        } else {
          try {
            var savedFmt = localStorage.getItem('shine_cockpit_num_format');
            if (savedFmt && typeof setNumberFormat === 'function') setNumberFormat(savedFmt);
          } catch(e) {}
        }
        if (s.defaultPage) setDefaultPageOption(s.defaultPage);
        if (s.drivingTipEnabled !== undefined) {
          const chkTip = document.getElementById('chk-drive-tip');
          if (chkTip) chkTip.checked = s.drivingTipEnabled !== false;
          onDrivingTipToggle();
        }
        if (s.carColor) setCarColor(s.carColor === '#ffffff' ? '#e2e8f0' : s.carColor);
        if (s.roofOption) setRoofOption(s.roofOption);
        if (s.lipOption) setLipOption(s.lipOption);
        if (s.seatOption) setSeatOption(s.seatOption);
        if (s.plateText) {
          state.plateText = s.plateText;
          const pInput = document.getElementById('plate-input');
          if (pInput) pInput.value = s.plateText;
          updateLicensePlateText(s.plateText);
        }
        if (s.speedLock !== undefined) {
          const chk = document.getElementById('chk-speed-lock');
          if (chk) chk.checked = !!s.speedLock;
          updateLockPillUi('speed_lock', !!s.speedLock);
        }
        if (s.walkLock !== undefined) {
          const chk = document.getElementById('chk-walk-lock');
          if (chk) chk.checked = !!s.walkLock;
          updateLockPillUi('walk_lock', !!s.walkLock);
        }
        if (s.offUnlock !== undefined) {
          const chk = document.getElementById('chk-off-unlock');
          if (chk) chk.checked = !!s.offUnlock;
          updateLockPillUi('off_unlock', !!s.offUnlock);
        }
        if (s.autoBoot !== undefined) {
          state.autoBoot = !!s.autoBoot;
          const chk = document.getElementById('chk-auto-boot');
          if (chk) chk.checked = !!s.autoBoot;
        }
        if (s.watchdogEnabled !== undefined) {
          const chkWd = document.getElementById('chk-watchdog');
          if (chkWd) chkWd.checked = !!s.watchdogEnabled;
        }
      } catch(e) {
        console.error('[Settings] Error applying saved settings JSON:', e);
      }
    }
    window.applySavedSettingsJson = applySavedSettingsJson;

    function loadSavedSettings() {
      let jsonStr = '';
      if (window.AndroidBridge && window.AndroidBridge.getSettings) {
        try { jsonStr = window.AndroidBridge.getSettings(); } catch(e) {}
      }
      if (!jsonStr) {
        try { jsonStr = localStorage.getItem('shine_cockpit_settings') || ''; } catch(e) {}
      }
      if (jsonStr) {
        window.applySavedSettingsJson(jsonStr);
      } else {
        let initialLang = state.lang || 'en';
        try {
          const legacyLang = localStorage.getItem('shine_cockpit_lang');
          if (legacyLang) initialLang = legacyLang;
        } catch(e) {}
        setLanguage(initialLang);
      }
      if (!state.defaultPage) {
        try {
          const savedDefPage = localStorage.getItem('shine_default_page');
          if (savedDefPage) setDefaultPageOption(savedDefPage);
        } catch(e) {}
      }
      if (!window.__initialDefaultPageApplied) {
        window.__initialDefaultPageApplied = true;
      }
      if (window.AndroidBridge && typeof window.AndroidBridge.isWatchdogEnabled === 'function') {
        try {
          const wd = window.AndroidBridge.isWatchdogEnabled();
          const chkWd = document.getElementById('chk-watchdog');
          if (chkWd) chkWd.checked = wd;
        } catch(e) {}
      }

      // Initialize persistent sunroof state (default to 'close')
      try {
        const savedSr = localStorage.getItem('shine_sunroof_user_state') || 'close';
        applySunroofVisualState(savedSr, false);
      } catch (e) {}
    }

    function toggleVehicleConfig(key, val) {
      if (key === 'speed_lock') sendCanCommand('AutomaticLocking', val ? 3 : 1);
      else if (key === 'walk_lock') sendCanCommand('WALK_LOCK_SWITCH', val ? 1 : 0);
      else if (key === 'off_unlock') sendCanCommand('ShutdownAutoUlockSwitch', val ? 1 : 0);
    }

    function sendCanCommand(command, data) {
      if (window.AndroidBridge && window.AndroidBridge.postCanCommand) {
        window.AndroidBridge.postCanCommand(command, JSON.stringify(data));
      }
    }

    /* ========================================================
       CAN TELEMETRY & INTERACTIVE VERTICAL INDICATORS
       ======================================================== */
    let lastInstFuelUpdate = 0;
    let cachedInstFuel = 0.0;

    /* ========================================================
       GENTLE DRIVING TIP
       A thin bar under the consumption. Its length follows the fuel use,
       smoothed over about 3 seconds so it does not flicker: short and green
       when driving smoothly, long and yellow on hard acceleration (12 L/100
       or more). Hidden when the car is stopped. No numbers, popups or sounds.
       Setting: drivingTipEnabled (on by default).
       ======================================================== */
    var drivingTipSmoothed = -1;
    var drivingTipLastMs = 0;
    var DRIVING_TIP_HARD = 12;   // L/100 km from which the bar turns yellow
    var DRIVING_TIP_FULL = 20;   // L/100 km at which the bar is full

    function drivingTipEnabled() {
      const c = document.getElementById('chk-drive-tip');
      return !c || c.checked;
    }

    function updateDrivingTip(instFuel, speed) {
      const track = document.getElementById('drive-tip-track');
      const fill = document.getElementById('drive-tip-fill');
      if (!track || !fill) return;
      const v = Number(instFuel);
      if (!drivingTipEnabled() || !(speed > 0) || !isFinite(v) || v < 0) {
        track.classList.remove('visible');
        drivingTipSmoothed = -1;
        return;
      }
      const now = Date.now();
      if (drivingTipSmoothed < 0) {
        drivingTipSmoothed = v;
      } else {
        const dt = Math.min(5000, Math.max(0, now - drivingTipLastMs));
        const k = 1 - Math.exp(-dt / 3000);
        drivingTipSmoothed += (v - drivingTipSmoothed) * k;
      }
      drivingTipLastMs = now;
      const ratio = Math.max(0.05, Math.min(1, drivingTipSmoothed / DRIVING_TIP_FULL));
      fill.style.width = Math.round(ratio * 100) + '%';
      fill.classList.toggle('hard', drivingTipSmoothed >= DRIVING_TIP_HARD);
      track.classList.add('visible');
    }
    window.updateDrivingTip = updateDrivingTip;

    function onDrivingTipToggle() {
      if (!drivingTipEnabled()) {
        const track = document.getElementById('drive-tip-track');
        if (track) track.classList.remove('visible');
        drivingTipSmoothed = -1;
      }
    }
    window.onDrivingTipToggle = onDrivingTipToggle;
    let lastRangeUpdate = 0;
    let cachedRange = 290;
    let cachedCurrentSpeed = 0;
    let cachedFuelRemain = 17.1;
    let cachedFuelPct = 31;
    let cachedAmbient = 24;
    let cachedAvgFuel = 6.5;
    let cachedCoolant = 90;

    function updateAmbientDisplay(temp) {
      cachedAmbient = temp;
      const aEl = document.getElementById('val-ambient');
      if (aEl) aEl.innerText = window.toLangDigits(temp);
      const barAmb = document.getElementById('bar-ambient');
      if (barAmb) {
        // Interactive horizontal bar: maps -5C..50C to 8%..100% width
        const pct = Math.max(8, Math.min(100, Math.round(((temp + 5) / 55) * 100)));
        barAmb.style.height = '100%';
        barAmb.style.width = pct + '%';
        if (temp >= 36) {
          barAmb.style.background = 'linear-gradient(90deg, #fdba74, #fca5a5)';
          barAmb.style.boxShadow = '0 0 8px rgba(253, 186, 116, 0.55)';
        } else {
          barAmb.style.background = 'linear-gradient(90deg, #93c5fd, #7dd3fc, #bae6fd)';
          barAmb.style.boxShadow = '0 0 8px rgba(186, 230, 253, 0.55)';
        }
      }
    }

    function updateInstFuelDisplay() {
      const fInstEl = document.getElementById('val-inst-fuel');
      const uInstEl = document.getElementById('unit-inst-fuel');
      if (!fInstEl) return;
      const isAr = (state.lang === 'ar');
      if (cachedCurrentSpeed <= 0) {
        if (cachedInstFuel > 0.05 && cachedInstFuel <= 5.0) {
          fInstEl.innerText = window.toLangDigits(cachedInstFuel.toFixed(1));
          if (uInstEl) uInstEl.innerText = isAr ? '\u0644\u062A\u0631/\u0633' : 'L/h';
        } else {
          fInstEl.innerText = window.toLangDigits('0.0');
          if (uInstEl) uInstEl.innerText = isAr ? '\u0644\u062A\u0631 / \u0661\u0660\u0660' : 'L/100';
        }
      } else {
        fInstEl.innerText = window.toLangDigits(cachedInstFuel.toFixed(1));
        if (uInstEl) uInstEl.innerText = isAr ? '\u0644\u062A\u0631 / \u0661\u0660\u0660' : 'L/100';
      }
    }

    function updateRangeDisplay() {
      const rEl = document.getElementById('val-range');
      if (rEl) rEl.innerText = window.toLangDigits(cachedRange);
    }

    function calculateInstantRange() {
      let cons = cachedInstFuel;
      if (!cons || cons < 1.0) {
        cons = (typeof cachedAvgFuel !== 'undefined' && cachedAvgFuel > 2.0) ? cachedAvgFuel : 6.4;
      }
      const remain = (cachedFuelRemain && cachedFuelRemain > 0) ? cachedFuelRemain : ((cachedFuelPct / 100) * 50);
      const calculatedRange = Math.round((remain / cons) * 100);
      cachedRange = Math.max(10, Math.min(1200, calculatedRange));
      updateRangeDisplay();
    }

    function updateFuelRemainingDisplay() {
      const fpEl = document.getElementById('val-fuel-pct');
      if (fpEl) fpEl.innerText = (state.lang === 'ar') ? ('%' + window.toLangDigits(cachedFuelPct)) : (window.toLangDigits(cachedFuelPct) + '%');
      const flEl = document.getElementById('val-fuel-liters');
      if (flEl) flEl.innerText = window.toLangDigits(Number(cachedFuelRemain).toFixed(1));
    }

    function updateFuelDisplays() {
      updateInstFuelDisplay();
      const fAvgEl = document.getElementById('val-avg-fuel');
      if (fAvgEl) fAvgEl.innerText = window.toLangDigits(Number(cachedAvgFuel).toFixed(1));
      const cEl = document.getElementById('val-coolant');
      if (cEl) cEl.innerText = window.toLangDigits(cachedCoolant);
      calculateInstantRange();
      updateFuelRemainingDisplay();
    }
    setInterval(calculateInstantRange, 5000);

    window.onCanTelemetry = function(data) {
      if (data.canConnected !== undefined) {
        updateCanStatusBadge(data.canConnected);
      }

      const isAr = (typeof state !== 'undefined' && state.lang === 'ar');
      if (data.locks) {
        if (data.locks.speedLock !== undefined && data.locks.speedLock >= 0) {
          const isSpeedOn = data.locks.speedLock > 1;
          const chk = document.getElementById('chk-speed-lock');
          if (chk && !chk.matches(':focus')) {
            chk.checked = isSpeedOn;
            updateLockPillUi('speed_lock', isSpeedOn);
            const stat = document.getElementById('stat-lock-speed');
            if (stat) stat.innerText = isAr ? (isSpeedOn ? '\u0627\u0644\u0634\u0628\u0643\u0629: \u0646\u0634\u0637 (\u0645\u0641\u0639\u0644)' : '\u0627\u0644\u0634\u0628\u0643\u0629: \u0646\u0634\u0637 (\u0645\u0639\u0637\u0644)') : (isSpeedOn ? 'CAN: ACTIVE (ON)' : 'CAN: ACTIVE (OFF)');
          }
        }
        if (data.locks.walkLock !== undefined && data.locks.walkLock >= 0) {
          const isWalkOn = data.locks.walkLock === 1;
          const chk = document.getElementById('chk-walk-lock');
          if (chk && !chk.matches(':focus')) {
            chk.checked = isWalkOn;
            updateLockPillUi('walk_lock', isWalkOn);
            const stat = document.getElementById('stat-lock-walk');
            if (stat) stat.innerText = isAr ? (isWalkOn ? '\u0627\u0644\u0634\u0628\u0643\u0629: \u0646\u0634\u0637 (\u0645\u0641\u0639\u0644)' : '\u0627\u0644\u0634\u0628\u0643\u0629: \u0646\u0634\u0637 (\u0645\u0639\u0637\u0644)') : (isWalkOn ? 'CAN: ACTIVE (ON)' : 'CAN: ACTIVE (OFF)');
          }
        }
        if (data.locks.offUnlock !== undefined && data.locks.offUnlock >= 0) {
          const isOffOn = data.locks.offUnlock === 1;
          const chk = document.getElementById('chk-off-unlock');
          if (chk && !chk.matches(':focus')) {
            chk.checked = isOffOn;
            updateLockPillUi('off_unlock', isOffOn);
            const stat = document.getElementById('stat-lock-off');
            if (stat) stat.innerText = isAr ? (isOffOn ? '\u0627\u0644\u0634\u0628\u0643\u0629: \u0646\u0634\u0637 (\u0645\u0641\u0639\u0644)' : '\u0627\u0644\u0634\u0628\u0643\u0629: \u0646\u0634\u0637 (\u0645\u0639\u0637\u0644)') : (isOffOn ? 'CAN: ACTIVE (ON)' : 'CAN: ACTIVE (OFF)');
          }
        }
      }

      if (data.speed !== undefined) {
        cachedCurrentSpeed = data.speed;
        updateSpeed(data.speed);
      }
      if (data.coolant !== undefined) {
        cachedCoolant = data.coolant;
        const cEl = document.getElementById('val-coolant');
        if (cEl) cEl.innerText = window.toLangDigits(data.coolant);
      }
      if (data.ambient !== undefined) {
        updateAmbientDisplay(data.ambient);
      }

      if (data.instFuel !== undefined) {
        cachedInstFuel = data.instFuel;
        updateDrivingTip(data.instFuel, cachedCurrentSpeed);
        const now = Date.now();
        if (now - lastInstFuelUpdate >= 800 || lastInstFuelUpdate === 0) {
          lastInstFuelUpdate = now;
          updateInstFuelDisplay();
        }
      }

      if (data.avgFuel !== undefined) {
        cachedAvgFuel = data.avgFuel;
        const fAvgEl = document.getElementById('val-avg-fuel');
        if (fAvgEl) fAvgEl.innerText = window.toLangDigits(Number(data.avgFuel).toFixed(1));
      }

      if (data.range !== undefined) {
        cachedRange = data.range;
        const now = Date.now();
        if (now - lastRangeUpdate >= 10000 || lastRangeUpdate === 0) {
          lastRangeUpdate = now;
          updateRangeDisplay();
        }
      }

      if (data.fuelPct !== undefined || data.fuelRemain !== undefined) {
        if (data.fuelPct !== undefined) cachedFuelPct = data.fuelPct;
        if (data.fuelRemain !== undefined) cachedFuelRemain = data.fuelRemain;
        updateFuelRemainingDisplay();
      }

      if (data.tpms) {
        updateTpmsTire('fl', data.tpms.fl);
        updateTpmsTire('fr', data.tpms.fr);
        updateTpmsTire('rl', data.tpms.rl);
        updateTpmsTire('rr', data.tpms.rr);
      }

      let visual3DChanged = false;
      if (data.sunroof !== undefined) {
        const srVal = String(data.sunroof).toLowerCase();
        if (srVal === 'open' || srVal === 'tilt' || srVal === 'close') {
          if (Date.now() > manualSunroofCooldown) {
            const srPos = Number(data.sunroofPos || 0);
            // Preserve tilt until physical sunroof actually closes ('close') or slides open (sunroofPos > 0)
            if (state.sunroof === 'tilt' && srVal === 'open' && srPos <= 0) {
              // Keep tilted state
            } else if (state.sunroof !== srVal) {
              applySunroofVisualState(srVal);
              visual3DChanged = true;
            }
          }
        }
      }

      if (data.doors) {
        const nowDoor = Date.now();
        let newlyOpenedDoor = null;
        let anyDoorChanged = false;
        for (let k in data.doors) {
          const canVal = data.doors[k] ? 1 : 0;
          const prevCanVal = lastCanDoors[k];
          lastCanDoors[k] = canVal;
          if (prevCanVal !== null && prevCanVal !== undefined && canVal !== prevCanVal) {
            anyDoorChanged = true;
          }
          if (k === 'trunk') {
            // Trunk: show open if physical trunk is open, or sync closed when physical trunk closes or after manual open cooldown
            if (canVal === 1 || (prevCanVal === 1 && canVal === 0) || nowDoor > (manualDoorLockUntil.trunk || 0)) {
              state.targetDoors.trunk = canVal;
              const btn = document.getElementById('btn-side-trunk');
              if (btn) btn.classList.toggle('active', canVal > 0);
            }
          } else if (state.targetDoors[k] !== undefined) {
            state.targetDoors[k] = canVal;
          }

          // Detect transition to open state (from 0 or null to 1)
          if (canVal === 1 && prevCanVal !== 1) {
            newlyOpenedDoor = k;
          }
        }

        if (newlyOpenedDoor) {
          visual3DChanged = true;
          if (typeof snapCameraToDoor === 'function') snapCameraToDoor(newlyOpenedDoor);
        } else if (anyDoorChanged) {
          visual3DChanged = true;
          // A door or trunk state changed (e.g., closed); always return to hero 10 seconds after last change
          if (typeof resetAutoReturnTimer === 'function') resetAutoReturnTimer(10000);
        }
      }

      if (data.lights) {
        let lgtChanged = false;
        const afterManualCooldown = Date.now() > manualLightCooldown;
        const syncSteadyLight = (canKey, stateKey) => {
          if (data.lights[canKey] === undefined) return;
          const cur = !!data.lights[canKey];
          const prev = lastCanLights[canKey];
          lastCanLights[canKey] = cur;
          if ((prev !== cur) || afterManualCooldown) {
            if (state.lights[stateKey] !== cur) {
              state.lights[stateKey] = cur;
              lgtChanged = true;
            }
          }
        };
        syncSteadyLight('lowBeam', 'dipped');
        syncSteadyLight('highBeam', 'main');
        syncSteadyLight('fogFront', 'fog');
        syncSteadyLight('posLight', 'position');

        if (data.lights.hazard !== undefined) {
          const hz = !!data.lights.hazard;
          const prevHz = lastCanLights.hazard;
          lastCanLights.hazard = hz;
          if ((prevHz !== hz) || afterManualCooldown) {
            if (state.flashers.hazard !== hz) {
              state.flashers.hazard = hz;
              lgtChanged = true;
            }
          }
        }
        // Left & Right Turn Signals: synced with physical stalk (0x92-0x10 / LightStatus) and screen controls
        if (data.lights.dirL !== undefined) {
          const dl = !!data.lights.dirL;
          const prevDl = lastCanLights.dirL;
          lastCanLights.dirL = dl;
          if (prevDl !== dl) {
            state.flashers.left = dl;
            if (dl) {
              state.flashers.right = false;
              state.flashers.hazard = false;
              flasherPhase = true;
              flasherTimer = 0;
            }
            lgtChanged = true;
          }
        }
        if (data.lights.dirR !== undefined) {
          const dr = !!data.lights.dirR;
          const prevDr = lastCanLights.dirR;
          lastCanLights.dirR = dr;
          if (prevDr !== dr) {
            state.flashers.right = dr;
            if (dr) {
              state.flashers.left = false;
              state.flashers.hazard = false;
              flasherPhase = true;
              flasherTimer = 0;
            }
            lgtChanged = true;
          }
        }
        if (lgtChanged) {
          visual3DChanged = true;
          updateLightBarVisuals();
          if (typeof updateLights === 'function') updateLights();
        }
      }

      if (data.gear !== undefined && state.gear !== data.gear) { state.gear = data.gear; visual3DChanged = true; }
      if (data.swcAngle !== undefined && Math.abs((state.swcAngle || 0) - data.swcAngle) >= 1) { state.swcAngle = data.swcAngle; visual3DChanged = true; }
      if (data.swcDirection !== undefined && state.swcDirection !== data.swcDirection) { state.swcDirection = data.swcDirection; visual3DChanged = true; }
      if ((data.speed || 0) > 0.5) { visual3DChanged = true; }

      if (!window.isCar3DFrozen && visual3DChanged) {
        idleRenderCountdown = 32;
        requestRender();
      }
    };

    function updateSpeed(s) {
      state.telemetry.speed = s;
      const spdEl = document.getElementById('disp-speed');
      if (spdEl) spdEl.textContent = window.toLangDigits(s);
    }

    // Telemetry Smooth 0.5s Simulation Loop (when running on browser / demo)
    setInterval(() => {
      if (!window.AndroidBridge) {
        const time = Date.now() / 2500;
        const simSpeed = Math.round(68 + Math.sin(time) * 18);
        const simInst = Math.round((6.1 + Math.sin(Date.now() / 3000) * 1.4) * 10) / 10;
        window.onCanTelemetry({
          speed: simSpeed,
          coolant: 90,
          ambient: 24,
          instFuel: simInst,
          avgFuel: 6.5,
          range: 290,
          fuelRemain: 17.1,
          fuelCapacity: 55,
          fuelPct: 31
        });
      }
    }, 500);

    // --- Top Statusbar Connectivity Engine (Bluetooth, Wi-Fi, Cellular) ---
    window.connectivityState = {
      bluetooth: true,
      wifi: true,
      cellular: true
    };

    window.setConnectivityStatus = function(type, isConnected) {
      if (window.connectivityState.hasOwnProperty(type)) {
        window.connectivityState[type] = !!isConnected;
        const el = document.getElementById('sb-indicator-' + type);
        if (el) {
          if (isConnected) {
            el.classList.remove('disconnected');
            el.classList.add('connected');
            el.setAttribute('title', type.toUpperCase() + ': Connected (Online)');
          } else {
            el.classList.remove('connected');
            el.classList.add('disconnected');
            el.setAttribute('title', type.toUpperCase() + ': Disconnected (Offline)');
          }
        }
        if (typeof window.updateConnectivityModalUI === 'function') {
          window.updateConnectivityModalUI(type);
        }
      }
    };

    window.updateConnectivityModalUI = function(type) {
      const types = type ? [type] : ['cellular', 'bluetooth', 'wifi'];
      types.forEach(function(t) {
        const isConnected = !!window.connectivityState[t];
        const btn = document.getElementById('conn-btn-' + t) || document.getElementById('conn-tile-' + t);
        if (btn) {
          if (isConnected) {
            btn.classList.remove('disconnected');
            btn.classList.add('connected');
          } else {
            btn.classList.remove('connected');
            btn.classList.add('disconnected');
          }
        }
      });
    };

    window.openConnectivityModal = function(initialFocus) {
      const modal = document.getElementById('connectivity-modal');
      const box = document.getElementById('connectivity-modal-box');
      if (!modal || !box) return;

      // Pause 3D Car Rendering during modal display to prevent lag/stutter
      if (typeof window.setCar3DFrozen === 'function') {
        window.setCar3DFrozen(true);
      }

      window.updateConnectivityModalUI();
      box.classList.remove('twist-out');
      box.classList.add('twist-in');
      modal.classList.add('active');
    };

    window.closeConnectivityModal = function(event) {
      if (event && event.stopPropagation) {
        event.stopPropagation();
      }
      const modal = document.getElementById('connectivity-modal');
      const box = document.getElementById('connectivity-modal-box');
      if (!modal || !box) return;

      box.classList.remove('twist-in');
      box.classList.add('twist-out');

      setTimeout(function() {
        modal.classList.remove('active');
        box.classList.remove('twist-out');
        // Resume 3D Car Rendering smoothly
        if (typeof window.setCar3DFrozen === 'function') {
          window.setCar3DFrozen(false);
        }
      }, 230);
    };

    // Single Tap Toggle
    window.toggleConnectivity = function(type) {
      if (window.connectivityState.hasOwnProperty(type)) {
        const newState = !window.connectivityState[type];
        window.setConnectivityStatus(type, newState);

        // Hardware toggle calls
        if (window.AndroidBridge) {
          if (type === 'wifi' && typeof window.AndroidBridge.setWifiEnabled === 'function') {
            window.AndroidBridge.setWifiEnabled(newState);
          } else if (type === 'bluetooth' && typeof window.AndroidBridge.setBluetoothEnabled === 'function') {
            window.AndroidBridge.setBluetoothEnabled(newState);
          }
        }

        // Haptic feedback
        if (window.navigator && window.navigator.vibrate) {
          window.navigator.vibrate(35);
        }
      }
    };

    window.toggleStatusIndicator = function(type) {
      window.openConnectivityModal(type);
    };

    // Long Press Progressive Glow Handling (Hold for 650ms -> Opens Android Native Settings)
    window._connHoldTimers = {};
    window._connLongPressFired = {};

    window.handleConnTilePointerDown = function(type, ev) {
      window._connLongPressFired[type] = false;
      const btn = document.getElementById('conn-btn-' + type) || document.getElementById('conn-tile-' + type);
      if (btn) btn.classList.add('charging');

      clearTimeout(window._connHoldTimers[type]);
      window._connHoldTimers[type] = setTimeout(function() {
        window._connLongPressFired[type] = true;
        if (btn) btn.classList.remove('charging');

        // Haptic feedback pulse on firing
        if (window.navigator && window.navigator.vibrate) {
          window.navigator.vibrate([40, 70, 40]);
        }

        // Open Android Native Settings
        if (window.AndroidBridge && typeof window.AndroidBridge.openConnectivitySettings === 'function') {
          window.AndroidBridge.openConnectivitySettings(type);
        } else {
          console.log('[Connectivity] Long-press fired: Open ' + type + ' native settings');
        }

        // Auto close modal smoothly after opening settings
        setTimeout(window.closeConnectivityModal, 220);
      }, 650);
    };

    window.handleConnTilePointerUp = function(type, ev) {
      const btn = document.getElementById('conn-btn-' + type) || document.getElementById('conn-tile-' + type);
      if (btn) btn.classList.remove('charging');
      clearTimeout(window._connHoldTimers[type]);

      if (!window._connLongPressFired[type]) {
        // Quick single tap: toggle hardware state
        window.toggleConnectivity(type);
      }
      window._connLongPressFired[type] = false;
    };

    window.handleConnTilePointerCancel = function(type, ev) {
      const btn = document.getElementById('conn-btn-' + type) || document.getElementById('conn-tile-' + type);
      if (btn) btn.classList.remove('charging');
      clearTimeout(window._connHoldTimers[type]);
      window._connLongPressFired[type] = false;
    };

    window.syncConnectivityFromAndroid = function() {
      if (window.AndroidBridge && typeof window.AndroidBridge.getConnectivityStatusJson === 'function') {
        try {
          const raw = window.AndroidBridge.getConnectivityStatusJson();
          if (raw) {
            const data = JSON.parse(raw);
            if (data.hasOwnProperty('bluetooth')) window.setConnectivityStatus('bluetooth', data.bluetooth);
            if (data.hasOwnProperty('wifi')) window.setConnectivityStatus('wifi', data.wifi);
            if (data.hasOwnProperty('cellular')) window.setConnectivityStatus('cellular', data.cellular);
          }
        } catch(e) {
          console.warn('[Statusbar] sync error:', e);
        }
      }
    };

    setTimeout(window.syncConnectivityFromAndroid, 1200);
    setInterval(window.syncConnectivityFromAndroid, 10000);

/* ============================================================================
   PHASE 1 INSTANT BOOT (HUD, Clock, Telemetry, Controls & Quick Apps)
   ============================================================================ */
(function bootPhase1Hud() {
  autoFitViewport();
  startDigitalClock();
  setupInstantButtons();
  loadSavedSettings();
  updateFuelDisplays();
  updateTpmsOverallBadge();
  renderQuickAppsDeck();

  window.addEventListener('resize', function() {
    autoFitViewport();
    if (typeof window.onResize3D === 'function') window.onResize3D();
  });

  if (window.location.hash === '#drawer') {
    setTimeout(() => { if (typeof openAppDrawer === 'function') openAppDrawer(); }, 200);
  } else if (window.location.hash === '#settings') {
    setTimeout(() => { if (typeof openSettings === 'function') openSettings(); }, 200);
  } else if (window.location.hash === '#sunroof') {
    setTimeout(() => { if (typeof openSunroofModal === 'function') openSunroofModal(); }, 200);
  }

  window.__cockpitHudReady = true;
  window.__cockpitHudReadyMs = Math.round(performance.now());
  console.log('[CockpitUI] Phase 1 HUD interactive in ' + window.__cockpitHudReadyMs + 'ms');
})();
