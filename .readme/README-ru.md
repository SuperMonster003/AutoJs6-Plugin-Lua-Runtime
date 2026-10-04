<!--suppress HtmlDeprecatedAttribute, HttpUrlsUsage -->

<div align="center">
  <p>
    <img src="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/app/src/main/res/mipmap/ic_launcher.png?raw=true" alt="lua-runtime-ic-launcher" border="0" width="128" />
  </p>

  <h1>Плагин среды Lua для AutoJs6</h1>

  <p>Запускает стандартные сценарии PUC Lua 5.4.8 в отдельном изолированном процессе</p>

  <p>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/releases"><img alt="GitHub release (latest by date)" src="https://img.shields.io/github/v/release/SuperMonster003/AutoJs6-Plugin-Lua-Runtime?label=Release"/></a>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/issues"><img alt="GitHub closed issues" src="https://img.shields.io/github/issues/SuperMonster003/AutoJs6-Plugin-Lua-Runtime?color=A24232&label=Issues"/></a>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/LICENSE"><img alt="GitHub License" src="https://img.shields.io/github/license/SuperMonster003/AutoJs6-Plugin-Lua-Runtime?color=534BAE&label=License"/></a>
  </p>
</div>

******

### Языки (Languages)

******

Текущий README.md доступен на следующих языках:

- [简体中文 [zh-Hans]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-zh-Hans.md)
- [繁體中文 (香港) [zh-Hant-HK]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-zh-Hant-HK.md)
- [繁體中文 (台灣) [zh-Hant-TW]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-zh-Hant-TW.md)
- [English [en]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-en.md)
- [Français [fr]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-fr.md)
- [Español [es]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-es.md)
- [日本語 [ja]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ja.md)
- [한국어 [ko]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ko.md)
- Русский [ru] # текущий
- [العربية [ar]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ar.md)

******

### Введение

******

Сам AutoJs6 выполняет JavaScript. Этот плагин добавляет второй язык сценариев: создайте файл `.lua` в редакторе AutoJs6 и запустите его; исходный текст будет передан отдельно установленному Provider, который выполняет PUC Lua 5.4.8 внутри выделенного процесса `:lua_runtime`. Сценариям доступен проверенный набор стандартных библиотек Lua и небольшой мост `autojs`, но недоступны файлы, процессы, переменные окружения и динамические нативные модули.

```text
application ID: io.github.supermonster003.autojs6.plugin.lua.runtime
plugin / engine / variant: lua-runtime / lua / puc-lua54
discovery actions: org.autojs.plugin.INFO / org.autojs.plugin.lua.RUNTIME
runtime process: :lua_runtime
minimum host build: 5276
```

Обе службы обнаружения защищены разрешением подписи `org.autojs.permission.PLUGIN`. Host и плагин должны использовать один сертификат; каждая capability остается выключенной, пока ее не разрешат согласование протокола и проверка. Публичного APK пока нет; смотрите разделы Сборка и Состояние проекта.

******

### Возможности

******

- Запускает текстовые сценарии Lua 5.4.8 (`.lua`) из редактора AutoJs6, передает console в реальном времени и возвращает один скалярный результат.
- Предоставляет контролируемые библиотеки base, string, math, table, utf8 и coroutine; coroutine наследуют deadline, отмену и учет памяти.
- Открывает `autojs.console`, `autojs.now()`, доступный только для чтения `autojs.arguments`, `autojs.device.info()`, постоянное хранилище Host `autojs.storage` и сквозной `autojs.ui.toast()`.
- Поддерживает соседние snapshot модулей: `job.lua` может загрузить текстовый модуль UTF-8 до 64 KiB из `job.modules/name.lua`.
- Выполняет одну задачу на процесс с заданными Host deadline, бюджетом памяти, кредитами вывода и fail-stop watchdog.
- Перед выполнением повторно проверяет точную длину, SHA-256 и строгий UTF-8; предварительно скомпилированные и бинарные Lua chunk всегда отклоняются.
- Поставляет нативные библиотеки `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` с выравниванием 16 KiB, а также README, CHANGELOG и тексты Android на 10 языках.

******

### Быстрый старт

******

**Как установить?** Публичного APK пока нет; соберите его по инструкции ниже. `providerDebug` должен использоваться с AutoJs6 debug, подписанным тем же сертификатом; release Provider требует release Host с той же подписью.

