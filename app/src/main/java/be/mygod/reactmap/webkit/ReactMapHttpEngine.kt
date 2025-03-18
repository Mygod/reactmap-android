package be.mygod.reactmap.webkit

import android.annotation.SuppressLint
import android.net.Uri
import android.net.http.ConnectionMigrationOptions
import android.net.http.HttpEngine
import android.os.Build
import android.os.ext.SdkExtensions
import android.webkit.CookieManager
import androidx.annotation.RequiresExtension
import androidx.core.content.edit
import androidx.core.net.toUri
import be.mygod.reactmap.App.Companion.app
import be.mygod.reactmap.util.headerLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.brotli.wrapper.enc.BrotliOutputStream
import org.brotli.wrapper.enc.Encoder
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

object ReactMapHttpEngine {
    private const val KEY_COOKIE = "cookie.graphql"
    const val KEY_BROTLI = "http.brotli"

    val isCronet get() = Build.VERSION.SDK_INT >= 34 || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7
    private var engineInternal: HttpEngine? = null
    @get:RequiresExtension(Build.VERSION_CODES.S, 7)
    val engine get() = synchronized(ReactMapHttpEngine) {
        engineInternal ?: HttpEngine.Builder(app.deviceStorage).apply {
            val cache = File(app.deviceStorage.cacheDir, "httpEngine")
            if (cache.mkdirs() || cache.isDirectory) {
                setStoragePath(cache.absolutePath)
                setEnableHttpCache(HttpEngine.Builder.HTTP_CACHE_DISK, 1024 * 1024 * 1024)
            }
            setConnectionMigrationOptions(ConnectionMigrationOptions.Builder().apply {
                setDefaultNetworkMigration(ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED)
                setPathDegradationMigration(ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED)
            }.build())
            setEnableBrotli(true)
        }.build().also {
            engineInternal = it
            Timber.d("HttpEngine ${HttpEngine.getVersionString()} initialized")
        }
    }
    @SuppressLint("NewApi")
    fun reset() = synchronized(ReactMapHttpEngine) {
        engineInternal?.apply {
            try {
                shutdown()
            } catch (e: IllegalStateException) {
                Timber.d(e)
            }
            engineInternal = null
        }
    }

    fun apiUrl(base: Uri) = base.buildUpon().apply {
        path("/graphql")
    }.build().toString()
    val apiUrl get() = apiUrl(app.activeUrl.toUri())

    private fun openConnection(url: String) = (if (isCronet) {
        engine.openConnection(URL(url))
    } else URL(url).openConnection()) as HttpURLConnection
    suspend fun <T> connectCancellable(url: String, block: suspend (HttpURLConnection) -> T): T {
        val conn = openConnection(url)
        return coroutineScope {
            @OptIn(InternalCoroutinesApi::class)    // https://github.com/Kotlin/kotlinx.coroutines/issues/4117
            coroutineContext.job.invokeOnCompletion(true) { conn.disconnect() }
            try {
                withContext(Dispatchers.IO) { block(conn) }
            } finally {
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

    private class ExposingBufferByteArrayOutputStream : ByteArrayOutputStream() {
        val buffer get() = buf
        val length get() = count
    }
    private val initBrotli by lazy { System.loadLibrary("brotli") }
    fun writeCompressed(conn: HttpURLConnection, body: String) {
        val brotli = app.pref.getBoolean(KEY_BROTLI, true)
        conn.setRequestProperty("Content-Encoding", if (brotli) {
            initBrotli
            "br"
        } else "deflate")
        conn.doOutput = true
        conn.instanceFollowRedirects = false
        val uncompressed = body.toByteArray()
        val out = ExposingBufferByteArrayOutputStream()
//        val time = System.nanoTime()
        (if (brotli) BrotliOutputStream(out, Encoder.Parameters().apply {
            setMode(Encoder.Mode.TEXT)
            setQuality(5)
        }) else DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION))).use { it.write(uncompressed) }
//        Timber.tag("CompressionStat").i("$brotli ${out.length}/${uncompressed.size} ~ ${out.length.toDouble() / uncompressed.size} ${(System.nanoTime() - time) * .000_001}ms")
        conn.setFixedLengthStreamingMode(out.length)
        conn.outputStream.use { it.write(out.buffer, 0, out.length) }
    }

    fun detectBrotliError(conn: HttpURLConnection): String? {
        val path = conn.headerLocation
        if (path.startsWith("/error/")) return Uri.decode(path.substring(7)).also {
            if (conn.url.host == app.activeUrl.toUri().host && it == "unsupported content encoding \"br\"") app.pref.edit { putBoolean(KEY_BROTLI, false) }
        }
        Timber.w(Exception(path))
        return path
    }
}
