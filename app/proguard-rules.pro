-keep class org.autojs.plugin.common.api.** { *; }
-keep class org.autojs.plugin.lua.runtime.api.** { *; }
-keep class io.github.supermonster003.autojs6.plugin.lua.runtime.service.** { *; }

# JNI resolves this callback by its Java method name and descriptor.
-keep interface io.github.supermonster003.autojs6.plugin.lua.runtime.NativeLuaOutputEmitter { *; }
-keepclassmembers class io.github.supermonster003.autojs6.plugin.lua.runtime.NativeLuaHostCapabilityBridge {
    byte[] invokeDeviceInfo();
    int takeFailureKind();
}
