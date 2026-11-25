# Titan Cockpit Launcher - Changelog

## Release v2.1.6 – 2026-11-25

### أولاً: اللغة العربية

#### 1. تحسينات المجسم ثلاثي الأبعاد والمقصورة الداخلية
- توحيد درجة الشفافية لزجاج الأبواب الأربعة والمثلثات الخلفية من الداخل والخارج لضمان مظهر بصري متناسق من جميع زوايا الرؤية.
- معالجة الفراغات الهيكلية ودمج الأجزاء التكميلية مباشرة داخل الشبكات الثابتة للمجسم دون أي حمل إضافي على المعالج الرسومي، شاملة منطقة التقاء التابلوه بحوض المحرك مع إزالة الزوائد الجانبية، وقوائم الأبواب الداخلية، وبطانة السقف، وتقفيل أسفل الهيكل.
- ترقية دقة معالجة خامات المقاعد إلى `1024x1024` مع تفعيل التنعيم الاتجاهي (`Anisotropic Filtering`).

#### 2. إعادة تصميم واجهة مشغل الصوتيات الرئيسي (Shine Wave Player)
- إعادة هيكلة البطاقة الرئيسية للمشغل عبر تكبير مساحة غلاف الألبوم والمجسم التفاعلي بشكل متناظر على الجانبين، وتوسيط عنوان المقطع أو المحطة بينهما، ونقل مؤشر الموجات الصوتية (`Equalizer`) إلى الأسفل مع دعم التمرير المباشر منه وإزالة شريط التمرير المكرر.
- معايرة أبعاد النافذة الرئيسية وأشرطة التبويبات وأزرار التحكم لتتوافق بالكامل مع أبعاد شاشة السيارة الفعلية (`960x1080`) دون أي اقتصاص أفقي أو رأسي في الواجهتين العربية والإنجليزية.

#### 3. تخصيص عناصر التحكم حسب المصدر ومزامنة البلوتوث
- قصر أزرار أوضاع التشغيل (`التكرار`، `التشغيل العشوائي`، `قائمة التشغيل`) وميزة السحب للتقديم والتأخير والضغط المطول (`±10 ثوانٍ`) من الشاشة أو أزرار المقود على المصادر الداعمة لها فقط (`الفلاشة والملفات المحلية` و`تطبيقات الميديا`)، وإخفاؤها تلقائياً في أوضاع `البلوتوث` و`راديو السيارة` و`راديو الإنترنت`.
- تفعيل عرض التوقيت الفعلي والمدة الزمنية ومؤشر موضع التشغيل اللحظي على كل من الشريط المموج في المشغل المصغر ومؤشر `Equalizer` في المشغل الرئيسي أثناء الاستماع عبر البلوتوث، مع تحسين دقة مزامنة بيانات `AVRCP` و`MediaSession`.

#### 4. استقرار وتشغيل راديو السيارة (FM / AM)
- معالجة تعارض توجيه الصوت مع خدمة النظام عبر توحيد إدارة مسار صوت الراديو (`STREAM_RADIO`) من خلال جسر السياسة الصوتية الموحد دون التأثير على سلاسة التنقل بين المصادر.
- تصحيح مطابقة نطاقات التردد (`FM` و`AM`) عند قراءة المحطات المخزنة في قاعدة بيانات النظام الأصلية، وإضافة ذاكرة تخزين دائمة للمحطات والتردد الأخير لكل نطاق، وضبط خطوة التردد القياسية لنطاق `AM` (`9 kHz`)، وتحسين آلية البحث التلقائي وحفظ المحطات مع تحديث الواجهة لحظياً.

---

### Second: English

#### 1. 3D Vehicle Model and Interior Cabin Refinements
- Unified semi-transparent glass rendering across all four door windows and rear quarter windows from both interior and exterior camera perspectives.
- Sealed structural cabin and chassis gaps by welding supplementary geometry directly into static meshes with zero additional draw-call overhead, including the dashboard-to-cowl firewall junction (with protruding lateral edges trimmed), door jambs, interior headliner, and underbody shield.
- Upgraded the dynamic seat texture pipeline to `1024x1024` with anisotropic filtering.

