# 📋 Titan Cockpit Launcher - Changelog

## 🌟 [v2.1.3] - 2026-11-23

### 🔄 التحديث التلقائي وصلاحيات التنزيل والتثبيت:
- **إضافة صلاحيات التنزيل والتثبيت (Auto-Updater Permissions):**
  - إضافة صلاحية `REQUEST_INSTALL_PACKAGES` و `INSTALL_PACKAGES` لتثبيت التحديثات مباشرة وبسلاسة من داخل اللانشر دون توقف أو أخطاء صلاحيات.
  - حفظ التحديثات المنزلة في مجلد التخزين المخصص للتطبيق لتجنب قيود وأخطاء أذونات التخزين (`SecurityException`).
  - معالجة وإلغاء قيود حماية الروابط `StrictMode` لمنع انهيار مدير التثبيت (`FileUriExposedException`).
  - فحص وطلب أذونات التخزين والميكروفون تلقائياً عند الإقلاع.

### 🎵 مشغل الصوتيات والاستوديو (Shine Wave Player):
- **إعادة تصميم الإكولايزر بالكامل:** شريط تفاعلي جديد مكون من 28 موجة صوتية متناسقة بألوان باستيل هادئة وإضاءات نيون متجاوبة مع كل الترددات الصامتة والمتحركة.
- **دمية المهرج الباستيلية الراقصة (Pastel Clown Mascot):** إضافة مهرج موسيقي مبهج بألوان الباستيل يرقص ويتمايل يميناً ويساراً مع قفزات الإيقاع، ويطلق فقاعات صابونية ساحرة أثناء تشغيل الموسيقى، ويهدأ في وضع استرخاء لطيف عند الإيقاف المؤقت، مع زر للتحكم في إظهاره أو إخفائه.
- **تمرير 10 ثوانٍ للأمام وللخلف (10s Seek):**
  - دعم التمرير السريع عبر أزرار الطارة (عجلة القيادة) بالضغط المطول (650 مللي ثانية).
  - دعم التمرير السريع عبر أزرار المشغل على الشاشة (الكارت الصغير والاستوديو الكامل) باللمس المطول (>500 مللي ثانية) مع إشعار عائم (+10s / -10s).
- **التمرير السريع عبر البلوتوث:** تفعيل النقر والتمرير على الشريط المموج لمقاطع البلوتوث عبر بروتوكول AVRCP 1.6 و MediaSession بدون تأخير.
- **إصلاح صور الألبومات عبر البلوتوث:** تنقية أسماء المقاطع من الزوائد والأقواس لتنزيل صور الألبومات العربية والأجنبية بنجاح.

### ☕ تحسينات عامة:
- **تحديث نافذة الدعم:** إضافة Paypal و InstaPay مع توجيه الرمز المباشر.
- **تحسين استجابة أزرار Play/Pause** واستئناف الصوت التلقائي عند التبديل بين البلوتوث والتطبيقات المحلية.

---

### 🌐 English Changelog:

### 🔄 Auto-Updater & System Permissions:
- **Download & Install Permissions:**
  - Added `REQUEST_INSTALL_PACKAGES` and `INSTALL_PACKAGES` permissions for seamless in-app auto updates.
  - Routed downloads to app-specific external storage to permanently prevent `SecurityException` during download.
  - Relaxed `StrictMode VmPolicy` to completely eliminate installer crash (`FileUriExposedException`).
  - Added automatic runtime permission checks on boot for storage and visualizer audio.

### 🎵 Shine Wave Audio Player & Studio:
- **Redesigned Studio Equalizer:** 28 smoothly curved reactive bars with pastel gradients and peak neon caps.
- **Pastel Jester / Clown Mascot:** Musical mascot that sways, jumps to the beat, and blows iridescent bubbles, with sleep stance when paused and show/hide toggle.
- **10-Second Seek:** Long-press Next/Previous on Steering Wheel (650ms) and on-screen player buttons (>500ms) with visual seek toasts.
- **Bluetooth Waveform Seeking:** Direct timeline scrub support over Bluetooth AVRCP 1.6.
- **Album Art Sanitizer:** Clean queries to fetch missing artwork for Arabic and international tracks.
- **Donation Hub:** Updated with Paypal and InstaPay.
