package be.mygod.reactmap

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.JsonWriter
import android.util.Log
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import be.mygod.reactmap.App.Companion.app
import be.mygod.reactmap.follower.BackgroundLocationReceiver
import be.mygod.reactmap.util.AlertDialogFragment
import be.mygod.reactmap.util.CreateDynamicDocument
import be.mygod.reactmap.util.findErrorStream
import be.mygod.reactmap.util.readableMessage
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import timber.log.Timber
import java.io.IOException
import java.io.Reader
import java.io.StringWriter
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Locale

class MainActivity : FragmentActivity() {
    companion object {
        const val ACTION_CONFIGURE = "be.mygod.reactmap.action.CONFIGURE"
        const val ACTION_RESTART_GAME = "be.mygod.reactmap.action.RESTART_GAME"
        private const val KEY_WELCOME = "welcome"
        private const val URL_RELOADING = "data:text/html;charset=utf-8,%3Ctitle%3ELoading...%3C%2Ftitle%3E%3Ch1%20style%3D%22display%3Aflex%3Bjustify-content%3Acenter%3Balign-items%3Acenter%3Btext-align%3Acenter%3Bheight%3A100vh%22%3ELoading..."

        private val filenameExtractor = "filename=(\"([^\"]+)\"|[^;]+)".toRegex(RegexOption.IGNORE_CASE)
        private val supportedHosts = setOf("discordapp.com", "discord.com")
    }

    private lateinit var web: WebView
    private lateinit var glocation: Glocation
    private lateinit var siteController: SiteController
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
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        val activeUrl = app.activeUrl
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
                        loadUrl(app.activeUrl)
                        return
                    }
                    if (url.toUri().host != hostname) return
                    muiMargin.apply()
                    BackgroundLocationReceiver.setup()  // redo setup in case cookie is updated
                    ReactMapHttpEngine.updateCookie()
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
                    finish()    // WebView cannot be reused but keep the process alive if possible
                    return true
                }
            }
            setDownloadListener { url, _, contentDisposition, mimetype, _ ->
                if (!url.startsWith("data:", true)) {
                    Toast.makeText(this@MainActivity, "Unsupported download: $url", Toast.LENGTH_LONG).show()
                    return@setDownloadListener
                }
                pendingJson = URLDecoder.decode(url.split(',', limit = 2)[1], "utf-8")
                createDocument.launch(mimetype to (filenameExtractor.find(contentDisposition)?.run {
                    groupValues[2].ifEmpty { groupValues[1] }
                } ?: "settings.json"))
            }
            loadUrl(activeUrl)
        }
        setContentView(web)
        if (app.pref.getBoolean(KEY_WELCOME, true)) {
            startConfigure()
            app.pref.edit { putBoolean(KEY_WELCOME, false) }
        }
        AlertDialogFragment.setResultListener<ConfigDialogFragment, ConfigDialogFragment.Ret>(this) { _, ret ->
            hostname = ret?.hostname ?: return@setResultListener
            web.loadUrl(URL_RELOADING)
        }
    }

    private fun buildResponse(request: WebResourceRequest, transform: (Reader) -> String): WebResourceResponse {
        val url = request.url.toString()
        val conn = ReactMapHttpEngine.openConnection(url) {
            requestMethod = request.method
            for ((key, value) in request.requestHeaders) addRequestProperty(key, value)
        }
        return WebResourceResponse(conn.contentType.split(';', limit = 2)[0], conn.contentEncoding, conn.responseCode,
            conn.responseMessage.let { if (it.isNullOrBlank()) "N/A" else it },
            conn.headerFields.mapValues { (_, value) -> value.joinToString() },
            if (conn.responseCode in 200..299) try {
                val charset = if (conn.contentEncoding == null) Charsets.UTF_8 else {
                    Charset.forName(conn.contentEncoding)
                }
                transform(conn.inputStream.bufferedReader(charset)).byteInputStream(charset)
            } catch (e: IOException) {
                Timber.d(e)
                conn.inputStream
            } else conn.findErrorStream)
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
    private fun startConfigure() = ConfigDialogFragment().apply { key() }.show(supportFragmentManager, null)
    private fun restartGame(packageName: String) {
        try {
            ProcessBuilder("su", "-c", "am force-stop $packageName &&" +
                    "am start -n $packageName/com.nianticproject.holoholo.libholoholo.unity.UnityMainActivity").start()
        } catch (e: Exception) {
            Timber.w(e)
            Toast.makeText(this@MainActivity, e.readableMessage, Toast.LENGTH_LONG).show()
        }
    }
}
