# Lua structured and multiple-result model V2

Status: **DESIGN ONLY — NOT IMPLEMENTED**

Design date: 2026-08-24

Protocol input reviewed: frozen AutoJs6 revision
`3b7378758c5a4f68e8680a78cf2c541c23628489`, Lua protocol 1.0.

## Outcome

V2 should represent the ordered Lua return list directly and encode each
admitted table as a bounded `LuaValue` tree. It must be negotiated as Lua
protocol 1.1 plus the exact capability `result.model.v2`.
Frozen V1 remains unchanged: a 1.0 request still accepts zero or one scalar,
still maps both zero
returns and one `nil` to `LuaValue.Nil`, and still returns zero descriptors.

This document does not change the three locked protocol AARs, provider metadata,
JNI code, service callback, or host. No implementation may advertise
`result.model.v2` until both host and provider changes below are staged together
and their conformance tests pass.

## Why a new result schema is required

The existing `LuaExecutionResult.value` already uses the general `LuaValue`
class, but the V1 result contract deliberately narrows it to one scalar. Encoding
the complete return list as a V1 `LuaValue.ArrayValue` would be ambiguous with a
script that returned one array-like table. It also cannot represent `nil` inside
the list because V1 `LuaValue.ArrayValue` forbids nil children.

V2 therefore introduces a distinct wire document, provisionally named
`SCHEMA_RESULT_V2`, rather than silently changing the meaning of
`SCHEMA_RESULT`:

| Tag | Field | Cardinality | Rule |
|---:|---|---|---|
| 1 | `requestId` | exactly one, required | Same 16-byte request identity as V1. |
| 2 | `value` | repeated, zero or more | Each occurrence is one independently encoded top-level `LuaValue`; top-level `Nil` is allowed. |
| 3 | `elapsedMillis` | exactly one, required | Same non-negative elapsed time as V1. |

The provisional schema uses the existing tagged-wire major version 1 and a new
schema ID allocated by the host protocol owner. The final numeric schema ID and
tag constants must be assigned in `lua-runtime-api`; this repository must not
invent them locally.

Repeated top-level fields preserve arity without nesting:

- zero returns → zero `value` fields;
- one `nil` → one `value` field containing `LuaValue.Nil`;
- `return 1, nil, "x"` → three ordered `value` fields;
- one table → one `value` field whose value is `ArrayValue` or `MapValue`.

This distinguishes zero returns, one nil, multiple returns, and one table while
reusing the reviewed value codec.

## Negotiation

The first compatible version is provisionally `LuaProtocolVersion(1, 1)`.
Selection is double-bound:

1. the Provider advertises protocol maximum 1.1 and capability
   `result.model.v2`;
2. the Host selects request protocol 1.1 and includes `result.model.v2` in
   `requiredCapabilities`.

Both conditions are mandatory. A 1.1 request without the capability, or a V2
capability on a 1.0 request, is rejected during negotiation. This avoids guessing
the callback schema and makes downgrade behavior deterministic.

`ILuaExecutionCallback.onCompleted(byte[] result,
ParcelFileDescriptor[] descriptors)` does not require an AIDL method change:
the selected request version tells the Host which result schema to decode.
Nevertheless, `lua-runtime-api` must add the V2 model, codec, validation, golden
wire fixtures, lifecycle-policy dispatch, and compatibility tests before the
new bytes are legal. The initial V2 model keeps `ParcelFileDescriptor[]
descriptors` empty; descriptor-backed values are explicitly out of scope.

## Lua-to-`LuaValue` mapping

V2 applies these deterministic rules to every top-level return and table child:

| Lua value | V2 value | Conditions |
|---|---|---|
| `nil` | `LuaValue.Nil` | Allowed only as a top-level return field. |
| Boolean | `BooleanValue` | Exact. |
| Integer | `Int64Value` | Must fit the Lua/Java signed 64-bit contract. |
| Float | `Float64Value` | Must be finite; NaN and infinities fail closed. |
| String | `StringValue` | Must be valid UTF-8 and no larger than the per-item limit. |
| Table with raw keys `1..n` only | `ArrayValue` | Dense, no holes, no extra keys; children cannot be nil. |
| Table with raw string keys only | `MapValue` | Keys must satisfy the existing UTF-8 byte limit; children cannot be nil. |
| Empty table | `ArrayValue(empty)` | A fixed convention; an empty map requires a future explicit constructor/type. |
| Function, thread, full/light userdata | rejected | No object, callback, Java, Android, or native pointer crosses the wire. |

Mixed-key and sparse tables fail with `RESULT_LIMIT`/an eventual dedicated
structured-result error; they are never partially converted. Conversion uses
raw iteration and raw reads, never `__pairs`, `__index`, `__len`, or other
metamethods. A table with a metatable is rejected so conversion cannot erase
behavioral identity and no untrusted finalizer is introduced during teardown.

