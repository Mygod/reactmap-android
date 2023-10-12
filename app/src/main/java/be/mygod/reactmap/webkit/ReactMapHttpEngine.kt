package be.mygod.reactmap.webkit

import android.net.http.ConnectionMigrationOptions
import android.net.http.HttpEngine
import android.os.Build
import android.os.ext.SdkExtensions
import android.webkit.CookieManager
import androidx.annotation.RequiresExtension
import androidx.core.content.edit
import androidx.core.net.toUri
import be.mygod.reactmap.App.Companion.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

    val apiUrl get() = app.activeUrl.toUri().buildUpon().apply {
        path("/graphql")
    }.build().toString()

    private fun openConnection(url: String) = (if (Build.VERSION.SDK_INT >= 34 || Build.VERSION.SDK_INT >=
        Build.VERSION_CODES.S && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7) {
        engine.openConnection(URL(url))
    } else URL(url).openConnection()) as HttpURLConnection
    suspend fun <T> connectCancellable(url: String, block: suspend (HttpURLConnection) -> T): T {
        val conn = openConnection(url)
        return suspendCancellableCoroutine { cont ->
            val job = GlobalScope.launch(Dispatchers.IO) {
                try {
                    cont.resume(block(conn))
                } catch (e: Throwable) {
                    cont.resumeWithException(e)
                } finally {
                    conn.disconnect()
                }
            }
            cont.invokeOnCancellation {
                job.cancel(it as? CancellationException)
                conn.disconnect()
            }
        }
    }

    fun connectWithCookie(url: String, setup: (HttpURLConnection) -> Unit) = openConnection(url).also { conn ->
        if (app.userManager.isUserUnlocked) {
            val cookie = CookieManager.getInstance()
            cookie.getCookie(url)?.let { conn.addRequestProperty("Cookie", it) }
            setup(conn)
            conn.headerFields["Set-Cookie"]?.forEach { cookie.setCookie(url, it) }
        } else {
            app.pref.getString(KEY_COOKIE, null)?.let { conn.addRequestProperty("Cookie", it) }
            setup(conn)
        }
    }

    fun updateCookie() {
        val cookie = CookieManager.getInstance()
        app.pref.edit { putString(KEY_COOKIE, cookie.getCookie(apiUrl)) }
        cookie.flush()
    }
}
