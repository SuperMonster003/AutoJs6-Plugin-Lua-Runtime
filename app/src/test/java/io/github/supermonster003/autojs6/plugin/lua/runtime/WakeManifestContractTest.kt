package io.github.supermonster003.autojs6.plugin.lua.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class WakeManifestContractTest {
    @Test fun protectedWakeIsDiscoverable() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val file = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.isFile }
        val doc = factory.newDocumentBuilder().parse(file)
        val android = "http://schemas.android.com/apk/res/android"
        fun org.w3c.dom.Element.attr(key: String) = getAttributeNS(android, key)
        fun elements(tag: String) = doc.getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element } }
        val activity = elements("activity").single { it.attr("name") == "io.github.supermonster003.autojs6.plugin.lua.runtime.WakeActivity" }
        assertEquals("true", activity.attr("exported"))
        assertEquals("org.autojs.permission.PLUGIN", activity.attr("permission"))
        assertEquals("@android:style/Theme.NoDisplay", activity.attr("theme"))
        val actions = activity.getElementsByTagName("action")
        assertEquals("org.autojs.plugin.action.WAKE", (actions.item(0) as org.w3c.dom.Element).attr("name"))
        val categories = activity.getElementsByTagName("category")
        assertEquals("android.intent.category.DEFAULT", (categories.item(0) as org.w3c.dom.Element).attr("name"))
        assertEquals("io.github.supermonster003.autojs6.plugin.lua.runtime.WakeActivity", elements("meta-data").single { it.attr("name") == "org.autojs.plugin.WAKE_ACTIVITY" }.attr("value"))
        assertTrue(elements("uses-permission").any { it.attr("name") == "org.autojs.permission.PLUGIN" })
    }
}
