# AutoJs6 protocol corresponding source

This directory preserves the corresponding Source Code Form for the three
AutoJs6 protocol AARs committed under `protocol/`. The source is licensed
under the Mozilla Public License 2.0; the unmodified license text from the
source revision is preserved as `LICENSE`.

## Exact provenance

The 35 files below `plugin-api/` are byte-for-byte Git blobs from AutoJs6
revision `3b7378758c5a4f68e8680a78cf2c541c23628489`:

- `plugin-api/common-plugin-api`;
- `plugin-api/protocol-wire-api`; and
- `plugin-api/lua-runtime-api`.

They include the module build scripts, consumer rules, main Kotlin/AIDL
sources, and module tests present at that revision. No file in the snapshot
has been modified. The module build scripts refer to AutoJs6's root build
conventions, so this directory is the preferred source for inspection and
modification, not a claim that the three directories form a standalone build.

`SOURCE_PROVENANCE.json` binds the source revision, module inventory, AAR
digests, source-file count, and deterministic tree fingerprint. The
fingerprint sorts files relative to `plugin-api/`, then hashes each UTF-8 path,
a NUL byte, the lowercase SHA-256 of that file, and a newline.

## Why the snapshot is local

During the 2026-08-25 release-material audit, the exact public GitHub commit
URL returned HTTP 404 even though the revision exists in the authorized local
AutoJs6 checkout. A moving upstream branch is not an adequate substitute for
the exact source corresponding to a distributed executable. The immutable
release tag's source archive must therefore retain this directory and release
notes must direct recipients here, whether or not the upstream commit later
becomes public.

When any protocol AAR changes, all three AARs, the protocol lock, this source
snapshot, and its provenance record must be refreshed together from one clean
source revision. `python tools/verify_repository.py --require-build-ready`
fails closed on source or provenance drift.