#### 2. Shine Wave Player Main Studio Redesign
- Restructured the primary player header with symmetrical enlarged album artwork and interactive 3D mascot frames, centered track and station metadata between them, relocated the live waveform equalizer directly underneath with integrated scrubbing support, and removed the redundant secondary seek bar.
- Calibrated panel dimensions, source navigation tabs, and transport controls to fit natively within the `960x1080` head-unit viewport without horizontal or vertical clipping in both RTL (Arabic) and LTR (English) layouts.

#### 3. Source-Specific Transport Capabilities and Bluetooth Telemetry
- Restricted `Repeat`, `Shuffle`, `Queue`, interactive waveform scrubbing, and `±10s` long-press/steering-wheel fast-seek strictly to supported sources (`USB & Local Files` and `Media Applications`), automatically hiding unsupported controls on `Bluetooth`, `Car Radio`, and `Internet Radio`.
- Enabled real-time elapsed/duration timestamps and live playback progress indicators on both the home mini-player waveform and the Studio equalizer during Bluetooth audio streaming, backed by `AVRCP` and `MediaSession` position interpolation.

#### 4. Car Radio (FM / AM) Tuner and Audio Routing Fixes
- Resolved OEM `AudioPolicyManager` singleton contention by routing `STREAM_RADIO` consistently through the unified `QingganAudioPolicyBridge` while preserving existing source-switching behavior.
- Fixed `FM`/`AM` band identifier mapping when importing stations from the OEM radio provider, added persistent per-band frequency memory and station caching, enforced standard `9 kHz` AM channel stepping, and improved automatic station scanning with real-time UI synchronization.

---

## v2.1.5 – 2026-11-24

### 🇸🇦 العربية
- ⚡ **قفزة ضخمة في أداء وسلاسة مجسم الـ 3D**:
  - رفع معدل الإطارات من 12 FPS إلى 45-50 FPS عبر بنية خامات Lambert المحسنة وتوليد خريطة انعكاسات استوديو بيئية فائقة الخفة بدون إجهاد المعالج الرسومي Adreno 506.
  - إزالة التهنيج وتقطيع اللمس في OrbitControls بدعم حركة ناعمة ومستقلة عن الفريمات.
- 🎨 **تحسينات بصرية واقعية وتفاصيل المجسم**:
  - تقفيل فجوة التابلوه مع الزجاج الأمامي ودمج جسر ديكور أصلي متناسق مع مقصورة السيارة.
  - ضبط تظليل الزجاج الأمامي وإضاءة مقصورة الصالون وفرش المقاعد بدرجات ألوان واقعية ومتوازنة.
- 🛡️ **منظومة أمان فتح الشنطة**:
  - منع استجابة فتح الشنطة على المجسم ثلاثي الأبعاد أثناء حركة السيارة مع إشعار صوتي/تنبيهي مطابق للواقع.

---

### 🇬🇧 English
- ⚡ **Huge 3D Cockpit Performance & Smoothness Boost**:
  - Boosted framerate from ~12 FPS to 45–50 FPS by migrating to lightweight Lambert material shaders with an optimized procedural studio environment map, eliminating GPU bottlenecks on Adreno 506.
  - Eliminated touch lag and stutter in OrbitControls with frame-rate independent damping and touch listener deduplication.
- 🎨 **Visual Refinements & Model Polish**:
  - Sealed dashboard-to-windshield gap with a native molded bridge matching the real car interior cabin.
  - Balanced windshield frit band shading, interior cabin lighting, and leather seat tones for realistic ambiance.
- 🛡️ **Trunk Safety Interlock**:
  - Prevented trunk toggle on the 3D model while vehicle is in motion, synced with real car safety logic and audio warning chime.

---

## v2.1.4 – 2026-11-24
- تحسينات في مشغل الموسيقى (إضافة تحكم كامل من الطارة، تحسين استجابة الأزرار، ودعم جميع مصادر الصوت).
- تحسينات في آلية عمل المجسم الـ3D (تحسين أداء التحميل والعرض، تقليل التأخير، وتحديث الإضاءة).
