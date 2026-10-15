package com.twig.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ComponentInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.Build
import android.os.Process
import android.util.Xml
import org.bouncycastle.asn1.ASN1String
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** App metadata for Properties; load on a worker thread. */
object AppDetails {
    enum class Kind(val title: Int) {
        SIGNATURE(R.string.info_app_signature), ACTIVITIES(R.string.info_app_activities),
        SHORTCUTS(R.string.info_app_shortcuts), SERVICES(R.string.info_app_services),
        RECEIVERS(R.string.info_app_receivers), PROVIDERS(R.string.info_app_providers),
    }
    sealed interface Action {
        data class Activity(val packageName: String, val className: String) : Action
        data class Intents(val intents: List<Intent>) : Action
        data class Hosted(val packageName: String, val id: String) : Action
    }
    data class Entry(val title: String, val rows: List<Pair<String, String>> = emptyList(), val action: Action? = null,
        val subtitle: String? = null, val enabled: Boolean = true, val exported: Boolean = false) {
        val actionable: Boolean get() = enabled && action != null
    }
    data class Group(val kind: Kind, val entries: List<Entry>, val notice: String? = null)
    private const val ANDROID = "http://schemas.android.com/apk/res/android"

    @Suppress("DEPRECATION")
    fun packageFlags(): Int = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
        PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA or
        PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS or
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    fun load(ctx: Context, info: PackageInfo, installed: Boolean): List<Group> {
        val pm = ctx.packageManager
        if (!installed) {
            (info.activities.orEmpty().asList() + info.services.orEmpty().asList() +
                info.receivers.orEmpty().asList() + info.providers.orEmpty().asList()).forEach {
                it.applicationInfo = info.applicationInfo
            }
        }
        fun name(component: ComponentInfo) = component.name.removePrefix(info.packageName + ".")
        fun label(component: ComponentInfo, keepDuplicate: Boolean = false): String? {
            val value = runCatching { component.loadLabel(pm).toString().trim() }.getOrDefault("")
            return value.takeIf { it.isNotEmpty() && (keepDuplicate || (it != component.name && it != name(component))) }
        }
        fun declaredLabel(component: ComponentInfo) =
            if (component.labelRes != 0 || !component.nonLocalizedLabel.isNullOrBlank()) label(component) else null
        fun active(component: ComponentInfo) = info.applicationInfo?.enabled != false && componentEnabled(ctx, component, installed)
        val activities = info.activities.orEmpty().sortedBy { it.name }.map { activity ->
            val active = active(activity)
            val canOpen = installed && active && activity.exported &&
                (activity.permission.isNullOrEmpty() || pm.checkPermission(activity.permission, ctx.packageName) == PackageManager.PERMISSION_GRANTED)
            Entry(name(activity), emptyList(), if (canOpen) Action.Activity(info.packageName, activity.name) else null,
                subtitle = if (canOpen) label(activity, keepDuplicate = true) else declaredLabel(activity), enabled = active)
        }.sortedBy { it.action == null }
        val services = info.services.orEmpty().sortedBy { it.name }.map { Entry(name(it), emptyList(), subtitle = declaredLabel(it), enabled = active(it), exported = it.exported) }
        val receivers = info.receivers.orEmpty().sortedBy { it.name }.map { Entry(name(it), emptyList(), subtitle = declaredLabel(it), enabled = active(it), exported = it.exported) }
        val providers = info.providers.orEmpty().sortedBy { it.name }.map { provider ->
            val rows = ArrayList<Pair<String, String>>()
            fun add(id: Int, value: String?) {
                value?.takeIf { it.isNotBlank() }?.let { rows += ctx.getString(id) to it }
            }
            add(R.string.info_app_authority, provider.authority)
            if (provider.readPermission == provider.writePermission) {
                add(R.string.info_app_permission, provider.readPermission)
            } else {
                add(R.string.info_app_read_permission, provider.readPermission)
                add(R.string.info_app_write_permission, provider.writePermission)
            }
            Entry(name(provider), rows, subtitle = declaredLabel(provider), enabled = active(provider), exported = provider.exported)
        }
        return listOf(signatures(ctx, info), Group(Kind.ACTIVITIES, activities), shortcuts(ctx, info, installed),
            Group(Kind.SERVICES, services), Group(Kind.RECEIVERS, receivers), Group(Kind.PROVIDERS, providers))
    }

