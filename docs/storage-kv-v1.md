# Persistent storage capability V1

Status: **DESIGN ONLY — NOT IMPLEMENTED OR ADVERTISED**

Design date: 2026-08-25

Protocol input reviewed: frozen AutoJs6 revision
`3b7378758c5a4f68e8680a78cf2c541c23628489`, Lua protocol 1.0.

## Outcome

A future `storage.kv.v1` Host capability may provide a small persistent
key/value store through `require("autojs").storage`. Persistence belongs to the
Host, not the Provider process. The Provider must expose only fixed operation
shapes, validate every Lua value before dispatch, and keep all storage I/O out
of JNI and the dedicated runtime process.

This document does not add a capability constant, Provider metadata entry,
Binder method, JNI function, Lua API, database, or protocol artifact. The
current Provider must continue to advertise only `device.info` and
`module.snapshot.v1`.

## Security principal and namespace

Storage is scoped to the Host's stable logical script principal. The
execution-scoped Host capability broker already belongs to the Host and must
close over that principal before the Provider receives it. The Provider sends
neither a namespace nor a filesystem path, and Lua cannot select another
principal.

At minimum, the Host scope key must include its own package/user identity and a
stable script identifier. Provider ID and storage schema version may be added
for migration, but a script-supplied name is never an authorization boundary.
Two executions of the same principal see the same store; unrelated scripts,
Android users/profiles, and Host packages must not.

If the canonical Host execution context cannot supply a stable principal to the
broker, implementation is blocked on a Host-side API change. Falling back to a
single Host-wide or Provider-wide namespace is forbidden because one script
could read or clear another script's data.

## Proposed Lua API

The initial Lua surface is synchronous and deliberately omits enumeration:

```lua
local storage = require("autojs").storage

local value = storage.get("counter")
storage.put("counter", 7)
local removed = storage.remove("counter")
local removedCount = storage.clear()
```

Exact behavior:

- `get(key)` returns the stored value, or `nil` when the key is absent;
- `put(key, value)` atomically inserts or replaces one non-nil value and returns
  `true` only after the Host commits it;
- `remove(key)` atomically removes one key and returns whether it existed; and
- `clear()` atomically removes every key belonging to the current principal and
  returns a non-negative integer count.

There is no `keys`, `list`, prefix scan, compare-and-swap, transaction handle,
watcher, expiry, or implicit conversion of `put(key, nil)` into deletion. Nil is
reserved for a missing `get`; callers must use `remove` explicitly.

## Key grammar

A key is strict UTF-8 that is also ASCII and must match
`[A-Za-z_][A-Za-z0-9._-]{0,63}`. Its encoded length is therefore 1 through 64
bytes. Keys are case-sensitive opaque identifiers; dots and hyphens are not
path separators and are never normalized.

Empty keys, leading digits, whitespace, NUL, slashes, backslashes, colons,
control characters, and non-ASCII text are rejected locally before Host
dispatch. The Host independently applies the same grammar before reading or
mutating persistent state.

## Fixed capability shapes

Every operation uses the exact capability name `storage.kv.v1`. The Provider
chooses the operation from a fixed native function; the script cannot supply an
arbitrary capability or operation string.

Requests are closed maps with no extra fields:

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

Unknown or duplicate fields, an unknown operation, a response that does not
match its request, non-empty descriptor arrays, or a negative/overflowed
`removedCount` is a protocol failure. Binder dispatch is performed once and is
never retried.

## Value mapping

Stored values reuse the existing bounded `LuaValue` model and quotas:

- Boolean, signed 64-bit integer, finite double, and valid UTF-8 string;
- dense 1-based arrays and string-key maps;
- maximum depth 32;
- maximum 4,096 nodes;
- maximum 256 KiB aggregate string/map-key data;
- maximum 1,023 entries per container;
- maximum 64 KiB per string; and
- maximum 1 KiB per map key.

Top-level and nested nil are rejected for `put`. Lua tables are converted with
raw operations only: no `__pairs`, `__index`, `__len`, or other metamethod is
invoked. Sparse/mixed-key tables, cycles, functions, threads, userdata,
light-userdata, metatables, non-finite numbers, invalid UTF-8, and mutation
during conversion fail the complete call before Host dispatch.

