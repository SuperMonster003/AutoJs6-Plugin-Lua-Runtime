# Persistent storage capability V1

Status: **R5 IMPLEMENTED — HOST/PROVIDER/API 37 VERIFIED**

Design date: 2026-08-25

Coordinated implementation date: 2026-08-27

Additional device evidence (2026-09-10): Samsung SM-A566B, Android 16 / API 36,
native ARM64 with 16 KiB pages. AutoJs6 5279 and the versionCode 62 development
Provider passed 3/3 Host storage-backend tests and the real-Host capability smoke,
including same-file persistence/increment, different-file isolation, and clear.
Exact artifacts, raw results, and device scope are recorded in
[`samsung-arm64-16k-validation.md`](samsung-arm64-16k-validation.md).

Implementation inputs:

- Provider revision `ba3a450aa57a88423db386d83090f7d69cde6fc3`;
- AutoJs6 Host capability revision
  `2db8355a5` (rebased onto `f18c2748d`); and
- the unchanged Lua protocol 1.0 artifacts locked by this repository.

## Outcome

`storage.kv.v1` provides a small synchronous persistent key/value store through
`require("autojs").storage`. Persistence belongs exclusively to the Host. The
Provider runtime process owns no database, file path, namespace, retry policy,
or Android storage API.

The implemented Lua surface has four fixed methods:

```lua
local storage = require("autojs").storage

local value = storage.get("counter")
storage.put("counter", 7)
local removed = storage.remove("counter")
local removedCount = storage.clear()
```

The capability is additive to protocol 1.0. It reuses the existing negotiated
capability list, `LuaHostCallRequest`/result/error envelopes, `LuaValue`, and
TaggedWire codec. No AIDL method, descriptor lane, protocol enum, or protocol
artifact changed.

## Resolved deployment blockers

The earlier R5 audit found two blockers: the Host had no stable script
principal or persistent dispatcher, and an originally proposed 320 KiB encoded
value could not fit in TaggedWire's 256 KiB document limit. The coordinated
implementation resolves both without widening the wire protocol:

- the Host derives an opaque principal only for a real file-backed `.lua`
  source and closes it into the execution-scoped broker;
- the Host owns app-private, synchronously committed persistence and repeats
  all key, value, shape, and quota checks;
- the canonical encoded-value ceiling is 252 KiB; and
- a test-built `put` request using maximum-length execution ID, call ID, and
  storage key proves that the envelope adds exactly 730 bytes.

The maximum request is therefore `252 * 1024 + 730 = 258,778` bytes, leaving
3,366 bytes below `TaggedWireLimits.maxDocumentBytes = 262,144`. Binder failure
is not used as quota enforcement.

Provider metadata now advertises `storage.kv.v1`. A session receives it only
when the selected Host also enables it and the current execution has a stable
file principal.

## Security principal and namespace

Storage is scoped to a Host-private `LuaStoragePrincipal`. The Host admits only
`LuaFileSource` executions and derives the identity from a trusted semantic
path/URI or the canonical existing `.lua` file path. Path and URI identities
are domain-separated, encoded as strict UTF-8, and SHA-256 hashed before they
reach persistence.

The raw path, URI, and digest never cross Binder and are never visible to Lua
or the Provider. The Provider cannot send a principal or choose a namespace.
The Host application's private data directory independently supplies Android
package and user/profile isolation.

Two executions of the same file identity use the same namespace. Different
file identities use different namespaces even when their contents or base file
names are identical. In-memory sources, missing files, and sources without a
stable Host-controlled identity do not request or receive `storage.kv.v1`.

Moving a script changes its identity and therefore selects a new namespace.
V1 deliberately has no alias, rename, sharing, import, or namespace-migration
API.

## Exact Lua behavior

- `get(key)` returns the stored value, or `nil` when the key is absent;
- `put(key, value)` atomically inserts or replaces one non-nil value and returns
  `true` only after the Host has synchronously committed it;
- `remove(key)` atomically removes one key and returns whether it existed; and
- `clear()` atomically removes all keys for the current principal and returns a
  non-negative integer count.

There is no enumeration, prefix scan, compare-and-swap, transaction handle,
watcher, expiry, or implicit conversion of `put(key, nil)` into deletion. Nil
is reserved for a missing `get`; callers must use `remove` explicitly.

## Key grammar

A key is strict UTF-8 that is also ASCII and must match
`[A-Za-z_][A-Za-z0-9._-]{0,63}`. Its encoded length is 1 through 64 bytes. Keys
are case-sensitive opaque identifiers; dots and hyphens are not path
separators and are never normalized.

Empty keys, leading digits, whitespace, NUL, slashes, backslashes, colons,
control characters, and non-ASCII text are rejected in native code before JNI.
Provider Kotlin and Host Kotlin independently apply the same grammar.

## Fixed request and response shapes

Every operation uses the exact capability name `storage.kv.v1`. The Provider
chooses the operation from one fixed native function; the script cannot supply
an arbitrary capability or operation string.

Requests are closed maps with no optional or extra fields:

```text
get     -> {op="get", key=string}
put     -> {op="put", key=string, value=LuaValue}
remove  -> {op="remove", key=string}
clear   -> {op="clear"}
```

Responses are also closed maps:

```text
get missing -> {found=false}
get present -> {found=true, value=LuaValue}
put         -> {stored=true}
remove      -> {removed=boolean}
clear       -> {removedCount=int64}
```

Unknown or duplicate fields, unknown operations, wrong types, responses that
do not match their request, non-empty descriptor arrays, `stored=false`, and a
negative or greater-than-256 `removedCount` fail closed.

## Value mapping

Stored values reuse the existing bounded `LuaValue` tree:

