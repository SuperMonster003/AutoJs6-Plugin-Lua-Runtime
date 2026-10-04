<!--suppress HtmlDeprecatedAttribute, HttpUrlsUsage -->

<div align="center">
  <p>
    <img src="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/app/src/main/res/mipmap/ic_launcher.png?raw=true" alt="lua-runtime-ic-launcher" border="0" width="128" />
  </p>

  <h1>إضافة بيئة Lua لـ AutoJs6</h1>

  <p>تشغل نصوص PUC Lua 5.4.8 القياسية في عملية مخصصة ومعزولة</p>

  <p>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/releases"><img alt="GitHub release (latest by date)" src="https://img.shields.io/github/v/release/SuperMonster003/AutoJs6-Plugin-Lua-Runtime?label=Release"/></a>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/issues"><img alt="GitHub closed issues" src="https://img.shields.io/github/issues/SuperMonster003/AutoJs6-Plugin-Lua-Runtime?color=A24232&label=Issues"/></a>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/LICENSE"><img alt="GitHub License" src="https://img.shields.io/github/license/SuperMonster003/AutoJs6-Plugin-Lua-Runtime?color=534BAE&label=License"/></a>
  </p>
</div>

******

### اللغات (Languages)

******

يتوفر ملف README.md الحالي باللغات التالية:

- [简体中文 [zh-Hans]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-zh-Hans.md)
- [繁體中文 (香港) [zh-Hant-HK]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-zh-Hant-HK.md)
- [繁體中文 (台灣) [zh-Hant-TW]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-zh-Hant-TW.md)
- [English [en]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-en.md)
- [Français [fr]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-fr.md)
- [Español [es]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-es.md)
- [日本語 [ja]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ja.md)
- [한국어 [ko]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ko.md)
- [Русский [ru]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ru.md)
- العربية [ar] # الحالي

******

### مقدمة

******

يشغل AutoJs6 لغة JavaScript في الأصل. تضيف هذه الإضافة لغة نصوص ثانية: أنشئ ملف `.lua` في محرر AutoJs6 وشغله, فتسلم الشيفرة إلى Provider مثبت بصورة مستقلة يشغل PUC Lua 5.4.8 داخل العملية المخصصة `:lua_runtime`. تحصل النصوص على مجموعة مراجعة من مكتبات Lua وجسر `autojs` صغير, من دون وصول إلى الملفات أو العمليات أو متغيرات البيئة أو الوحدات الأصلية الديناميكية.

```text
application ID: io.github.supermonster003.autojs6.plugin.lua.runtime
plugin / engine / variant: lua-runtime / lua / puc-lua54
discovery actions: org.autojs.plugin.INFO / org.autojs.plugin.lua.RUNTIME
runtime process: :lua_runtime
minimum host build: 5276
```

تحمي صلاحية التوقيع `org.autojs.permission.PLUGIN` خدمتي الاكتشاف. يجب أن يستخدم Host والإضافة الشهادة نفسها, وتبقى كل capability معطلة حتى تسمح بها مفاوضة البروتوكول والتحقق. لا يتوفر APK عام حتى الآن, لذلك راجع قسمي البناء وحالة المشروع.

******

### الميزات

******

- تشغل نصوص Lua 5.4.8 (`.lua`) النصية من محرر AutoJs6, وتبث console مباشرة, وتعيد نتيجة scalar واحدة عند الاكتمال.
- توفر مكتبات base وstring وmath وtable وutf8 وcoroutine المقيدة, وترث coroutine حدود deadline والإلغاء وحساب الذاكرة.
- توفر `autojs.console` و`autojs.now()` و`autojs.arguments` للقراءة فقط و`autojs.device.info()` و`autojs.storage` الدائمة في Host وجسر `autojs.ui.toast()` كاملا.
- تدعم snapshot الوحدات المجاورة: يمكن لـ `job.lua` تحميل وحدة نصية UTF-8 حتى 64 KiB من `job.modules/name.lua`.
- تشغل تنفيذا واحدا في كل عملية مع deadline وميزانية ذاكرة وcredits للإخراج وwatchdog من نوع fail-stop يحددها Host.
- تعيد التحقق من الطول الدقيق وSHA-256 وUTF-8 الصارم قبل التنفيذ, وترفض دائما Lua chunk المترجمة مسبقا أو الثنائية.
- تتضمن مكتبات `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` أصلية بمحاذاة 16 KiB, إضافة إلى README وCHANGELOG ونصوص Android بعشر لغات.

