<!--suppress HtmlDeprecatedAttribute, HttpUrlsUsage -->

<div align="center">
  <p>
    <img src="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/app/src/main/res/mipmap/ic_launcher.png?raw=true" alt="lua-runtime-ic-launcher" border="0" width="128" />
  </p>

  <h1>Plugin de ejecución Lua para AutoJs6</h1>

  <p>Ejecuta scripts PUC Lua 5.4.8 estándar en un proceso dedicado y aislado</p>

  <p>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/releases"><img alt="GitHub release (latest by date)" src="https://img.shields.io/github/v/release/SuperMonster003/AutoJs6-Plugin-Lua-Runtime?label=Release"/></a>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/issues"><img alt="GitHub closed issues" src="https://img.shields.io/github/issues/SuperMonster003/AutoJs6-Plugin-Lua-Runtime?color=A24232&label=Issues"/></a>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/LICENSE"><img alt="GitHub License" src="https://img.shields.io/github/license/SuperMonster003/AutoJs6-Plugin-Lua-Runtime?color=534BAE&label=License"/></a>
  </p>
</div>

******

### Idiomas (Languages)

******

El README.md actual está disponible en los siguientes idiomas:

- [简体中文 [zh-Hans]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-zh-Hans.md)
- [繁體中文 (香港) [zh-Hant-HK]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-zh-Hant-HK.md)
- [繁體中文 (台灣) [zh-Hant-TW]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-zh-Hant-TW.md)
- [English [en]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-en.md)
- [Français [fr]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-fr.md)
- Español [es] # actual
- [日本語 [ja]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ja.md)
- [한국어 [ko]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ko.md)
- [Русский [ru]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ru.md)
- [العربية [ar]](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/.readme/README-ar.md)

******

### Introducción

******

AutoJs6 ejecuta JavaScript de forma nativa. Este plugin añade un segundo lenguaje: crea un archivo `.lua` en el editor de AutoJs6 y ejecútalo; el código se entrega a un Provider instalado por separado, que ejecuta PUC Lua 5.4.8 dentro del proceso dedicado `:lua_runtime`. Los scripts reciben un subconjunto revisado de la biblioteca Lua y un puente `autojs` pequeño, sin acceso a archivos, procesos, variables de entorno ni módulos nativos dinámicos.

```text
application ID: io.github.supermonster003.autojs6.plugin.lua.runtime
plugin / engine / variant: lua-runtime / lua / puc-lua54
discovery actions: org.autojs.plugin.INFO / org.autojs.plugin.lua.RUNTIME
runtime process: :lua_runtime
minimum host build: 5276
```

Los dos servicios de descubrimiento están protegidos por el permiso de firma `org.autojs.permission.PLUGIN`. El host y el plugin deben usar el mismo certificado; cada capacidad permanece desactivada hasta que la negociación y validación del protocolo la admitan. Todavía no hay un APK público; consulta Construcción y Estado del proyecto.

******

### Funciones

******

- Ejecuta scripts Lua 5.4.8 (`.lua`) de texto plano desde el editor AutoJs6, transmite la consola en vivo y devuelve un resultado escalar al terminar.
- Ofrece las bibliotecas controladas base, string, math, table, utf8 y coroutine; las coroutines heredan deadline, cancelación y contabilidad de memoria.
- Expone `autojs.console`, `autojs.now()`, `autojs.arguments` de solo lectura, `autojs.device.info()`, `autojs.storage` persistente en Host y `autojs.ui.toast()` de extremo a extremo.
- Carga snapshots de módulos adyacentes: `job.lua` puede leer módulos UTF-8 de hasta 64 KiB desde `job.modules/name.lua`.
- Ejecuta una tarea por proceso con deadline, presupuesto de memoria, créditos de salida y watchdog fail-stop definidos por el host.
- Verifica de nuevo longitud exacta, SHA-256 y UTF-8 estricto antes de ejecutar; siempre rechaza chunks Lua precompilados o binarios.
- Incluye bibliotecas nativas `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` alineadas a 16 KiB, más README, CHANGELOG y textos Android en 10 idiomas.

