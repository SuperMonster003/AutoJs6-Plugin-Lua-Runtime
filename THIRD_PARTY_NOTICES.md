# Third-party notices

The repository's root MIT license applies to repository-owned code. The
following components retain their upstream licenses and notices.

## AutoJs6 protocol APIs

The application consumes three frozen Android libraries from AutoJs6:

| Binary | Source module | SHA-256 |
|---|---|---|
| `protocol/common-plugin-api.aar` | `:plugin-api:common-plugin-api` | `d745bb24d6a6995e68ebea27d592faec162f0bad4f9bf70cb038a480ad8c6df2` |
| `protocol/protocol-wire-api.aar` | `:plugin-api:protocol-wire-api` | `a850d2d649638e9131b68a0a3806c950e6be8aa0fa5c5f1e7a575c9260ebf202` |
| `protocol/lua-runtime-api.aar` | `:plugin-api:lua-runtime-api` | `a40a0fff299f60520d07a2739a8f0c9ab004d3f307ff016bab35dc902635b851` |

Source repository: https://github.com/SuperMonster003/AutoJs6

Source revision: `3b7378758c5a4f68e8680a78cf2c541c23628489`

License: Mozilla Public License 2.0 (MPL-2.0).

The exact corresponding source, provenance record, and license are preserved
under
[`third_party/autojs6-protocol-source`](third_party/autojs6-protocol-source/README.md).
Release notes must point recipients to that immutable tagged source. They must
not rely only on an upstream default branch or an unavailable commit URL.

## Kotlin Standard Library 2.3.20

The application directly depends on `org.jetbrains.kotlin:kotlin-stdlib:2.3.20`,
copyright JetBrains s.r.o. and Kotlin Programming Language contributors.
Kotlin is distributed under the Apache License 2.0.

Upstream: https://github.com/JetBrains/kotlin/tree/v2.3.20

License: [third_party/apache-2.0/LICENSE.txt](third_party/apache-2.0/LICENSE.txt)

## JetBrains Annotations 13.0

`org.jetbrains:annotations:13.0` is the compile dependency declared by the
Kotlin Standard Library metadata. Its published source headers identify
JetBrains s.r.o. copyright and the Apache License 2.0.

Upstream: https://github.com/JetBrains/java-annotations

License: [third_party/apache-2.0/LICENSE.txt](third_party/apache-2.0/LICENSE.txt)

## Android NDK r28c LLVM runtimes

The native shared object is built by Android NDK r28c (28.2.13676358) with
Clang 19.0.1 based on Android LLVM revision
`97a699bf4812a18fb657c2779f5296a4ab2694d2`. Its link uses
`-static-libstdc++`, so selected LLVM runtime portions, including libc++ and
libc++abi support, are embedded in `libautojs_lua_runtime.so` rather than
shipped as a separate `libc++_shared.so`.

Current LLVM code is Apache-2.0 with LLVM Exceptions; legacy libc++/libc++abi
portions are also offered under the University of Illinois/NCSA and MIT terms
recorded by the toolchain. The exact r28c Windows prebuilt toolchain notice is
preserved byte-for-byte (130,424 bytes, SHA-256
`f96f763beb66a7ba7a667647fc64c0226ace875e590c831fdd9579ec1c1d91e1`).

Upstream: https://android.googlesource.com/toolchain/llvm-project/+/97a699bf4812a18fb657c2779f5296a4ab2694d2

License and notices:
[third_party/android-ndk-r28c/NOTICE.toolchain.txt](third_party/android-ndk-r28c/NOTICE.toolchain.txt)

## PUC Lua 5.4.8

The native runtime uses PUC Lua 5.4.8, copyright 1994-2025 Lua.org, PUC-Rio,
and distributed under the MIT License. Its source tree is vendored under
`app/src/main/cpp/vendor/lua-5.4.8` and is bound to the archive digest,
extraction metadata, source inventory, and tree digest recorded by the frozen
vendor lock.

Upstream: https://www.lua.org/

License: [third_party/lua-5.4/LICENSE.txt](third_party/lua-5.4/LICENSE.txt)
