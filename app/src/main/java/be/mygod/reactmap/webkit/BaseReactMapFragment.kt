package be.mygod.reactmap.webkit

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.JsonWriter
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.DownloadListener
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import be.mygod.reactmap.App.Companion.app
import be.mygod.reactmap.BuildConfig
import be.mygod.reactmap.R
import be.mygod.reactmap.follower.BackgroundLocationReceiver
import be.mygod.reactmap.util.UnblockCentral
import be.mygod.reactmap.util.findErrorStream
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.nio.charset.Charset
import java.util.Locale
import java.util.regex.Matcher

abstract class BaseReactMapFragment : Fragment(), DownloadListener {
    companion object {
        // https://github.com/rollup/rollup/blob/10bdaa325a94ca632ef052e929a3e256dc1c7ade/docs/configuration-options/index.md?plain=1#L876
        private val vendorJsMatcher = "/vendor-[0-9a-z_-]{8}\\.js".toRegex(RegexOption.IGNORE_CASE)

        /**
         * Raw regex: ([,}]\s*)this(?=\.callInitHooks\(\)[,;]\s*this\._zoomAnimated\s*=)
         *           if (options.center && options.zoom !== void 0) {
         *             this.setView(toLatLng(options.center), options.zoom, { reset: true });
         *           }
         *           this.callInitHooks();
         *           this._zoomAnimated = TRANSITION && any3d && !mobileOpera && this.options.zoomAnimation;
         * or match minimized fragment: ",this.callInitHooks(),this._zoomAnimated="
         */
        private val injectMapInitialize = "([,}]\\s*)this(?=\\.callInitHooks\\(\\)[,;]\\s*this\\._zoomAnimated\\s*=)"
            .toPattern()
        /**
         * Raw regex: ([;}]\s*this\._stop\(\);)(?=\s*var )
         *           if (options.animate === false || !any3d) {
         *             return this.setView(targetCenter, targetZoom, options);
         *           }
         *           this._stop();
         *           var from = this.project(this.getCenter()), to = this.project(targetCenter), size = this.getSize(), startZoom = this._zoom;
         * or match minimized fragment: ";this._stop();var "
         */
        private val injectMapFlyTo = "([;}]\\s*this\\._stop\\(\\);)(?=\\s*var )".toPattern()
        /**
         * Raw regex: (,\s*maxHeight:\s*null,\s*autoPan:\s*)(?:true|!0)(?=,\s*autoPanPaddingTopLeft:\s*null,)
         *           minWidth: 50,
         *           maxHeight: null,
         *           autoPan: true,
         *           autoPanPaddingTopLeft: null,
         *           autoPanPaddingBottomRight: null,
         * or match minimized fragment: ",maxHeight:null,autoPan:!0,autoPanPaddingTopLeft:null,"
         */
        private val injectPopupAutoPan = "(,\\s*maxHeight:\\s*null,\\s*autoPan:\\s*)(?:true|!0)(?=,\\s*autoPanPaddingTopLeft:\\s*null,)"
            .toPattern()
        /**
         * Raw regex: ([,;]\s*this\._map\.on\("locationfound",\s*this\._onLocationFound,\s*)(?=this\)[,;])
         *             this._active = true;
         *             this._map.on("locationfound", this._onLocationFound, this);
         *             this._map.on("locationerror", this._onLocationError, this);
         * or match minimized fragment: ",this._map.on("locationfound",this._onLocationFound,this),"
         */
        private val injectLocateControlActivate = "([,;]\\s*this\\._map\\.on\\(\"locationfound\",\\s*this\\._onLocationFound,\\s*)(?=this\\)[,;])"
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

        private val setInt by lazy { FileDescriptor::class.java.getDeclaredMethod("setInt$", Int::class.java) }
        @get:RequiresApi(29)
        private val os by lazy { Class.forName("libcore.io.Libcore").getDeclaredField("os").get(null) }
        private val nullFd by lazy { Os.open("/dev/null", OsConstants.O_RDONLY, 0) }
    }

    protected lateinit var web: WebView
    protected lateinit var glocation: Glocation
    private lateinit var postInterceptor: PostInterceptor
    protected lateinit var hostname: String

    private var loginText: String? = null
    protected var loaded = false
        private set

    protected abstract fun onShowFileChooser(filePathCallback: ValueCallback<Array<Uri>>?,
                                             fileChooserParams: FileChooserParams)
    protected abstract fun onReceiveTitle(title: String?)
    protected abstract fun onRenderProcessGone()
    abstract fun requestLocationPermissions(): Boolean?

    protected open fun onHistoryUpdated() { }
    protected open fun onPageStarted() { }
    protected open fun onPageFinished() { }
    protected open fun findActiveUrl() = app.activeUrl.also { hostname = it.toUri().host!! }
    protected open fun onConfigAvailable(config: JSONObject) { }
    protected open fun onUnsupportedUri(uri: Uri) = app.launchUrl(requireContext(), uri)
    protected open fun onAuthUri(url: Uri) = false
    protected open fun onJsAlert(message: String?, result: JsResult) = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        Timber.d("Creating ReactMapFragment")
        web = WebView(requireContext()).apply {
            settings.apply {
                domStorageEnabled = true
                @SuppressLint("SetJavaScriptEnabled")
                javaScriptEnabled = true
            }
            glocation = Glocation(this, this@BaseReactMapFragment)
            postInterceptor = PostInterceptor(this)
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

                override fun onReceivedTitle(view: WebView?, title: String?) = onReceiveTitle(title)
                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult) =
                    onJsAlert(message, result)

