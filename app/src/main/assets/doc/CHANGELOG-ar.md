******

### سجل الإصدارات

******

# v0.1.1

###### 2026/09/11

* `تحسين` التحقق أثناء البناء من محاذاة صفحات 16 KB للمكتبات الأصلية ذات 64 بت, مع فحص عقد manifest وتقارير JSON
* `تحسين` إضافة تنشيط مضيف محمي وبيانات دقيقة للحزمة المثبتة وجمع الإصدارات الموقعة مع توحيد الموارد وفحص المستندات للقراءة فقط
* `تحسين` توسيع حزم ABI الأصلية وبيانات الإضافة الوصفية لتشمل arm64-v8a وarmeabi-v7a وx86 وx86_64, مع ملفات APK عامة ومنفصلة متطابقة لكل ABI

# v0.1.0-rc.2

###### 2026/08/27

* `تلميح` ما زال مرشح تغليف موقع تم التحقق منه محليا: لا يوجد tag عام أو GitHub Release, ويحافظ receipt الثابت على `deviceVerified=false/runtimeVerified=false`
* `تلميح` ألغيت تجربة arm64-v8a الفعلية وproduction soak لسبعة أيام بقرار owner; يبقى دليل اليومين والمعيار المجمد محفوظين, وينتقل الاستقرار إلى fix-on-report
* `ميزة` إضافة coroutine المقيدة و`autojs.now()` و`console.info/warn` وجانب Provider من `ui.toast.v1` وتشخيص crash وأحداث `AutoJs6LuaWatchdog`
* `ميزة` تنفيذ `storage.kv.v1` المتفاوض عليه مع storage في Host معزولة لكل file script وأشكال get/put/remove/clear ثابتة وcanonical value محدودة ومن دون retry, وإكمال `ui.toast.v1` عبر Host
* `ميزة` إضافة snapshot لوحدات النص وحجج التنفيذ للقراءة فقط وجسر معلومات الجهاز مع استمرار deadline والإلغاء والذاكرة وحصص الإخراج
* `ميزة` توفير native library لـ arm64-v8a وx86_64 وحفظ دليل x86_64 عبر Host حقيقية على API 24 و31 و36 و37
* `إصلاح` أصبح deadline الصغير الذي ينتهي قبل وصول `start()` ينتج نهاية `TIMEOUT/QUEUE` حتمية واحدة بدلا من session بلا نهاية
* `إصلاح` تشديد `math.randomseed` وmapping رفض capability في Host كي تنتهي الاستدعاءات غير الممنوحة كـ `HOST_CAPABILITY`
* `تحسين` استبدال أربعة أوضاع Boolean بمتغيرات `providerDebug/providerRelease/nativeTestDebug/faultTestDebug` وعزل اكتشاف الإنتاج فعليا عن fault harness التخريبي
* `تحسين` الانتقال إلى نهج الإضافات الشقيقة `.python/generate_markdown.py` + `.readme/` + `.changelog/` مع توثيق كامل بعشر لغات و`zh-Hans` كملف README جذري
* `تحسين` فصل R5 من `ROADMAP-R4.md` إلى `ROADMAP-R5.md` وإزالة مهمة خانات الصينية التقليدية التي لم تعد لازمة
* `تحسين` إضافة حساب PFD المنطقي وعلى مستوى OS وبوابة offline بأمر واحد وCI مرنة والتحقق من release artifact وتدقيق استبعاد fault-harness
* `تبعية` تثبيت PUC Lua 5.4.8 وAndroid NDK 28.2.13676358 وCMake 3.22.1

# v0.1.0-rc.1

###### 2026/08/13

* `تلميح` تم توقيع أول مرشح محلي Provider-enabled واختباره على جهاز, من دون إنشاء tag أو release عام
* `ميزة` تقديم عملية `:lua_runtime` المستقلة وتنفيذ Lua النصي وconsole والنتائج scalar واكتشاف Binder Provider في AutoJs6
* `إصلاح` رفض الطلبات المشوهة أو الزائدة عبر تحقق fail-closed للبروتوكول وdigest وUTF-8 وdeadline والذاكرة والإخراج
* `تحسين` إنشاء دليل قابل للمراجعة لملفات AAR ومصادر Lua وABI ومحاذاة 16 KiB والتوقيع ومصفوفة rollback
* `تبعية` مبني على PUC Lua 5.4.8 القياسي وبروتوكول AutoJs6 Lua 1.0 المجمد
