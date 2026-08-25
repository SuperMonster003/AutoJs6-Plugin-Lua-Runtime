-keep class org.autojs.plugin.common.api.** { *; }
-keep class org.autojs.plugin.lua.runtime.api.** { *; }
-keep class io.github.supermonster003.autojs6.plugin.lua.runtime.service.** { *; }

# The frozen common-plugin-api AAR retains the compile-time @Parcelize annotation
# reference, while its generated Parcelable implementation is already bytecode.
-dontwarn kotlinx.parcelize.Parcelize

# JNI resolves this callback by its Java method name and descriptor.
-keep interface io.github.supermonster003.autojs6.plugin.lua.runtime.NativeLuaOutputEmitter { *; }
-keepclassmembers class io.github.supermonster003.autojs6.plugin.lua.runtime.NativeLuaHostCapabilityBridge {
    byte[] invokeDeviceInfo();
    byte[] loadModule(byte[]);
    void showToast(byte[]);
    int takeFailureKind();
}
