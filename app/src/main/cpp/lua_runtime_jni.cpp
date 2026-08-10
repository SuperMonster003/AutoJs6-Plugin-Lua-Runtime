#include <jni.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>

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
constexpr size_t kMaxScalarStringBytes = 64 * 1024;
constexpr jlong kMaxMemoryBytes = 256LL * 1024LL * 1024LL;
constexpr jlong kMaxTimeoutMillis = 10LL * 60LL * 1000LL;
constexpr int kHookInstructionCount = 10'000;

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
};

struct ExecutionControl {
    JNIEnv* environment;
    jobject cancellation_probe;
    jmethodID cancellation_method;
    std::chrono::steady_clock::time_point deadline;
    TerminationReason termination_reason;
};

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

jobject fail_for_termination(JNIEnv* environment, TerminationReason reason) {
    switch (reason) {
        case TerminationReason::kCancelled:
            return fail(environment, "CANCELLED", "Lua execution was cancelled");
        case TerminationReason::kDeadlineExceeded:
            return fail(environment, "DEADLINE_EXCEEDED", "Lua execution exceeded its deadline");
        case TerminationReason::kControlFailure:
            return fail(environment, "INTERNAL", "Lua cancellation probe failed");
        case TerminationReason::kNone:
            return fail(environment, "INTERNAL", "Lua termination state is inconsistent");
    }
    return fail(environment, "INTERNAL", "Lua termination state is unknown");
}

jobject fail_for_lua_status(JNIEnv* environment, int status, bool loading) {
    if (status == LUA_ERRMEM) {
        return fail(environment, "MEMORY_LIMIT", "Lua memory limit was exceeded");
    }
    if (loading && status == LUA_ERRSYNTAX) {
        return fail(environment, "SYNTAX", "Lua source could not be parsed as a text chunk");
    }
    if (loading) {
        return fail(environment, "INTERNAL", "Lua text loader failed");
    }
    return fail(environment, "RUNTIME", "Lua execution failed inside a protected call");
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
    jlong memory_limit_bytes,
    jlong timeout_millis,
    jobject cancellation_probe) {
    static_assert(LUA_EXTRASPACE >= sizeof(ExecutionControl*));
    if (source == nullptr || source_name_utf8 == nullptr || cancellation_probe == nullptr) {
        return fail(environment, "INTERNAL", "Native Lua execution input is null");
    }
    const jsize source_length = environment->GetArrayLength(source);
    const jsize source_name_length = environment->GetArrayLength(source_name_utf8);
    if (source_length < 0 || source_length > kMaxSourceBytes) {
        return fail(environment, "INTERNAL", "Lua source exceeds the native admission limit");
    }
    if (source_name_length <= 0 || source_name_length > kMaxSourceNameBytes) {
        return fail(environment, "INTERNAL", "Lua source name exceeds the native admission limit");
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

    ExecutionControl control{
        environment,
        cancellation_probe,
        cancellation_method,
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
        return fail_for_lua_status(environment, status, false);
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
        return fail_for_lua_status(environment, status, true);
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
        return fail_for_lua_status(environment, status, false);
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