                override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>?,
                                               fileChooserParams: FileChooserParams) = true.also {
                    onShowFileChooser(filePathCallback, fileChooserParams)
                }
            }
            val muiMargin = ReactMapMuiMarginListener(this)
            webViewClient = object : WebViewClient() {
                override fun doUpdateVisitedHistory(view: WebView?, url: String, isReload: Boolean) {
                    onHistoryUpdated()
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
                    postInterceptor.clear()
                    val uri = url.toUri()
                    if (!BuildConfig.DEBUG && "http".equals(uri.scheme, true)) {
                        web.loadUrl(uri.buildUpon().scheme("https").build().toString())
                    }
                    if (uri.host == hostname) {
                        glocation.setupGeolocation()
                        postInterceptor.setup()
                    }
                    onPageStarted()
                }

                override fun onPageFinished(view: WebView?, url: String) {
                    onPageFinished()
                    if (url.toUri().host != hostname) return
                    muiMargin.apply()
                    BackgroundLocationReceiver.setup()  // redo setup in case cookie is updated
                    ReactMapHttpEngine.updateCookie()
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val parsed = request.url
                    if ("http".equals(parsed.scheme, true)) {
                        Snackbar.make(view, R.string.error_https_only, Snackbar.LENGTH_SHORT).show()
                        return !BuildConfig.DEBUG
                    }
                    val host = parsed.host?.lowercase(Locale.ENGLISH)
                    return when {
                        hostname.equals(host, true) -> false
                        host in supportedHosts -> onAuthUri(parsed)
                        else -> {
                            onUnsupportedUri(parsed)
                            true
                        }
                    }
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                    val path = request.url.path ?: return null
                    if (request.url.host == hostname) {
                        if (path == "/api/settings") return handleSettings(request)
                        if (path.startsWith("/locales/") && path.endsWith("/translation.json")) {
                            return handleTranslation(request)
                        }
                        if (vendorJsMatcher.matchEntire(path) != null) return handleVendorJs(request)
                        postInterceptor.extractBody(request)?.let { return handleGraphql(request, it) }
                    }
                    if (ReactMapHttpEngine.isCronet && (path.substringAfterLast('.').lowercase(Locale.ENGLISH)
                                in mediaExtensions || request.requestHeaders.any { (key, value) ->
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
                    if (detail.didCrash()) {
                        Timber.w(Exception("WebView crashed @ priority ${detail.rendererPriorityAtExit()}"))
                    } else if (isAdded) {
                        FirebaseAnalytics.getInstance(context).logEvent("webviewExit",
                            bundleOf("priority" to detail.rendererPriorityAtExit()))
                    }
                    onRenderProcessGone()
                    return true
                }
            }
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
            setDownloadListener(this@BaseReactMapFragment)
        }
        return web
    }

    override fun onResume() {
        super.onResume()
        if (loaded) return
        loaded = true
        web.loadUrl(findActiveUrl())
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
    }
    private fun handleSettings(request: WebResourceRequest) = buildResponse(request) { reader ->
        val response = reader.readText()
        try {
            val config = JSONObject(response)
            onConfigAvailable(config)
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
            matcher.usePattern(injectPopupAutoPan)
            if (!matcher.find()) {
                Timber.w(Exception("injectPopupAutoPan unmatched"))
                return@buildResponse response
            }
            replace("$1!1")
            matcher.usePattern(injectLocateControlActivate)
            if (!matcher.find()) {
                Timber.w(Exception("injectLocateControlActivate unmatched"))
                return@buildResponse response
            }
            replace("$1window._hijackedLocateControl=")
        }
    }
    private fun handleGraphql(request: WebResourceRequest, body: String) = try {
        val url = request.url.toString()
        val conn = ReactMapHttpEngine.connectWithCookie(url) { conn ->
            setupConnection(request, conn)
            ReactMapHttpEngine.writeCompressed(conn, body)
        }
        if (conn.responseCode == 302) {
            ReactMapHttpEngine.detectBrotliError(conn)?.let {
                lifecycleScope.launch { Snackbar.make(web, it, Snackbar.LENGTH_LONG).show() }
            }
            null
        } else createResponse(conn) { _ -> conn.findErrorStream }
    } catch (e: IOException) {
        Timber.d(e)
        null
    } catch (e: Exception) {
        Timber.w(e)
        null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        web.destroy()
    }

    fun terminate() {
        web.destroy()
        ReactMapHttpEngine.reset()
        try {
            for (file in File("/proc/self/fd").listFiles() ?: emptyArray()) try {
                val fdInt = file.name.toInt()
                val fd = FileDescriptor().apply { setInt(this, fdInt) }
                val endpoint = try {
                    Os.getsockname(fd)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EBADF || e.errno == OsConstants.ENOTSOCK) continue else throw e
                }
                if (endpoint !is InetSocketAddress) continue
                when (val type = UnblockCentral.getsockoptInt(null, fd, OsConstants.SOL_SOCKET, OsConstants.SO_TYPE)) {
                    OsConstants.SOCK_STREAM -> { }
                    OsConstants.SOCK_DGRAM -> continue
                    else -> {
                        Timber.w(Exception("Unknown $type to $endpoint"))
                        continue
                    }
                }
                val ownerTag = if (Build.VERSION.SDK_INT >= 29) try {
                    UnblockCentral.fdsanGetOwnerTag(os, fd) as Long
                } catch (e: Exception) {
                    Timber.w(e)
                    0
                } else 0
                Timber.d("Resetting $fdInt owned by $ownerTag if is 0 -> $endpoint")
                if (ownerTag == 0L) Os.dup2(nullFd, fdInt)
            } catch (e: Exception) {
                Timber.w(e)
            }
        } catch (e: IOException) {
            Timber.d(e)
        }
    }
}
