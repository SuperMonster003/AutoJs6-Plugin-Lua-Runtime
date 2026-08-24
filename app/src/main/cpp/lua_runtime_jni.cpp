#include <jni.h>

#include <algorithm>
#include <bit>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <memory>
#include <new>
#include <string>

#if defined(AUTOJS_LUA_DEBUG_FAULT_HARNESS)
#include <thread>
#endif

extern "C" {
#include "lauxlib.h"
#include "lua.h"
#include "lualib.h"
}

namespace {

constexpr jsize kMaxSourceBytes = 16 * 1024 * 1024;
constexpr jsize kMaxSourceNameBytes = 1024;
constexpr size_t kMaxOutputChunkBytes = 32 * 1024;
constexpr jint kStdoutStreamWireCode = 1;
constexpr jint kStderrStreamWireCode = 2;
constexpr size_t kMaxScalarStringBytes = 64 * 1024;
constexpr size_t kMaxModuleNameBytes = 64;
constexpr jsize kMaxModuleSourceBytes = 64 * 1024;
constexpr jsize kMaxArgumentSnapshotBytes = 256 * 1024 + 17 * 4096 + 16;
constexpr size_t kMaxArgumentDepth = 32;
constexpr size_t kMaxArgumentNodes = 4096;
constexpr size_t kMaxArgumentDataBytes = 256 * 1024;
constexpr uint32_t kMaxArgumentContainerEntries = 1023;
constexpr uint32_t kMaxArgumentStringOrBytes = 64 * 1024;
constexpr uint32_t kMaxArgumentMapKeyBytes = 1024;
constexpr uint32_t kArgumentMagic = 0x41364C41U;  // A6LA
constexpr uint8_t kArgumentVersion = 1U;
constexpr size_t kMaxFailureMessageBytes = 256U;
constexpr jlong kMaxMemoryBytes = 256LL * 1024LL * 1024LL;
constexpr jlong kMaxTimeoutMillis = 10LL * 60LL * 1000LL;
constexpr int kHookInstructionCount = 10'000;

static_assert(
    std::numeric_limits<lua_Integer>::is_signed
        && std::numeric_limits<lua_Integer>::digits >= 63,
    "autojs.now requires a signed 64-bit Lua integer");

struct MemoryBudget {
    size_t limit;
    size_t used;
    bool accounting_failed;
};

void* bounded_allocator(void* opaque, void* pointer, size_t old_size, size_t new_size) {
    auto* budget = static_cast<MemoryBudget*>(opaque);
    if (budget == nullptr) {
        return nullptr;
    }
    const size_t accounted_old_size = pointer == nullptr ? 0U : old_size;
    if (accounted_old_size > budget->used) {
        // A trusted Lua allocator callback must never report more live bytes than were admitted.
        // Once accounting is inconsistent, deny every later allocation while still honoring
        // deallocation callbacks so lua_close can release already-owned blocks.
        budget->accounting_failed = true;
    }

    if (new_size == 0U) {
        std::free(pointer);
        budget->used = accounted_old_size <= budget->used
            ? budget->used - accounted_old_size
            : 0U;
        return nullptr;
    }
    if (budget->accounting_failed) {
        return nullptr;
    }

    const size_t used_without_old = budget->used - accounted_old_size;

    // Reject before realloc: when an allocator returns null, Lua requires the
    // original block to remain valid. Never free a successful replacement and
    // then report failure.
    if (used_without_old > budget->limit || new_size > budget->limit - used_without_old) {
        return nullptr;
    }

    void* replacement = std::realloc(pointer, new_size);
    if (replacement == nullptr) {
        return nullptr;
    }

    budget->used = used_without_old + new_size;
    return replacement;
}

class LuaStateOwner {
public:
    explicit LuaStateOwner(lua_State* state) : state_(state) {}

    LuaStateOwner(const LuaStateOwner&) = delete;
    LuaStateOwner& operator=(const LuaStateOwner&) = delete;

    ~LuaStateOwner() {
        close();
    }