    fun open(ctx: Context, entry: Entry) {
        check(entry.actionable)
        when (val action = entry.action) {
            is Action.Activity -> ctx.startActivity(Intent()
                .setComponent(ComponentName(action.packageName, action.className)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            is Action.Intents -> {
                val intents = action.intents.map { Intent(it) }.toTypedArray()
                intents.first().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivities(intents)
            }
            is Action.Hosted -> {
                check(Build.VERSION.SDK_INT >= 25)
                ctx.getSystemService(LauncherApps::class.java).startShortcut(
                    action.packageName, action.id, null, null, Process.myUserHandle())
            }
            null -> error("No launch action")
        }
    }

    private fun componentEnabled(ctx: Context, component: ComponentInfo, installed: Boolean): Boolean {
        if (!installed) return component.enabled
        val state = runCatching {
            ctx.packageManager.getComponentEnabledSetting(ComponentName(component.packageName, component.name))
        }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && component.enabled)
    }

    /** Direct shortcuts must be accessible to Twig; LauncherApps may launch private shortcut targets. */
    internal fun shortcutAction(ctx: Context, intents: List<Intent>, installed: Boolean): Action? {
        if (!installed || intents.isEmpty()) return null
        val pm = ctx.packageManager
        val accessible = intents.all { intent ->
            val activity = runCatching { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo }.getOrNull()
            activity != null && activity.applicationInfo?.enabled != false && componentEnabled(ctx, activity, true) &&
                (activity.packageName == ctx.packageName || (activity.exported &&
                    (activity.permission.isNullOrEmpty() || pm.checkPermission(activity.permission, ctx.packageName) == PackageManager.PERMISSION_GRANTED)))
        }
        return if (accessible) Action.Intents(intents.map { Intent(it) }) else null
    }

    @Suppress("DEPRECATION")
    private fun signatures(ctx: Context, info: PackageInfo): Group {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
                ?: info.signatures
        } else info.signatures
        val entries = signatures.orEmpty().distinct().map { signature ->
            val bytes = signature.toByteArray()
            val rows = ArrayList<Pair<String, String>>()
            var commonName = ""
            runCatching {
                CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
            }.getOrNull()?.let { cert ->
                commonName = runCatching {
                    val value = X500Name.getInstance(cert.subjectX500Principal.encoded).getRDNs(BCStyle.CN).firstOrNull()?.first?.value
                    (value as? ASN1String)?.string.orEmpty()
                }.getOrDefault("")
                rows += ctx.getString(R.string.info_app_subject) to cert.subjectX500Principal.name
                rows += ctx.getString(R.string.info_app_issuer) to cert.issuerX500Principal.name
                rows += ctx.getString(R.string.info_app_serial_number) to cert.serialNumber.toString(16).uppercase(java.util.Locale.ROOT)
                rows += ctx.getString(R.string.info_app_signature_algorithm) to cert.sigAlgName
                rows += ctx.getString(R.string.info_app_validity) to
                    (Format.time(cert.notBefore.time, ctx) + " — " + Format.time(cert.notAfter.time, ctx))
            }
            rows += "SHA-256" to digest("SHA-256", bytes)
            Entry(commonName, rows)
        }
        return Group(Kind.SIGNATURE, entries)
    }

    private fun digest(algorithm: String, bytes: ByteArray) =
        MessageDigest.getInstance(algorithm).digest(bytes).joinToString(":") { "%02X".format(it) }

