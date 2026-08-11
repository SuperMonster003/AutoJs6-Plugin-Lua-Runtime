# Lua Runtime

Independent Android runtime provider for the frozen Lua Binder protocol. The
provider targets the `lua54` runtime slot with PUC Lua 5.4.8 and runs in the
dedicated `:lua_runtime` process.

## Current checkpoint

This repository contains a working, independently versioned runtime Provider
whose production discovery remains default-off:

- application ID: `io.github.supermonster003.autojs6.plugin.lua.runtime`
- plugin / engine / variant: `lua-runtime` / `lua` / `puc-lua54`
- INFO action: `org.autojs.plugin.INFO`
- RUNTIME action: `org.autojs.plugin.lua.RUNTIME`
- supported native targets: `arm64-v8a` and `x86_64`
- pinned native toolchain: NDK `28.2.13676358` and CMake `3.22.1`
- both exported services require `org.autojs.permission.PLUGIN`
- both services accept an explicit component-only bind whose action is null
- both services are default-disabled by a generated Boolean resource

The protocol AARs and PUC Lua sources are immutable local inputs, the Gradle
wrapper is pinned, and focused JVM, native-device, process-recovery, and
explicit production-Provider pilot evidence exists. Ordinary builds still
keep native execution and Provider discovery disabled; no public release or
default-enabled Provider claim is made at this checkpoint.

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

The verified archive has been staged into the immutable vendored source tree. See
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
execution loader calls `luaL_loadbufferx(..., "t")`. No loader without an
explicit text-only mode may be introduced, because `lundump.c` remains part of
the Lua core.

## Execution boundary

`LuaRuntimeService` now owns a source-only Binder/session skeleton behind the
default-off provider gate. It duplicates and closes source descriptors,
verifies exact length/SHA-256/EOF/strict UTF-8 on a process-wide zero-queue
worker, admits one active execution, bounds retained/unstarted sessions, and
contains callback/terminal/close races. Native-enabled builds now select
`NativeLuaExecutionRunner`, while native-disabled builds retain
`DisabledLuaExecutionRunner`. Provider discovery remains an independent,
default-off gate. A controlled `require("autojs")` exposes only
`console.log(string)` and `console.error(string)`; both use the existing
sequence, credit, chunk, and total-output limits. General module loading,
`print`, and `warn` remain unavailable.

A process-wide watchdog is bound to each admitted execution token. It arms only
immediately before worker dispatch, shortens its grace window on cancel, close,
or callback death, and clears its token on normal finish. If the worker survives
the end-to-end deadline or stop request plus the cleanup grace, the dedicated
runtime process is poisoned and terminated. Focused Android evidence covers
kill/rebind/new-PID recovery, while Provider discovery remains default-off.

Before public Provider enablement, the implementation still needs:

1. add explicit argument binding and the first host-broker capability
2. complete the product-facing editor and persistent-entry experience
3. create a signed Provider-enabled release candidate
4. run the focused release compatibility and rollback sample

Android-native instrumentation can call `NativeLuaRuntime.execute()` with
`native=true/provider=false` to verify scalar results, controlled console
output, failures, hooks, and allocator recovery without discovering either
production service. A separate opt-in smoke exercises the production
INFO/RUNTIME Binder path while keeping repository defaults disabled.

The repository JVM suite contains 38 tests, while focused Android evidence is
kept separate for native, Binder/PFD, process-recovery, and Provider paths.

## CI

The static job requires immutable protocol and Lua inputs, validates the pinned
repository wrapper, and fails closed if build readiness regresses. The
Gradle/native job uses that wrapper with native enabled and provider disabled.
CI wiring is not Binder/PFD, install, release-signing, API-matrix, or device
recovery evidence.

The input-gate Python suite currently covers 21 normal and hostile cases for
lock schemas and duplicate keys, revision syntax, artifact inventory/digests,
tree-lock consistency, default-off parsing, CI provider containment, Git-ignore
precedence, intake-script drift, and watchdog token/fail-stop wiring. It is a
static gate only; current output is
`STATIC_SCAFFOLD_OK protocol=ready lua=ready build_ready=true`. Build and release
automation must continue invoking
`python tools/verify_repository.py --require-build-ready`.

Static lock consistency does not prove that a Git revision exists, that an AAR
was produced by that revision, or that a re-locked Lua tree came from the
pinned archive. Those provenance claims remain staging/build gates. Provider
state must likewise be checked in the merged manifest or APK once Gradle work
is permitted; source and CI-text checks are not runtime evidence.

## Remaining release validation

Follow the host repository's Lua roadmap for capability and product work.
Release signing, a small API/ABI compatibility sample, and production rollback
evidence remain separate from development smoke results.

## License

Repository code is distributed under the MIT License. PUC Lua is also
MIT-licensed; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and the
preserved Lua license text.