******

### بدء سريع

******

**كيف أثبتها?** لا يوجد APK عام بعد, فابنه كما هو موضح أدناه. يجب إقران `providerDebug` بنسخة AutoJs6 debug تحمل الشهادة نفسها, كما يجب إقران release Provider بنسخة release Host ذات التوقيع نفسه.

**كيف أفعلها?** لا يوجد مفتاح. يكتشف AutoJs6 تلقائيا Provider ذا التوقيع نفسه, ولا تستخدم Lua خاصية Boolean تجريبية.

**كيف أشغل نصا?** أنشئ في محرر AutoJs6 ملفا ينتهي اسمه بـ `.lua`, واكتب شيفرة Lua ثم شغله. يظهر console مباشرة وتتلقى Host قيمة scalar المعادة عند الانتهاء.

**ماذا أفحص عند الفشل?** ترفض Host الأقدم من versionCode 5276 الإرسال عبر `LUA_RUNTIME_UNAVAILABLE/HOST_VERSION_UNSUPPORTED`. ينتهي deadline كـ `TIMEOUT`, وتجاوز الذاكرة كـ `MEMORY_LIMIT`, وجسر Host غير الممنوح كـ `HOST_CAPABILITY`; وهي نهايات أمان حتمية.

******

### مثال الاستخدام

******

يستخدم هذا المثال القائم على ملف storage الدائمة وToast في R5 التي تمنحها Host متوافقة:

```lua
local autojs = require("autojs")

print("Hello from AutoJs6 Lua Runtime", _VERSION)
autojs.console.warn("stderr, ordered with console output")

local started = autojs.now()
local total = 0
for index = 1, 1000 do
    total = total + index
end
autojs.console.info(("sum=%d in %d ms"):format(total, autojs.now() - started))

local device = autojs.device.info()
autojs.console.log("running on: " .. device.manufacturer .. " " .. device.model)

local runCount = (autojs.storage.get("run_count") or 0) + 1
autojs.storage.put("run_count", runCount)
autojs.ui.toast(("Lua run #%d complete"):format(runCount))

return total
```

لإعادة استخدام الشيفرة, أنشئ `job.modules/` بجانب نص البداية `job.lua`, وضع `helper.lua` داخله ثم استدع `require("helper")`. أسماء وحدات V1 هي معرفات ASCII مسطحة حتى 64 محرفا; ترفض المسارات والنقاط والوحدات الثنائية ووحدات C.

******

### API النصوص

******

يعيد `require("autojs")` جدول الجسر. يكتب `autojs.console.log/info(text)` إلى stdout و`error/warn(text)` إلى stderr, وتستخدم `print(...)` و`warn(text)` العامتان التدفقات المقيدة نفسها. يعيد `autojs.now()` أجزاء Unix epoch بالميلي ثانية. يمثل `autojs.arguments` snapshot للقراءة فقط. يعيد `autojs.device.info()` حقول brand وmanufacturer وmodel وdevice وproduct وsdkInt. للscript القائم على ملف ذي ID ثابت توفر `autojs.storage.get/put/remove/clear` قيما دائمة في Host: key من ASCII بطول 1 إلى 64 bytes, وcanonical value حتى 252 KiB, و256 keys / 2 MiB لكل principal, و64 operations / 32 mutations لكل تنفيذ. يسمح `autojs.ui.toast(text)` بأربع استدعاءات وبـ1 إلى 1024 bytes من UTF-8 الصارم, ويرسل مرة واحدة بلا retry. تعيد Host التي لا تمنح أيا من capability النتيجة `HOST_CAPABILITY` بلا local fallback أو retry.

المكتبات المتاحة هي base وstring وmath وtable وutf8 وcoroutine. أزيلت عمدا `io` و`os` و`debug` و`package` و`load` و`loadfile` و`dofile` و`string.dump` و`pcall` و`xpcall` و`getmetatable` و`setmetatable`. محمل المصدر الوحيد هو `luaL_loadbufferx(..., "t")` في وضع النص, لذلك لا تدخل chunk ثنائية. راجع [`docs/native-execution-core.md`](docs/native-execution-core.md) و[`docs/safe-standard-library-subset.md`](docs/safe-standard-library-subset.md).

