# Lua Provider 0.1.0-rc.2 signed packaging candidate

Status: **SIGNED PACKAGING VERIFIED — DEVICE/RUNTIME PENDING**

Build date: 2026-08-25

## Scope

This record binds the first R4-E release-engineering checkpoint to clean plugin
revision `a0ae189ac8cba042848412a671c91b0b8a7c44e1`, versionName
`0.1.0-rc.2`, and versionCode 43. It proves reproducible local construction,
signing, Provider enablement, release packaging, ABI inventory, alignment, and
fault-harness exclusion for the exact artifacts below.

It deliberately does not claim installation, Plugin Center discovery, a real
Host-to-Provider execution, device compatibility, upgrade/rollback behavior,
production soak, publication, or availability. Both canonical receipts keep
`deviceVerified=false` and `runtimeVerified=false`. Those flags may be changed
only by separately archived device evidence for these exact artifact digests.

## Inputs and invocation boundary

The hardened `tools/build_runnable_provider.ps1` required the plugin worktree to
be clean before and after the build and verified that `VERSION_BUILD=43` equaled
the 43-commit source history. It accepted only absolute paths to an external
signing properties file, external JKS, the matching AutoJs6 APK, and Android SDK.
Signing aliases/passwords were read inside Gradle/verifier processes and were
not copied into this repository or written into the receipt.

The script ran `:app:clean`, `:app:testDebugUnitTest`, and
`:app:assembleRelease` with native execution and Provider discovery enabled,
fault harness disabled, release-candidate mode enabled, `--rerun-tasks`, and
`--offline`. All 87 Gradle tasks executed fresh; the build completed successfully
in about 2 minutes 15 seconds, including R8 and lintVital. The JVM suite passed
59/59. Immediately before this candidate build, the same implementation tree
also passed 44/44 Python hostile/static tests and the repository local offline
gate.

The Host comparison APK was AutoJs6 6.8.0 versionCode 5276, 34,583,727 bytes,
SHA-256
`0303d66688beff27ed512b952b084f409b349abb908b049d64157ec07c6ceeb4`.
It carried the same signing certificate as the candidate and met the minimum
Host versionCode 5276. The adjacent Host checkout was read-only at revision
`4a9718d63923834c9a99fd70e0cd58c898e138f6` with 48 dirty entries; therefore
this record uses that APK only as an exact version/signer comparator and makes
no clean-Host provenance claim. No Host file was changed.

## Exact artifacts

All artifacts are local ignored build outputs and are not committed to Git.

| Artifact | Bytes | ABI inventory | SHA-256 |
|---|---:|---|---|
| `app-universal-release.apk` | 1,299,883 | arm64-v8a, x86_64 | `93f72bc7d38a975b939e97e9e8a47873fc43a1419290cda07f0b803d27c85dfd` |
| `app-arm64-v8a-release.apk` | 720,946 | arm64-v8a | `257f4c4a9dceed4fc58e089651370abaaa1384cf09c5f69e6fb21f244d409210` |
| `app-x86_64-release.apk` | 709,975 | x86_64 | `c92fbea3c878d7b2ba1c28bdca168201b98c28c6bf62a953f63e2c9771f45a12` |

Every APK has exactly one signer with certificate SHA-256
`31a681fcfffb3e428420cae280ded89292b12a3b0f59e19b7a73e32a8ae4c213`.
The packaged native payload digests are:

- arm64-v8a:
  `0271aae2859d270ec9f3df2499ca8118dc25d8cc2f14f1b13c2ab193d546503f`;
- x86_64:
  `7baaa5731b7dd471093f53480d2617ecbc8bf5042e97d8928a82a4979e4242c6`.

The split and universal APKs contain byte-identical native payloads for their
shared ABI. All ELF LOAD segments and APK ZIP entries pass the 16,384-byte page
alignment gates.

## Strict artifact gate

`verify_release_candidate_artifacts.ps1` derived the expected signing
certificate from the explicitly pinned JKS through a process-scoped password
environment variable and cleared that variable afterward. It checked:

- exactly 59 fresh unit-test results with no failure, error, or skip;
- three and only three release APKs with versionCode 43/versionName 0.1.0-rc.2;
- one matching signer across all outputs;
- non-debuggable packaged identities and enabled Provider discovery;
- exact arm64-v8a/x86_64 split and universal inventories;
- byte-identical native payloads plus 16 KiB ZIP and ELF alignment;
- `LUA_NATIVE_ENABLED=true`, `LUA_PROVIDER_ENABLED=true`, and
  `LUA_FAULT_HARNESS_ENABLED=false`; and
- absence of debug fault services and native crash/wedge JNI symbols from the
  release manifest and ELF files.

## Canonical receipts

```text
SIGNED_RELEASE_CANDIDATE_ARTIFACT_GATE_PASS provider=true faultHarness=false elfPageAlign=16384 zipPageAlign=16384 deviceVerified=false runtimeVerified=false
```

```text
RUNNABLE_LUA_PROVIDER_OK revision=a0ae189ac8cba042848412a671c91b0b8a7c44e1 apk=app/build/outputs/apk/release/app-universal-release.apk apkBytes=1299883 apkSha256=93f72bc7d38a975b939e97e9e8a47873fc43a1419290cda07f0b803d27c85dfd versionName=0.1.0-rc.2 versionCode=43 hostVersionCode=5276 abis=arm64-v8a,x86_64 signerSha256=31a681fcfffb3e428420cae280ded89292b12a3b0f59e19b7a73e32a8ae4c213 sourceClean=true artifactGateVerified=true deviceVerified=false runtimeVerified=false
```

No APK was installed as part of this packaging checkpoint. No ADB command was
issued and no physical device was touched. The next R4-E item must use the exact
artifact hashes above or rebuild and archive a new candidate identity.

## Subsequent device evidence

Later on 2026-08-25, the exact x86_64 split identified above was installed on
API 37 `emulator-5554`; its installed `base.apk` reproduced SHA-256
`c92fbea3c878d7b2ba1c28bdca168201b98c28c6bf62a953f63e2c9771f45a12`.
Real Host INFO/RUNTIME discovery, two `LuaPluginScriptEngine` executions,
result mapping, `device.info`, and INFO-level console feedback passed under run
ID `rc2emu-install-20260825051216`. The separately archived evidence is
[`release-candidate-rc2-emulator-smoke.md`](release-candidate-rc2-emulator-smoke.md).

This does not rewrite the immutable packaging receipts above. Their universal
artifact flags remain false, and the combined R4-E device item remains open
until the authorized arm64-v8a physical-device half is also archived.