Lua strings have no text/bytes type bit. V1 therefore stores only valid UTF-8
`StringValue`; `BytesValue` requires a future explicit constructor and is not
guessed from content. A retrieved value is decoded through the same bounded
execution-local mapper used for Host results and arguments; no Java, Android,
database, or file object enters Lua.

## Quotas

All limits are independently enforced by Provider and Host:

- at most 256 keys per principal;
- at most 256 KiB of logical data in one value;
- at most 320 KiB in one canonical encoded stored value;
- at most 2 MiB of canonical encoded key/value bytes per principal;
- at most 64 storage operations per execution;
- at most 32 mutations (`put`, `remove`, or `clear`) per execution; and
- at most 1 MiB of cumulative returned value data and 1 MiB of cumulative
  accepted write value data per execution.

The operation slot is charged before dispatch. A write-byte slot is charged
after local validation but before the Host call. Host rejection does not refund
either slot, preventing a script from probing quotas without cost. `clear`
counts as one mutation and does not bypass the operation ceiling regardless of
the number of removed keys.

Quota overflow is deterministic and does not evict old entries. There is no
least-recently-used cleanup, truncation, partial table storage, or partial
`clear`. Checked or saturating arithmetic is mandatory for every aggregate
counter.

## Atomicity, durability, and ordering

The Host linearizes operations per principal. Within that order:

- a successful `put` replaces the complete previous value atomically;
- `remove` observes either the previous entry or its absence;
- `clear` removes the complete principal namespace atomically; and
- a success response is sent only after the Host's durable commit completes.

No Provider retry is allowed after timeout, callback death, Binder failure, or
an ambiguous Host-process failure. Retrying a mutation could duplicate an
effect whose acknowledgement was lost. The Host may attach an execution/call
ID to its private transaction log for deduplication, but that does not authorize
the Provider to retry.

Concurrent executions belonging to the same principal are ordered by the Host.
The initial API provides no multi-call transaction, so a `get` followed by a
`put` is not an atomic read/modify/write sequence. Last committed write wins.

## Clear and retention policy

`storage.clear()` affects only the current stable principal. The Host must also
delete that namespace when the principal is permanently deleted and when the
user explicitly clears the Host's data. Host uninstall/data-clear naturally
removes all stores owned by that Host.

Provider update, Provider process death, script execution failure, and ordinary
cache cleanup must not silently remove persistent entries. Provider uninstall
does not by itself prove that Host-owned data should be erased; the Host may
retain or remove it according to an explicit user-visible policy. Schema
migration must be atomic, versioned, and fail closed without returning partially
decoded values.

The capability provides app-private persistence, not a credential vault or
end-to-end encryption guarantee. Secrets require a separately reviewed Host
facility with authentication and key-management semantics.

## Negotiation and denial

The Provider may advertise `storage.kv.v1` only after the Host and Provider
implement the exact shapes and tests in this document. The execution request
must explicitly grant/select the capability. Missing grant produces the same
deterministic `DENIED` / `HOST_CAPABILITY` path as the current reviewed Host
capabilities; no storage work, disk access, prompt, fallback, or retry occurs.

An older Host or Provider continues without `autojs.storage`. There is no
downgrade to a local Provider database, SharedPreferences, filesystem file, or
unversioned operation shape after denial.

## Required implementation evidence

Before capability advertisement, tests must cover:

- every accepted and rejected key boundary in Provider and Host validators;
- grant and denial for all four operations without dispatch on denial;
- exact request/response maps and rejection of extra/duplicate/wrong-type
  fields and non-empty descriptors;
- scalar, dense-array, and map round trips plus every forbidden Lua type,
  metatable, cycle, depth/node/data/container limit, invalid UTF-8, and OOM;
- per-value, per-principal, per-execution operation/mutation/read/write quotas;
- atomic replace/remove/clear, namespace isolation, and no partial mutation;
- persistence across Provider process restart and Host restart;
- callback death, timeout, cancellation, ambiguous commit, and proof that the
  Provider never retries;
- concurrent same-principal ordering and different-principal isolation; and
- deletion, Host data-clear, update/migration, and downgrade behavior.

Until those gates pass, no `STORAGE_KV_CAPABILITY`, `autojs.storage` field,
Provider metadata entry, JNI method, or Host persistence implementation is
permitted in this repository.
