cmake_minimum_required(VERSION 3.22.1)
project(autojs_lua_runtime LANGUAGES C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED AUTOJS_LUA_RUNTIME_SLOT)
    message(FATAL_ERROR "AUTOJS_LUA_RUNTIME_SLOT must be pinned by the Android build")
endif()
if(NOT "${AUTOJS_LUA_RUNTIME_SLOT}" STREQUAL "lua54")
    message(FATAL_ERROR "Unsupported Lua runtime slot: ${AUTOJS_LUA_RUNTIME_SLOT}")
endif()

option(AUTOJS_LUA_DEBUG_FAULT_HARNESS "Compile debug-only native fault entry points" OFF)
if(AUTOJS_LUA_DEBUG_FAULT_HARNESS AND NOT "${CMAKE_BUILD_TYPE}" MATCHES "^[Dd]ebug$")
    message(FATAL_ERROR "The native Lua fault harness may only be compiled for a debug variant")
endif()

set(LUA54_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/vendor/lua-5.4.8")
include("${CMAKE_CURRENT_SOURCE_DIR}/cmake/lua54-sources.cmake")

foreach(lua_source IN LISTS LUA54_SOURCES)
    if(NOT EXISTS "${LUA54_ROOT}/${lua_source}")
        message(FATAL_ERROR
            "Pinned PUC Lua 5.4.8 source is incomplete: ${lua_source}. "
            "Follow vendor/lua-5.4.8/README.md; never substitute placeholder sources.")
    endif()
endforeach()

list(TRANSFORM LUA54_SOURCES PREPEND "${LUA54_ROOT}/")

add_library(autojs_lua_runtime SHARED
    lua_runtime_jni.cpp
    ${LUA54_SOURCES}
)

target_include_directories(autojs_lua_runtime PRIVATE
    "${LUA54_ROOT}/src"
)

target_compile_definitions(autojs_lua_runtime PRIVATE
    LUA_USE_POSIX
)

if(AUTOJS_LUA_DEBUG_FAULT_HARNESS)
    target_compile_definitions(autojs_lua_runtime PRIVATE
        AUTOJS_LUA_DEBUG_FAULT_HARNESS=1
    )
endif()

target_compile_options(autojs_lua_runtime PRIVATE
    -Wall
    -Wextra
    -Werror=return-type
    -fvisibility=hidden
)

target_link_libraries(autojs_lua_runtime PRIVATE log)
