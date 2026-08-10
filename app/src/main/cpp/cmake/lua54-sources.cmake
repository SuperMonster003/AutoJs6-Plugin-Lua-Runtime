# Exact Lua core plus the explicitly admitted libraries.
# Deliberately excluded:
#   linit.c    - prevents luaL_openlibs from becoming the provider bootstrap
#   ldblib.c   - debug library
#   liolib.c   - unrestricted io library
#   loslib.c   - OS/process library, including os.execute
#   loadlib.c  - package.loadlib and dynamic C module loading
#   lua.c / luac.c - standalone executables
set(LUA54_SOURCES
    src/lapi.c
    src/lcode.c
    src/lctype.c
    src/ldebug.c
    src/ldo.c
    src/ldump.c
    src/lfunc.c
    src/lgc.c
    src/llex.c
    src/lmem.c
    src/lobject.c
    src/lopcodes.c
    src/lparser.c
    src/lstate.c
    src/lstring.c
    src/ltable.c
    src/ltm.c
    src/lundump.c
    src/lvm.c
    src/lzio.c
    src/lauxlib.c
    src/lbaselib.c
    src/lmathlib.c
    src/lstrlib.c
    src/ltablib.c
    src/lutf8lib.c
)
