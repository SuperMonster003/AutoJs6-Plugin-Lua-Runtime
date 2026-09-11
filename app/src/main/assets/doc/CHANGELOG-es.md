******

### Historial de versiones

******

# v0.1.1

###### 2026/09/11

* `Mejora` Verificación de compilación de la alineación de páginas de 16 KB en bibliotecas nativas de 64 bits, con controles del contrato manifest e informes JSON

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

# v0.1.0-rc.1

###### 2026/08/13

* `Nota` El primer candidato local Provider-enabled se firmó y probó en dispositivo, sin crear tag ni release público
* `Función` Se introdujeron el proceso independiente `:lua_runtime`, ejecución Lua de texto, consola, resultados escalares y descubrimiento Binder de AutoJs6
* `Corrección` Se rechazaron solicitudes malformadas o excesivas mediante validación fail-closed de protocolo, digest, UTF-8, deadline, memoria y salida
* `Mejora` Se estableció evidencia verificable para AAR, fuentes Lua, ABI, alineación 16 KiB, firma y matrices de reversión
* `Dependencia` Basado en PUC Lua 5.4.8 estándar y el protocolo Lua AutoJs6 1.0 congelado
