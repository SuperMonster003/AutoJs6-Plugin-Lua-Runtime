# GitHub Release draft — AutoJs6 Lua Runtime v0.1.0-rc.2

> **DRAFT — DO NOT PUBLISH.** The hashes below identify the validated rc.2
> baseline from revision `a0ae189ac8cba042848412a671c91b0b8a7c44e1`.
> Before publication, rebuild from the explicitly selected tag target and
> replace the revision, byte counts, and all hashes. The arm64 physical-device
> evidence and first production soak are still pending.

GitHub settings when eventually admitted:

- tag: `v0.1.0-rc.2`;
- title: `AutoJs6 Lua Runtime 0.1.0-rc.2`;
- release type: **pre-release**;
- target: exact revision in the final clean build receipt;
- latest release: **false**.

## Proposed public body

This is the second release candidate of the independent AutoJs6 Lua 5.4
runtime Provider. It supplies a bounded native Lua runtime through the frozen
AutoJs6 plugin protocol, with deterministic timeout/cancellation behavior,
closed capability negotiation, bounded result/console transport, private crash
diagnostics, and fail-stop watchdog recovery.

### Compatibility

- minimum AutoJs6 Host: versionCode **5276** (AutoJs6 6.8.0);
- Android: API **24+**, target API 36;
- ABIs: `arm64-v8a` and `x86_64`;
- application ID: `io.github.supermonster003.autojs6.plugin.lua.runtime`;
- Provider version: `0.1.0-rc.2`, baseline versionCode 43;
- signing certificate SHA-256:
  `31a681fcfffb3e428420cae280ded89292b12a3b0f59e19b7a73e32a8ae4c213`.

Do not re-sign the APK. AutoJs6 requires the Provider and Host signatures to
match. Hosts older than versionCode 5276 reject dispatch deterministically as
`LUA_RUNTIME_UNAVAILABLE` / `HOST_VERSION_UNSUPPORTED`.

### Validation-baseline assets

These values are mandatory audit inputs, not yet public asset authorization:

| Proposed asset name | Bytes | ABI inventory | SHA-256 |
|---|---:|---|---|
| `autojs6-lua-runtime-0.1.0-rc.2-universal.apk` | 1,299,883 | arm64-v8a, x86_64 | `93f72bc7d38a975b939e97e9e8a47873fc43a1419290cda07f0b803d27c85dfd` |
| `autojs6-lua-runtime-0.1.0-rc.2-arm64-v8a.apk` | 720,946 | arm64-v8a | `257f4c4a9dceed4fc58e089651370abaaa1384cf09c5f69e6fb21f244d409210` |
| `autojs6-lua-runtime-0.1.0-rc.2-x86_64.apk` | 709,975 | x86_64 | `c92fbea3c878d7b2ba1c28bdca168201b98c28c6bf62a953f63e2c9771f45a12` |

Verify a downloaded asset independently:

```powershell
Get-FileHash .\autojs6-lua-runtime-0.1.0-rc.2-universal.apk -Algorithm SHA256
apksigner verify --verbose --print-certs .\autojs6-lua-runtime-0.1.0-rc.2-universal.apk
```

The final public table must be regenerated from the exact tagged build. A size,
hash, signer, version, or revision mismatch blocks publication.

### Evidence status

The baseline candidate passed 59 JVM tests, the repository's hostile/static
suite, R8/lintVital, Provider/fault-harness packaging gates, single-signer
checks, ABI split/universal identity, and 16 KiB ZIP/ELF alignment. Its exact
x86_64 split passed real-Host execution on emulators at API 24, 31, 36, and 37,
including rc.1-to-rc.2 replacement, uninstall/reinstall, and older-Host
rejection behavior.

The immutable packaging receipt still states `deviceVerified=false` and
`runtimeVerified=false` because it binds the universal candidate and is not
rewritten by later evidence. The following release gates remain open:

- arm64-v8a physical-device install and Host end-to-end smoke;
- first complete production soak; and
- final clean rebuild whose revision is the actual tag target.

### Source and licenses

Repository-owned code is MIT-licensed. PUC Lua 5.4.8 is MIT-licensed. Kotlin
stdlib 2.3.20 and JetBrains annotations 13.0 are Apache-2.0. LLVM runtime
portions statically linked by Android NDK r28c retain Apache-2.0 with LLVM
Exceptions and the legacy terms in the preserved exact toolchain notice. The
three AutoJs6 protocol AARs are MPL-2.0; their exact corresponding Source Code Form is
included in the tagged repository source archive under
`third_party/autojs6-protocol-source` and bound to the AAR hashes by
`SOURCE_PROVENANCE.json`. See `THIRD_PARTY_NOTICES.md` and the preserved full
license texts before redistribution.

### Installation note

Install the split matching the device ABI, or use the universal APK. Keep the
existing official AutoJs6 installation and do not alter either signature.
This Provider is discovered and invoked by AutoJs6; it is not a standalone Lua
launcher.

---

Operator-only closure rule: follow `docs/public-release-policy.md`; obtain
explicit human confirmation before creating the remote tag or GitHub Release.
