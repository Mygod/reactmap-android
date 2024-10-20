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
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import be.mygod.reactmap.App.Companion.app
import be.mygod.reactmap.MainActivity
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
import java.io.InputStream
import java.io.Reader
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Locale
import java.util.regex.Matcher

class ReactMapFragment : Fragment() {
    companion object {
        private const val HOST_APPLE_MAPS = "maps.apple.com"
        private const val DADDR_APPLE_MAPS = "daddr"

        private val filenameExtractor = "filename=(\"([^\"]+)\"|[^;]+)".toRegex(RegexOption.IGNORE_CASE)
        // https://github.com/rollup/rollup/blob/10bdaa325a94ca632ef052e929a3e256dc1c7ade/docs/configuration-options/index.md?plain=1#L876
        private val vendorJsMatcher = "/vendor-[0-9a-z_-]{8}\\.js".toRegex(RegexOption.IGNORE_CASE)
        private val flyToMatcher = "/@/([0-9.-]+)/([0-9.-]+)(?:/([0-9.-]+))?/?".toRegex()

        /**
         * Raw regex: ([,}][\n\r\s]*)this(?=\.callInitHooks\(\)[,;][\n\r\s]*this\._zoomAnimated\s*=)
         *           if (options.center && options.zoom !== void 0) {
         *             this.setView(toLatLng(options.center), options.zoom, { reset: true });
         *           }
         *           this.callInitHooks();
         *           this._zoomAnimated = TRANSITION && any3d && !mobileOpera && this.options.zoomAnimation;
         * or match minimized fragment: ",this.callInitHooks(),this._zoomAnimated="
         */
        private val injectMapInitialize = "([,}][\\n\\r\\s]*)this(?=\\.callInitHooks\\(\\)[,;][\\n\\r\\s]*this\\._zoomAnimated\\s*=)"
            .toPattern()
        /**
         * Raw regex: ([;}][\n\r\s]*this\._stop\(\);)(?=[\n\r\s]*var )
         *           if (options.animate === false || !any3d) {
         *             return this.setView(targetCenter, targetZoom, options);
         *           }
         *           this._stop();
         *           var from = this.project(this.getCenter()), to = this.project(targetCenter), size = this.getSize(), startZoom = this._zoom;
         * or match minimized fragment: ";this._stop();var "
         */
        private val injectMapFlyTo = "([;}][\\n\\r\\s]*this\\._stop\\(\\);)(?=[\\n\\r\\s]*var )".toPattern()
        /**
         * Raw regex: ([,;][\n\r\s]*this\._map\.on\("locationfound",\s*this\._onLocationFound,\s*)(?=this\)[,;])
         *             this._active = true;
         *             this._map.on("locationfound", this._onLocationFound, this);
         *             this._map.on("locationerror", this._onLocationError, this);
         * or match minimized fragment: ",this._map.on("locationfound",this._onLocationFound,this),"
         */
        private val injectLocateControlActivate = "([,;][\\n\\r\\s]*this\\._map\\.on\\(\"locationfound\",\\s*this\\._onLocationFound,\\s*)(?=this\\)[,;])"
            .toPattern()

        private val supportedHosts = setOf("discordapp.com", "discord.com", "telegram.org", "oauth.telegram.org")
        private val mediaExtensions = setOf(
            "apng", "png", "avif", "gif", "jpg", "jpeg", "jfif", "pjpeg", "pjp", "png", "svg", "webp", "bmp", "ico", "cur",
            "wav", "mp3", "mp4", "aac", "ogg", "flac",
            "css", "js",
            "ttf", "otf", "woff", "woff2",
        )
        private val mediaAcceptMatcher = "image/.*|text/css(?:[,;].*)?".toRegex(RegexOption.IGNORE_CASE)

        private val newWebResourceResponse by lazy {
            WebResourceResponse::class.java.getDeclaredConstructor(Boolean::class.java, String::class.java,
                String::class.java, Int::class.java, String::class.java, Map::class.java, InputStream::class.java)
        }
    }

