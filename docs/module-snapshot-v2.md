# Module snapshot capability V2

Status: **DESIGN ONLY — NOT IMPLEMENTED OR ADVERTISED**

Design date: 2026-08-24

## Outcome

A future `module.snapshot.v2` may add dotted ASCII module names, aggregate
per-execution quotas, and cache metrics while retaining the current frozen-text
model. It must not add package-path search, filesystem access, binary chunks,
dynamic C modules, Java objects, or retry behavior.

The current implementation remains exactly `module.snapshot.v1`: a flat ASCII
name matching `[A-Za-z_][A-Za-z0-9_]{0,63}`, one source snapshot of at most
64 KiB, strict UTF-8 plus SHA-256 validation, text-only loading, execution-local
caching, and dependency-cycle rejection. `LuaProviderMetadata.capabilities`
must continue to advertise only the implemented V1 capability until every V2
gate in this document passes.

## Negotiation

The exact capability identifier is `module.snapshot.v2`. Selection happens
once when the execution session is admitted:

1. the Provider advertises `module.snapshot.v2` only in a build containing the
   V2 Kotlin/JNI implementation and conformance tests;
2. the Host grants/selects the exact same capability for the request; and
3. the Provider freezes the selected module policy into the process-private
   execution request before JNI dispatch.

Both sides must agree. A V2-aware Host may select V1 for an older Provider. A
V2 execution must never retry a denied, missing, malformed, or failed V2 lookup
as V1: a retry could resolve different source under a weaker name policy and
would duplicate a Host call. Exactly one snapshot version is active per
execution. The built-in exact name `autojs` remains process-local and is not a
Host lookup under either version.

This proposal does not require a new result schema, descriptor channel, or AIDL
method. If the canonical Host protocol cannot freeze the selected capability at
session creation, that protocol must evolve first; this repository must not
infer selection from a Host error after execution has begun.

## Dotted-name grammar

V2 names must satisfy all of the following:

- strict UTF-8 bytes that are also ASCII;
- total encoded length from 1 through 255 bytes;
- no more than 16 dot-separated segments; and
- every segment matches `[A-Za-z_][A-Za-z0-9_]{0,62}`.

The complete validation expression is
`[A-Za-z_][A-Za-z0-9_]{0,62}(?:\.[A-Za-z_][A-Za-z0-9_]{0,62}){0,15}`
plus the independent 255-byte total limit. Validation is performed in Kotlin
before the Host call and independently in JNI before copying or caching the
name.

Dots are opaque hierarchy separators in a capability key; they are not mapped
to `/`, `\`, a URI, an Android asset, or a filesystem path by the Provider.
Leading/trailing dots, empty segments, `..`, slashes, backslashes, colons,
hyphens, whitespace, NUL, and non-ASCII characters are rejected. Names are
case-sensitive and are never normalized.

## Request and response shape

The Host request retains the V1 fixed shape:

```text
{name=string}
```

The only admitted responses remain:

```text
{found=false}
{found=true, source=bytes, sha256=bytes}
```

No optional path, MIME type, version, redirect, descriptor, or nested dependency
list is accepted. `source` remains at most 64 KiB, must be strict UTF-8, and its
32-byte SHA-256 must match before JNI. The native loader continues to call
`luaL_loadbufferx(..., "t")`. Binder dispatch is synchronous, deadline/cancel
aware, and never retried.

## Aggregate execution quotas

V2 adds one execution-local quota ledger shared by the main chunk's complete
module graph:

- at most 64 distinct non-`autojs` module names may reach resolution, counting
  found and not-found names;
- at most 512 KiB of verified source bytes may be admitted in total;
- each individual source remains capped at 64 KiB;
- exact-name cache hits consume neither another Host call nor more source-byte
  quota; and
- counters use checked or saturating unsigned arithmetic and never wrap.

The distinct-name slot is charged before the Host call. The source-byte total is
charged after response shape, UTF-8, digest, and per-module validation but before
copying/loading the chunk. Crossing either ceiling terminates the execution with
a deterministic module-limit failure; it does not evict an older module, retry,
or fall back to V1.

## Cache and cycle semantics

The cache key is the exact validated name. A successfully evaluated module is
cached by Lua value, including `false`; a nil return is normalized to `true` as
in V1. The loading set is separate from the completed-value cache. Requiring a
name already in the loading set rejects the dependency cycle before another
Host call. Module load/runtime failure ends the whole execution and never
publishes a partial cache entry.

Negative Host responses may be recorded for metrics, but `require` still raises
immediately and Lua-level protected calls remain unavailable. No cache survives
the execution, and no snapshot may be shared across callers or sessions.

## Cache metrics

V2 maintains these internal, per-execution counters:

- `lookupRequests`: validated non-`autojs` `require` calls;
- `cacheHits`: lookups served from the completed-value cache;
- `cacheMisses`: lookups that begin first resolution;
- `hostCalls`: actual capability dispatches;
- `foundSnapshots` and `notFoundSnapshots`;
- `sourceBytesAdmitted`;
- `cycleRejects`; and
- `loadFailures`.

`cacheHits + cacheMisses == lookupRequests` for calls that pass name validation;
normally `hostCalls == cacheMisses`, except a local quota/cancellation/deadline
rejection may stop a miss before dispatch. Nil-as-true and cached `false` both
count as hits. Invalid names and the built-in `autojs` module are excluded.

The counters are diagnostic only: they are not exposed to Lua, console output,
or the current V1 result callback. A later structured execution-statistics
contract may transport them after Host protocol review. Until then they may be
asserted only inside Provider/native tests.

## Required implementation evidence

Before advertising V2, tests must cover:

- minimum/maximum dotted names and every rejected separator/empty-segment case;
- the independent 16-segment and 255-byte ceilings in Kotlin and JNI;
- exactly 64 distinct lookups, the rejected 65th lookup, and the 512 KiB source
  total with checked accounting;
- exact-name cache hits for tables, `false`, and nil-as-true without a second
  Host call;
- dotted dependency cycles that fail closed;
- malformed response, invalid UTF-8, SHA mismatch, oversized source, Host
  denial, cancellation, deadline, and OOM;
- counter invariants for hit, miss, not-found, cycle, and load failure; and
- a compatibility matrix in which V1-only peers retain the exact current flat
  name behavior and no V2 error is retried as V1.

Until those tests and the Host-side selection support exist, this document is
planning only. No `MODULE_SNAPSHOT_V2_CAPABILITY` constant, metadata entry, or
V2 JNI method is permitted in the current implementation.
