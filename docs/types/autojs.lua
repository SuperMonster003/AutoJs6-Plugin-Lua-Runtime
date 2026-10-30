---@meta autojs
-- LuaLS definitions of the `autojs` module of the AutoJs6 Lua Runtime (source app/src/main/cpp/lua_runtime_jni.cpp,
-- install_autojs_module, commit 9ced281). AutoJs6 tools/api-coverage/language_runtimes.py checks the names against
-- the plugin. The global `print(...)` and `warn(text)` keep their standard Lua 5.4 signatures.

---The bridge table returned by `require("autojs")`.
---@class autojs
local autojs = {}

---Read-only snapshot of the arguments the host passed to this execution.
---@type table<string, any>
autojs.arguments = {}

---Returns the current time in Unix epoch milliseconds.
---@return integer
function autojs.now() end

---Console output: `log` and `info` write standard output, `warn` and `error` write standard error.
---@class autojs.console
autojs.console = {}

---Writes one line to standard output.
---@param text string
function autojs.console.log(text) end

---Writes one line to standard output.
---@param text string
function autojs.console.info(text) end

---Writes one line to standard error.
---@param text string
function autojs.console.warn(text) end

---Writes one line to standard error.
---@param text string
function autojs.console.error(text) end

---Device information (needs the device capability granted by the host).
---@class autojs.device
autojs.device = {}

---@class autojs.DeviceInfo
---@field brand string
---@field manufacturer string
---@field model string
---@field device string
---@field product string
---@field sdkInt integer

---Returns the brand, manufacturer, model, device, product and SDK level of the device.
---@return autojs.DeviceInfo
function autojs.device.info() end

---Host-persisted values for scripts with a stable file identity (needs the storage capability). Keys are ASCII
---identifiers of 1 to 64 bytes; a normalized value takes at most 252 KiB.
---@class autojs.storage
autojs.storage = {}

---Returns the stored value, or `nil` when the key is absent.
---@param key string
---@return any
function autojs.storage.get(key) end

---Stores a non-nil value: booleans, integers, finite numbers, UTF-8 strings and tables of them (contiguous arrays or
---string keys). Returns `true` once the host has committed it.
---@param key string
---@param value boolean|integer|number|string|table
---@return boolean
function autojs.storage.put(key, value) end

---Removes a key and returns whether it existed.
---@param key string
---@return boolean
function autojs.storage.remove(key) end

---Removes every key of this script and returns how many were removed.
---@return integer
function autojs.storage.clear() end

---User interface (needs the ui capability).
---@class autojs.ui
autojs.ui = {}

---Shows a short toast with 1 to 1024 bytes of strict UTF-8 text.
---@param text string
function autojs.ui.toast(text) end

return autojs
