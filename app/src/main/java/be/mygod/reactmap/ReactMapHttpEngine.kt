package be.mygod.reactmap

import android.net.http.ConnectionMigrationOptions
import android.net.http.HttpEngine
import android.os.Build
import android.os.ext.SdkExtensions
import android.webkit.CookieManager
import androidx.annotation.RequiresExtension
import androidx.core.content.edit
import be.mygod.reactmap.App.Companion.app
import be.mygod.reactmap.follower.LocationSetter
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ReactMapHttpEngine {
    private const val KEY_COOKIE = "cookie.graphql"

    @get:RequiresExtension(Build.VERSION_CODES.S, 7)
    private val engine by lazy @RequiresExtension(Build.VERSION_CODES.S, 7) {
        val cache = File(app.deviceStorage.cacheDir, "httpEngine")
        HttpEngine.Builder(app.deviceStorage).apply {
            if (cache.mkdirs() || cache.isDirectory) {
                setStoragePath(cache.absolutePath)
                setEnableHttpCache(HttpEngine.Builder.HTTP_CACHE_DISK, 1024 * 1024)
            }
            setConnectionMigrationOptions(ConnectionMigrationOptions.Builder().apply {
                setDefaultNetworkMigration(ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED)
                setPathDegradationMigration(ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED)
            }.build())
            setEnableBrotli(true)
        }.build()
    }

    fun openConnection(url: String, setup: HttpURLConnection.() -> Unit) = ((if (Build.VERSION.SDK_INT >= 34 ||
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7) {
        engine.openConnection(URL(url))
    } else URL(url).openConnection()) as HttpURLConnection).apply {
        if (app.userManager.isUserUnlocked) {
            val cookie = CookieManager.getInstance()
            cookie.getCookie(url)?.let { addRequestProperty("Cookie", it) }
            setup()
            headerFields["Set-Cookie"]?.forEach { cookie.setCookie(url, it) }
        } else {
            app.pref.getString(KEY_COOKIE, null)?.let { addRequestProperty("Cookie", it) }
            setup()
        }
    }

    fun updateCookie() = app.pref.edit {
        putString(KEY_COOKIE, CookieManager.getInstance().getCookie(LocationSetter.apiUrl))
    }
}