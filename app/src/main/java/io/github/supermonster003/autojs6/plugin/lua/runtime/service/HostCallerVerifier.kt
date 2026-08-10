package io.github.supermonster003.autojs6.plugin.lua.runtime.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import io.github.supermonster003.autojs6.plugin.lua.runtime.LuaProviderMetadata

internal fun interface LuaSessionCallerVerifier {
    fun enforceSessionOwner(expectedUid: Int)
}

internal class HostCallerVerifier(context: Context) : LuaSessionCallerVerifier {
    private val packageManager = context.applicationContext.packageManager
    private val providerPackageName = context.applicationContext.packageName

    fun enforceAllowedCaller(): Int = Binder.getCallingUid().also(::enforceAllowedUid)

    override fun enforceSessionOwner(expectedUid: Int) {
        val callingUid = Binder.getCallingUid()
        if (callingUid != expectedUid) {
            throw SecurityException("Lua runtime session UID does not match its owner")
        }
        enforceAllowedUid(callingUid)
    }

    @Suppress("DEPRECATION")
    private fun enforceAllowedUid(uid: Int) {
        val hostPackageName = LuaProviderMetadata.HOST_PACKAGE_NAME
        val packages = packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
        if (hostPackageName !in packages) {
            throw SecurityException("Calling UID does not own the allowed host package")
        }
        val hostUid = try {
            packageManager.getApplicationInfo(hostPackageName, 0).uid
        } catch (error: PackageManager.NameNotFoundException) {
            throw SecurityException("The allowed host package is not installed", error)
        }
        if (hostUid != uid) {
            throw SecurityException("Calling UID does not match the allowed host package")
        }
        if (
            packageManager.checkSignatures(providerPackageName, hostPackageName) !=
            PackageManager.SIGNATURE_MATCH
        ) {
            throw SecurityException("Lua runtime and host signatures do not match")
        }
    }
}