**Как включить?** Переключателя нет. AutoJs6 автоматически обнаруживает Provider с той же подписью, а для Lua нет экспериментального свойства Boolean.

**Как запустить сценарий?** Создайте в редакторе AutoJs6 файл с окончанием `.lua`, напишите исходный код Lua и нажмите запуск. console передается в реальном времени, а по завершении Host получает скалярное значение.

**Что проверить при ошибке?** Host с versionCode ниже 5276 отклоняет dispatch как `LUA_RUNTIME_UNAVAILABLE/HOST_VERSION_UNSUPPORTED`. Истечение deadline дает `TIMEOUT`, превышение памяти `MEMORY_LIMIT`, а неразрешенный мост Host `HOST_CAPABILITY`; это детерминированные безопасные завершения.

******

### Пример использования

******

Этот файловый пример использует постоянное хранилище R5 и Toast, разрешенные совместимым Host:

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

Для повторного использования кода создайте `job.modules/` рядом с входным `job.lua`, поместите туда `helper.lua` и вызовите `require("helper")`. Имена модулей V1 являются плоскими ASCII идентификаторами до 64 символов; пути, точки, бинарные модули и модули C отклоняются.

******

### API сценариев

******

`require("autojs")` возвращает таблицу моста. `autojs.console.log/info(text)` пишет в stdout, `error/warn(text)` в stderr; глобальные `print(...)` и `warn(text)` используют те же контролируемые потоки. `autojs.now()` возвращает миллисекунды Unix epoch. `autojs.arguments` содержит snapshot аргументов только для чтения. `autojs.device.info()` возвращает brand, manufacturer, model, device, product и sdkInt. Для файлового script со стабильным ID `autojs.storage.get/put/remove/clear` хранит значения в Host: ASCII key от 1 до 64 bytes, canonical value до 252 KiB, 256 keys / 2 MiB на principal, 64 operations / 32 mutations на выполнение. `autojs.ui.toast(text)` допускает до 4 вызовов и от 1 до 1024 bytes строгого UTF-8, отправляет один раз и не повторяет. Host без любой из этих capability возвращает `HOST_CAPABILITY` без local fallback или retry.

Доступны base, string, math, table, utf8 и coroutine. `io`, `os`, `debug`, `package`, `load`, `loadfile`, `dofile`, `string.dump`, `pcall`, `xpcall`, `getmetatable` и `setmetatable` намеренно выключены. Единственный загрузчик исходного кода работает в текстовом режиме `luaL_loadbufferx(..., "t")`, поэтому бинарный chunk не попадет в среду. Подробности в [`docs/native-execution-core.md`](docs/native-execution-core.md) и [`docs/safe-standard-library-subset.md`](docs/safe-standard-library-subset.md).

******

### Ограничения и безопасность

******

- В процессе выполняется один сценарий, сохраняется не более двух подготовленных session и нет queue; дополнительные request сразу завершаются ошибкой.
- Host задает сквозной deadline и бюджет памяти; instruction hook применяет отмену и timeout внутри плотных циклов и coroutine.
- Вывод ограничен порядком, credit, chunk 32 KiB и общим пределом; бесконечная печать завершается детерминированно и не переполняет Host.
- При истечении cleanup выделенный процесс помечается poison и завершается, после чего Host связывается с новым; каждый fail-stop создает событие `AutoJs6LuaWatchdog` без содержимого.
- Перед native crash или остановкой watchdog хранится только фиксированная диагностика 20-byte: failure kind, phase и 8-byte префикс source-hash, без текста сценария.

******

### Совместимость

******

Требуются Android 24+ (minSdk 24, targetSdk 37), ABI `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`, а также AutoJs6 versionCode 5276 или новее с тем же сертификатом. Матрица x86_64 для установки, выполнения с реальным Host и удаления сохранена для API 24, 31 и 36, плюс end-to-end smoke API 37. arm64-v8a собирается и проходит artifact gate, но не проверялся на физическом устройстве; найденные в работе дефекты исправляются по отчетам.

******

### Сборка

******

Используйте JDK 17+, Android SDK, NDK `28.2.13676358` и CMake `3.22.1`. Обычный Provider для разработки собирается Gradle Wrapper из репозитория:

