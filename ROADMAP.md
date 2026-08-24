# Lua Runtime Plugin Roadmap

Checked items mean the named source or static record exists. They do not imply
Gradle, Android, native, device, signing, or release acceptance.

## R3-A - Independent scaffold

- [x] Create a separate sibling directory with its own lowercase Gradle project
  name and application ID.
- [x] Add an independent version file, release-signing environment contract,
  dedicated vector icon, and CI workflow.
- [x] Declare INFO and RUNTIME services in `:lua_runtime`, protected by the
  plugin permission and compatible with actionless explicit binding.
- [x] Keep discovery default-off until native execution and conformance pass.
- [x] Freeze the three-AAR protocol intake and SHA-256 lock workflow without
  copying uncommitted or cache-derived binaries.
- [x] Freeze PUC Lua 5.4.8 URL/SHA-256, exact CMake source admission, JNI probe,
  bounded allocator, and safe-library baseline without fake vendor sources.
- [x] Initialize Git and establish the independent release history.
- [ ] Add the ten-locale generated README/changelog/resource workflow.
- [x] Generate and commit a repository-owned Gradle wrapper.
- [x] Compile and assemble the native-enabled, provider-disabled scaffold.

## R3-B - Immutable inputs

- [x] Commit and gate the host protocol modules at an immutable revision.
- [x] Stage all three protocol AARs and verify their lock digests.
- [x] Import the verified PUC Lua 5.4.8 source and license from the locked
  archive; change vendor status only after review.
- [x] Rebuild the pinned native inputs from a detached clean checkout. The two
  clean builds are not byte-identical, so deterministic APK bytes are not
  claimed.

R3-B static input-gate evidence on 2026-08-10:

- [x] Add exact Git-ignore exceptions for the future three protocol AARs and
  repository wrapper JAR without admitting arbitrary binary caches.
- [x] Reject protocol lock schema/repository/revision drift, unknown or extra
  artifacts, digest mismatch, and symlinked artifacts.
- [x] Validate the locked Lua archive identity and the staged tree's
  self-consistent root, count, digest, no-symlink rule, timestamp, and offline
  intake workflow.
- [x] Parse both Gradle defaults as false and reject extra, indirect, or inline
  CI provider enablement while retaining the native-only compile lane.
- [x] Reject duplicate JSON members and nested Git-ignore overrides for the
  immutable-input paths.
- [x] Add a fail-closed `--require-build-ready` mode for future build/release
  gates; it intentionally rejects the current incomplete input state.
- Pure Python static tests report 23/23 and the repository verifier reports
  `protocol=ready lua=ready build_ready=true`. The three protocol AARs are
  locked to AutoJs6 revision `ae422391759810ce9dbbf0bc119bb47834c66b85`;
  the 63-file Lua tree is locked to SHA-256
  `abc2321841ec25281797b4b3bb855d6ad1caa8ddea265ef0d47844cf959dcda8`.
- Static self-consistency is not commit-existence, AAR-from-commit, or
  archive-to-tree provenance. Those claims remain blocked on the clean intake
  and build gates above; provider disablement in a merged manifest/APK remains
  deferred with Gradle validation.

## R3-C - Runtime execution

- [x] Author one process-wide serial execution worker, one active execution,
  a retained-session ceiling, and no queue.
- [x] Author explicit text-only source loading and binary-chunk rejection.
- [x] Author exact length, EOF, SHA-256, and strict UTF-8 source verification.
- [x] Author allocator, end-to-end deadline, output-credit, and scalar-result
  limits.
- [x] Author `lua_sethook` cancellation/deadline checks and protected calls.
- [x] Author output credits, unique terminal delivery, callback-close
  linearization, and idempotent cancel/close.
- [x] Map the bounded V1 argument model into `require("autojs").arguments`
  through a process-private versioned snapshot, without broadening scalar
  results.
- [x] Select the native runner only in native-enabled builds while keeping the
  discoverable provider independently default-off.
