package be.mygod.reactmap

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import be.mygod.reactmap.App.Companion.app
import timber.log.Timber
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_CONFIGURE = "be.mygod.reactmap.action.CONFIGURE"
        private const val PREF_NAME = "reactmap"
        private const val KEY_ACTIVE_URL = "url.active"
        private const val KEY_HISTORY_URL = "url.history"
        private const val URL_DEFAULT = "https://www.reactmap.dev"

        private val filenameExtractor = "filename=(\"([^\"]+)\"|[^;]+)".toRegex(RegexOption.IGNORE_CASE)
        private val supportedHosts = setOf("discordapp.com", "discord.com")
    }

    private lateinit var web: WebView
    private lateinit var glocation: Glocation
    private lateinit var siteController: SiteController
    private lateinit var pref: SharedPreferences
    private lateinit var hostname: String
    private var isRoot = false

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
        WindowCompat.setDecorFitsSystemWindows(window, false)
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
                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    onBackPressedCallback.isEnabled = web.canGoBack()
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    glocation.clear()
                    isRoot = URL(url).run {
                        when {
                            host != hostname -> false
                            path == "/" -> {
                                glocation.setupGeolocation()
                                muiMargin.apply()
                                true
                            }
                            else -> {
                                if (path.startsWith("/@/")) {
                                    glocation.setupGeolocation()
                                    muiMargin.apply()
                                }
                                false
                            }
                        }
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val parsed = request.url
                    return when {
                        parsed.host?.lowercase(Locale.ROOT).let { it != hostname && it !in supportedHosts } -> {
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
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == ACTION_CONFIGURE) AlertDialog.Builder(this).apply {
            val historyUrl = pref.getStringSet(KEY_HISTORY_URL, null) ?: setOf(URL_DEFAULT)
            val editText = AutoCompleteTextView(this@MainActivity).apply {
                setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.select_dialog_item,
                    historyUrl.toTypedArray()))
                setText(pref.getString(KEY_ACTIVE_URL, URL_DEFAULT))
            }
            setView(editText)
            setTitle("ReactMap URL:")
            setPositiveButton(android.R.string.ok) { _, _ ->
                val (uri, host) = try {
                    editText.text!!.toString().toUri().run { toString() to host!! }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, e.localizedMessage ?: e.javaClass.name, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                pref.edit {
                    putString(KEY_ACTIVE_URL, uri)
                    putStringSet(KEY_HISTORY_URL, historyUrl + uri)
                }
                hostname = host
                web.loadUrl(uri)
            }
            setNegativeButton(android.R.string.cancel, null)
        }.show()
    }
}