******

### Inicio rápido

******

**Cómo se instala?** Todavía no hay APK público; compílalo como se indica abajo. `providerDebug` debe acompañar a un AutoJs6 de depuración firmado con el mismo certificado; un Provider release debe acompañar al host release con la misma firma.

**Cómo se activa?** No hay interruptor. AutoJs6 descubre automáticamente un Provider con la misma firma y Lua no usa ninguna propiedad Boolean experimental.

**Cómo se ejecuta un script?** Crea en el editor AutoJs6 un archivo que termine en `.lua`, escribe el código Lua y pulsa ejecutar. La consola se transmite en vivo y el host recibe el escalar devuelto al finalizar.

**Qué revisar si falla?** Un host anterior al versionCode 5276 rechaza el envío con `LUA_RUNTIME_UNAVAILABLE/HOST_VERSION_UNSUPPORTED`. El deadline termina como `TIMEOUT`, el exceso de memoria como `MEMORY_LIMIT` y un puente Host no concedido como `HOST_CAPABILITY`; son finales de seguridad deterministas.

******

### Ejemplo de uso

******

Este ejemplo basado en archivo usa almacenamiento R5 y Toast concedidos por un Host compatible:

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

Para reutilizar código, crea `job.modules/` junto al script de entrada `job.lua`, añade `helper.lua` y llama a `require("helper")`. Los nombres V1 son identificadores ASCII planos de 64 caracteres como máximo; se rechazan rutas, puntos, módulos binarios y módulos C.

******

### API de scripts

******

`require("autojs")` devuelve la tabla del puente. `autojs.console.log/info(text)` escribe stdout y `error/warn(text)` stderr; las funciones globales `print(...)` y `warn(text)` usan los mismos flujos controlados. `autojs.now()` devuelve milisegundos Unix. `autojs.arguments` es el snapshot de argumentos de solo lectura. `autojs.device.info()` devuelve brand, manufacturer, model, device, product y sdkInt. Para un script de archivo con identidad estable, `autojs.storage.get/put/remove/clear` ofrece valores persistentes del Host: claves ASCII de 1 a 64 bytes, valor canónico de hasta 252 KiB y 256 claves / 2 MiB por principal; cada ejecución admite 64 operaciones y 32 mutaciones. `autojs.ui.toast(text)` admite hasta 4 llamadas y de 1 a 1024 bytes UTF-8 estrictos, envía una vez y nunca reintenta. Un Host que no conceda cualquiera de estas capacidades devuelve `HOST_CAPABILITY`, sin fallback local ni reintento.

Las bibliotecas disponibles son base, string, math, table, utf8 y coroutine. `io`, `os`, `debug`, `package`, `load`, `loadfile`, `dofile`, `string.dump`, `pcall`, `xpcall`, `getmetatable` y `setmetatable` están desactivados a propósito. El único cargador es `luaL_loadbufferx(..., "t")` en modo texto, por lo que ningún chunk binario entra al runtime. Consulta [`docs/native-execution-core.md`](docs/native-execution-core.md) y [`docs/safe-standard-library-subset.md`](docs/safe-standard-library-subset.md).

******

### Límites y seguridad

******

- Se ejecuta un script por proceso, se retienen como máximo dos sesiones preparadas y no hay cola; las solicitudes adicionales fallan de inmediato.
- El host fija un deadline extremo a extremo y un presupuesto de memoria; un hook de instrucciones aplica cancelación y timeout dentro de bucles y coroutines.
- La salida se limita por orden, crédito, chunks de 32 KiB y un máximo total; un bucle de impresión infinito termina sin inundar el host.
- Si expira la limpieza, el proceso dedicado se envenena y termina antes de enlazar uno nuevo; cada fail-stop emite un evento `AutoJs6LuaWatchdog` sin contenido.
- Antes de un crash nativo o una terminación watchdog solo se guarda un diagnóstico fijo de 20-byte: tipo de fallo, fase y prefijo source-hash de 8-byte, nunca el script.