- [x] Add a process-wide, execution-token-bound fail-stop watchdog for deadline,
  cancel, close, and Binder-death cleanup overruns. Android kill/rebind recovery
  remains a device gate.
- [x] Account for every incoming, duplicated, returned, and abandoned PFD.
- [x] Keep coroutine, debug, unrestricted io, OS/process access, dynamic C
  modules, reflection, metatable installation, and loadlib unavailable in the
  source allowlist.

R3-C source evidence on 2026-08-10:

- The repository-owned Gradle 9.6.1 wrapper compiles Kotlin/Java/JNI and both
  admitted ABIs with native enabled and provider disabled. The exact focused
  JVM gate count is owned by `verification.properties`, including
  token/stop/finish/scheduler watchdog races and R4 descriptor accounting.
- The service now selects `NativeLuaExecutionRunner` only when
  `BuildConfig.LUA_NATIVE_ENABLED` is true. Both exported services remain
  controlled by the packaged false provider resource, so this is not device or
  production-provider execution evidence.
- `python tools/verify_repository.py --require-build-ready` reports
  `STATIC_SCAFFOLD_OK protocol=ready lua=ready build_ready=true`.
- The watchdog grants each admitted execution an unforgeable process-local
  token, preserves the first stop time, applies a two-second cleanup grace, and
  poisons the process before `Process.killProcess`/`Runtime.halt`. Normal finish
  revokes both timers before the active token can be replaced, so an old timer
  cannot terminate a later session. This is source/JVM evidence until Android
  proves Binder death, a new PID, and a successful post-recovery execution.
- The named cross-process fixture records balanced logical PFD ownership for
  its covered cases, but OS-level FD-leak accounting and several hostile paths
  remain unchecked. The retained
  cap's third create currently fails synchronously instead of returning a typed
  BUSY session, very small request deadlines may expire before an oneway start
  is received, and a blocking non-regular source FD still needs hostile Android
  containment evidence.

## R3-D - Deferred gates

- [x] Run focused JVM and static App compilation.
- [x] Assemble arm64-v8a, x86_64, and universal debug artifacts.
- [x] Verify packaged ABIs, 16 KiB ELF segments, and APK ZIP alignment.
- [x] Run the provider-disabled native instrumentation gate for scalar results,
  error classification, hook cancellation/deadline, Lua allocator OOM, and
  post-failure reuse while INFO/RUNTIME remain disabled.
- [x] Run the named partial cross-package Binder/PFD conformance and hostile
  lifecycle matrix; keep the untested cases below explicit.
- [x] Exercise Provider discovery only in explicit smoke and release-candidate
  builds; keep repository and ordinary development defaults disabled.
- [x] Add a default-off signed-release-candidate gate that consumes external
  signing properties and a keystore by absolute path without copying secrets.
- [x] Create the signed `0.1.0-rc.1` artifacts from independent revision
  `0497d5061171421e655e0256ef5a1fe1566c5a23` and retain their exact evidence.

The completed local gates do not establish public release or production
readiness. Remaining gaps include OS-level FD-leak evidence, a typed BUSY result
for the retained-session cap, the tiny-deadline/oneway-start race, blocking
non-regular source containment, broader update/uninstall and peer-death cases,
and a production soak. Any post-RC source or protocol-input change requires a
new candidate identity and fresh evidence.

The checkable local artifact gate is:

```powershell
.\tools\verify_debug_artifacts.ps1 `
    -SdkRoot 'E:\.android\sdk' `
    -BuildToolsVersion '37.0.0' `
    -NdkVersion '28.2.13676358'
```

It requires a clean revision whose positive `VERSION_BUILD` equals its commit
count, the exact JVM count from `verification.properties`, exactly three debug
APK outputs, a single common signer,
the exact split/universal ABI inventories, 16 KiB ZIP and ELF LOAD alignment,
and packaged `native=true/provider=false` gates. It does not run Gradle, ADB,
Binder, native execution, install, process recovery, or release signing.
