# Public release material and immutable tag policy

Status: **MATERIAL PREPARED — PUBLICATION NOT AUTHORIZED**

Audit date: 2026-08-25 (Asia/Shanghai)

## Boundary

This document defines the R4-E public-release materials and operator boundary.
It does not create or push a tag, create a GitHub Release, upload an APK, or
authorize publication. Every remote mutation requires a separate explicit
human confirmation after all blockers below are closed.

No Git remote is configured in the current plugin checkout. A publication
operator must first identify and review the intended public repository; no URL
is inferred from the directory name or copyright owner.

## License audit

The release dependency inventory is:

| Component | Version/provenance | License | Repository material |
|---|---|---|---|
| Repository-owned code | release revision | MIT | root `LICENSE` |
| PUC Lua | 5.4.8 frozen vendor tree | MIT | vendored source plus `third_party/lua-5.4/LICENSE.txt` |
| Kotlin Standard Library | 2.3.20 | Apache-2.0 | `third_party/apache-2.0/LICENSE.txt` |
| JetBrains Annotations | 13.0, transitive from stdlib metadata | Apache-2.0 | `third_party/apache-2.0/LICENSE.txt` |
| LLVM runtimes selected by the native linker | Android NDK r28c, Clang 19.0.1 / Android LLVM `97a699bf4812a18fb657c2779f5296a4ab2694d2` | Apache-2.0 WITH LLVM-exception, plus recorded legacy UIUC/MIT terms | exact `third_party/android-ndk-r28c/NOTICE.toolchain.txt` |
| AutoJs6 protocol APIs | revision `3b7378758c5a4f68e8680a78cf2c541c23628489` | MPL-2.0 | exact local corresponding source and license under `third_party/autojs6-protocol-source` |

The Gradle release runtime classpath contains Kotlin stdlib 2.3.20 and its
annotations 13.0 dependency in addition to the three local protocol AARs. The
release native link uses `-static-libstdc++`; the resulting ELF requires only
Android platform `liblog`, `libm`, `libdl`, and `libc`, so selected LLVM
runtime code is embedded and its exact NDK r28c notice is retained. The
AARs contain no embedded license or notice file, and release packaging excludes
generic `META-INF/LICENSE*`/`NOTICE*` entries. Therefore the repository notice,
license texts, corresponding source, and release-note disclosure are part of
the distribution boundary, not optional documentation.

Kotlin's upstream repository has a section-4(d) NOTICE for the Kotlin Compiler.
The consumed standard-library JAR contains no NOTICE entry, and the compiler
notice does not describe the stdlib component shipped here; it is not copied as
an APK notice. Copyright attribution remains in `THIRD_PARTY_NOTICES.md`.

The exact AutoJs6 revision returned HTTP 404 from the public commit URL during
this audit. To avoid a broken MPL source offer, 35 exact source blobs for the
three shipped modules are committed locally and fingerprinted in
`SOURCE_PROVENANCE.json`. The immutable release source archive must include
that directory and the Release body must tell recipients where to find it.

This is an engineering compliance inventory, not legal advice. Any different
distribution channel or dependency set needs a fresh review.

## Tag naming and immutability

The public `v0.1.0` series uses exactly these forms:

- release candidates: `v0.1.0-rc.N`, where `N` is a positive integer;
- stable release: `v0.1.0`;
- later stable fixes: `v0.1.1`, `v0.1.2`, and so on.

The `v` prefix exists only in the Git tag. Removing it must produce the exact
`VERSION_NAME`. Tags are annotated and immutable: never force-update, delete
and recreate, or reuse a published tag. A superseded candidate receives a new
monotonically increasing `rc.N`; a stable correction receives a new patch
version. Names such as `latest`, `release`, and moving branch aliases are not
release identities.

The tag target must be the clean source revision named by the final runnable
provider receipt. Its `VERSION_BUILD` must equal that revision's commit count.
All attached APKs must come from that exact revision and one invocation. The
validated rc.2 hashes in the draft currently bind historical build revision
`a0ae189ac8cba042848412a671c91b0b8a7c44e1`; they are an auditable baseline,
not permission to tag a later documentation revision with those binaries.

## Mandatory pre-publication gates

Every item must be checked in a reviewed copy of this list before any remote
operation:

- [ ] A public Git remote and repository owner are explicitly confirmed.
- [ ] The arm64-v8a physical-device install, Plugin Center discovery, real Lua
  execution, result, and console evidence is archived for the exact APK.
- [ ] The production soak standard and first complete run both pass.
- [ ] A clean final candidate is rebuilt; its receipt revision equals the
  intended tag target and its `deviceVerified`/`runtimeVerified` claims are
  supported by separately archived exact-digest evidence.
- [ ] All three APK byte counts and SHA-256 values in the Release body are
  replaced with values from that final invocation and independently rehashed.
- [ ] All APKs have one expected signer and certificate SHA-256
  `31a681fcfffb3e428420cae280ded89292b12a3b0f59e19b7a73e32a8ae4c213`.
- [ ] The protocol AAR lock and corresponding-source provenance agree, and the
  tagged source archive contains all license/notice files, including the exact
  NDK r28c toolchain notice.
- [ ] The Release remains marked as a pre-release for an `-rc.N` tag; only the
  stable `v0.1.0` release may be marked latest.
- [ ] A human explicitly authorizes the exact tag push and Release publication.

A failure after publication is handled by publishing a new immutable version
and describing withdrawal or supersession in release notes. It is never
handled by replacing an APK under an existing tag.

## Operator command shape — documentation only

The following illustrates the reviewed command shape; it has not been run and
must not be run until every gate above is closed:

```powershell
git status --short
git tag -a v0.1.0-rc.2 <EXACT_RECEIPT_REVISION> -m 'AutoJs6 Lua Runtime 0.1.0-rc.2'
git push <CONFIRMED_REMOTE> v0.1.0-rc.2
```

GitHub Release creation, asset upload, pre-release selection, and publication
are separate reviewed actions after the tag push. The repository draft is
[`docs/release-v0.1.0-rc.2-draft.md`](release-v0.1.0-rc.2-draft.md).
