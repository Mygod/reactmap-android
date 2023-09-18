package be.mygod.reactmap

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.ConnectionMigrationOptions
import android.net.http.HttpEngine
import android.os.Build
import android.os.Bundle
import android.os.ext.SdkExtensions
import android.util.JsonWriter
import android.util.Log
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresExtension
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import be.mygod.reactmap.App.Companion.app
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.Reader
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Locale

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_CONFIGURE = "be.mygod.reactmap.action.CONFIGURE"
        const val ACTION_RESTART_GAME = "be.mygod.reactmap.action.RESTART_GAME"
        private const val PREF_NAME = "reactmap"
        private const val KEY_WELCOME = "welcome"
        private const val KEY_ACTIVE_URL = "url.active"
        private const val KEY_HISTORY_URL = "url.history"
        private const val URL_DEFAULT = "https://www.reactmap.dev"
        private const val URL_RELOADING = "data:text/html;charset=utf-8,%3Ctitle%3ELoading...%3C%2Ftitle%3E%3Ch1%20style%3D%22display%3Aflex%3Bjustify-content%3Acenter%3Balign-items%3Acenter%3Btext-align%3Acenter%3Bheight%3A100vh%22%3ELoading..."

        private val filenameExtractor = "filename=(\"([^\"]+)\"|[^;]+)".toRegex(RegexOption.IGNORE_CASE)
        private val supportedHosts = setOf("discordapp.com", "discord.com")

        @get:RequiresExtension(Build.VERSION_CODES.S, 7)
        private val engine by lazy @RequiresExtension(Build.VERSION_CODES.S, 7) {
            val cache = File(app.cacheDir, "httpEngine")
            HttpEngine.Builder(app).apply {
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
    }

    private lateinit var web: WebView
    private lateinit var glocation: Glocation
    private lateinit var siteController: SiteController
    private lateinit var pref: SharedPreferences
    private lateinit var hostname: String
    private val windowInsetsController by lazy { WindowCompat.getInsetsController(window, web) }
    private var loginText: String? = null

    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private val getContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        pendingFileCallback?.onReceiveValue(if (uri == null) emptyArray() else arrayOf(uri))
        pendingFileCallback = null
    }
    private var pendingJson: String? = null
    private val createDocument = registerForActivityResult(CreateDynamicDocument()) { uri ->
        val json = pendingJson
        if (json != null && uri != null) contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(json) }
        pendingJson = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        pref = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        val activeUrl = pref.getString(KEY_ACTIVE_URL, URL_DEFAULT)!!
        hostname = Uri.parse(activeUrl).host!!
        web = WebView(this).apply {
            settings.apply {
                domStorageEnabled = true
                @SuppressLint("SetJavaScriptEnabled")
                javaScriptEnabled = true
            }
            glocation = Glocation(this)
            siteController = SiteController(this@MainActivity)
            webChromeClient = object : WebChromeClient() {
                @Suppress("KotlinConstantConditions")
                override fun onConsoleMessage(consoleMessage: ConsoleMessage) = consoleMessage.run {
                    Timber.tag("WebConsole").log(when (messageLevel()) {
                        ConsoleMessage.MessageLevel.TIP -> Log.INFO
                        ConsoleMessage.MessageLevel.LOG -> Log.VERBOSE
                        ConsoleMessage.MessageLevel.WARNING -> Log.WARN
                        ConsoleMessage.MessageLevel.ERROR -> Log.ERROR
                        ConsoleMessage.MessageLevel.DEBUG -> Log.DEBUG
                        else -> error(messageLevel())
                    }, "${sourceId()}:${lineNumber()} - ${message()}")
                    true
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    siteController.title = title
                }

                override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>?,
                                               fileChooserParams: FileChooserParams): Boolean {
                    require(fileChooserParams.mode == FileChooserParams.MODE_OPEN)
                    pendingFileCallback?.onReceiveValue(null)
                    pendingFileCallback = filePathCallback
                    getContent.launch(fileChooserParams.acceptTypes.single())
                    return true
                }
            }
            val onBackPressedCallback = object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() = web.goBack()
            }
            onBackPressedDispatcher.addCallback(onBackPressedCallback)
            val muiMargin = ReactMapMuiMarginListener(this)
            webViewClient = object : WebViewClient() {
                override fun doUpdateVisitedHistory(view: WebView?, url: String, isReload: Boolean) {
                    onBackPressedCallback.isEnabled = web.canGoBack()
                    if (url.toUri().path?.trimEnd('/') == "/login") loginText?.let { login ->
                        val writer = StringWriter()
                        writer.write("document.location = document.evaluate('//a[text()=")
                        JsonWriter(writer).use {
                            it.isLenient = true
                            it.value(login)
                        }
                        writer.write(
                            "]', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue.href;")
                        web.evaluateJavascript(writer.toString(), null)
                    }
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    glocation.clear()
                    if (url.toUri().host == hostname) glocation.setupGeolocation()
                }

                override fun onPageFinished(view: WebView?, url: String) {
                    if (url == URL_RELOADING) {
                        loadUrl(pref.getString(KEY_ACTIVE_URL, URL_DEFAULT)!!)
                        return
                    }
                    if (url.toUri().host == hostname) muiMargin.apply()
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val parsed = request.url
                    return when {
                        parsed.host?.lowercase(Locale.ENGLISH).let { it != hostname && it !in supportedHosts } -> {
                            app.launchUrl(this@MainActivity, parsed)
                            true
                        }
                        "http".equals(parsed.scheme, true) -> {
                            Toast.makeText(view.context, "HTTP traffic disallowed", Toast.LENGTH_SHORT).show()
                            true
                        }
                        else -> false
                    }
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest) =
                    if (!"https".equals(request.url.scheme, true) || request.url.host != hostname) null
                    else when (val path = request.url.path) {
                        // Since CookieManager.getCookie does not return session cookie on main requests,
                        // we can only edit secondary files
                        "/api/settings" -> handleSettings(request)
                        else -> if (path?.startsWith("/locales/") == true && path.endsWith("/translation.json")) {
                            handleTranslation(request)
                        } else null
                    }

                @TargetApi(26)
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    if (detail.didCrash()) {
                        Timber.w(Exception("WebView crashed @ priority ${detail.rendererPriorityAtExit()}"))
                    } else {
                        FirebaseAnalytics.getInstance(this@MainActivity).logEvent("webviewExit",
                            bundleOf("priority" to detail.rendererPriorityAtExit()))
                    }
                    return false
                }
            }
            setDownloadListener { url, _, contentDisposition, mimetype, _ ->
                require(url.startsWith("data:", true))
                pendingJson = URLDecoder.decode(url.split(',', limit = 2)[1], "utf-8")
                createDocument.launch(mimetype to (filenameExtractor.find(contentDisposition)?.run {
                    groupValues[2].ifEmpty { groupValues[1] }
                } ?: "settings.json"))
            }
            loadUrl(activeUrl)
        }
        setContentView(web)
        if (pref.getBoolean(KEY_WELCOME, true)) {
            startConfigure()
            pref.edit { putBoolean(KEY_WELCOME, false) }
        }
    }

    private fun buildResponse(request: WebResourceRequest, transform: (Reader) -> String): WebResourceResponse {
        val url = request.url.toString()
        val conn = (if (Build.VERSION.SDK_INT >= 34 || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7) {
            engine.openConnection(URL(url))
        } else URL(url).openConnection()) as HttpURLConnection
        conn.requestMethod = request.method
        for ((key, value) in request.requestHeaders) conn.addRequestProperty(key, value)
        val cookie = CookieManager.getInstance()
        conn.addRequestProperty("Cookie", cookie.getCookie(url))
        conn.headerFields["Set-Cookie"]?.forEach { cookie.setCookie(url, it) }
        return WebResourceResponse(conn.contentType.split(';', limit = 2)[0], conn.contentEncoding, conn.responseCode,
            conn.responseMessage, conn.headerFields.mapValues { (_, value) -> value.joinToString() },
            if (conn.responseCode in 200..299) try {
                val charset = if (conn.contentEncoding == null) Charsets.UTF_8 else {
                    Charset.forName(conn.contentEncoding)
                }
                transform(conn.inputStream.bufferedReader(charset)).byteInputStream(charset)
            } catch (e: IOException) {
                Timber.d(e)
                conn.inputStream
            } else conn.inputStream)
    }
    private fun handleSettings(request: WebResourceRequest) = buildResponse(request) { reader ->
        val response = reader.readText()
        try {
            val config = JSONObject(response)
            val tileServers = config.getJSONArray("tileServers")
            lifecycleScope.launch {
                web.evaluateJavascript("JSON.parse(localStorage.getItem('local-state')).state.settings.tileServers") {
                    val name = JSONTokener(it).nextValue() as? String
                    windowInsetsController.isAppearanceLightStatusBars =
                        (tileServers.length() - 1 downTo 0).asSequence().map { i -> tileServers.getJSONObject(i) }
                            .firstOrNull { obj -> obj.getString("name") == name }?.optString("style") != "dark"
                }
            }
            val mapConfig = config.getJSONObject("map")
            if (mapConfig.optJSONArray("holidayEffects")?.length() != 0) {
                mapConfig.put("holidayEffects", JSONArray())
                config.toString()
            } else response
        } catch (e: JSONException) {
            Timber.w(e)
            response
        }
    }
    private fun handleTranslation(request: WebResourceRequest) = buildResponse(request) { reader ->
        val response = reader.readText()
        try {
            loginText = JSONObject(response).getString("login")
        } catch (e: JSONException) {
            Timber.w(e)
        }
        response
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        when (intent?.action) {
            ACTION_CONFIGURE -> startConfigure()
            ACTION_RESTART_GAME -> AlertDialog.Builder(this).apply {
                setTitle("Pick game version to restart")
                setMessage("This feature requires root")
                setPositiveButton("Standard") { _, _ -> restartGame("com.nianticlabs.pokemongo") }
                setNegativeButton("Samsung") { _, _ -> restartGame("com.nianticlabs.pokemongo.ares") }
                setNeutralButton(android.R.string.cancel, null)
            }.show()
        }
    }
    private fun startConfigure() = AlertDialog.Builder(this).apply {
        val historyUrl = pref.getStringSet(KEY_HISTORY_URL, null) ?: setOf(URL_DEFAULT)
        val editText = AutoCompleteTextView(this@MainActivity).apply {
            setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.select_dialog_item,
                historyUrl.toTypedArray()))
            setText(pref.getString(KEY_ACTIVE_URL, URL_DEFAULT))
        }
        setView(editText)
        setTitle("ReactMap URL:")
        setMessage("You can return to this dialog later by clicking on the notification.")
        setPositiveButton(android.R.string.ok) { _, _ ->
            val (uri, host) = try {
                editText.text!!.toString().toUri().run {
                    require("https".equals(scheme, true)) { "Only HTTPS is allowed" }
                    toString() to host!!
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.localizedMessage ?: e.javaClass.name, Toast.LENGTH_LONG).show()
                return@setPositiveButton
            }
            pref.edit {
                putString(KEY_ACTIVE_URL, uri)
                putStringSet(KEY_HISTORY_URL, historyUrl + uri)
            }
            hostname = host
            web.loadUrl(URL_RELOADING)
        }
        setNegativeButton(android.R.string.cancel, null)
    }.show()
    private fun restartGame(packageName: String) {
        try {
            ProcessBuilder("su", "-c", "am force-stop $packageName &&" +
                    "am start -n $packageName/com.nianticproject.holoholo.libholoholo.unity.UnityMainActivity").start()
        } catch (e: Exception) {
            Timber.w(e)
            Toast.makeText(this@MainActivity, e.localizedMessage ?: e.javaClass.name, Toast.LENGTH_LONG).show()
        }
    }
}