******

### الحدود والأمان

******

- ينفذ نص واحد في كل عملية, مع جلستين مجهزتين كحد أقصى ومن دون queue; تفشل الطلبات الإضافية فورا.
- تحدد Host deadline شاملا وميزانية ذاكرة لكل تشغيل, ويطبق instruction hook الإلغاء وtimeout داخل الحلقات وcoroutine.
- يقيد الإخراج بالترتيب وcredit وchunk بحجم 32 KiB وسقف إجمالي; تنتهي حلقة الطباعة اللانهائية من دون إغراق Host.
- عند انتهاء cleanup تسمم العملية المخصصة ثم توقف قبل ربط عملية جديدة; يطلق كل fail-stop حدث `AutoJs6LuaWatchdog` بلا محتوى.
- قبل native crash أو إيقاف watchdog يحفظ تشخيص ثابت بحجم 20-byte فقط: failure kind وphase وبادئة source-hash بحجم 8-byte, وليس محتوى النص.

******

### التوافق

******

يلزم Android 24+ (minSdk 24, targetSdk 37), وABI من `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`, وAutoJs6 versionCode 5276 أو أحدث بالشهادة نفسها. تم أرشفة مصفوفة x86_64 للتثبيت والتنفيذ عبر Host الحقيقية والإزالة على API 24 و31 و36, إضافة إلى smoke شامل على API 37. يبنى arm64-v8a ويجتاز artifact gate لكنه لم يختبر على جهاز فعلي; تصلح العيوب عند الإبلاغ عنها.

******

### البناء

******

استخدم JDK 17+ وAndroid SDK وNDK `28.2.13676358` وCMake `3.22.1`. ابن Provider العادي للتطوير باستخدام Gradle Wrapper في المستودع:

```powershell
.\gradlew.bat :app:assembleProviderDebug
```

#### متغيرات البناء

- `providerDebug`: بناء تطوير عادي يتضمن خدمتي اكتشاف الإنتاج.
- `providerRelease`: متغير release الوحيد; من دون مواد توقيع خارجية ينتج artifact من نوع unsigned غير قابل للنشر فقط.
- `nativeTestDebug` / `faultTestDebug`: متغيرا instrumentation بمعرفي application ID مستقلين, مع إزالة اكتشاف الإنتاج فعليا من manifest المدمج.
- يترجم `faultTestDebug` وحده fault harness التخريبي; أزيلت مفاتيح Boolean القديمة `-Pautojs.lua.*.enabled`.

#### بناء release موقع

مرر إلى أداة بناء المستودع المسارات المطلقة لمواد التوقيع الخارجية وAPK من AutoJs6 يحمل التوقيع نفسه:

```powershell
.\tools\build_runnable_provider.ps1 `
    -SigningPropertiesFile '<absolute sign.properties path>' `
    -SigningStoreFile '<absolute JKS path>' `
    -HostApk '<absolute matching AutoJs6 APK path>'
