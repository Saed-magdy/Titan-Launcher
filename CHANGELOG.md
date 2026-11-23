# 📋 Titan Cockpit Launcher - Changelog

## 🌟 [v2.1.4] - 2026-11-23

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

### 🛠️ تحديثات وإصلاحات مشغل الصوتيات الشاملة (Audio Player Fixes):
- **التحكم الكامل في التطبيقات الخارجية (VLC & External Apps):**
  - إصلاح استجابة أزرار التشغيل والإيقاف المؤقت (Play / Pause / Stop) عند التحكم في تطبيق VLC والمشغلات الخارجية، واكتشاف الجلسة النشطة تلقائياً دون تعارض.
- **تحديث مؤقت التقدم اللحظي لملفات الفلاشة والتخزين:**
  - تفعيل عداد الثواني وشريط التقدم في الوقت الفعلي (1s Real-time Progress Ticker) أثناء تشغيل الملفات الصوتية من الفلاشة والذاكرة.
  - خفض الحد الأدنى لمدة المقاطع الممسوحة إلى 5 ثوانٍ وتوسيع نطاق قراءة وحدات USB التلقائية.
- **قائمة التشغيل الموحدة والديناميكية (Unified Dynamic Queue):**
  - قراءة قائمة التشغيل تلقائياً وعرضها بدقة من أي مصدر نشط (VLC MediaSession، ملفات الفلاشة، محطات راديو السيارة، راديو الإنترنت) مع إمكانية القفز المباشر لأي مقطع أو محطة.
- **البحث والتسجيل التلقائي لمحطات الراديو (Auto-Scan & Save):**
  - إضافة ميزة وزر المسح التلقائي لترددات FM وحفظ القنوات ذات الإشارة الصافية تلقائياً في قائمة القنوات والمفضلة.
- **تشغيل راديو الإنترنت وتوجيه عتاد الصوت DSP:**
  - توجيه عتاد الصوت تلقائياً عبر QingganAudioPolicyBridge لمنع صمت الصوت عند الانتقال للإنترنت والمشغلات الخارجية، مع تزويد المشغل بباقة مسبقة من الإذاعات المصرية والعربية عالية الموثوقية (إذاعة القرآن الكريم، نجوم FM، راديو مصر، ميجا FM، 9090، الأغاني، روتانا، سوا، سكاي نيوز).
- **إصلاح انهيار خيط البلوتوث (Qinggan BT Stack Fix):**
  - ضمان تهيئة مهايئ البلوتوث WL8BTAdapter عبر Main Looper لمنع انهيار Looper.prepare عند ضغطات عجلة القيادة أو الشاشة.

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
- **Comprehensive Audio Player & Controller Fixes:**
  - **External Player (VLC) Control:** Fixed Play/Pause/Stop button response and automatic active MediaSession detection when controlling VLC or third-party audio apps.
  - **Real-Time Storage Progress Ticker:** Active 1-second progress ticker for local and USB drive playback, lowered minimum track duration threshold to 5s, and expanded storage volume discovery.
  - **Unified Dynamic Queue:** Real-time queue reading and instant item navigation across all audio sources (VLC MediaSession items, USB files, Car Radio frequencies, Internet Radio stations).
  - **OEM Radio Auto-Scan & Save:** Added Auto-Scan & Save action that sweeps the FM band and automatically saves all high-clarity frequencies into station presets.
  - **Internet Radio & Hardware Audio DSP Policy:** Ensured car hardware audio DSP routes properly to `STREAM_MUSIC` (streamType 3) to prevent silence bugs, with 10+ pre-seeded high-reliability Egyptian and Arabic live streams (Cairo Quran, Nogoum FM, Radio Masr, Mega FM, etc.).
  - **Qinggan Bluetooth Stack Thread Crash Fix:** Synchronized `WL8BTAdapter` initialization on the Main Looper, eliminating the `Can't create handler inside thread that has not called Looper.prepare()` crash on steering wheel key presses.
- **Redesigned Studio Equalizer:** 28 smoothly curved reactive bars with pastel gradients and peak neon caps.
- **Pastel Jester / Clown Mascot:** Musical mascot that sways, jumps to the beat, and blows iridescent bubbles, with sleep stance when paused and show/hide toggle.
- **10-Second Seek:** Long-press Next/Previous on Steering Wheel (650ms) and on-screen player buttons (>500ms) with visual seek toasts.
- **Bluetooth Waveform Seeking:** Direct timeline scrub support over Bluetooth AVRCP 1.6.
- **Album Art Sanitizer:** Clean queries to fetch missing artwork for Arabic and international tracks.
- **Donation Hub:** Updated with Paypal and InstaPay.
