package be.mygod.reactmap.webkit

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.JsonWriter
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import be.mygod.reactmap.App.Companion.app
import be.mygod.reactmap.R
import be.mygod.reactmap.follower.BackgroundLocationReceiver
import be.mygod.reactmap.util.CreateDynamicDocument
import be.mygod.reactmap.util.findErrorStream
import com.google.android.material.snackbar.Snackbar
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

class ReactMapFragment @JvmOverloads constructor(private var overrideUri: Uri? = null) : Fragment() {
    companion object {
        private val filenameExtractor = "filename=(\"([^\"]+)\"|[^;]+)".toRegex(RegexOption.IGNORE_CASE)
        private val vendorJsMatcher = "/vendor-[0-9a-z]{8}\\.js".toRegex(RegexOption.IGNORE_CASE)
        private val flyToMatcher = "/@/([0-9.-]+)/([0-9.-]+)(?:/([0-9.-]+))?/?".toRegex()
        private val mapHijacker = "(?<=[\\n\\r\\s,])this(?=.callInitHooks\\(\\)[,;][\\n\\r\\s]*this._zoomAnimated\\s*=)"
            .toPattern()
        private val supportedHosts = setOf("discordapp.com", "discord.com", "telegram.org", "oauth.telegram.org")
    }

    lateinit var web: WebView
    private lateinit var glocation: Glocation
    private lateinit var siteController: SiteController
    private lateinit var hostname: String
    private var loginText: String? = null
    private val windowInsetsController by lazy { WindowCompat.getInsetsController(requireActivity().window, web) }

    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private val getContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        pendingFileCallback?.onReceiveValue(if (uri == null) emptyArray() else arrayOf(uri))
        pendingFileCallback = null
    }
    private var pendingJson: String? = null
    private val createDocument = registerForActivityResult(CreateDynamicDocument()) { uri ->
        val json = pendingJson
        if (json != null && uri != null) {
            requireContext().contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(json) }
        }
        pendingJson = null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        Timber.d("Creating ReactMapFragment")
        val activeUrl = overrideUri?.toString() ?: app.activeUrl
        hostname = (overrideUri ?: Uri.parse(activeUrl)).host!!
        val activity = requireActivity()
        web = WebView(activity).apply {
            settings.apply {
                domStorageEnabled = true
                @SuppressLint("SetJavaScriptEnabled")
                javaScriptEnabled = true
            }
            glocation = Glocation(this, this@ReactMapFragment)
            siteController = SiteController(this@ReactMapFragment)
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
            val onBackPressedCallback = object : OnBackPressedCallback(false), DefaultLifecycleObserver {
                override fun handleOnBackPressed() = web.goBack()
                override fun onDestroy(owner: LifecycleOwner) = remove()
            }
            activity.onBackPressedDispatcher.addCallback(onBackPressedCallback)
            lifecycle.addObserver(onBackPressedCallback)
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
                    if (url.toUri().host != hostname) return
                    muiMargin.apply()
                    BackgroundLocationReceiver.setup()  // redo setup in case cookie is updated
                    ReactMapHttpEngine.updateCookie()
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val parsed = request.url
                    return when {
                        parsed.host?.lowercase(Locale.ENGLISH).let { it != hostname && it !in supportedHosts } -> {
                            app.launchUrl(view.context, parsed)
                            true
                        }
                        "http".equals(parsed.scheme, true) -> {
                            Snackbar.make(view, R.string.error_https_only, Snackbar.LENGTH_SHORT).show()
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
                        null -> null
                        else -> if (path.startsWith("/locales/") && path.endsWith("/translation.json")) {
                            handleTranslation(request)
                        } else if (vendorJsMatcher.matchEntire(path) != null) handleVendorJs(request) else null
                    }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError) {
                    if (!request.isForMainFrame) return
                    Snackbar.make(web, "${error.description} (${error.errorCode})", Snackbar.LENGTH_INDEFINITE).apply {
                        setAction(R.string.web_refresh) { web.reload() }
                    }.show()
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    if (detail.didCrash()) {
                        Timber.w(Exception("WebView crashed @ priority ${detail.rendererPriorityAtExit()}"))
                    } else if (isAdded) {
                        FirebaseAnalytics.getInstance(context).logEvent("webviewExit",
                            bundleOf("priority" to detail.rendererPriorityAtExit()))
                    }
                    // WebView cannot be reused but keep the process alive if possible. pretend to ask to recreate
                    if (isAdded) setFragmentResult("ReactMapFragment", Bundle())
                    return true
                }
            }
            setDownloadListener { url, _, contentDisposition, mimetype, _ ->
                if (!url.startsWith("data:", true)) {
                    Snackbar.make(web, context.getString(R.string.error_unsupported_download, url),
                        Snackbar.LENGTH_LONG).show()
                    return@setDownloadListener
                }
                pendingJson = URLDecoder.decode(url.substringAfter(','), "utf-8")
                createDocument.launch(mimetype to (filenameExtractor.find(contentDisposition)?.run {
                    groupValues[2].ifEmpty { groupValues[1] }
                } ?: "settings.json"))
            }
            loadUrl(activeUrl)
        }
        return CoordinatorLayout(activity).apply {
            addView(web, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    private fun buildResponse(request: WebResourceRequest, transform: (Reader) -> String) = try {
        val url = request.url.toString()
        val conn = ReactMapHttpEngine.connectWithCookie(url) { conn ->
            conn.requestMethod = request.method
            for ((key, value) in request.requestHeaders) conn.addRequestProperty(key, value)
        }
        val charset = if (conn.contentEncoding == null) Charsets.UTF_8 else {
            Charset.forName(conn.contentEncoding)
        }
        WebResourceResponse(conn.contentType?.substringBefore(';'), conn.contentEncoding, conn.responseCode,
            conn.responseMessage.let { if (it.isNullOrBlank()) "N/A" else it },
            conn.headerFields.mapValues { (_, value) -> value.joinToString() },
            if (conn.responseCode in 200..299) try {
                transform(conn.inputStream.bufferedReader(charset)).byteInputStream(charset)
            } catch (e: IOException) {
                Timber.d(e)
                conn.inputStream.bufferedReader(charset).readText().byteInputStream(charset)
            } else conn.findErrorStream.bufferedReader(charset).readText().byteInputStream(charset))
    } catch (e: IOException) {
        Timber.d(e)
        null
    } catch (e: IllegalArgumentException) {
        Timber.d(e)
        null
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
    private fun handleVendorJs(request: WebResourceRequest) = buildResponse(request) { reader ->
        val response = reader.readText()
        val matcher = mapHijacker.matcher(response)
        if (matcher.find()) (if (Build.VERSION.SDK_INT >= 34) StringBuilder().also {
            matcher.appendReplacement(it, "(window._hijackedMap=this)")
            matcher.appendTail(it)
        } else StringBuffer().also {
            matcher.appendReplacement(it, "(window._hijackedMap=this)")
            matcher.appendTail(it)
        }).toString() else {
            Timber.w(Exception("vendor.js unmatched"))
            response
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        web.destroy()
    }

    fun handleUri(uri: Uri?) = uri?.host?.let { host ->
        Timber.d("Handling URI $uri")
        if (view == null) overrideUri = uri else {
            if (host != hostname) {
                hostname = host
                return web.loadUrl(uri.toString())
            }
            val path = uri.path
            if (path.isNullOrEmpty() || path == "/") return@let
            val match = flyToMatcher.matchEntire(path) ?: return web.loadUrl(uri.toString())
            val script = StringBuilder(
                "window._hijackedMap.flyTo([${match.groupValues[1]}, ${match.groupValues[2]}]")
            match.groups[3]?.let { script.append(", ${it.value}") }
            script.append(')')
            web.evaluateJavascript(script.toString(), null)
        }
    }
    fun terminate() = web.destroy()
}