```powershell
.\gradlew.bat :app:assembleProviderDebug
```

#### Варианты сборки

- `providerDebug`: обычная сборка для разработки с двумя производственными службами обнаружения.
- `providerRelease`: единственный release вариант; без внешней подписи создает только непубликуемый unsigned artifact.
- `nativeTestDebug` / `faultTestDebug`: instrumentation варианты с отдельными application ID и физически удаленным из merged manifest производственным обнаружением.
- Разрушительный fault harness компилирует только `faultTestDebug`; старые Boolean переключатели `-Pautojs.lua.*.enabled` удалены.

#### Подписанная release сборка

Передайте сборщику репозитория абсолютные пути к внешним материалам подписи и APK AutoJs6 с той же подписью:

```powershell
.\tools\build_runnable_provider.ps1 `
    -SigningPropertiesFile '<absolute sign.properties path>' `
    -SigningStoreFile '<absolute JKS path>' `
    -HostApk '<absolute matching AutoJs6 APK path>'
```

Сценарий требует clean revision, где commit count равен versionCode, выполняет полную offline пересборку, сравнивает сертификаты Host/Provider и запускает строгий artifact gate. Receipt доказывает только подписанную упаковку, а не установку или выполнение на устройстве.

******

### Состояние проекта

******

Текущая версия `0.1.3-rc.2` является локально проверенным кандидатом подписанной упаковки, а не публичным release. Revision кандидата `a0ae189ac8cba042848412a671c91b0b8a7c44e1`, SHA-256 universal APK `93f72bc7d38a975b939e97e9e8a47873fc43a1419290cda07f0b803d27c85dfd`; immutable receipt сохраняет `deviceVerified=false/runtimeVerified=false`. Затем split x86_64 прошел smoke с реальным Host на API 37 и матрицу API 24, 31, 36. Физическая проверка arm64 и семидневный soak были сняты 2026-08-26; далее действует fix-on-report. См. [`docs/release-candidate-rc2.md`](docs/release-candidate-rc2.md), [`docs/release-candidate-rc2-emulator-smoke.md`](docs/release-candidate-rc2-emulator-smoke.md), [`docs/release-candidate-rc2-api-matrix.md`](docs/release-candidate-rc2-api-matrix.md), [`docs/public-release-policy.md`](docs/public-release-policy.md). R3 находится в [ROADMAP.md](ROADMAP.md), R4 в [ROADMAP-R4.md](ROADMAP-R4.md), R5 в [ROADMAP-R5.md](ROADMAP-R5.md).

******

### Проверка и выпуск

******

Проверки сопровождения по умолчанию работают offline, чтобы уменьшить шум Cloudflare 502/524/529 в сети разработки.

#### Локализованная документация

`.readme/` и `.changelog/` содержат общие template и JSON для 10 языков. `.python/generate_markdown.py` создает 10 README и встроенные CHANGELOG, а `zh-Hans` записывает в корневой `README.md`. Строки Android находятся в каталогах `values-*`. После изменения JSON выполните:

```powershell
python .\.python\generate_markdown.py
python .\.python\generate_markdown.py --check
```

#### Автономная проверка

```powershell
.\tools\verify_local.ps1
```

Сценарий объединяет build-ready verifier, hostile suite Python и `:app:testProviderDebugUnitTest --offline`, затем сверяет XML report с единым test count из `verification.properties`. CI повторяет те же границы.

#### Неизменяемые входы

Протокол содержит ровно три локальных AAR, закрепленных SHA-256. Нативный вход PUC Lua 5.4.8 имеет archive SHA-256 `4f18ddae154e793e46eeab727c59ef1c0c0c2b744e7b94219710d76f530629ae`; verifier пересчитывает fingerprint всего дерева, а CMake допускает только проверенные source. См. [`protocol/README.md`](protocol/README.md) и [`vendor-lock.json`](app/src/main/cpp/vendor/vendor-lock.json).

#### Проверка release artifact

`providerRelease` можно собрать unsigned, но публикуемый кандидат должен читать подпись из внешних абсолютных путей и выполнять fresh offline задачи:

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

Затем запустите artifact verifier с тем же сертификатом. Он проверяет JVM count, signer, ABI, выравнивание ZIP/ELF 16 KiB, non-debuggable и отсутствие fault harness; это остается доказательством упаковки.

#### Контроль fault-harness перед выпуском

Перед пересборкой подписанного кандидата создайте fault variant и проверяемые unsigned release intermediate одним clean canonical invocation:

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

Маркер успеха должен быть `RELEASE_VARIANT_FAULT_HARNESS_EXCLUSION_PASS`; `faultTestDebug` должен содержать изолированные службы и fault JNI symbol, а `providerRelease` должен исключать их. Также запустите `LuaRuntimeFaultRecoveryInstrumentationTest` на одноразовом устройстве; receipt не заменяет это доказательство. Иначе состояние `UNVERIFIED_FAULT_HARNESS`.

******

### Дополнительные материалы

******

Границы выполнения: [`docs/native-execution-core.md`](docs/native-execution-core.md), [`docs/coroutine-control-boundary.md`](docs/coroutine-control-boundary.md), [`docs/pcall-boundary-decision.md`](docs/pcall-boundary-decision.md), [`docs/storage-kv-v1.md`](docs/storage-kv-v1.md), [`docs/ui-toast-v1.md`](docs/ui-toast-v1.md). Будущие проекты: [`docs/module-snapshot-v2.md`](docs/module-snapshot-v2.md), [`docs/result-model-v2.md`](docs/result-model-v2.md), [`docs/execution-statistics-v1.md`](docs/execution-statistics-v1.md). Доказательства выпуска: [`docs/host-lifecycle-matrix.md`](docs/host-lifecycle-matrix.md), [`docs/release-upgrade-matrix.md`](docs/release-upgrade-matrix.md), [`docs/public-release-policy.md`](docs/public-release-policy.md).

******

### История выпусков

******

# v0.1.3

###### 2026/10/04

* `Улучшено` Значки центра плагинов используют размеры, положение, светлые и тёмные изображения и круглые фоны, настроенные в Icon Studio, сохраняя исходники и параметры для воспроизведения

# v0.1.2

###### 2026/09/19

* `Исправлено` Предупреждения чтения SDK XML v4 с AGP 9.1 и ошибочный запуск проверки выравнивания нативных библиотек APK при сборке модульных тестов JVM, устраненные общими плагинами сборки 1.8.3
* `Улучшено` Подняты compileSdk и targetSdk до 37 (Android 17); поведение плагина не зависит от нового целевого уровня

# v0.1.1

###### 2026/09/11

* `Улучшено` Проверка выравнивания страниц 16 KB для 64-битных нативных библиотек при сборке, включая контракт manifest и отчеты JSON
* `Улучшено` Добавлены защищенная активация, точные метаданные установленного пакета и сбор подписанных выпусков; согласованы локализация и проверка документации без записи
* `Улучшено` Расширение набора нативных ABI и метаданных плагина до arm64-v8a, armeabi-v7a, x86 и x86_64 с согласованными универсальными и отдельными APK для каждой ABI

##### Другие выпуски

* [CHANGELOG.md](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/app/src/main/assets/doc/CHANGELOG-ru.md)

******

### Лицензия

******

Собственный код репозитория распространяется по MIT License. Сторонние компоненты включают PUC Lua по MIT, Kotlin и JetBrains annotations по Apache-2.0, статически связанные части Android NDK LLVM runtime по записанным условиям LLVM и API протокола AutoJs6 по MPL-2.0. См. [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) и полные тексты.

******

### Структура локализованных ресурсов

******

```text
.readme/lang_*.json
.changelog/lang_*.json
.python/generate_markdown.py
app/src/main/assets/doc/CHANGELOG-*.md
app/src/main/res/values-*/strings.xml
```

`.python/generate_markdown.py` создает README и встроенный CHANGELOG для всех 10 языков из JSON; изменяйте JSON, а не сгенерированный Markdown. Строки Android хранятся в каталогах ресурсов.

******

### Ссылки

******

- Проект AutoJs6: https://github.com/SuperMonster003/AutoJs6
- Проект Lua: https://www.lua.org
- Уведомления третьих сторон: https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/THIRD_PARTY_NOTICES.md
- Лицензия проекта: https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/LICENSE


[16 KB page alignment and build verification](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/main/docs/16kb.md)