```

تتطلب الأداة clean revision يساوي فيها commit count قيمة versionCode, وتعيد البناء بالكامل offline, وتقارن شهادتي Host وProvider, وتشغل artifact gate الصارم. يثبت receipt التغليف الموقع فقط ولا يعوض تثبيت الجهاز أو دليل runtime.

******

### حالة المشروع

******

الإصدار الحالي `0.1.3-rc.2` مرشح تغليف موقع تم التحقق منه محليا, وليس release عاما. revision المرشح هو `a0ae189ac8cba042848412a671c91b0b8a7c44e1`, وSHA-256 لملف APK universal هو `93f72bc7d38a975b939e97e9e8a47873fc43a1419290cda07f0b803d27c85dfd`; يحافظ receipt الثابت على `deviceVerified=false/runtimeVerified=false`. اجتاز split x86_64 لاحقا smoke عبر Host حقيقية على API 37 ومصفوفة API 24 و31 و36. ألغيت تجربة arm64 الفعلية وsoak سبعة أيام في 2026-08-26, وتتبع الاستقرار الآن fix-on-report. راجع [`docs/release-candidate-rc2.md`](docs/release-candidate-rc2.md) و[`docs/release-candidate-rc2-emulator-smoke.md`](docs/release-candidate-rc2-emulator-smoke.md) و[`docs/release-candidate-rc2-api-matrix.md`](docs/release-candidate-rc2-api-matrix.md) و[`docs/public-release-policy.md`](docs/public-release-policy.md). يوجد R3 في [ROADMAP.md](ROADMAP.md), وR4 في [ROADMAP-R4.md](ROADMAP-R4.md), وR5 في [ROADMAP-R5.md](ROADMAP-R5.md).

******

### التحقق وهندسة الإصدار

******

تعمل بوابات الصيانة offline افتراضيا لتقليل ضوضاء Cloudflare 502/524/529 في شبكة التطوير.

#### التوثيق المترجم

يحتوي `.readme/` و`.changelog/` على القوالب المشتركة ومصادر JSON لعشر لغات. ينشئ `.python/generate_markdown.py` عشرة ملفات README وCHANGELOG داخل APK, ويكتب `zh-Hans` إلى `README.md` الجذري. تحفظ نصوص Android في أدلة `values-*`. بعد تعديل JSON شغل:

```powershell
python .\.python\generate_markdown.py
python .\.python\generate_markdown.py --check
```

#### بوابة التحقق من دون اتصال

```powershell
.\tools\verify_local.ps1
```

تجمع الأداة verifier الجاهز للبناء واختبارات Python العدائية و`:app:testProviderDebugUnitTest --offline`, ثم تطابق تقارير XML مع test count الوحيد في `verification.properties`. تكرر CI الحدود نفسها.

#### المدخلات الثابتة

يتكون البروتوكول من ثلاثة ملفات AAR محلية مقفلة بـ SHA-256 فقط. المدخل الأصلي هو PUC Lua 5.4.8 مع archive SHA-256 `4f18ddae154e793e46eeab727c59ef1c0c0c2b744e7b94219710d76f530629ae`; يعيد verifier حساب بصمة الشجرة كاملة, ولا يسمح CMake إلا بالمصادر المراجعة. راجع [`protocol/README.md`](protocol/README.md) و[`vendor-lock.json`](app/src/main/cpp/vendor/vendor-lock.json).

#### التحقق من artifact الإصدار

يمكن بناء `providerRelease` من نوع unsigned, لكن المرشح القابل للنشر يجب أن يقرأ التوقيع من مسارات مطلقة خارجية وينفذ مهام fresh offline:

```powershell
$releaseArgs = @(
    ':app:clean'
    ':app:testProviderDebugUnitTest'
    ':app:assembleProviderRelease'
    '-Pautojs.lua.release.signingPropertiesFile=<absolute sign.properties path>'
    '-Pautojs.lua.release.signingStoreFile=<absolute JKS path>'
    '--rerun-tasks'
    '--offline'
    '--no-daemon'
    '--console=plain'
)
.\gradlew.bat @releaseArgs
```

```powershell
.\tools\verify_release_candidate_artifacts.ps1 `
    -InvocationStartedAtUtc '<UTC start of that Gradle invocation>' `
    -SigningPropertiesFile '<absolute sign.properties path>' `
    -SigningStoreFile '<absolute JKS path>' `
    -SdkRoot '<Android SDK root>'
```

ثم شغل artifact verifier بالشهادة نفسها. يفحص JVM count وsigner وABI ومحاذاة ZIP/ELF بحجم 16 KiB وnon-debuggable واستبعاد fault harness; ويبقى ذلك دليل تغليف فقط.

#### قائمة fault-harness قبل النشر

قبل إعادة بناء مرشح موقع, أنشئ متغير fault ووسائط release من نوع unsigned القابلة للتدقيق في clean canonical invocation واحدة:

```powershell
$faultStarted = [DateTimeOffset]::UtcNow.ToString(
    'yyyy-MM-ddTHH:mm:ss.ffffffZ'
)
$faultArgs = @(
    ':app:clean'
    ':app:assembleFaultTestDebug'
    ':app:compileProviderReleaseKotlin'
    ':app:processProviderReleaseMainManifest'
    ':app:externalNativeBuildProviderRelease'
    '--rerun-tasks'
    '--offline'
    '--no-daemon'
    '--console=plain'
)
.\gradlew.bat @faultArgs
.\tools\verify_fault_harness_artifacts.ps1 `
    -InvocationStartedAtUtc $faultStarted `
    -SdkRoot '<Android SDK root>'
