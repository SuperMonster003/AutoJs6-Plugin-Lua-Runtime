package io.github.supermonster003.autojs6.plugin.lua.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.autojs.plugin.common.api.IPluginInfoProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WakeContractInstrumentedTest {
    @Suppress("DEPRECATION")
    @Test fun wakeAndInfoAreDiscoverableAndNonHostUidIsRejected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.packageManager
        val wake = Intent("org.autojs.plugin.action.WAKE").addCategory(Intent.CATEGORY_DEFAULT).setPackage(context.packageName)
        val activity = manager.queryIntentActivities(wake, 0).single().activityInfo
        assertEquals("io.github.supermonster003.autojs6.plugin.lua.runtime.WakeActivity", activity.name)
        assertTrue(activity.exported)
        assertEquals("org.autojs.permission.PLUGIN", activity.permission)
        val application = manager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        assertEquals(activity.name, application.metaData.getString("org.autojs.plugin.WAKE_ACTIVITY"))
        val discovery = Intent("org.autojs.plugin.INFO").addCategory("lua-runtime").setPackage(context.packageName)
        val service = manager.queryIntentServices(discovery, 0).single().serviceInfo
        assertTrue(service.exported)
        assertEquals("org.autojs.permission.PLUGIN", service.permission)
        val latch = CountDownLatch(1)
        var remote: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { remote = binder; latch.countDown() }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent().setComponent(ComponentName(service.packageName, service.name)), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("INFO binding timed out", latch.await(10, TimeUnit.SECONDS))
            assertEquals("org.autojs.plugin.common.api.IPluginInfoProvider", remote!!.interfaceDescriptor)
            // This instrumentation runs as the plugin UID. Production metadata is host-only.
            // The matching AutoJs6 host must perform the positive INFO round trip.
            try {
                IPluginInfoProvider.Stub.asInterface(remote).info
                fail("Non-host UID obtained host-only plugin metadata")
            } catch (expected: SecurityException) {
                assertNotNull(expected.message)
            }
        } finally { context.unbindService(connection) }
    }
}
