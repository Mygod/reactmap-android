package be.mygod.reactmap

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

class MainActivity : ComponentActivity() {
    companion object {
        private const val HOSTNAME = "www.reactmap.dev"

        private val filenameExtractor = "filename=(\"([^\"]+)\"|[^;]+)".toRegex(RegexOption.IGNORE_CASE)
        private val supportedHosts = setOf(HOSTNAME, "discordapp.com", "discord.com")
    }

    private lateinit var web: WebView
    private lateinit var glocation: Glocation
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
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        web = WebView(this).apply {
            settings.apply {
                domStorageEnabled = true
                @SuppressLint("SetJavaScriptEnabled")
                javaScriptEnabled = true
            }
            glocation = Glocation(this)
            webChromeClient = object : WebChromeClient() {
                @Suppress("KotlinConstantConditions")
                override fun onConsoleMessage(consoleMessage: ConsoleMessage) = consoleMessage.run {
                    Log.println(when (messageLevel()) {
                        ConsoleMessage.MessageLevel.TIP -> Log.INFO
                        ConsoleMessage.MessageLevel.LOG -> Log.VERBOSE
                        ConsoleMessage.MessageLevel.WARNING -> Log.WARN
                        ConsoleMessage.MessageLevel.ERROR -> Log.ERROR
                        ConsoleMessage.MessageLevel.DEBUG -> Log.DEBUG
                        else -> error(messageLevel())
                    }, "WebConsole", "${sourceId()}:${lineNumber()} - ${message()}")
                    true
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
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    glocation.clear()
                    isRoot = URL(url).run {
                        when {
                            host != HOSTNAME -> false
                            path == "/" -> {
                                glocation.setupGeolocation()
                                true
                            }
                            else -> {
                                if (path.startsWith("/@/")) glocation.setupGeolocation()
                                false
                            }
                        }
                    }
                }

                @Deprecated("Deprecated in API level 24")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    val parsed = Uri.parse(url)
                    return when {
                        parsed.host?.lowercase(Locale.ROOT) !in supportedHosts -> {
                            startActivity(Intent(Intent.ACTION_VIEW, parsed))
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
            loadUrl("https://www.reactmap.dev")
        }
        setContentView(web)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onBackPressed() = if (web.canGoBack()) web.goBack() else super.onBackPressed()
}