```

يجب أن تكون علامة النجاح `RELEASE_VARIANT_FAULT_HARNESS_EXCLUSION_PASS`; يجب أن يحتوي `faultTestDebug` الخدمات المعزولة ورموز fault JNI, وأن يستبعدها `providerRelease`. شغل أيضا `LuaRuntimeFaultRecoveryInstrumentationTest` على جهاز مؤقت; لا يعوض receipt هذا الدليل. وإلا تكون الحالة `UNVERIFIED_FAULT_HARNESS`.

******

### قراءة إضافية

******

حدود التنفيذ: [`docs/native-execution-core.md`](docs/native-execution-core.md), [`docs/coroutine-control-boundary.md`](docs/coroutine-control-boundary.md), [`docs/pcall-boundary-decision.md`](docs/pcall-boundary-decision.md), [`docs/storage-kv-v1.md`](docs/storage-kv-v1.md), [`docs/ui-toast-v1.md`](docs/ui-toast-v1.md). التصاميم المستقبلية: [`docs/module-snapshot-v2.md`](docs/module-snapshot-v2.md), [`docs/result-model-v2.md`](docs/result-model-v2.md), [`docs/execution-statistics-v1.md`](docs/execution-statistics-v1.md). أدلة الإصدار: [`docs/host-lifecycle-matrix.md`](docs/host-lifecycle-matrix.md), [`docs/release-upgrade-matrix.md`](docs/release-upgrade-matrix.md), [`docs/public-release-policy.md`](docs/public-release-policy.md).

******

### سجل الإصدارات

******

# v0.1.3

###### 2026/10/04

* `تحسين` تستخدم أيقونات مركز الملحقات الأحجام والمواضع والصور الفاتحة والداكنة والخلفيات الدائرية المعدلة في Icon Studio مع الاحتفاظ بالمصادر والمعلمات لإعادة إنتاجها

# v0.1.2

###### 2026/09/19

* `إصلاح` تحذيرات قراءة SDK XML v4 مع AGP 9.1 وتشغيل فحص محاذاة مكتبات APK الأصلية خطأ عند تجميع اختبارات JVM, باستخدام إضافات البناء المشتركة 1.8.3
* `تحسين` رفع compileSdk و targetSdk إلى 37 (Android 17)؛ لا يعتمد سلوك المكون الإضافي على الهدف الجديد

# v0.1.1

###### 2026/09/11

* `تحسين` التحقق أثناء البناء من محاذاة صفحات 16 KB للمكتبات الأصلية ذات 64 بت, مع فحص عقد manifest وتقارير JSON
* `تحسين` إضافة تنشيط مضيف محمي وبيانات دقيقة للحزمة المثبتة وجمع الإصدارات الموقعة مع توحيد الموارد وفحص المستندات للقراءة فقط
* `تحسين` توسيع حزم ABI الأصلية وبيانات الإضافة الوصفية لتشمل arm64-v8a وarmeabi-v7a وx86 وx86_64, مع ملفات APK عامة ومنفصلة متطابقة لكل ABI

##### مزيد من الإصدارات

* [CHANGELOG.md](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/app/src/main/assets/doc/CHANGELOG-ar.md)

******

### الترخيص

******

توزع شيفرة المستودع وفق MIT License. تشمل المكونات الخارجية PUC Lua وفق MIT, وKotlin وJetBrains annotations وفق Apache-2.0, وأجزاء Android NDK LLVM runtime المرتبطة ساكنا وفق شروط LLVM المسجلة, وواجهات بروتوكول AutoJs6 وفق MPL-2.0. راجع [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) والنصوص الكاملة.

******

### بنية الموارد المترجمة

******

```text
.readme/lang_*.json
.changelog/lang_*.json
.python/generate_markdown.py
app/src/main/assets/doc/CHANGELOG-*.md
app/src/main/res/values-*/strings.xml
```

ينشئ `.python/generate_markdown.py` ملفات README وCHANGELOG داخل APK لكل اللغات العشر من JSON; عدل مصادر JSON بدلا من Markdown الناتج. تدار نصوص Android في أدلة الموارد الخاصة بها.

******

### الروابط

******

- مشروع AutoJs6: https://github.com/SuperMonster003/AutoJs6
- مشروع Lua: https://www.lua.org
- إشعارات الجهات الخارجية: https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/THIRD_PARTY_NOTICES.md
- ترخيص المشروع: https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/LICENSE


[16 KB page alignment and build verification](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/main/docs/16kb.md)