    private lateinit var web: WebView
    private lateinit var glocation: Glocation
    private lateinit var siteController: SiteController
    private lateinit var hostname: String
    private var loginText: String? = null
    private val mainActivity by lazy { activity as MainActivity }
    private val windowInsetsController by lazy { WindowCompat.getInsetsController(mainActivity.window, web) }

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
    private var loaded = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        Timber.d("Creating ReactMapFragment")
        web = WebView(mainActivity).apply {
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
            val onBackPressedCallback = object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() = web.goBack()
            }
            mainActivity.onBackPressedDispatcher.addCallback(viewLifecycleOwner, onBackPressedCallback)
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
                    mainActivity.pendingOverrideUri = null
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

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                    val path = request.url.path ?: return null
                    if ("https".equals(request.url.scheme, true) && request.url.host == hostname) {
                        if (path == "/api/settings") return handleSettings(request)
                        if (path.startsWith("/locales/") && path.endsWith("/translation.json")) {
                            return handleTranslation(request)
                        }
                        if (vendorJsMatcher.matchEntire(path) != null) return handleVendorJs(request)
                    }
                    if (ReactMapHttpEngine.isCronet && (path.substringAfterLast('.')
                        .lowercase(Locale.ENGLISH) in mediaExtensions || request.requestHeaders.any { (key, value) ->
                            "Accept".equals(key, true) && mediaAcceptMatcher.matches(value)
                        })) try {
                        val conn = ReactMapHttpEngine.engine.openConnection(URL(
                            request.url.toString())) as HttpURLConnection
                        setupConnection(request, conn)
                        return createResponse(conn) { conn.findErrorStream }
                    } catch (e: IOException) {
                        Timber.d(e)
                    }
                    return null
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError) {
                    if (!request.isForMainFrame) return
                    Snackbar.make(web, "${error.description} (${error.errorCode})", Snackbar.LENGTH_INDEFINITE).apply {
                        setAction(R.string.web_refresh) { web.reload() }
                    }.show()
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    mainActivity.currentFragment = null
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
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
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
        }
        return web
    }

    override fun onResume() {
        super.onResume()
        if (loaded) return
        loaded = true
        val overrideUrl = mainActivity.pendingOverrideUri
        val activeUrl = (activeUrl@{
            if (HOST_APPLE_MAPS.equals(overrideUrl?.host, true)) {
                val daddr = overrideUrl?.getQueryParameter(DADDR_APPLE_MAPS)
                if (!daddr.isNullOrBlank()) {
                    hostname = Uri.parse(app.activeUrl).host!!
                    return@activeUrl "https://$hostname/@/${daddr.replace(',', '/')}"
                }
            }
            if (overrideUrl != null) {
                hostname = overrideUrl.host!!
                overrideUrl.toString()
            } else app.activeUrl.also { hostname = Uri.parse(it).host!! }
        })()
        web.loadUrl(activeUrl)
    }

    private fun setupConnection(request: WebResourceRequest, conn: HttpURLConnection) {
        conn.requestMethod = request.method
        for ((key, value) in request.requestHeaders) conn.addRequestProperty(key, value)
    }
    private fun createResponse(conn: HttpURLConnection, data: (Charset) -> InputStream): WebResourceResponse {
        val charset = if (conn.contentEncoding == null) Charsets.UTF_8 else {
            Charset.forName(conn.contentEncoding)
        }
        return newWebResourceResponse.newInstance(false, conn.contentType?.substringBefore(';'), conn.contentEncoding,
            conn.responseCode, conn.responseMessage, conn.headerFields.mapValues { (_, value) -> value.joinToString() },
            data(charset))
    }
    private fun buildResponse(request: WebResourceRequest, transform: (Reader) -> String) = try {
        val url = request.url.toString()
        val conn = ReactMapHttpEngine.connectWithCookie(url) { conn -> setupConnection(request, conn) }
        createResponse(conn) { charset -> if (conn.responseCode in 200..299) try {
            transform(conn.inputStream.bufferedReader(charset)).byteInputStream(charset)
        } catch (e: IOException) {
            Timber.d(e)
            conn.inputStream.bufferedReader(charset).readText().byteInputStream(charset)
        } else conn.findErrorStream.bufferedReader(charset).readText().byteInputStream(charset) }
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
    private inline fun buildString(matcher: Matcher, work: ((String) -> Unit) -> Unit) = (if (Build.VERSION.SDK_INT >=
        34) StringBuilder().also { s ->
            work { matcher.appendReplacement(s, it) }
            matcher.appendTail(s)
        } else StringBuffer().also { s ->
            work { matcher.appendReplacement(s, it) }
            matcher.appendTail(s)
        }).toString()
    private fun handleVendorJs(request: WebResourceRequest) = buildResponse(request) { reader ->
        val response = reader.readText()
        val matcher = injectMapInitialize.matcher(response)
        if (!matcher.find()) {
            Timber.w(Exception("injectMapInitialize unmatched"))
            return@buildResponse response
        }
        buildString(matcher) { replace ->
            replace("$1(window._hijackedMap=this)")
            matcher.usePattern(injectMapFlyTo)
            if (!matcher.find()) {
                Timber.w(Exception("injectMapFlyTo unmatched"))
                return@buildResponse response
            }
            replace("$1window._hijackedLocateControl&&(window._hijackedLocateControl._userPanned=!0);")
            matcher.usePattern(injectLocateControlActivate)
            if (!matcher.find()) {
                Timber.w(Exception("injectLocateControlActivate unmatched"))
                return@buildResponse response
            }
            replace("$1window._hijackedLocateControl=")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        web.destroy()
    }

    private fun flyToUrl(destination: String, zoom: String? = null, urlOnFail: () -> String) {
        val script = StringBuilder("!!window._hijackedMap.flyTo([$destination]")
        zoom?.let { script.append(", $it") }
        script.append(')')
        web.evaluateJavascript(script.toString()) {
            if (it == true.toString()) mainActivity.pendingOverrideUri = null else {
                Timber.w(Exception(it))
                web.loadUrl(urlOnFail())
            }
        }
    }
    fun handleUri(uri: Uri?) = uri?.host?.let { host ->
        if (view == null || !loaded) return false
        if (HOST_APPLE_MAPS.equals(host, true)) {
            val daddr = uri.getQueryParameter(DADDR_APPLE_MAPS)
            if (daddr.isNullOrBlank()) return true
            flyToUrl(daddr) { "https://$hostname/@/${daddr.replace(',', '/')}" }
            return false
        }
        if (!host.equals(hostname, true)) {
            hostname = host
            web.loadUrl(uri.toString())
            return false
        }
        val path = uri.path
        if (path.isNullOrEmpty() || path == "/") return true
        val match = flyToMatcher.matchEntire(path)
        if (match == null) {
            web.loadUrl(uri.toString())
            return false
        }
        flyToUrl("${match.groupValues[1]}, ${match.groupValues[2]}", match.groups[3]?.value) { uri.toString() }
        false
    }
    fun terminate() = web.destroy()
}
