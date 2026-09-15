# Android ABI support

Current source builds include `arm64-v8a`, `armeabi-v7a`, `x86_64`, and `x86`.
Each enabled variant produces four individual ABI APKs and one universal APK.
The build filters, runtime metadata, and artifact verification scripts use
the same four-ABI inventory.

All four libraries are compiled from the existing, pinned PUC Lua 5.4.8 sources
and the repository's `lua_runtime_jni.cpp`. The upstream archive and source-tree
fingerprint remain recorded in `app/src/main/cpp/vendor/vendor-lock.json`.
No new third-party binary or engine version is required. NDK `28.2.13676358`
and CMake `3.22.1` use static libc++, so a separate `libc++_shared.so` is not
needed. The CMake linker settings retain 16 KiB LOAD alignment for every ABI.

The added 32-bit targets were cross-compiled and linked from the unmodified
native sources on 2026-09-12. The Lua integer assertions still require signed
64-bit integers on 32-bit processes. Native compilation and APK checks do not
establish device execution; run the native execution and Provider discovery
tests on each newly supported ABI before claiming runtime validation.

The new x86 `nativeTestDebug` APK subsequently passed all 23 tests in
`NativeLuaRuntimeInstrumentationTest` on an Android 10 / API 29 x86 emulator
with 4096-byte pages. This exercises the native Lua runner; that test variant
deliberately removes production Provider discovery services. The new ARM32
target has build and ELF evidence only. The local test transcript and device
identity are under `app/build/reports/abi-support/runtime/`.

Existing release-candidate receipts and device reports describe the exact
historical two-ABI artifacts and remain unchanged. Their APK hashes and runtime
results do not describe a newly built four-ABI universal APK.
