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
    @Test fun protectedWakeIsDiscoverable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.packageManager
        val wake = Intent("org.autojs.plugin.action.WAKE").addCategory(Intent.CATEGORY_DEFAULT).setPackage(context.packageName)
        val activity = manager.queryIntentActivities(wake, 0).single().activityInfo
        assertEquals("io.github.supermonster003.autojs6.plugin.lua.runtime.WakeActivity", activity.name)
        assertTrue(activity.exported)
        assertEquals("org.autojs.permission.PLUGIN", activity.permission)
        val application = manager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        assertEquals(activity.name, application.metaData.getString("org.autojs.plugin.WAKE_ACTIVITY"))
    }
}
