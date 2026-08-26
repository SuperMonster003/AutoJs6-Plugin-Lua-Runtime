# PUC Lua 5.4.8 vendor intake

Status: **vendored**.

The repository contains the verified upstream source, not a placeholder Lua
implementation. To reproduce the intake from a clean pre-intake checkout,
obtain the official `lua-5.4.8.tar.gz` archive outside the build, review its
license, and run:

```powershell
.\tools\stage_lua_source.ps1 -Archive <path-to-lua-5.4.8.tar.gz>
```

The script verifies the archive against `vendor-lock.json`, imports only the
archive's exact `src` tree, checks the CMake admission list, computes its
canonical path/content fingerprint, updates the lock, and runs the repository
verifier. It refuses an existing source tree.

The static input gate binds the exact archive name, HTTPS URL, archive digest,
source directory, and `src` root before it will fingerprint a tree. A vendored
tree must contain no symlinks, must match its recorded file count and canonical
path/content SHA-256, and must carry a UTC intake timestamp. In the
`not-vendored` state, the tree, fingerprint, count, and timestamp must all be
absent. These checks validate a completed offline intake and do not download
the archive. Every current Android variant compiles the vendored native runtime.

The tree fingerprint proves that the checked-in tree matches its lock; by
itself it does not prove that an arbitrary re-locked tree came from the pinned
archive. Archive-to-tree provenance therefore remains dependent on executing
and reviewing the offline intake against the independently verified archive.

Expected layout after intake:

```text
lua-5.4.8/
  README.md
  src/
    lapi.c
    lua.h
    ...
```

All files listed by `../../cmake/lua54-sources.cmake` must exist and match the
intake record. The build has no native/provider Boolean switches: native Lua is
mandatory, while production service exposure is selected by the explicit
Provider variant.

To inspect the fingerprint of an already staged tree without changing the
lock, run:

```powershell
python tools/verify_repository.py --print-vendor-tree
```

Record both emitted values in `vendor-lock.json`, change its status only after
reviewing the exact tree, and commit the source plus lock together. The normal
repository verifier recomputes the canonical path/content digest and rejects a
missing, added, or changed source file.
