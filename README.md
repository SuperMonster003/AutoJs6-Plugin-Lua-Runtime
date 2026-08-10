# Lua Runtime

Independent Android runtime provider for the frozen Lua Binder protocol. The
provider targets the `lua54` runtime slot with PUC Lua 5.4.8 and runs in the
dedicated `:lua_runtime` process.

## Current checkpoint

This repository is an R3 source scaffold, not a production runtime:

- application ID: `io.github.supermonster003.autojs6.plugin.lua.runtime`
- plugin / engine / variant: `lua-runtime` / `lua` / `puc-lua54`
- INFO action: `org.autojs.plugin.INFO`
- RUNTIME action: `org.autojs.plugin.lua.RUNTIME`
- supported native targets: `arm64-v8a` and `x86_64`
- pinned native toolchain: NDK `28.2.13676358` and CMake `3.22.1`
- both exported services require `org.autojs.permission.PLUGIN`
- both services accept an explicit component-only bind whose action is null
- both services are default-disabled by a generated Boolean resource

The Android project has not been compiled by Gradle. Only the Android-free
execution subset has a direct Kotlin/JUnit diagnostic. The protocol AARs have
not been generated or synchronized, PUC Lua sources have not been vendored, no
Gradle wrapper has been generated, and no repository or release history has
been initialized. There is no installable or runnable Lua provider claim at
this checkpoint.

## Fail-closed build switches

`autojs.lua.native.enabled` and `autojs.lua.provider.enabled` default to
`false`. The provider switch cannot be enabled unless the native switch is
also enabled.

The native switch activates CMake, which fails if any pinned Lua source is
missing. The provider switch controls both manifest services and must remain
off until the native execution, lifecycle, descriptor, and Android conformance
gates pass. Installing a default scaffold build therefore cannot affect host
runtime selection.

## Protocol inputs

The App consumes exactly three repository-local, SHA-256-locked AARs:

- `protocol/common-plugin-api.aar`
- `protocol/protocol-wire-api.aar`
- `protocol/lua-runtime-api.aar`

See [protocol/README.md](protocol/README.md). Mutable sibling paths and
unversioned cache artifacts are not release inputs.

`tools/verify_repository.py` parses the protocol/vendor locks, the two
default-off Gradle properties, and every literal CI reference to the provider
switch. CI may compile the native scaffold with `native=true`, but its sole
provider value must remain `false`. Exact Git-ignore exceptions reserve
`protocol/*.aar` and `gradle/wrapper/gradle-wrapper.jar` for later immutable
intake without broadly admitting cached AAR/JAR files. Duplicate JSON members
and nested Git-ignore files capable of overriding those paths fail closed.

## Native source intake

The frozen upstream input is:

- PUC Lua 5.4.8
- `https://www.lua.org/ftp/lua-5.4.8.tar.gz`
- SHA-256
  `4f18ddae154e793e46eeab727c59ef1c0c0c2b744e7b94219710d76f530629ae`

The archive has not been downloaded or extracted in this repository. See
[vendor-lock.json](app/src/main/cpp/vendor/vendor-lock.json) and the
[vendor intake note](app/src/main/cpp/vendor/lua-5.4.8/README.md).
After intake, the repository verifier recomputes a canonical path/content
digest and file count for the complete `src` tree; a lone sentinel file cannot
change the lock to `vendored`.

The CMake source inventory deliberately excludes `linit.c`, `lcorolib.c`,
`ldblib.c`, `liolib.c`, `loslib.c`, and `loadlib.c`. The JNI probe uses a
bounded `lua_newstate` allocator and opens only base, math, string, table, and
UTF-8 libraries; it then removes protected calls, base loaders, and
metatable access as well as `string.dump`. Coroutine support remains excluded
until hook-inheritance tests.

Removing these globals is defense in depth, not the binary-chunk gate. The
future execution loader must call `luaL_loadbufferx(..., "t")` or an
equivalent text-only API. No loader without an explicit text-only mode may be
introduced, because `lundump.c` remains part of the Lua core.

## Execution boundary

`LuaRuntimeService` now owns a source-only Binder/session skeleton behind the
default-off provider gate. It duplicates and closes source descriptors,
verifies exact length/SHA-256/EOF/strict UTF-8 on a process-wide zero-queue
worker, admits one active execution, bounds retained/unstarted sessions, and
contains callback/terminal/close races. The service deliberately injects
`DisabledLuaExecutionRunner`; the uncompiled JNI adapter is not connected.
Consequently this is lifecycle source for review, not a functional provider.

Before provider enablement, the implementation still needs:

1. stage the immutable protocol AARs and verified PUC Lua source
2. compile the Kotlin/AIDL/JNI boundary with the pinned Android toolchain
3. connect `NativeLuaExecutionRunner` only after its native tests pass
4. add native stdout/stderr credits, argument binding, and the host broker
5. prove complete PFD/session accounting across real Binder processes
6. prove cancellation, native crash, OOM, wedged-call, and process recovery
7. pass ABI, 16 KiB alignment, signing, install, and release gates

The Android-free execution state machine currently has a standalone Kotlin
2.3.20/JDK 21 diagnostic of 23/23 tests. This does not compile AIDL or Android
sources, load native code, exercise Binder/PFD behavior, or enable discovery.

## CI

The static job always validates identity, manifest boundaries, default-off
flags, locks, and input state. The Gradle/native job is skipped until all three
protocol AARs and the pinned Lua source are present. CI wiring is not local
Gradle, APK, Binder, ABI, 16 KiB alignment, signing, or device evidence.

The input-gate Python suite currently covers 15 normal and hostile cases for
lock schemas and duplicate keys, revision syntax, artifact inventory/digests,
tree-lock consistency, default-off parsing, CI provider containment, Git-ignore
precedence, and intake-script drift. It is a static gate only; current output
remains `protocol=not-staged`, `lua=not-vendored`, and `build_ready=false`.
After immutable inputs are actually staged, build-required/release automation
must invoke `python tools/verify_repository.py --require-build-ready`; the same
command intentionally fails at this checkpoint.

Static lock consistency does not prove that a Git revision exists, that an AAR
was produced by that revision, or that a re-locked Lua tree came from the
pinned archive. Those provenance claims remain staging/build gates. Provider
state must likewise be checked in the merged manifest or APK once Gradle work
is permitted; source and CI-text checks are not runtime evidence.

## Deferred validation

Gradle, ADB, connected tasks, installation, and device work remain deferred
while the protected soak is active. When that restriction is lifted, follow
[ROADMAP.md](ROADMAP.md) and record static, Android/Binder, ABI, alignment, and
release evidence separately.

## License

Repository code is distributed under the MIT License. PUC Lua is also
MIT-licensed; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and the
preserved Lua license text.
