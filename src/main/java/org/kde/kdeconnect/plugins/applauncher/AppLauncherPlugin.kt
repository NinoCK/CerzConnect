/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.applauncher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.widget.Toast
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect_tp.R
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * CerzConnect: browse and launch the applications installed on the PC.
 *
 * Talks to the App Launcher plugin of GSConnect. The PC sends the apps shown in its app grid,
 * their icons as base64 PNGs, and launches one by its desktop file ID when asked.
 */
@LoadablePlugin
class AppLauncherPlugin : Plugin() {

    data class App(val id: String, val name: String, val iconKey: String?)

    /** Sorted by name, as the PC sends it. Empty until the first list arrives. */
    @Volatile
    var apps: List<App> = emptyList()
        private set

    /** False until the PC has answered at least once */
    @Volatile
    var appsReceived: Boolean = false
        private set

    private val icons = ConcurrentHashMap<String, Bitmap>()
    // The cache file each icon in memory came from, to spot icons that changed on the PC
    private val iconFiles = ConcurrentHashMap<String, String>()
    private val appsUpdated = ConcurrentHashMap<String, () -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val iconSize: Int
        get() = (ICON_SIZE_DP * context.resources.displayMetrics.density).toInt()

    private val cacheDir: File
        get() = File(context.cacheDir, "applauncher/${device.deviceId}")

    override val displayName: String
        get() = context.resources.getString(R.string.pref_plugin_applauncher)

    override val description: String
        get() = context.resources.getString(R.string.pref_plugin_applauncher_desc)

    override fun onCreate() {
        requestAppList()
    }

    override fun onDestroy() {
        appsUpdated.clear()
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (np.type != PACKET_TYPE_APPLAUNCHER) return false

        when {
            np.has("appList") -> onAppList(np)
            np.has("icons") -> onIcons(np)
            np.has("launched") -> onLaunched(np)
            else -> return false
        }
        return true
    }

    private fun onAppList(np: NetworkPacket) {
        val list = np.getJsonArray("appList")?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"].string() ?: return@mapNotNull null
            App(id, obj["name"].string() ?: id, obj["icon"].string())
        } ?: emptyList()

        apps = list
        appsReceived = true

        // Icons of apps that are gone, or whose icon changed, are no longer needed
        val files = list.associate { it.id to cacheFile(it).name }
        for (id in icons.keys) {
            if (iconFiles[id] != files[id]) {
                icons.remove(id)
                iconFiles.remove(id)
            }
        }
        cacheDir.listFiles()?.forEach { if (it.name !in files.values) it.delete() }

        for (app in list) {
            if (icons.containsKey(app.id)) continue
            loadCachedIcon(app)?.let { putIcon(app, it) }
        }
        notifyAppsUpdated()

        val missing = list.filter { !icons.containsKey(it.id) }.map { it.id }
        if (missing.isNotEmpty()) requestIcons(missing)
    }

    private fun onIcons(np: NetworkPacket) {
        val received = np.getJsonObject("icons") ?: return
        val byId = apps.associateBy { it.id }

        for ((id, value) in received) {
            val app = byId[id] ?: continue
            val data = value.string() ?: continue
            try {
                val bytes = Base64.decode(data, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                putIcon(app, bitmap)
                saveCachedIcon(app, bytes)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Bad icon for $id", e)
            }
        }
        notifyAppsUpdated()
    }

    private fun onLaunched(np: NetworkPacket) {
        if (np.getBoolean("success", true)) return

        val id = np.getString("launched", "")
        val name = apps.firstOrNull { it.id == id }?.name ?: id
        mainHandler.post {
            Toast.makeText(context, context.getString(R.string.cerz_app_launch_failed, name), Toast.LENGTH_SHORT).show()
        }
    }

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.contentOrNull

    fun getIcon(id: String): Bitmap? = icons[id]

    private fun putIcon(app: App, bitmap: Bitmap) {
        iconFiles[app.id] = cacheFile(app).name
        icons[app.id] = bitmap
    }

    fun requestAppList() {
        val np = NetworkPacket(PACKET_TYPE_APPLAUNCHER_REQUEST)
        np["requestAppList"] = true
        device.sendPacket(np)
    }

    private fun requestIcons(ids: List<String>) {
        val np = NetworkPacket(PACKET_TYPE_APPLAUNCHER_REQUEST)
        np["requestIcons"] = ids
        np["iconSize"] = iconSize
        device.sendPacket(np)
    }

    fun launch(id: String) {
        val np = NetworkPacket(PACKET_TYPE_APPLAUNCHER_REQUEST)
        np["launch"] = id
        device.sendPacket(np)
    }

    /** The callback runs on the main thread */
    fun setAppsUpdatedHandler(id: String, callback: () -> Unit) {
        appsUpdated[id] = callback
    }

    fun removeAppsUpdatedHandler(id: String) {
        appsUpdated.remove(id)
    }

    private fun notifyAppsUpdated() {
        mainHandler.post { appsUpdated.values.forEach { it() } }
    }

    // One file per app, named after everything that changes how its icon looks
    private fun cacheFile(app: App): File {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("${app.id}\n${app.iconKey}\n$iconSize".toByteArray())
        return File(cacheDir, digest.joinToString("") { "%02x".format(it) } + ".png")
    }

    private fun loadCachedIcon(app: App): Bitmap? {
        val file = cacheFile(app)
        return if (file.exists()) BitmapFactory.decodeFile(file.path) else null
    }

    private fun saveCachedIcon(app: App, png: ByteArray) {
        try {
            cacheDir.mkdirs()
            cacheFile(app).writeBytes(png)
        } catch (e: IOException) {
            Log.e(TAG, "Couldn't cache the icon for ${app.id}", e)
        }
    }

    override val supportedPacketTypes: Array<String> = arrayOf(PACKET_TYPE_APPLAUNCHER)

    override val outgoingPacketTypes: Array<String> = arrayOf(PACKET_TYPE_APPLAUNCHER_REQUEST)

    companion object {
        private const val TAG = "AppLauncherPlugin"
        private const val ICON_SIZE_DP = 40
        const val PACKET_TYPE_APPLAUNCHER = "kdeconnect.applauncher"
        const val PACKET_TYPE_APPLAUNCHER_REQUEST = "kdeconnect.applauncher.request"
    }
}