******

### Compatibilidad

******

Se requieren Android 24+ (minSdk 24, targetSdk 37), ABI `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`, y AutoJs6 versionCode 5276 o más reciente con el mismo certificado. La matriz x86_64 de instalación, ejecución con host real y desinstalación está archivada para API 24, 31 y 36, más un smoke end-to-end API 37. arm64-v8a se compila y verifica como artefacto, pero no se ha ejecutado en un dispositivo físico; los defectos reales se corrigen cuando se informan.

******

### Construcción

******

Usa JDK 17+, Android SDK, NDK `28.2.13676358` y CMake `3.22.1`. Compila el Provider de desarrollo con el Gradle Wrapper del repositorio:

```powershell
.\gradlew.bat :app:assembleProviderDebug
```

#### Variantes de construcción

- `providerDebug`: compilación de desarrollo normal con los dos servicios de descubrimiento de producción.
- `providerRelease`: única variante release; sin firma externa solo genera un artefacto unsigned no publicable.
- `nativeTestDebug` / `faultTestDebug`: variantes de instrumentación con application ID independientes y servicios de producción eliminados físicamente del manifest combinado.
- Solo `faultTestDebug` compila el fault harness destructivo; los antiguos interruptores Boolean `-Pautojs.lua.*.enabled` ya no existen.

#### Construcción release firmada

Usa el constructor del repositorio con rutas absolutas al material de firma externo y a un APK AutoJs6 con la misma firma:

```powershell
.\tools\build_runnable_provider.ps1 `
    -SigningPropertiesFile '<absolute sign.properties path>' `
    -SigningStoreFile '<absolute JKS path>' `
    -HostApk '<absolute matching AutoJs6 APK path>'
