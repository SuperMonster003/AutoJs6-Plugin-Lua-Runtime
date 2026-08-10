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
- [ ] Initialize Git and establish the independent release history.
- [ ] Add the ten-locale generated README/changelog/resource workflow.
- [ ] Generate and commit a repository-owned Gradle wrapper.
- [ ] Compile or assemble the scaffold.

## R3-B - Immutable inputs

- [ ] Commit and gate the host protocol modules at an immutable revision.
- [ ] Stage all three protocol AARs and verify their lock digests.
- [ ] Import the verified PUC Lua 5.4.8 source and license from the locked
  archive; change vendor status only after review.
- [ ] Reproduce native inputs from a clean checkout.

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
- Pure Python static tests report 15/15 and the repository verifier reports
  `protocol=not-staged lua=not-vendored build_ready=false`. No artifact,
  vendored source, wrapper, Git history, Gradle result, or Android evidence was
  created by this gate.
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
- [ ] Account for every incoming, duplicated, returned, and abandoned PFD.
- [x] Keep coroutine, debug, unrestricted io, OS/process access, dynamic C
  modules, reflection, metatable installation, and loadlib unavailable in the
  source allowlist.

R3-C source evidence on 2026-08-10:

- The Android-free execution sources compile with cached Kotlin 2.3.20 on JDK
  21, and direct JUnitCore reports 23/23 with zero failures. This is a
  standalone diagnostic, not the Gradle/JNI/Android gate. An initial launcher
  attempt omitted the compiler's cached annotations dependency and stopped
  before source compilation; the corrected invocation supplied it.
- The JNI adapter and six native-boundary tests are authored but not compiled
  or run. The service still injects `DisabledLuaExecutionRunner`.
- `python tools/verify_repository.py` reports
  `STATIC_SCAFFOLD_OK protocol=not-staged lua=not-vendored build_ready=false`.
- PFD accounting remains unchecked until generated AIDL and cross-process
  Android evidence prove every ownership path. A wedged native runner still
  requires dedicated-process termination and recovery evidence. The retained
  cap's third create currently fails synchronously instead of returning a typed
  BUSY session, very small request deadlines may expire before an oneway start
  is received, and a blocking non-regular source FD still needs hostile Android
  containment evidence.

## R3-D - Deferred gates

- [ ] Run focused JVM and static App compilation.
- [ ] Assemble arm64-v8a, x86_64, and universal debug artifacts.
- [ ] Verify packaged ABIs, 16 KiB ELF segments, and APK ZIP alignment.
- [ ] Run cross-package Binder/PFD conformance and hostile lifecycle cases.
- [ ] Enable provider discovery only after all previous gates pass.
- [ ] Create signed release artifacts and a clean, independently versioned
  release history.