- Boolean, signed 64-bit integer, finite double, and strict UTF-8 string;
- dense 1-based arrays and string-key maps;
- maximum depth 32 and maximum 4,096 nodes;
- maximum 256 KiB aggregate string/map-key data;
- maximum 1,023 entries per container;
- maximum 64 KiB per string and 1 KiB per map key; and
- maximum 252 KiB for the complete canonical `LuaValueCodec` document.

Nil and byte-string values are not storable. The native mapper rejects sparse
or mixed tables, cycles, functions, threads, userdata, light userdata,
metatables, non-finite numbers, invalid UTF-8, and over-limit values before the
Host call. Table traversal uses only raw Lua operations, so `__pairs`,
`__index`, `__len`, and other metamethods never execute during conversion.

The private JNI snapshot and public protocol document are separate bounded
encodings. Provider Kotlin decodes the private snapshot, validates the complete
tree, canonicalizes it with `LuaValueCodec`, and applies the 252 KiB limit
before constructing the Host request. The Host repeats text-only validation,
canonical encoding, and size admission before persistence.

## Quotas

Provider and Host both enforce execution-local limits:

- at most 64 storage operations per execution;
- at most 32 mutations (`put`, `remove`, or `clear`) per execution;
- at most 1 MiB of cumulative canonical values returned per execution; and
- at most 1 MiB of cumulative canonical values accepted for writes per
  execution.

The Host persistence layer additionally enforces:

- at most 256 keys per principal;
- at most 252 KiB in one canonical encoded value; and
- at most 2 MiB of canonical encoded key plus value bytes per principal.

Operation and mutation slots are charged before the sole dispatch. Write-byte
quota is charged after complete local validation but before dispatch; read-byte
quota is charged before a returned value enters native Lua. A denial, Host
error, timeout, cancellation, malformed response, or lost acknowledgement does
not refund a charged slot. Arithmetic is checked before addition.

Quota overflow never evicts, truncates, partially stores, or partially clears
data. A rejected replacement leaves the previously committed value unchanged.

## Host persistence and ordering

The current Host uses a versioned app-private SharedPreferences namespace named
from the opaque principal digest. Values are canonical `LuaValueCodec` bytes
stored as canonical Base64 strings; Java or Android objects are never stored.

One process-wide lock per principal linearizes reads and mutations. While
holding that lock, each mutation decodes the current namespace, computes the
complete projected key/value quota, and calls synchronous
`SharedPreferences.Editor.commit()`. Success is acknowledged only after
`commit()` returns true. Corrupt type, Base64, key, or value data fails closed
instead of being silently discarded.

This gives atomic single-operation replace, remove, and clear semantics. V1 has
no multi-call transaction, so `get` followed by `put` is not an atomic
read/modify/write operation; last committed write wins.

Provider update, Provider process death, execution failure, and ordinary cache
cleanup do not remove Host-owned entries. `storage.clear()` removes the current
namespace, while Android Host app-data clear or Host uninstall removes all
namespaces. Script deletion or movement does not currently discover and delete
the old opaque namespace; those entries remain app-private and unreachable
until Host app-data clear. V1 does not claim credential-vault, encryption, or
user-authentication semantics.

## Negotiation, denial, and retry rule

Provider metadata and the Host allowlist both contain `storage.kv.v1`. Session
admission intersects the two inventories. The Host requests the capability only
for a stable file-backed source and closes the corresponding principal into the
session broker.

An older Host, missing grant, in-memory script, or invalid principal follows the
existing deterministic `CAPABILITY_DENIED`/`CAPABILITY_UNAVAILABLE` to
`HOST_CAPABILITY` path. No Provider-local map, database, SharedPreferences,
filesystem fallback, prompt, or alternate operation shape is attempted.

Each explicit Lua call produces at most one JNI call, one Provider Kotlin
capability invocation, and one `broker.invoke(...)`. Neither Provider nor Host
contains a mutation retry loop. Timeout, callback death, Binder failure, or an
ambiguous commit never causes an automatic replacement call with either the
same or a new call ID.

## Verification evidence

The coordinated implementation was verified on 2026-08-27 with these focused
gates:

- Provider JVM boundary tests cover the four exact shapes, key/value admission,
  canonical encoding, operation/mutation/read/write quotas, malformed results,
  denial, and absence of fallback;
- the complete Provider
  `NativeLuaRuntimeInstrumentationTest` passed 23/23 on API 37
  `emulator-5560` (`x86_64,arm64-v8a`, 16 KiB page), including scalar/table
  round trips, raw table conversion, invalid UTF-8/types/tables, quotas, denial,
  and no retry;
- Host JVM focused tests passed 35/35, covering principal selection, maximum
  envelope size, fixed dispatcher shapes, quotas, denial, Toast, and engine
  capability selection;
- `LuaHostStorageInstrumentationTest` passed 3/3 on the same emulator, covering
  persistence across backend instances, principal isolation, projected 2 MiB
  rejection without replacement, synchronous remove/clear, and 32 concurrent
  writers without lost entries; and
- the real independently installed Host + official Provider smoke passed 1/1,
  proving same-file persistence across executions, different-file isolation,
  clear, negotiated dispatch, and coexistence with module snapshots and Host
  Toast delivery.

Every install and test command named `emulator-5560` explicitly. Connected
physical devices were not installed, uninstalled, or used as evidence.

The device test reconstructs the storage backend rather than killing the Host
process. Durability follows the synchronous app-private SharedPreferences
commit contract; a separate forced Host-process-restart test is not claimed.

## Non-goals and future work

V1 intentionally omits namespace enumeration, transactions, CAS, expiration,
sharing, export/import, encryption, rename migration, and orphan collection.
Those features require a separate Host policy and protocol/security review;
they must not widen `storage.kv.v1` implicitly.