    void close() {
        if (state_ != nullptr) {
            lua_close(state_);
            state_ = nullptr;
        }
    }

private:
    lua_State* state_;
};

void remove_global(lua_State* state, const char* name) {
    lua_pushnil(state);
    lua_setglobal(state, name);
}

int open_safe_libraries(lua_State* state) {
    static const luaL_Reg libraries[] = {
        {LUA_GNAME, luaopen_base},
        {LUA_MATHLIBNAME, luaopen_math},
        {LUA_STRLIBNAME, luaopen_string},
        {LUA_TABLIBNAME, luaopen_table},
        {LUA_UTF8LIBNAME, luaopen_utf8},
        {nullptr, nullptr},
    };
    for (const luaL_Reg* library = libraries; library->func != nullptr; ++library) {
        luaL_requiref(state, library->name, library->func, 1);
        lua_pop(state, 1);
    }

    // Base-library loaders can read or introduce unreviewed code even without io/package.
    remove_global(state, "dofile");
    remove_global(state, "load");
    remove_global(state, "loadfile");

    // A count-hook error is catchable by Lua's protected-call functions. Removing them prevents
    // an untrusted script from repeatedly swallowing cancellation/deadline interrupts forever.
    remove_global(state, "pcall");
    remove_global(state, "xpcall");

    // User-created __gc/__close metamethods can make lua_close execute attacker-controlled code
    // after the protected chunk returns. The source-only MVP exposes no metatable mutation or
    // discovery path, so state teardown cannot acquire an unbounded user finalizer.
    remove_global(state, "getmetatable");
    remove_global(state, "setmetatable");

    // stdout/stderr must not bypass the future credit-controlled Binder callback path.
    remove_global(state, "print");
    remove_global(state, "warn");

    // Binary chunk creation is not part of the initial source-only runtime.
    lua_getglobal(state, LUA_STRLIBNAME);
    lua_pushnil(state);
    lua_setfield(state, -2, "dump");
    lua_pop(state, 1);
    return 0;
}

enum class TerminationReason {
    kNone,
    kCancelled,
    kDeadlineExceeded,
    kControlFailure,
    kOutputRejected,
    kHostCallRejected,
};

struct ExecutionControl {
    JNIEnv* environment;
    jobject cancellation_probe;
    jmethodID cancellation_method;
    jobject output_emitter;
    jmethodID output_method;
    jobject host_capability_bridge;
    jmethodID device_info_method;
    jmethodID load_module_method;
    jmethodID host_failure_method;
    std::chrono::steady_clock::time_point deadline;
    TerminationReason termination_reason;
};

struct NativeArgumentView {
    const uint8_t* bytes;
    size_t size;
};

struct NativeArgumentReader {
    const uint8_t* cursor;
    const uint8_t* end;
    size_t nodes;
    size_t data_bytes;
    bool failed;
};

bool read_argument_u8(NativeArgumentReader* reader, uint8_t* value) {
    if (reader->failed || reader->cursor == reader->end) {
        reader->failed = true;
        return false;
    }
    *value = *reader->cursor++;
    return true;
}

bool read_argument_u32(NativeArgumentReader* reader, uint32_t* value) {
    if (reader->failed || static_cast<size_t>(reader->end - reader->cursor) < 4U) {
        reader->failed = true;
        return false;
    }
    *value = (static_cast<uint32_t>(reader->cursor[0]) << 24U) |
        (static_cast<uint32_t>(reader->cursor[1]) << 16U) |
        (static_cast<uint32_t>(reader->cursor[2]) << 8U) |
        static_cast<uint32_t>(reader->cursor[3]);
    reader->cursor += 4;
    return true;
}

bool read_argument_u64(NativeArgumentReader* reader, uint64_t* value) {
    if (reader->failed || static_cast<size_t>(reader->end - reader->cursor) < 8U) {
        reader->failed = true;
        return false;
    }
    uint64_t decoded = 0U;
    for (int index = 0; index < 8; ++index) {
        decoded = (decoded << 8U) | static_cast<uint64_t>(reader->cursor[index]);
    }
    reader->cursor += 8;
    *value = decoded;
    return true;
}

bool read_argument_bytes(
    NativeArgumentReader* reader,
    uint32_t maximum_length,
    const uint8_t** bytes,
    size_t* length) {
    uint32_t encoded_length = 0U;
    if (!read_argument_u32(reader, &encoded_length) || encoded_length > maximum_length) {
        reader->failed = true;
        return false;
    }
    const size_t decoded_length = static_cast<size_t>(encoded_length);
    if (reader->data_bytes > kMaxArgumentDataBytes - decoded_length ||
        static_cast<size_t>(reader->end - reader->cursor) < decoded_length) {
        reader->failed = true;
        return false;
    }
    *bytes = reader->cursor;
    *length = decoded_length;
    reader->cursor += decoded_length;
    reader->data_bytes += decoded_length;
    return true;
}

bool push_native_argument_value(
    lua_State* state,
    NativeArgumentReader* reader,
    size_t depth,
    bool allow_nil) {
    if (reader->failed || depth > kMaxArgumentDepth || reader->nodes >= kMaxArgumentNodes) {
        reader->failed = true;
        return false;
    }
    ++reader->nodes;

    uint8_t kind = 0U;
    if (!read_argument_u8(reader, &kind)) {
        return false;
    }
    switch (kind) {
        case 0U:
            if (!allow_nil) {
                reader->failed = true;
                return false;
            }
            lua_pushnil(state);
            return true;
        case 1U:
            lua_pushboolean(state, 0);
            return true;
        case 2U:
            lua_pushboolean(state, 1);
            return true;
        case 3U: {
            uint64_t encoded = 0U;
            if (!read_argument_u64(reader, &encoded)) {
                return false;
            }
            static_assert(sizeof(lua_Integer) == sizeof(int64_t));
            static_assert(std::numeric_limits<lua_Integer>::is_signed);
            lua_pushinteger(state, static_cast<lua_Integer>(std::bit_cast<int64_t>(encoded)));
            return true;
        }
        case 4U: {
            uint64_t encoded = 0U;
            if (!read_argument_u64(reader, &encoded)) {
                return false;
            }
            const double value = std::bit_cast<double>(encoded);
            if (!std::isfinite(value)) {
                reader->failed = true;
                return false;
            }
            lua_pushnumber(state, static_cast<lua_Number>(value));
            return true;
        }
        case 5U:
        case 6U: {
            const uint8_t* bytes = nullptr;
            size_t length = 0U;
            if (!read_argument_bytes(
                    reader,
                    kMaxArgumentStringOrBytes,
                    &bytes,
                    &length)) {
                return false;
            }
            lua_pushlstring(state, reinterpret_cast<const char*>(bytes), length);
            return true;
        }
        case 7U: {
            uint32_t count = 0U;
            if (!read_argument_u32(reader, &count) || count > kMaxArgumentContainerEntries) {
                reader->failed = true;
                return false;
            }
            lua_createtable(state, static_cast<int>(count), 0);
            for (uint32_t index = 0U; index < count; ++index) {
                if (!push_native_argument_value(state, reader, depth + 1U, false)) {
                    return false;
                }
                lua_rawseti(state, -2, static_cast<lua_Integer>(index + 1U));
            }
            return true;
        }
        case 8U: {
            uint32_t count = 0U;
            if (!read_argument_u32(reader, &count) || count > kMaxArgumentContainerEntries) {
                reader->failed = true;
                return false;
            }
            lua_createtable(state, 0, static_cast<int>(count));
            for (uint32_t index = 0U; index < count; ++index) {
                const uint8_t* key = nullptr;
                size_t key_length = 0U;
                if (!read_argument_bytes(
                        reader,
                        kMaxArgumentMapKeyBytes,
                        &key,
                        &key_length)) {
                    return false;
                }
                lua_pushlstring(state, reinterpret_cast<const char*>(key), key_length);
                lua_pushvalue(state, -1);
                lua_rawget(state, -3);
                const bool duplicate_key = lua_type(state, -1) != LUA_TNIL;
                lua_pop(state, 1);
                if (duplicate_key) {
                    reader->failed = true;
                    return false;
                }
                if (!push_native_argument_value(state, reader, depth + 1U, false)) {
                    return false;
                }
                lua_rawset(state, -3);
            }
            return true;
        }
        default:
            reader->failed = true;
            return false;
    }
}

bool push_native_arguments(lua_State* state, const NativeArgumentView* arguments) {
    if (arguments == nullptr || arguments->bytes == nullptr || arguments->size < 6U ||
        arguments->size > static_cast<size_t>(kMaxArgumentSnapshotBytes)) {
        return false;
    }
    NativeArgumentReader reader{
        arguments->bytes,
        arguments->bytes + arguments->size,
        0U,
        0U,
        false,
    };
    uint32_t magic = 0U;
    uint8_t version = 0U;
    if (!read_argument_u32(&reader, &magic) || magic != kArgumentMagic ||
        !read_argument_u8(&reader, &version) || version != kArgumentVersion ||
        !push_native_argument_value(state, &reader, 0U, true)) {
        return false;
    }
    return !reader.failed && reader.cursor == reader.end;
}

bool poll_execution_control(ExecutionControl* control) {
    if (control->termination_reason != TerminationReason::kNone) {
        return false;
    }
    if (std::chrono::steady_clock::now() >= control->deadline) {
        control->termination_reason = TerminationReason::kDeadlineExceeded;
        return false;
    }
    const jboolean cancelled = control->environment->CallBooleanMethod(
        control->cancellation_probe,
        control->cancellation_method);
    if (control->environment->ExceptionCheck()) {
        // Never let an arbitrary cancellation-probe exception escape through Lua's longjmp path.
        control->environment->ExceptionClear();
        control->termination_reason = TerminationReason::kControlFailure;
        return false;
    }
    if (cancelled == JNI_TRUE) {
        control->termination_reason = TerminationReason::kCancelled;
        return false;
    }
    return true;
}

void execution_hook(lua_State* state, lua_Debug* /* debug */) {
    auto** slot = static_cast<ExecutionControl**>(lua_getextraspace(state));
    ExecutionControl* control = slot == nullptr ? nullptr : *slot;
    if (control == nullptr || !poll_execution_control(control)) {
        // The text is deliberately fixed. The reason is retained outside the Lua stack and is
        // checked before any Lua status or result can become observable to the caller.
        luaL_error(state, "AutoJs Lua execution interrupted");
    }
}

ExecutionControl* execution_control(lua_State* state) {
    auto** slot = static_cast<ExecutionControl**>(lua_getextraspace(state));
    return slot == nullptr ? nullptr : *slot;
}

int emit_autojs_console(lua_State* state, jint stream_wire_code) {
    ExecutionControl* control = execution_control(state);
    if (control == nullptr || lua_gettop(state) != 1 || lua_type(state, 1) != LUA_TSTRING) {
        return luaL_error(state, "AutoJs console expects exactly one string");
    }
    if (!poll_execution_control(control)) {
        return luaL_error(state, "AutoJs Lua execution interrupted");
    }
    size_t text_length = 0U;
    const char* text = lua_tolstring(state, 1, &text_length);
    if (text == nullptr || text_length == 0U || text_length > kMaxOutputChunkBytes) {
        return luaL_error(state, "AutoJs console output is empty or exceeds its chunk limit");
    }

    jbyteArray bytes = control->environment->NewByteArray(static_cast<jsize>(text_length));
    if (bytes == nullptr) {
        if (control->environment->ExceptionCheck()) {
            control->environment->ExceptionClear();
        }
        control->termination_reason = TerminationReason::kControlFailure;
        return luaL_error(state, "AutoJs console bridge failed");
    }
    control->environment->SetByteArrayRegion(
        bytes,
        0,
        static_cast<jsize>(text_length),
        reinterpret_cast<const jbyte*>(text));
    if (control->environment->ExceptionCheck()) {
        control->environment->ExceptionClear();
        control->environment->DeleteLocalRef(bytes);
        control->termination_reason = TerminationReason::kControlFailure;
        return luaL_error(state, "AutoJs console bridge failed");
    }
    const jboolean emitted = control->environment->CallBooleanMethod(
        control->output_emitter,
        control->output_method,
        stream_wire_code,
        bytes);
    control->environment->DeleteLocalRef(bytes);
    if (control->environment->ExceptionCheck()) {
        control->environment->ExceptionClear();
        control->termination_reason = TerminationReason::kControlFailure;
        return luaL_error(state, "AutoJs console bridge failed");
    }
    if (emitted != JNI_TRUE) {
        control->termination_reason = TerminationReason::kOutputRejected;
        return luaL_error(state, "AutoJs console output was rejected");
    }
    return 0;
}

int autojs_console_print(lua_State* state) {
    const int top = lua_gettop(state);
    ExecutionControl* control = execution_control(state);
    if (control == nullptr) {
        return luaL_error(state, "AutoJs print bridge is unavailable");
    }
    if (!poll_execution_control(control)) {
        return luaL_error(state, "AutoJs Lua execution interrupted");
    }

    std::string output;
    output.reserve(top > 0 ? std::min<size_t>(kMaxOutputChunkBytes, static_cast<size_t>(top * 16)) : 1);
    for (int index = 1; index <= top; ++index) {
        size_t text_length = 0U;
        const char* text = luaL_tolstring(state, index, &text_length);
        if (text == nullptr) {
            return luaL_error(state, "AutoJs console print value is not string-convertible");
        }
        if (!output.empty()) {
            if (output.size() >= kMaxOutputChunkBytes) {
                return luaL_error(state, "AutoJs console output is too large");
            }
            output.push_back('\t');
        }
        if (text_length > 0U) {
            if (output.size() + text_length >= kMaxOutputChunkBytes) {
                return luaL_error(state, "AutoJs console output is too large");
            }
            output.append(text, text_length);
        }
        lua_pop(state, 1);
    }
    if (output.size() + 1U > kMaxOutputChunkBytes) {
        return luaL_error(state, "AutoJs console output is too large");
    }
    output.push_back('\n');
    lua_settop(state, 0);
    lua_pushlstring(state, output.data(), output.size());
    return emit_autojs_console(state, kStdoutStreamWireCode);
}

int autojs_console_log(lua_State* state) {
    return emit_autojs_console(state, kStdoutStreamWireCode);
}

int autojs_console_error(lua_State* state) {
    return emit_autojs_console(state, kStderrStreamWireCode);
}

int autojs_now(lua_State* state) {
    ExecutionControl* control = execution_control(state);
    if (control == nullptr || lua_gettop(state) != 0) {
        return luaL_error(state, "autojs.now expects no arguments");
    }
    if (!poll_execution_control(control)) {
        return luaL_error(state, "AutoJs Lua execution interrupted");
    }

    const auto unix_epoch_millis = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    lua_pushinteger(state, static_cast<lua_Integer>(unix_epoch_millis));
    return 1;
}

int controlled_math_randomseed(lua_State* state) {
    ExecutionControl* control = execution_control(state);
    const int argument_count = lua_gettop(state);
    if (control == nullptr || argument_count < 1 || argument_count > 2
        || !lua_isinteger(state, 1)
        || (argument_count == 2 && !lua_isinteger(state, 2))) {
        return luaL_error(state, "math.randomseed expects one or two explicit integer seeds");
    }
    if (!poll_execution_control(control)) {
        return luaL_error(state, "AutoJs Lua execution interrupted");
    }

    lua_pushvalue(state, lua_upvalueindex(1));
    lua_insert(state, 1);
    lua_call(state, argument_count, 2);
    if (!poll_execution_control(control)) {
        return luaL_error(state, "AutoJs Lua execution interrupted");
    }
    return 2;
}

int push_host_result(lua_State* state) {
    if (lua_gettop(state) != 1 || lua_type(state, 1) != LUA_TLIGHTUSERDATA) {
        return luaL_error(state, "AutoJs host result input is unavailable");
    }
    const auto* view = static_cast<const NativeArgumentView*>(lua_touserdata(state, 1));
    if (!push_native_arguments(state, view)) {
        return luaL_error(state, "AutoJs host result input is invalid");
    }
    return 1;
}

void record_host_call_failure(ExecutionControl* control) {
    const jint failure_kind = control->environment->CallIntMethod(
        control->host_capability_bridge,
        control->host_failure_method);
    if (control->environment->ExceptionCheck()) {
        control->environment->ExceptionClear();
        control->termination_reason = TerminationReason::kControlFailure;
    } else if (failure_kind == 1) {
        control->termination_reason = TerminationReason::kCancelled;
    } else if (failure_kind == 2) {
        control->termination_reason = TerminationReason::kDeadlineExceeded;
    } else if (poll_execution_control(control)) {
        control->termination_reason = TerminationReason::kHostCallRejected;
    }
}

enum class ProtectedHostMappingResult {
    kOk,
    kCopyAllocationFailed,
    kCopyUnavailable,
    kMemoryLimit,
    kInvalid,
};

// Owns the copied JVM payload while the decoder runs under lua_pcall. This helper never raises a
// Lua error: its unique_ptr is always destroyed before the calling Lua C function may longjmp.
// The caller places push_host_result on the stack before entering this ownership scope.
ProtectedHostMappingResult copy_and_map_host_result(
    lua_State* state,
    ExecutionControl* control,
    jbyteArray encoded,
    jsize length) {
    auto bytes = std::unique_ptr<uint8_t[]>(
        new (std::nothrow) uint8_t[static_cast<size_t>(length)]);
    if (bytes == nullptr) {
        control->environment->DeleteLocalRef(encoded);
        return ProtectedHostMappingResult::kCopyAllocationFailed;
    }
    control->environment->GetByteArrayRegion(
        encoded,
        0,
        length,
        reinterpret_cast<jbyte*>(bytes.get()));
    control->environment->DeleteLocalRef(encoded);
    if (control->environment->ExceptionCheck()) {
        control->environment->ExceptionClear();
        return ProtectedHostMappingResult::kCopyUnavailable;
    }
    NativeArgumentView view{
        bytes.get(),
        static_cast<size_t>(length),
    };
    // A Lua C function starts with LUA_MINSTACK free slots. This non-allocating light-userdata
    // push cannot grow the stack, and all decoding/allocation that follows is protected by pcall.
    lua_pushlightuserdata(state, &view);
    const int status = lua_pcall(state, 1, 1, 0);
    if (status == LUA_OK) {
        return ProtectedHostMappingResult::kOk;
    }
    return status == LUA_ERRMEM
        ? ProtectedHostMappingResult::kMemoryLimit
        : ProtectedHostMappingResult::kInvalid;
}

int autojs_device_info(lua_State* state) {
    ExecutionControl* control = execution_control(state);
    if (control == nullptr || lua_gettop(state) != 0) {
        return luaL_error(state, "AutoJs device.info expects no arguments");
    }
    if (!poll_execution_control(control)) {
        return luaL_error(state, "AutoJs Lua execution interrupted");
    }
    auto* encoded = static_cast<jbyteArray>(control->environment->CallObjectMethod(
        control->host_capability_bridge,
        control->device_info_method));
    if (control->environment->ExceptionCheck()) {
        control->environment->ExceptionClear();
        record_host_call_failure(control);
        return luaL_error(state, "AutoJs device.info host call failed");
    }
    if (encoded == nullptr) {
        control->termination_reason = TerminationReason::kControlFailure;
        return luaL_error(state, "AutoJs device.info response is unavailable");
    }
    const jsize length = control->environment->GetArrayLength(encoded);
    if (length < 6 || length > kMaxArgumentSnapshotBytes) {
        control->environment->DeleteLocalRef(encoded);
        control->termination_reason = TerminationReason::kControlFailure;
        return luaL_error(state, "AutoJs device.info response exceeds its native limit");
    }
    lua_pushcfunction(state, push_host_result);
    const ProtectedHostMappingResult mapping =
        copy_and_map_host_result(state, control, encoded, length);
    switch (mapping) {
        case ProtectedHostMappingResult::kOk:
            break;
        case ProtectedHostMappingResult::kCopyAllocationFailed:
            control->termination_reason = TerminationReason::kControlFailure;
            return luaL_error(state, "AutoJs device.info response copy allocation failed");
        case ProtectedHostMappingResult::kCopyUnavailable:
            control->termination_reason = TerminationReason::kControlFailure;
            return luaL_error(state, "AutoJs device.info response bytes are unavailable");
        case ProtectedHostMappingResult::kMemoryLimit:
            return luaL_error(state, "AutoJs device.info mapping exceeded the memory limit");
        case ProtectedHostMappingResult::kInvalid:
            control->termination_reason = TerminationReason::kControlFailure;
            return luaL_error(state, "AutoJs device.info response is invalid");
    }
    if (!poll_execution_control(control)) {
        return luaL_error(state, "AutoJs Lua execution interrupted");
    }
    return 1;
}

bool is_flat_ascii_module_name(const char* name, size_t length) {
    if (name == nullptr || length == 0U || length > kMaxModuleNameBytes) {
        return false;
    }
    const auto first = static_cast<unsigned char>(name[0]);
    if (!((first >= 'A' && first <= 'Z') || (first >= 'a' && first <= 'z') || first == '_')) {
        return false;
    }
    for (size_t index = 1U; index < length; ++index) {
        const auto character = static_cast<unsigned char>(name[index]);
        if (!((character >= 'A' && character <= 'Z') ||
              (character >= 'a' && character <= 'z') ||
              (character >= '0' && character <= '9') || character == '_')) {
            return false;
        }
    }
    return true;
}

void clear_module_loading(lua_State* state) {
    lua_pushvalue(state, lua_upvalueindex(3));
    lua_pushvalue(state, 1);
    lua_pushnil(state);
    lua_rawset(state, -3);
    lua_pop(state, 1);
}

struct NativeModuleView {
    const char* source;
    size_t source_size;
    const char* chunk_name;
};

int push_module_chunk(lua_State* state) {
    if (lua_gettop(state) != 1 || lua_type(state, 1) != LUA_TLIGHTUSERDATA) {
        return luaL_error(state, "Lua module source input is unavailable");
    }
    const auto* module = static_cast<const NativeModuleView*>(lua_touserdata(state, 1));
    if (module == nullptr || module->source == nullptr || module->chunk_name == nullptr) {
        return luaL_error(state, "Lua module source input is invalid");
    }
    const int module_load_status = luaL_loadbufferx(
        state,
        module->source,
        module->source_size,
        module->chunk_name,
        "t");
    if (module_load_status != LUA_OK) {
        return lua_error(state);
    }
    return 1;
}

enum class ProtectedModuleLoadResult {
    kOk,
    kCopyAllocationFailed,
    kCopyUnavailable,
    kInterrupted,
    kLuaError,
};

// Copies one frozen module and invokes its text-only loader under lua_pcall. No Lua error is
// raised from this ownership scope, so the source buffer is deterministically destroyed before
// restricted_require propagates a loader error with lua_error/luaL_error.
// The caller places push_module_chunk on the stack before entering this helper.
ProtectedModuleLoadResult copy_and_load_module(
    lua_State* state,
    ExecutionControl* control,
    jbyteArray source_bytes,
    jsize source_length,
    const char* name,
    size_t name_length) {
    const size_t copy_length = source_length == 0 ? 1U : static_cast<size_t>(source_length);
    auto source_copy = std::unique_ptr<char[]>(new (std::nothrow) char[copy_length]);
    if (source_copy == nullptr) {
        control->environment->DeleteLocalRef(source_bytes);
        return ProtectedModuleLoadResult::kCopyAllocationFailed;
    }
    if (source_length > 0) {
        control->environment->GetByteArrayRegion(
            source_bytes,
            0,
            source_length,
            reinterpret_cast<jbyte*>(source_copy.get()));
    }
    control->environment->DeleteLocalRef(source_bytes);
    if (control->environment->ExceptionCheck()) {
        control->environment->ExceptionClear();
        return ProtectedModuleLoadResult::kCopyUnavailable;
    }
    if (!poll_execution_control(control)) {
        return ProtectedModuleLoadResult::kInterrupted;
    }

    char chunk_name[kMaxModuleNameBytes + 2]{};
    chunk_name[0] = '=';
    std::memcpy(chunk_name + 1, name, name_length);
    chunk_name[name_length + 1] = '\0';
    NativeModuleView module_view{
        source_copy.get(),
        static_cast<size_t>(source_length),
        chunk_name,
    };
    // The light-userdata push uses an already guaranteed LUA_MINSTACK slot; the text loader and
    // all allocations are inside the protected call.
    lua_pushlightuserdata(state, &module_view);
    return lua_pcall(state, 1, 1, 0) == LUA_OK
        ? ProtectedModuleLoadResult::kOk
        : ProtectedModuleLoadResult::kLuaError;
}

int restricted_require(lua_State* state) {
    if (lua_gettop(state) != 1 || lua_type(state, 1) != LUA_TSTRING) {
        return luaL_error(state, "require expects one admitted module name");
    }
    size_t name_length = 0U;
    const char* name = lua_tolstring(state, 1, &name_length);
    if (name != nullptr && name_length == 6U && std::memcmp(name, "autojs", 6U) == 0) {
        lua_pushvalue(state, lua_upvalueindex(1));
        return 1;
    }
    if (!is_flat_ascii_module_name(name, name_length)) {
        return luaL_error(state, "Lua module name is not admitted");
    }

    // The first result is retained exactly, including false and table identity. A nil result is
    // normalized to true below, matching Lua's require convention while keeping nil available as
    // the cache-miss sentinel.
    lua_pushvalue(state, lua_upvalueindex(2));
    lua_pushvalue(state, 1);
    lua_rawget(state, -2);
    if (!lua_isnil(state, -1)) {
        return 1;
    }
    lua_pop(state, 2);

    lua_pushvalue(state, lua_upvalueindex(3));
    lua_pushvalue(state, 1);
    lua_rawget(state, -2);
    const bool already_loading = lua_toboolean(state, -1) != 0;
    lua_pop(state, 2);
    if (already_loading) {
        return luaL_error(state, "Lua module snapshot dependency cycle rejected");
    }
    lua_pushvalue(state, lua_upvalueindex(3));
    lua_pushvalue(state, 1);
    lua_pushboolean(state, 1);
    lua_rawset(state, -3);
    lua_pop(state, 1);

    ExecutionControl* control = execution_control(state);
    if (control == nullptr || !poll_execution_control(control)) {
        clear_module_loading(state);
        return luaL_error(state, "AutoJs Lua execution interrupted");
    }

    jbyteArray name_bytes = control->environment->NewByteArray(static_cast<jsize>(name_length));
    if (name_bytes == nullptr) {
        if (control->environment->ExceptionCheck()) {
            control->environment->ExceptionClear();
        }
        control->termination_reason = TerminationReason::kControlFailure;
        clear_module_loading(state);
        return luaL_error(state, "Lua module snapshot bridge failed");
    }
    control->environment->SetByteArrayRegion(
        name_bytes,
        0,
        static_cast<jsize>(name_length),
        reinterpret_cast<const jbyte*>(name));
    if (control->environment->ExceptionCheck()) {
        control->environment->ExceptionClear();
        control->environment->DeleteLocalRef(name_bytes);
        control->termination_reason = TerminationReason::kControlFailure;
        clear_module_loading(state);
        return luaL_error(state, "Lua module snapshot bridge failed");
    }
    auto* source_bytes = static_cast<jbyteArray>(control->environment->CallObjectMethod(
        control->host_capability_bridge,
        control->load_module_method,
        name_bytes));
    control->environment->DeleteLocalRef(name_bytes);
    if (control->environment->ExceptionCheck()) {
        control->environment->ExceptionClear();
        record_host_call_failure(control);
        clear_module_loading(state);
        return luaL_error(state, "Lua module snapshot host call failed");
    }
    if (source_bytes == nullptr) {
        if (!poll_execution_control(control)) {
            clear_module_loading(state);
            return luaL_error(state, "AutoJs Lua execution interrupted");
        }
        clear_module_loading(state);
        return luaL_error(state, "Lua module snapshot is unavailable");
    }

    const jsize source_length = control->environment->GetArrayLength(source_bytes);
    if (source_length < 0 || source_length > kMaxModuleSourceBytes) {
        control->environment->DeleteLocalRef(source_bytes);
        control->termination_reason = TerminationReason::kControlFailure;
        clear_module_loading(state);
        return luaL_error(state, "Lua module snapshot exceeds its native limit");
    }
    lua_pushcfunction(state, push_module_chunk);
    const ProtectedModuleLoadResult module_load = copy_and_load_module(
        state,
        control,
        source_bytes,
        source_length,
        name,
        name_length);
    switch (module_load) {
        case ProtectedModuleLoadResult::kOk:
            break;
        case ProtectedModuleLoadResult::kCopyAllocationFailed:
            control->termination_reason = TerminationReason::kControlFailure;
            clear_module_loading(state);
            return luaL_error(state, "Lua module snapshot copy allocation failed");
        case ProtectedModuleLoadResult::kCopyUnavailable:
            control->termination_reason = TerminationReason::kControlFailure;
            clear_module_loading(state);
            return luaL_error(state, "Lua module snapshot bytes are unavailable");
        case ProtectedModuleLoadResult::kInterrupted:
            clear_module_loading(state);
            return luaL_error(state, "AutoJs Lua execution interrupted");
        case ProtectedModuleLoadResult::kLuaError:
            clear_module_loading(state);
            return lua_error(state);
    }

    const int status = lua_pcall(state, 0, 1, 0);
    clear_module_loading(state);
    if (control->termination_reason != TerminationReason::kNone) {
        return luaL_error(state, "AutoJs Lua execution interrupted");
    }
    if (status != LUA_OK) {
        return lua_error(state);
    }
    if (!poll_execution_control(control)) {
        return luaL_error(state, "AutoJs Lua execution interrupted");
    }
    if (lua_isnil(state, -1)) {
        lua_pop(state, 1);
        lua_pushboolean(state, 1);
    }

    lua_pushvalue(state, lua_upvalueindex(2));
    lua_pushvalue(state, 1);
    lua_pushvalue(state, 2);
    lua_rawset(state, -3);
    lua_pop(state, 1);
    return 1;
}

int install_autojs_module(lua_State* state) {
    if (lua_gettop(state) != 1 || lua_type(state, 1) != LUA_TLIGHTUSERDATA) {
        return luaL_error(state, "AutoJs argument bridge input is unavailable");
    }
    const auto* arguments = static_cast<const NativeArgumentView*>(lua_touserdata(state, 1));

    lua_getglobal(state, LUA_MATHLIBNAME);
    if (lua_type(state, -1) != LUA_TTABLE) {
        return luaL_error(state, "AutoJs math library is unavailable");
    }
    lua_getfield(state, -1, "randomseed");
    if (lua_type(state, -1) != LUA_TFUNCTION) {
        return luaL_error(state, "AutoJs math.randomseed is unavailable");
    }
    lua_pushcclosure(state, controlled_math_randomseed, 1);
    lua_setfield(state, -2, "randomseed");
    lua_pop(state, 1);

    lua_pushcfunction(state, autojs_console_print);
    lua_setglobal(state, "print");
    lua_pushcfunction(state, autojs_console_error);
    lua_setglobal(state, "warn");
    lua_newtable(state);
    lua_newtable(state);
    lua_pushcfunction(state, autojs_console_log);
    lua_setfield(state, -2, "log");
    lua_pushcfunction(state, autojs_console_log);
    lua_setfield(state, -2, "info");
    lua_pushcfunction(state, autojs_console_error);
    lua_setfield(state, -2, "error");
    lua_pushcfunction(state, autojs_console_error);
    lua_setfield(state, -2, "warn");
    lua_setfield(state, -2, "console");

    lua_newtable(state);
    lua_pushcfunction(state, autojs_device_info);
    lua_setfield(state, -2, "info");
    lua_setfield(state, -2, "device");

    lua_pushcfunction(state, autojs_now);
    lua_setfield(state, -2, "now");

    if (!push_native_arguments(state, arguments)) {
        return luaL_error(state, "AutoJs argument bridge input is invalid");
    }
    lua_setfield(state, -2, "arguments");

    lua_pushvalue(state, -1);
    lua_newtable(state);
    lua_newtable(state);
    lua_pushcclosure(state, restricted_require, 3);
    lua_setglobal(state, "require");
    lua_pop(state, 1);
    return 0;
}

bool throw_bridge_exception(JNIEnv* environment, const char* kind, const char* message) {
    jclass exception_class = environment->FindClass("java/lang/IllegalStateException");
    if (exception_class == nullptr) {
        return false;
    }
    char encoded[320]{};
    std::snprintf(encoded, sizeof(encoded), "%s|%s", kind, message);
    const jint result = environment->ThrowNew(exception_class, encoded);
    environment->DeleteLocalRef(exception_class);
    return result == JNI_OK;
}

jobject fail(JNIEnv* environment, const char* kind, const char* message) {
    throw_bridge_exception(environment, kind, message);
    return nullptr;
}

std::string lua_to_string_or_fallback(
    lua_State* state,
    int stack_index,
    const char* fallback) {
    size_t text_length = 0U;
    const char* text = lua_tolstring(state, stack_index, &text_length);
    if (text == nullptr || text_length == 0U) {
        return std::string(fallback);
    }
    if (text_length > kMaxFailureMessageBytes) {
        text_length = kMaxFailureMessageBytes;
    }
    return std::string(text, text_length);
}

jobject fail_for_termination(JNIEnv* environment, TerminationReason reason) {
    switch (reason) {
        case TerminationReason::kCancelled:
            return fail(environment, "CANCELLED", "Lua execution was cancelled");
        case TerminationReason::kDeadlineExceeded:
            return fail(environment, "DEADLINE_EXCEEDED", "Lua execution exceeded its deadline");
        case TerminationReason::kControlFailure:
            return fail(environment, "INTERNAL", "Lua execution control bridge failed");
        case TerminationReason::kOutputRejected:
            return fail(environment, "RUNTIME", "Lua console output was rejected");
        case TerminationReason::kHostCallRejected:
            return fail(environment, "HOST_CAPABILITY", "Lua host capability call was rejected");
        case TerminationReason::kNone:
            return fail(environment, "INTERNAL", "Lua termination state is inconsistent");
    }
    return fail(environment, "INTERNAL", "Lua termination state is unknown");
}

jobject fail_for_lua_status(JNIEnv* environment, int status, bool loading, lua_State* state) {
    if (status == LUA_ERRMEM) {
        return fail(environment, "MEMORY_LIMIT", "Lua memory limit was exceeded");
    }
    const auto detail = state == nullptr
        ? "Lua execution failed inside a protected call"
        : lua_to_string_or_fallback(
            state,
            -1,
            loading
                ? "Lua source could not be parsed as a text chunk"
                : "Lua execution failed inside a protected call"
        );
    if (loading && status == LUA_ERRSYNTAX) {
        return fail(
            environment,
            "SYNTAX",
            ("Lua source could not be parsed as a text chunk: " + detail).c_str()
        );
    }
    if (loading) {
        return fail(
            environment,
            "INTERNAL",
            ("Lua text loader failed: " + detail).c_str()
        );
    }
    return fail(environment, "RUNTIME", detail.c_str());
}

jobject fail_for_lua_status(JNIEnv* environment, int status, bool loading) {
    return fail_for_lua_status(environment, status, loading, nullptr);
}

jobject fail_for_allocator_accounting(JNIEnv* environment) {
    return fail(environment, "INTERNAL", "Lua allocator accounting became inconsistent");
}

jobject box_boolean(JNIEnv* environment, bool value) {
    jclass type = environment->FindClass("java/lang/Boolean");
    if (type == nullptr) {
        return nullptr;
    }
    jmethodID factory = environment->GetStaticMethodID(type, "valueOf", "(Z)Ljava/lang/Boolean;");
    jobject result = factory == nullptr
        ? nullptr
        : environment->CallStaticObjectMethod(type, factory, value ? JNI_TRUE : JNI_FALSE);
    environment->DeleteLocalRef(type);
    return result;
}

jobject box_long(JNIEnv* environment, jlong value) {
    jclass type = environment->FindClass("java/lang/Long");
    if (type == nullptr) {
        return nullptr;
    }
    jmethodID factory = environment->GetStaticMethodID(type, "valueOf", "(J)Ljava/lang/Long;");
    jobject result = factory == nullptr
        ? nullptr
        : environment->CallStaticObjectMethod(type, factory, value);
    environment->DeleteLocalRef(type);
    return result;
}

jobject box_double(JNIEnv* environment, jdouble value) {
    jclass type = environment->FindClass("java/lang/Double");
    if (type == nullptr) {
        return nullptr;
    }
    jmethodID factory = environment->GetStaticMethodID(type, "valueOf", "(D)Ljava/lang/Double;");
    jobject result = factory == nullptr
        ? nullptr
        : environment->CallStaticObjectMethod(type, factory, value);
    environment->DeleteLocalRef(type);
    return result;
}

jobject box_lua_result(JNIEnv* environment, lua_State* state) {
    const int result_count = lua_gettop(state);
    if (result_count == 0) {
        return nullptr;
    }
    if (result_count != 1) {
        return fail(environment, "UNSUPPORTED_RESULT", "Lua V1 requires zero or one scalar result");
    }

    switch (lua_type(state, 1)) {
        case LUA_TNIL:
            return nullptr;
        case LUA_TBOOLEAN:
            return box_boolean(environment, lua_toboolean(state, 1) != 0);
        case LUA_TNUMBER:
            if (lua_isinteger(state, 1)) {
                static_assert(std::numeric_limits<lua_Integer>::is_signed);
                static_assert(sizeof(lua_Integer) <= sizeof(jlong));
                const lua_Integer value = lua_tointeger(state, 1);
                return box_long(environment, static_cast<jlong>(value));
            } else {
                const lua_Number value = lua_tonumber(state, 1);
                if (!std::isfinite(static_cast<double>(value))) {
                    return fail(environment, "UNSUPPORTED_RESULT", "Lua returned a non-finite number");
                }
                return box_double(environment, static_cast<jdouble>(value));
            }
        case LUA_TSTRING: {
            size_t length = 0U;
            const char* text = lua_tolstring(state, 1, &length);
            if (text == nullptr) {
                return fail(environment, "INTERNAL", "Lua string result is unavailable");
            }
            if (length > kMaxScalarStringBytes) {
                return fail(environment, "RESULT_LIMIT", "Lua string result exceeds the V1 scalar limit");
            }
            auto* result = environment->NewByteArray(static_cast<jsize>(length));
            if (result == nullptr) {
                return nullptr;
            }
            if (length > 0U) {
                environment->SetByteArrayRegion(
                    result,
                    0,
                    static_cast<jsize>(length),
                    reinterpret_cast<const jbyte*>(text));
            }
            return result;
        }
        default:
            return fail(environment, "UNSUPPORTED_RESULT", "Lua returned an unsupported V1 value type");
    }
}

bool probe_runtime(size_t memory_limit) {
    MemoryBudget budget{memory_limit, 0U, false};
    lua_State* state = lua_newstate(bounded_allocator, &budget);
    if (state == nullptr) {
        return false;
    }
    LuaStateOwner owner(state);

    lua_pushcfunction(state, open_safe_libraries);
    const bool opened = lua_pcall(state, 0, 0, 0) == LUA_OK;
    owner.close();
    return opened && !budget.accounting_failed && budget.used == 0U;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_supermonster003_autojs6_plugin_lua_runtime_NativeLuaRuntime_nativeLanguageVersion(
    JNIEnv* environment,
    jobject /* receiver */) {
    return environment->NewStringUTF(
        LUA_VERSION_MAJOR "." LUA_VERSION_MINOR "." LUA_VERSION_RELEASE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_supermonster003_autojs6_plugin_lua_runtime_NativeLuaRuntime_nativeProbe(
    JNIEnv* /* environment */,
    jobject /* receiver */,
    jlong memory_limit_bytes) {
    if (memory_limit_bytes <= 0) {
        return JNI_FALSE;
    }
    const auto unsigned_limit = static_cast<unsigned long long>(memory_limit_bytes);
    if (unsigned_limit > std::numeric_limits<size_t>::max()) {
        return JNI_FALSE;
    }
    return probe_runtime(static_cast<size_t>(unsigned_limit)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_io_github_supermonster003_autojs6_plugin_lua_runtime_NativeLuaRuntime_nativeExecute(
    JNIEnv* environment,
    jobject /* receiver */,
    jbyteArray source,
    jbyteArray source_name_utf8,
    jbyteArray arguments,
    jlong memory_limit_bytes,
    jlong timeout_millis,
    jobject cancellation_probe,
    jobject output_emitter,
    jobject host_capability_bridge) {
    static_assert(LUA_EXTRASPACE >= sizeof(ExecutionControl*));
    if (source == nullptr || source_name_utf8 == nullptr || arguments == nullptr ||
        cancellation_probe == nullptr || output_emitter == nullptr || host_capability_bridge == nullptr) {
        return fail(environment, "INTERNAL", "Native Lua execution input is null");
    }
    const jsize source_length = environment->GetArrayLength(source);
    const jsize source_name_length = environment->GetArrayLength(source_name_utf8);
    const jsize argument_length = environment->GetArrayLength(arguments);
    if (source_length < 0 || source_length > kMaxSourceBytes) {
        return fail(environment, "INTERNAL", "Lua source exceeds the native admission limit");
    }
    if (source_name_length <= 0 || source_name_length > kMaxSourceNameBytes) {
        return fail(environment, "INTERNAL", "Lua source name exceeds the native admission limit");
    }
    if (argument_length < 6 || argument_length > kMaxArgumentSnapshotBytes) {
        return fail(environment, "INTERNAL", "Lua arguments exceed the native admission limit");
    }
    if (memory_limit_bytes <= 0 || memory_limit_bytes > kMaxMemoryBytes ||
        static_cast<unsigned long long>(memory_limit_bytes) > std::numeric_limits<size_t>::max()) {
        return fail(environment, "INTERNAL", "Lua memory limit is outside the native admission range");
    }
    if (timeout_millis <= 0 || timeout_millis > kMaxTimeoutMillis) {
        return fail(environment, "INTERNAL", "Lua timeout is outside the native admission range");
    }

    char chunk_name[kMaxSourceNameBytes + 2]{};
    chunk_name[0] = '=';
    environment->GetByteArrayRegion(
        source_name_utf8,
        0,
        source_name_length,
        reinterpret_cast<jbyte*>(chunk_name + 1));
    if (environment->ExceptionCheck()) {
        return nullptr;
    }
    if (std::memchr(chunk_name + 1, '\0', static_cast<size_t>(source_name_length)) != nullptr) {
        return fail(environment, "INTERNAL", "Lua source name contains a null byte");
    }
    chunk_name[source_name_length + 1] = '\0';

    jclass probe_class = environment->GetObjectClass(cancellation_probe);
    if (probe_class == nullptr) {
        return nullptr;
    }
    jmethodID cancellation_method = environment->GetMethodID(
        probe_class,
        "getAsBoolean",
        "()Z");
    environment->DeleteLocalRef(probe_class);
    if (cancellation_method == nullptr) {
        if (environment->ExceptionCheck()) {
            environment->ExceptionClear();
        }
        return fail(environment, "INTERNAL", "Lua cancellation probe contract is unavailable");
    }

    jclass output_emitter_class = environment->GetObjectClass(output_emitter);
    if (output_emitter_class == nullptr) {
        return nullptr;
    }
    jmethodID output_method = environment->GetMethodID(
        output_emitter_class,
        "emitUtf8",
        "(I[B)Z");
    environment->DeleteLocalRef(output_emitter_class);
    if (output_method == nullptr) {
        if (environment->ExceptionCheck()) {
            environment->ExceptionClear();
        }
        return fail(environment, "INTERNAL", "Lua output emitter contract is unavailable");
    }


    jclass host_capability_class = environment->GetObjectClass(host_capability_bridge);
    if (host_capability_class == nullptr) {
        return nullptr;
    }
    jmethodID device_info_method = environment->GetMethodID(
        host_capability_class,
        "invokeDeviceInfo",
        "()[B");
    if (device_info_method == nullptr) {
        if (environment->ExceptionCheck()) {
            environment->ExceptionClear();
        }
        environment->DeleteLocalRef(host_capability_class);
        return fail(environment, "INTERNAL", "Lua device.info bridge contract is unavailable");
    }
    jmethodID load_module_method = environment->GetMethodID(
        host_capability_class,
        "loadModule",
        "([B)[B");
    if (load_module_method == nullptr) {
        if (environment->ExceptionCheck()) {
            environment->ExceptionClear();
        }
        environment->DeleteLocalRef(host_capability_class);
        return fail(environment, "INTERNAL", "Lua module snapshot bridge contract is unavailable");
    }
    jmethodID host_failure_method = environment->GetMethodID(
        host_capability_class,
        "takeFailureKind",
        "()I");
    environment->DeleteLocalRef(host_capability_class);
    if (host_failure_method == nullptr) {
        if (environment->ExceptionCheck()) {
            environment->ExceptionClear();
        }
        return fail(environment, "INTERNAL", "Lua device.info failure bridge contract is unavailable");
    }

    ExecutionControl control{
        environment,
        cancellation_probe,
        cancellation_method,
        output_emitter,
        output_method,
        host_capability_bridge,
        device_info_method,
        load_module_method,
        host_failure_method,
        std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_millis),
        TerminationReason::kNone,
    };
    if (!poll_execution_control(&control)) {
        return fail_for_termination(environment, control.termination_reason);
    }

    MemoryBudget budget{static_cast<size_t>(memory_limit_bytes), 0U, false};
    lua_State* state = lua_newstate(bounded_allocator, &budget);
    if (state == nullptr) {
        if (budget.accounting_failed) {
            return fail_for_allocator_accounting(environment);
        }
        return fail(environment, "MEMORY_LIMIT", "Lua state allocation exceeded its memory limit");
    }
    LuaStateOwner owner(state);
    if (budget.accounting_failed) {
        return fail_for_allocator_accounting(environment);
    }
    *static_cast<ExecutionControl**>(lua_getextraspace(state)) = &control;

    lua_pushcfunction(state, open_safe_libraries);
    int status = lua_pcall(state, 0, 0, 0);
    if (budget.accounting_failed) {
        return fail_for_allocator_accounting(environment);
    }
    if (status != LUA_OK) {
        return fail_for_lua_status(environment, status, false, state);
    }
    if (!poll_execution_control(&control)) {
        return fail_for_termination(environment, control.termination_reason);
    }

    jbyte* argument_bytes = environment->GetByteArrayElements(arguments, nullptr);
    if (argument_bytes == nullptr) {
        if (environment->ExceptionCheck()) {
            return nullptr;
        }
        return fail(environment, "INTERNAL", "Lua argument bytes are unavailable");
    }
    NativeArgumentView argument_view{
        reinterpret_cast<const uint8_t*>(argument_bytes),
        static_cast<size_t>(argument_length),
    };
    lua_pushcfunction(state, install_autojs_module);
    lua_pushlightuserdata(state, &argument_view);
    status = lua_pcall(state, 1, 0, 0);
    environment->ReleaseByteArrayElements(arguments, argument_bytes, JNI_ABORT);
    if (budget.accounting_failed) {
        return fail_for_allocator_accounting(environment);
    }
    if (status != LUA_OK) {
        if (status == LUA_ERRMEM) {
            return fail(environment, "MEMORY_LIMIT", "Lua argument mapping exceeded its memory limit");
        }
        return fail(environment, "INTERNAL", "Lua argument mapping failed");
    }
    if (!poll_execution_control(&control)) {
        return fail_for_termination(environment, control.termination_reason);
    }

    jbyte* source_bytes = nullptr;
    const char* source_text = "";
    if (source_length > 0) {
        source_bytes = environment->GetByteArrayElements(source, nullptr);
        if (source_bytes == nullptr) {
            if (environment->ExceptionCheck()) {
                return nullptr;
            }
            return fail(environment, "INTERNAL", "Lua source bytes are unavailable");
        }
        source_text = reinterpret_cast<const char*>(source_bytes);
    }
    status = luaL_loadbufferx(
        state,
        source_text,
        static_cast<size_t>(source_length),
        chunk_name,
        "t");
    if (source_bytes != nullptr) {
        environment->ReleaseByteArrayElements(source, source_bytes, JNI_ABORT);
    }
    if (budget.accounting_failed) {
        return fail_for_allocator_accounting(environment);
    }
    if (!poll_execution_control(&control)) {
        return fail_for_termination(environment, control.termination_reason);
    }
    if (status != LUA_OK) {
        return fail_for_lua_status(environment, status, true, state);
    }

    lua_sethook(state, execution_hook, LUA_MASKCOUNT, kHookInstructionCount);
    status = lua_pcall(state, 0, LUA_MULTRET, 0);
    lua_sethook(state, nullptr, 0, 0);
    if (budget.accounting_failed) {
        return fail_for_allocator_accounting(environment);
    }
    if (control.termination_reason != TerminationReason::kNone) {
        return fail_for_termination(environment, control.termination_reason);
    }
    if (!poll_execution_control(&control)) {
        return fail_for_termination(environment, control.termination_reason);
    }
    if (status != LUA_OK) {
        return fail_for_lua_status(environment, status, false, state);
    }
    jobject result = box_lua_result(environment, state);
    const bool result_failed = environment->ExceptionCheck();
    owner.close();
    if (result_failed) {
        return nullptr;
    }
    if (budget.accounting_failed || budget.used != 0U) {
        if (result != nullptr) {
            environment->DeleteLocalRef(result);
        }
        return fail_for_allocator_accounting(environment);
    }
    return result;
}

#if defined(AUTOJS_LUA_DEBUG_FAULT_HARNESS)
extern "C" JNIEXPORT void JNICALL
Java_io_github_supermonster003_autojs6_plugin_lua_runtime_debug_NativeLuaFaults_nativeCrash(
    JNIEnv* /* environment */,
    jobject /* receiver */) {
    std::abort();
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_supermonster003_autojs6_plugin_lua_runtime_debug_NativeLuaFaults_nativeWedge(
    JNIEnv* /* environment */,
    jobject /* receiver */) {
    for (;;) {
        // This intentionally never returns and never polls Lua cancellation. The debug-only
        // remote-process harness must recover exclusively through the real process watchdog.
        std::this_thread::sleep_for(std::chrono::hours(24));
    }
}
#endif