```

El script exige una revision limpia cuyo número de commits coincida con versionCode, reconstruye desde cero sin red, compara certificados Host/Provider y ejecuta el control estricto de artefactos. El recibo solo prueba el empaquetado firmado, no la instalación ni la ejecución en dispositivo.

******

### Estado del proyecto

******

La versión actual `0.1.2-rc.2` es un candidato de empaquetado firmado validado localmente, no una publicación pública. La revision candidata es `a0ae189ac8cba042848412a671c91b0b8a7c44e1` y el SHA-256 del APK universal es `93f72bc7d38a975b939e97e9e8a47873fc43a1419290cda07f0b803d27c85dfd`; el recibo inmutable conserva `deviceVerified=false/runtimeVerified=false`. El split x86_64 superó después el smoke con host real en API 37 y la matriz API 24, 31 y 36. La prueba física arm64 y el soak de siete días se retiraron el 2026-08-26; la estabilidad ahora sigue fix-on-report. Consulta [`docs/release-candidate-rc2.md`](docs/release-candidate-rc2.md), [`docs/release-candidate-rc2-emulator-smoke.md`](docs/release-candidate-rc2-emulator-smoke.md), [`docs/release-candidate-rc2-api-matrix.md`](docs/release-candidate-rc2-api-matrix.md) y [`docs/public-release-policy.md`](docs/public-release-policy.md). R3 está en [ROADMAP.md](ROADMAP.md), R4 en [ROADMAP-R4.md](ROADMAP-R4.md) y R5 en [ROADMAP-R5.md](ROADMAP-R5.md).

******

### Verificación y publicación

******

Los controles de mantenimiento funcionan sin red por defecto para reducir el ruido Cloudflare 502/524/529 de la red de desarrollo.

#### Documentación localizada

`.readme/` y `.changelog/` guardan las plantillas y fuentes JSON de 10 idiomas. `.python/generate_markdown.py` genera 10 README, los CHANGELOG incluidos y escribe `zh-Hans` en el `README.md` raíz. Las cadenas Android se mantienen en los directorios `values-*`. Tras cambiar JSON, ejecuta:

```powershell
python .\.python\generate_markdown.py
python .\.python\generate_markdown.py --check
```

#### Control sin conexión

```powershell
.\tools\verify_local.ps1
```

Este script encadena el verifier build-ready, la suite Python hostil y `:app:testProviderDebugUnitTest --offline`, y comprueba los informes XML con el recuento único de `verification.properties`. CI replica esos límites.

#### Entradas inmutables

El protocolo contiene exactamente tres AAR locales bloqueados por SHA-256. La entrada nativa es PUC Lua 5.4.8, archive SHA-256 `4f18ddae154e793e46eeab727c59ef1c0c0c2b744e7b94219710d76f530629ae`; el verifier recalcula la huella completa y CMake solo admite fuentes revisadas. Consulta [`protocol/README.md`](protocol/README.md) y [`vendor-lock.json`](app/src/main/cpp/vendor/vendor-lock.json).

#### Validación de artefactos release

`providerRelease` puede compilarse unsigned, pero un candidato publicable debe leer las firmas desde rutas absolutas externas y ejecutar tareas fresh sin conexión:

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

Después ejecuta el verifier con el mismo certificado. Comprueba recuento JVM, signer, ABI, alineación ZIP/ELF de 16 KiB, non-debuggable y exclusión del fault harness; sigue siendo evidencia de empaquetado.

#### Lista fault-harness previa a la publicación

Antes de reconstruir un candidato firmado, genera la variante fault y los intermedios release unsigned en una sola invocación clean canónica:

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

El marcador debe ser `RELEASE_VARIANT_FAULT_HARNESS_EXCLUSION_PASS`; `faultTestDebug` debe contener servicios aislados y símbolos JNI fault, y `providerRelease` debe excluirlos. Ejecuta además `LuaRuntimeFaultRecoveryInstrumentationTest` en un dispositivo desechable; el recibo no sustituye esa evidencia. En caso contrario queda `UNVERIFIED_FAULT_HARNESS`.

******

### Más información

******

Límites de ejecución: [`docs/native-execution-core.md`](docs/native-execution-core.md), [`docs/coroutine-control-boundary.md`](docs/coroutine-control-boundary.md), [`docs/pcall-boundary-decision.md`](docs/pcall-boundary-decision.md), [`docs/storage-kv-v1.md`](docs/storage-kv-v1.md), [`docs/ui-toast-v1.md`](docs/ui-toast-v1.md). Diseños futuros: [`docs/module-snapshot-v2.md`](docs/module-snapshot-v2.md), [`docs/result-model-v2.md`](docs/result-model-v2.md), [`docs/execution-statistics-v1.md`](docs/execution-statistics-v1.md). Evidencia de publicación: [`docs/host-lifecycle-matrix.md`](docs/host-lifecycle-matrix.md), [`docs/release-upgrade-matrix.md`](docs/release-upgrade-matrix.md), [`docs/public-release-policy.md`](docs/public-release-policy.md).

******

### Historial de versiones

******

# v0.1.2

###### 2026/09/15

* `Mejora` compileSdk y targetSdk suben a 37 (Android 17); el comportamiento del plugin no depende del nuevo objetivo

# v0.1.1

###### 2026/09/11

* `Mejora` Verificación de compilación de la alineación de páginas de 16 KB en bibliotecas nativas de 64 bits, con controles del contrato manifest e informes JSON
* `Mejora` Añadir activación protegida, metadatos precisos y recopilación de versiones firmadas; unificar recursos y comprobaciones documentales de solo lectura
* `Mejora` Ampliar el empaquetado de ABI nativas y los metadatos del complemento a arm64-v8a, armeabi-v7a, x86 y x86_64, con APK universales e individuales coherentes

# v0.1.0-rc.2

###### 2026/08/27

* `Nota` Sigue siendo un candidato de empaquetado firmado validado localmente: no existe tag público ni GitHub Release y el recibo inmutable conserva `deviceVerified=false/runtimeVerified=false`
* `Nota` El smoke físico arm64-v8a y el soak de siete días se retiraron por decisión del owner; los dos días completados y el estándar congelado siguen archivados y la estabilidad pasa a fix-on-report
* `Función` Se añadieron coroutines controladas, `autojs.now()`, `console.info/warn`, el lado Provider de `ui.toast.v1`, diagnósticos de crash y eventos `AutoJs6LuaWatchdog`
* `Función` Se implementó `storage.kv.v1` negociado con persistencia Host aislada por script de archivo, formas fijas get/put/remove/clear, valores canónicos acotados y sin reintento, y se completó `ui.toast.v1` entregado por Host
* `Función` Se añadieron snapshots de módulos de texto, argumentos de solo lectura y el puente de información del dispositivo manteniendo deadline, cancelación, memoria y cuotas de salida
* `Función` Se incluyen bibliotecas arm64-v8a y x86_64 con evidencia x86_64 de Host real archivada en API 24, 31, 36 y 37
* `Corrección` Un deadline mínimo que vence antes de llegar `start()` ahora produce un único terminal `TIMEOUT/QUEUE` en lugar de una sesión sin estado final
* `Corrección` Se reforzaron `math.randomseed` y el mapeo de rechazo de capability Host para que las llamadas no concedidas terminen como `HOST_CAPABILITY`
* `Mejora` Se sustituyeron cuatro modos Boolean por `providerDebug/providerRelease/nativeTestDebug/faultTestDebug` y se aislaron físicamente el descubrimiento de producción y el fault harness destructivo
* `Mejora` Se migró a la convención `.python/generate_markdown.py` + `.readme/` + `.changelog/` de los plugins hermanos, con documentación completa en 10 idiomas y `zh-Hans` como README raíz
* `Mejora` R5 se separó de `ROADMAP-R4.md` en `ROADMAP-R5.md` y se eliminó la tarea obsoleta de ranuras de chino tradicional
* `Mejora` Se añadieron contabilidad PFD lógica y de OS, control offline en un comando, CI resistente, validación release y auditorías de exclusión fault-harness
* `Dependencia` Se fijan PUC Lua 5.4.8, Android NDK 28.2.13676358 y CMake 3.22.1

##### Más versiones

* [CHANGELOG.md](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/app/src/main/assets/doc/CHANGELOG-es.md)

******

### Licencia

******

El código propio del repositorio usa MIT License. Los componentes de terceros incluyen PUC Lua bajo MIT, Kotlin y JetBrains annotations bajo Apache-2.0, partes Android NDK LLVM runtime enlazadas estáticamente bajo sus términos LLVM, y las API del protocolo AutoJs6 bajo MPL-2.0. Consulta [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) y los textos completos.

******

### Diseño de recursos localizados

******

```text
.readme/lang_*.json
.changelog/lang_*.json
.python/generate_markdown.py
app/src/main/assets/doc/CHANGELOG-*.md
app/src/main/res/values-*/strings.xml
```

`.python/generate_markdown.py` genera README y CHANGELOG incluido para los 10 idiomas desde JSON; edita las fuentes JSON y no el Markdown generado. Las cadenas Android se gestionan en sus directorios.

******

### Enlaces

******

- Proyecto AutoJs6: https://github.com/SuperMonster003/AutoJs6
- Proyecto Lua: https://www.lua.org
- Avisos de terceros: https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/THIRD_PARTY_NOTICES.md
- Licencia del proyecto: https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/master/LICENSE


[16 KB page alignment and build verification](https://github.com/SuperMonster003/AutoJs6-Plugin-Lua-Runtime/blob/main/docs/16kb.md)
