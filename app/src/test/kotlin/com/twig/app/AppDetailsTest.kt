package com.twig.app

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FsRegistry
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDetailsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val pkg = "com.example.inspect"
    private fun activity(name: String, exported: Boolean = true, enabled: Boolean = true, permission: String? = null) =
        ActivityInfo().apply {
            this.name = "$pkg.$name"; packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg; this.enabled = true }
            this.exported = exported; this.enabled = enabled; this.permission = permission
        }
    private fun readIntent(parser: org.xmlpull.v1.XmlPullParser): Intent {
        val ns = "http://schemas.android.com/apk/res/android"
        return Intent(parser.getAttributeValue(ns, "action")).apply {
            val pkg = parser.getAttributeValue(ns, "targetPackage")
            val cls = parser.getAttributeValue(ns, "targetClass")
            if (pkg != null && cls != null) component = ComponentName(pkg, cls)
        }
    }
    private fun info() = PackageInfo().apply {
        packageName = pkg
        applicationInfo = ApplicationInfo().apply { packageName = pkg; enabled = true; nonLocalizedLabel = "Inspected" }
        activities = arrayOf(activity("Public"), activity("Internal", exported = false),
            activity("Disabled", enabled = false), activity("Protected", permission = "test.private.permission"))
        services = arrayOf(ServiceInfo().apply {
            packageName = pkg; name = "$pkg.SyncService"; enabled = true; exported = false
            processName = "$pkg:worker"; permission = "test.service.permission"
        })
        receivers = arrayOf(activity("BootReceiver", exported = false))
        providers = arrayOf(ProviderInfo().apply {
            packageName = pkg; name = "$pkg.DataProvider"; enabled = true; exported = true
            authority = "$pkg.data"; readPermission = "test.read"; writePermission = "test.write"
        })
    }
    @Before fun setup() {
        shadowOf(app.packageManager).installPackage(info())
        shadowOf(app.getSystemService(LauncherApps::class.java)).setHasShortcutHostPermission(false)
    }

    @Test fun `all six groups include internal and disabled components but only accessible activities can launch`() {
        val groups = AppDetails.load(app, info(), true)
        assertEquals(AppDetails.Kind.values().toList(), groups.map { it.kind })
        val activities = groups.single { it.kind == AppDetails.Kind.ACTIVITIES }.entries
        assertEquals(4, activities.size)
        assertEquals(listOf("$pkg.Public"), activities.mapNotNull { (it.action as? AppDetails.Action.Activity)?.className })
        assertEquals(listOf("Public", "Disabled", "Internal", "Protected"), activities.map { it.title })
        assertTrue(activities.all { it.rows.isEmpty() })
        assertFalse(activities.single { it.title == "Disabled" }.enabled)
        assertTrue(activities.single { it.title == "Internal" }.enabled)
        assertEquals("SyncService", groups.single { it.kind == AppDetails.Kind.SERVICES }.entries.single().title)
        assertEquals("BootReceiver", groups.single { it.kind == AppDetails.Kind.RECEIVERS }.entries.single().title)
        assertEquals("DataProvider", groups.single { it.kind == AppDetails.Kind.PROVIDERS }.entries.single().title)
        assertNotNull(groups.single { it.kind == AppDetails.Kind.SHORTCUTS }.notice)
    }

    @Test fun `non-activity exported markers and provider authorities and permissions are preserved`() {
        val pi = info().apply {
            receivers!!.single().exported = true
            providers = providers!! + arrayOf(ProviderInfo().apply {
                packageName = pkg; name = "$pkg.SharedProvider"; enabled = false; exported = false
                authority = "$pkg.shared"; readPermission = "test.shared"; writePermission = "test.shared"
            }, ProviderInfo().apply {
                packageName = pkg; name = "$pkg.EmptyProvider"; enabled = true
                authority = ""; readPermission = ""; writePermission = null
            })
        }
        val groups = AppDetails.load(app, pi, false)
        assertTrue(groups.single { it.kind == AppDetails.Kind.ACTIVITIES }.entries.none { it.exported })
        assertFalse(groups.single { it.kind == AppDetails.Kind.SERVICES }.entries.single().exported)
        assertTrue(groups.single { it.kind == AppDetails.Kind.RECEIVERS }.entries.single().exported)
        val providers = groups.single { it.kind == AppDetails.Kind.PROVIDERS }.entries
        val provider = providers.single { it.title == "DataProvider" }
        assertTrue(provider.exported)
        assertEquals(listOf("Authority" to "$pkg.data", "Read permission" to "test.read",
            "Write permission" to "test.write"), provider.rows)
        val shared = providers.single { it.title == "SharedProvider" }
        assertFalse(shared.enabled)
        assertFalse(shared.exported)
        assertEquals(listOf("Authority" to "$pkg.shared", "Permission" to "test.shared"), shared.rows)
        assertTrue(providers.single { it.title == "EmptyProvider" }.rows.isEmpty())
    }

    @Test fun `component override enables an activity and APK inspection never offers installed-app launches`() {
        app.packageManager.setComponentEnabledSetting(ComponentName(pkg, "$pkg.Disabled"),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        val groups = AppDetails.load(app, info(), true)
        assertTrue(groups.single { it.kind == AppDetails.Kind.ACTIVITIES }.entries.any { (it.action as? AppDetails.Action.Activity)?.className == "$pkg.Disabled" })
        assertTrue(AppDetails.load(app, info(), false).single { it.kind == AppDetails.Kind.ACTIVITIES }.entries.all { it.action == null })
    }

    @Test fun `labels accompany class names and accessible activities sort first by class name`() {
        val pi = info().apply {
            activities = arrayOf(activity("ZOpen").apply { nonLocalizedLabel = "First label" },
                activity("AInternal", exported = false).apply { nonLocalizedLabel = "Internal" },
                activity("BOpen").apply { nonLocalizedLabel = "Second label" },
                activity("Duplicate").apply { nonLocalizedLabel = "$pkg.Duplicate" })
            services!!.single().nonLocalizedLabel = "Sync"
            receivers!!.single().nonLocalizedLabel = "Boot"
            providers!!.single().nonLocalizedLabel = "Data"
        }
        val groups = AppDetails.load(app, pi, true)
        assertEquals(listOf("BOpen", "Duplicate", "ZOpen", "AInternal"),
            groups.single { it.kind == AppDetails.Kind.ACTIVITIES }.entries.map { it.title })
        assertEquals(listOf("Second label", "$pkg.Duplicate", "First label", "Internal"), groups.single { it.kind == AppDetails.Kind.ACTIVITIES }.entries.map { it.subtitle })
        assertEquals(listOf(true, true, true, false), groups.single { it.kind == AppDetails.Kind.ACTIVITIES }.entries.map { it.action != null })
        assertEquals("SyncService", groups.single { it.kind == AppDetails.Kind.SERVICES }.entries.single().title)
        assertEquals("BootReceiver", groups.single { it.kind == AppDetails.Kind.RECEIVERS }.entries.single().title)
        assertEquals("DataProvider", groups.single { it.kind == AppDetails.Kind.PROVIDERS }.entries.single().title)
        assertEquals("Sync", groups.single { it.kind == AppDetails.Kind.SERVICES }.entries.single().subtitle)
        assertEquals("Boot", groups.single { it.kind == AppDetails.Kind.RECEIVERS }.entries.single().subtitle)
        assertEquals("Data", groups.single { it.kind == AppDetails.Kind.PROVIDERS }.entries.single().subtitle)
        assertTrue(AppDetails.load(app, pi, false).single { it.kind == AppDetails.Kind.ACTIVITIES }.entries.all { it.action == null })
    }

    @Test fun `components without their own label do not repeat the inherited application name`() {
        val pi = info().apply {
            activities!!.forEach { it.applicationInfo = applicationInfo }
            services!!.single().applicationInfo = applicationInfo
            receivers!!.single().applicationInfo = applicationInfo
            providers!!.single().applicationInfo = applicationInfo
        }
        assertEquals("Inspected", pi.services!!.single().loadLabel(app.packageManager).toString())
        val installed = AppDetails.load(app, pi, true).single { it.kind == AppDetails.Kind.ACTIVITIES }.entries
        assertEquals("Inspected", installed.single { it.title == "Public" }.subtitle)
        assertTrue(installed.filter { it.action == null }.all { it.subtitle == null })
        val groups = AppDetails.load(app, pi, false)
        for (kind in listOf(AppDetails.Kind.ACTIVITIES, AppDetails.Kind.SERVICES, AppDetails.Kind.RECEIVERS, AppDetails.Kind.PROVIDERS))
            assertTrue(groups.single { it.kind == kind }.entries.all { it.subtitle == null })
    }

    @Test fun `launch uses only the requested explicit component`() {
        AppDetails.open(app, AppDetails.Entry("Public", action = AppDetails.Action.Activity(pkg, "$pkg.Public")))
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(ComponentName(pkg, "$pkg.Public"), intent.component)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun `Activity shortcuts launch the explicit component with a persistent action and rendered icon`() {
        val pi = info().apply {
            activities = activities!! + activity("AnotherPublic")
            activities!!.first { it.name == "$pkg.Public" }.nonLocalizedLabel = "Open page"
        }
        shadowOf(app.packageManager).installPackage(pi)
        val entry = AppDetails.load(app, pi, true).single { it.kind == AppDetails.Kind.ACTIVITIES }
            .entries.single { it.title == "Public" }
        val shortcut = AppDetails.activityShortcut(app, entry)
        assertEquals("Open page", shortcut.shortLabel.toString())
        assertEquals(ComponentName(pkg, "$pkg.Public"), shortcut.intent.component)
        assertEquals(Intent.ACTION_MAIN, shortcut.intent.action)
        assertTrue(shortcut.intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(androidx.core.graphics.drawable.IconCompat.TYPE_BITMAP, shortcut.icon!!.type)
        assertEquals(shortcut.id, AppDetails.activityShortcut(app, entry).id)
        assertNotEquals(shortcut.id, AppDetails.activityShortcut(app,
            entry.copy(action = AppDetails.Action.Activity(pkg, "$pkg.AnotherPublic"))).id)
        assertEquals(entry.title, AppDetails.activityShortcut(app, entry.copy(subtitle = null)).shortLabel.toString())
    }

    @Test fun `unavailable Activity entries cannot produce a launcher shortcut`() {
        val entries = AppDetails.load(app, info(), true).single { it.kind == AppDetails.Kind.ACTIVITIES }.entries
        for (entry in entries.filter { !it.actionable }) {
            try { AppDetails.activityShortcut(app, entry); fail("must reject ${entry.title}") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Suppress("DEPRECATION")
    @Test fun `signature fingerprints come from certificates rather than package metadata`() {
        val pi = info().apply { signatures = arrayOf(Signature(byteArrayOf(1, 2, 3))) }
        val entry = AppDetails.load(app, pi, false).single { it.kind == AppDetails.Kind.SIGNATURE }.entries.single()
        assertEquals("03:90:58:C6:F2:C0:CB:49:2C:53:3B:0A:4D:14:EF:77:CC:0F:78:AB:CC:CE:D5:28:7D:84:A1:A2:01:1C:FB:81", entry.rows.single { it.first == "SHA-256" }.second)
    }

    @Suppress("DEPRECATION")
    @Test fun `certificate details include serial number and signature algorithm with only SHA-256 fingerprint`() {
        val bytes = javaClass.getResourceAsStream("/app-details-cert.pem")!!.use { it.readBytes() }
        val cert = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(bytes.inputStream()) as java.security.cert.X509Certificate
        val pi = info().apply { signatures = arrayOf(Signature(cert.encoded)) }
        val entry = AppDetails.load(app, pi, false).single { it.kind == AppDetails.Kind.SIGNATURE }.entries.single()
        assertEquals("Twig Test Certificate", entry.title)
        assertEquals("1234AB", entry.rows.single { it.first == "Serial number" }.second)
        assertEquals("SHA256withRSA", entry.rows.single { it.first == "Signature algorithm" }.second)
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }
        assertEquals(fingerprint, entry.rows.single { it.first == "SHA-256" }.second)
        assertFalse(entry.rows.any { it.first == "SHA-1" })
        assertTrue(entry.rows.any { it.first == "Subject" && it.second.contains("Twig Test Certificate") })
    }

    @Test fun `signing history is available through the modern signing API`() {
        val signing = SigningInfo()
        shadowOf(signing).setSignatures(arrayOf(Signature(byteArrayOf(2))))
        shadowOf(signing).setPastSigningCertificates(arrayOf(Signature(byteArrayOf(1)), Signature(byteArrayOf(2))))
        val entries = AppDetails.load(app, info().apply { signingInfo = signing }, false)
            .single { it.kind == AppDetails.Kind.SIGNATURE }.entries
        assertEquals(2, entries.size)
    }

    @Test fun `manifest shortcut parsing preserves labels and disabled state`() {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        val ns = "http://schemas.android.com/apk/res/android"
        parser.setInput(StringReader("""<shortcuts xmlns:android="$ns">
            <shortcut android:shortcutId="first" android:shortcutShortLabel="First" android:enabled="false">
                <intent android:action="android.intent.action.VIEW" android:targetPackage="$pkg" android:targetClass="$pkg.One"/>
                <intent android:action="android.intent.action.VIEW" android:targetClass="$pkg.Two"/>
            </shortcut>
            <shortcut android:shortcutId="second" android:shortcutShortLabel="Second"/>
        </shortcuts>"""))
        val result = AppDetails.readShortcutXml(app, parser,
            label = { parser.getAttributeValue(ns, "shortcutShortLabel").orEmpty() },
            readIntent = { readIntent(parser) },
            enabled = { parser.getAttributeValue(ns, "enabled") != "false" })
        assertEquals(listOf("first", "second"), result.keys.toList())
        assertEquals("first", result.getValue("first").title)
        assertEquals("First", result.getValue("first").subtitle)
        assertFalse(result.getValue("first").enabled)
        assertTrue(result.getValue("second").enabled)
        assertTrue(result.values.all { it.rows.isEmpty() })
    }

    @Test fun `own dynamic shortcuts are listed without launcher privileges`() {
        val manager = app.getSystemService(ShortcutManager::class.java)
        manager.addDynamicShortcuts(listOf(ShortcutInfo.Builder(app, "dynamic-test").setShortLabel("Dynamic")
            .setIntent(Intent(Intent.ACTION_VIEW)).build()))
        val pi = info().apply { packageName = app.packageName }
        val group = AppDetails.load(app, pi, true).single { it.kind == AppDetails.Kind.SHORTCUTS }
        assertNull(group.notice)
        assertTrue(group.entries.any { it.title == "dynamic-test" && it.subtitle == "Dynamic" })
        assertTrue(group.entries.single { it.title == "dynamic-test" }.rows.isEmpty())
    }

    @Test fun `shortcut launch preserves intent stack data categories and extras`() {
        val root = Intent(Intent.ACTION_MAIN).setComponent(ComponentName(pkg, "$pkg.Public"))
        val destination = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com/chat/42"))
            .setComponent(ComponentName(pkg, "$pkg.Public")).addCategory("test.shortcut")
            .putExtra("conversation", 42)
        val action = AppDetails.shortcutAction(app, listOf(root, destination), installed = true)
        assertNotNull(action)
        AppDetails.open(app, AppDetails.Entry("Chat", emptyList(), action = action))
        val launched = listOf(shadowOf(app).nextStartedActivity, shadowOf(app).nextStartedActivity)
        val first = launched.single { it.action == Intent.ACTION_MAIN }
        val last = launched.single { it.action == Intent.ACTION_VIEW }
        assertTrue(first.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(0, root.flags)
        assertEquals("https://example.com/chat/42", last.dataString)
        assertTrue(last.hasCategory("test.shortcut"))
        assertEquals(42, last.getIntExtra("conversation", 0))
    }

    @Test fun `shortcuts require an accessible installed intent stack rather than the owning launcher activity`() {
        fun intent(name: String) = Intent(Intent.ACTION_VIEW).setComponent(ComponentName(pkg, "$pkg.$name"))
        for (name in listOf("Internal", "Disabled", "Protected", "Missing")) {
            assertNull(AppDetails.shortcutAction(app, listOf(intent("Public"), intent(name)), installed = true))
        }
        assertNull(AppDetails.shortcutAction(app, listOf(intent("Public")), installed = false))
        assertNull(AppDetails.shortcutAction(app, emptyList(), installed = true))
        app.packageManager.setComponentEnabledSetting(ComponentName(pkg, "$pkg.Public"),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        assertNull(AppDetails.shortcutAction(app, listOf(intent("Public")), installed = true))
    }

    @Test fun `declared shortcuts can launch only while enabled and viewing an installed app`() {
        fun parse(installed: Boolean): Map<String, AppDetails.Entry> {
            val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
            val ns = "http://schemas.android.com/apk/res/android"
            parser.setInput(StringReader("""<shortcuts xmlns:android="$ns">
                <shortcut android:shortcutId="open" android:shortcutShortLabel="open">
                    <intent android:action="android.intent.action.VIEW" android:targetPackage="$pkg" android:targetClass="$pkg.Public"/>
                </shortcut>
                <shortcut android:shortcutId="disabled" android:enabled="false">
                    <intent android:action="android.intent.action.VIEW" android:targetPackage="$pkg" android:targetClass="$pkg.Public"/>
                </shortcut>
            </shortcuts>"""))
            return AppDetails.readShortcutXml(app, parser,
                label = { parser.getAttributeValue(ns, "shortcutShortLabel").orEmpty() },
                readIntent = { readIntent(parser) },
                enabled = { parser.getAttributeValue(ns, "enabled") != "false" }, installed = installed)
        }
        val installed = parse(true)
        assertTrue(installed.getValue("open").actionable)
        assertEquals("open", installed.getValue("open").subtitle)
        assertFalse(installed.getValue("disabled").actionable)
        assertFalse(installed.getValue("disabled").enabled)
        assertTrue(parse(false).values.none { it.actionable })
    }

    @Test fun `installed app properties request and retain component and signing metadata`() {
        FsRegistry.register(AppsFileSystem(app))
        val section = FileInfo.load(app, XFile("apps", "/user/$pkg", false)).sections
            .single { it.title == app.getString(R.string.info_section_app) }
        assertEquals(6, section.appGroups.size)
        assertEquals(4, section.appGroups.single { it.kind == AppDetails.Kind.ACTIVITIES }.entries.size)
    }

    @Config(qualifiers = "zh")
    @Test fun `inspection labels use English fallback in other locales`() {
        assertEquals(listOf("Signature", "Activities", "Shortcuts", "Services", "Receivers", "Providers"),
            AppDetails.Kind.values().map { app.getString(it.title) })
        assertEquals("None", app.getString(R.string.info_app_none))
        assertEquals("Serial number", app.getString(R.string.info_app_serial_number))
    }

    @Config(sdk = [24])
    @Test fun `inspection works on Android 7 without newer shortcut or signing APIs`() {
        assertEquals(6, AppDetails.load(app, info(), true).size)
        assertTrue(AppDetails.packageFlags() and PackageManager.GET_SIGNATURES != 0)
    }
}
