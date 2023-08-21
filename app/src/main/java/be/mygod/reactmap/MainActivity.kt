package be.mygod.reactmap

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.app.ComponentActivity
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

class MainActivity : ComponentActivity() {
    companion object {
        private const val REQUEST_LOCATION = 100
        private const val REQUEST_FILE_PICKER = 200
        private const val REQUEST_FILE_SAVE = 201
        private const val HOSTNAME = "www.reactmap.dev"

        private val filenameExtractor = "filename=(\"([^\"]+)\"|[^;]+)".toRegex(RegexOption.IGNORE_CASE)
        private val supportedHosts = setOf(HOSTNAME, "discordapp.com", "discord.com")
    }

    private lateinit var web: WebView
    private lateinit var glocation: Glocation
    private var isRoot = false
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingJson: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        web = WebView(this).apply {
            settings.apply {
                domStorageEnabled = true
                @SuppressLint("SetJavaScriptEnabled")
                javaScriptEnabled = true
            }
            glocation = Glocation(this, REQUEST_LOCATION)
            webChromeClient = object : WebChromeClient() {
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
                    check(fileChooserParams.mode == FileChooserParams.MODE_OPEN ||
                            fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                    pendingFileCallback?.onReceiveValue(null)
                    pendingFileCallback = filePathCallback
                    startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                            fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
//                        putExtra(Intent.EXTRA_MIME_TYPES, fileChooserParams.acceptTypes)
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }, REQUEST_FILE_PICKER)
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
                check(url.startsWith("data:", true))
                pendingJson = URLDecoder.decode(url.split(',', limit = 2)[1], "utf-8")
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = mimetype
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_TITLE, filenameExtractor.find(contentDisposition)?.run {
                        groupValues[2].run { if (isEmpty()) groupValues[1] else this }
                    } ?: "settings.json")
                }, REQUEST_FILE_SAVE)
            }
            loadUrl("https://www.reactmap.dev")
        }
        setContentView(web)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onBackPressed() = if (web.canGoBack()) web.goBack() else super.onBackPressed()

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) = when (requestCode) {
        REQUEST_FILE_PICKER -> {
            pendingFileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            pendingFileCallback = null
        }
        REQUEST_FILE_SAVE -> {
            val json = pendingJson
            if (json != null && resultCode == RESULT_OK) {
                contentResolver.openOutputStream(data?.data!!)!!.bufferedWriter().use { it.write(json) }
            }
            pendingJson = null
        }
        else -> super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_LOCATION -> glocation.onRequestPermissionsResult(grantResults.all {
                it == PackageManager.PERMISSION_GRANTED
            })
            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }
}