An active-ancestor identity set detects cycles. A table reached again while it
is still being converted is rejected. Repeated references to a completed,
acyclic table may be encoded again by value; each copy consumes the aggregate
node/data budget, so alias identity is intentionally not preserved. Map entries
are serialized in unsigned UTF-8 byte order for deterministic wire bytes.

Lua strings do not carry a text-versus-bytes type bit. V2 therefore retains V1
result behavior: valid UTF-8 becomes `StringValue`; invalid UTF-8 is rejected.
`BytesValue` is not produced until a separately reviewed explicit Lua API can
create a binary result value without heuristic guessing.

## Aggregate quotas

The entire return list shares one quota state. Limits are not reset per return
or per table:

- `MAX_VALUE_DEPTH = 32`;
- `MAX_VALUE_NODES = 4_096` across all top-level values and descendants;
- `MAX_VALUE_DATA_BYTES = 256 KiB` across strings and map keys;
- `MAX_VALUE_CONTAINER_ENTRIES = 1_023` per table and as the maximum number of
  top-level returns;
- `MAX_VALUE_STRING_OR_BYTES = 64 KiB` per string;
- `MAX_MAP_KEY_BYTES = 1 KiB` per map key.

The list itself consumes one logical container for quota accounting even though
it is represented as repeated fields on the wire. Overflow, arithmetic wrap,
excessive stack depth, allocation failure, a cycle, a forbidden key/value type,
or mutation detected during conversion fails the whole result. No partial
result callback is allowed.

Conversion runs while the Lua allocator and execution control remain active.
It polls cancel/deadline at bounded intervals, and the outer native layer checks
the out-of-band termination reason before publishing encoded bytes. The
implementation must avoid recursive C++ traversal at the maximum depth or prove
its stack bound; an explicit work stack is preferred.

## Compatibility matrix

| Host | Provider | Request | Required outcome |
|---|---|---|---|
| V1-only | V1/V2-capable | 1.0 | Existing zero-or-one scalar behavior and `SCHEMA_RESULT`. |
| V2-capable | V1-only | 1.0 after negotiation | Existing V1 behavior; Host must not request V2. |
| V2-capable | V2-capable | 1.1 + capability | `SCHEMA_RESULT_V2`, ordered returns, bounded tables. |
| V2-capable | V2-capable | 1.1 without capability | Deterministic negotiation rejection. |
| V1-only | incorrectly V2-emitting Provider | 1.0 | Host rejects the unknown schema; Provider is non-conformant. |

Protocol-minor negotiation remains monotonic. There is no content sniffing,
fallback decode after a schema error, or retry of an already executed script.

## Host-side changes required

- Add protocol 1.1 and `result.model.v2` constants to the canonical
  `lua-runtime-api` source.
- Add `LuaExecutionResultV2`, `SCHEMA_RESULT_V2`, repeated-value codec support,
  validation, golden bytes, malformed-input tests, and lifecycle-policy rules.
- Select 1.1 only after capability intersection and include the capability in
  `requiredCapabilities`.
- Map the ordered result list into the AutoJs6 engine without collapsing zero
  returns, one nil, or multiple returns.
- Keep the V1 decode path for older Providers and never retry an executed
  request because result decoding failed.
- Rebuild and restage all three protocol AARs from one clean committed Host
  revision; update the immutable lock only through the existing intake workflow.

## Provider-side changes required

- Advertise protocol 1.1 and `result.model.v2` only in a build containing the
  completed implementation.
- Preserve the selected request version through the session controller to the
  result encoder.
- Replace scalar-only boxing with bounded, deterministic list/tree conversion
  for V2 while retaining the exact V1 branch.
- Add native tests for zero/one-nil/multiple returns; empty/dense/map tables;
  cycles, aliasing, sparse and mixed keys; metatables; depth/nodes/data limits;
  invalid UTF-8; OOM; cancellation and deadline during conversion.
- Add Binder compatibility tests for every row of the matrix and assert that
  both V1 and initial V2 return zero descriptors.
- Extend the repository verifier only after the canonical protocol artifacts
  and implementation land together.

## Non-goals

V2 does not expose functions, coroutines, userdata, metatables, Java/Android
objects, arbitrary descriptors, streaming results, shared table identity, or
table mutation after completion. It does not enable Lua-level `pcall`/`xpcall`
and does not change console or Host capability quotas.

Until the Host-side changes required above are committed and restaged, this
document is compatibility planning only. The current Provider must continue to
advertise protocol 1.0 and reject tables or multiple returns.