    private fun shortcuts(ctx: Context, info: PackageInfo, installed: Boolean): Group {
        val entries = LinkedHashMap<String, Entry>()
        var incomplete = false
        val pm = ctx.packageManager
        val resources by lazy { pm.getResourcesForApplication(info.applicationInfo!!) }
        for (activity in info.activities.orEmpty()) {
            if ((activity.metaData?.getInt("android.app.shortcuts", 0) ?: 0) == 0) continue
            runCatching {
                activity.loadXmlMetaData(pm, "android.app.shortcuts")?.use { parser ->
                    entries.putAll(readShortcutXml(ctx, parser,
                        label = {
                            val res = parser.getAttributeResourceValue(ANDROID, "shortcutShortLabel", 0)
                            if (res != 0) resources.getString(res)
                            else parser.getAttributeValue(ANDROID, "shortcutShortLabel").orEmpty()
                        }, enabled = { parser.getAttributeBooleanValue(ANDROID, "enabled", true) && info.applicationInfo?.enabled != false },
                        readIntent = { Intent.parseIntent(resources, parser, Xml.asAttributeSet(parser)) }, installed = installed))
                }
            }.onFailure { incomplete = true }
        }
        var dynamicReadable = !installed
        if (installed && Build.VERSION.SDK_INT >= 25) {
            runCatching {
                var hosted = false
                val live: List<ShortcutInfo> = if (info.packageName == ctx.packageName) {
                    val manager = ctx.getSystemService(ShortcutManager::class.java)
                    dynamicReadable = true
                    manager.manifestShortcuts + manager.dynamicShortcuts + manager.pinnedShortcuts
                } else {
                    val launcher = ctx.getSystemService(LauncherApps::class.java)
                    if (launcher.hasShortcutHostPermission()) {
                        hosted = true
                        dynamicReadable = true
                        launcher.getShortcuts(LauncherApps.ShortcutQuery().setPackage(info.packageName).setQueryFlags(
                            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED), Process.myUserHandle()).orEmpty()
                    } else emptyList()
                }
                live.distinctBy { it.id }.forEach {
                    val active = it.isEnabled && info.applicationInfo?.enabled != false
                    val action = if (!active) null else if (hosted) Action.Hosted(info.packageName, it.id)
                        else shortcutAction(ctx, it.intents.orEmpty().toList(), installed)
                    val label = it.shortLabel?.toString()?.takeIf { label -> label.isNotBlank() && (action != null || label != it.id) }
                    entries[it.id] = Entry(it.id, subtitle = label, enabled = active, action = action)
                }
            }.onFailure { dynamicReadable = false }
        }
        val notice = when {
            incomplete -> ctx.getString(R.string.info_app_details_unavailable)
            !dynamicReadable -> ctx.getString(R.string.info_app_shortcuts_limited)
            else -> null
        }
        return Group(Kind.SHORTCUTS, entries.values.toList(), notice)
    }

    internal fun readShortcutXml(ctx: Context, parser: XmlPullParser, label: () -> String,
        readIntent: () -> Intent, enabled: () -> Boolean = { true }, installed: Boolean = false): Map<String, Entry> {
        val entries = LinkedHashMap<String, Entry>()
        var id: String? = null
        var title = ""
        var active = true
        var intents = ArrayList<Intent>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) when (parser.name) {
                "shortcut" -> {
                    id = parser.getAttributeValue(ANDROID, "shortcutId")
                    title = label()
                    active = enabled()
                    intents = arrayListOf()
                }
                "intent" -> if (id != null) intents += readIntent()
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "shortcut") {
                id?.let {
                    val action = if (active) shortcutAction(ctx, intents, installed) else null
                    entries[it] = Entry(it, subtitle = title.takeIf { label -> label.isNotBlank() && (action != null || label != it) },
                        enabled = active, action = action)
                }
                id = null
            }
        }
        return entries
    }

}
