package be.mygod.reactmap.webkit

import android.util.LongSparseArray
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import be.mygod.reactmap.R
import com.google.common.hash.Hashing
import java.nio.charset.Charset

class PostInterceptor(private val web: WebView) {
    private val bodyLookup = LongSparseArray<String>().also {
        web.addJavascriptInterface(this, "_postInterceptor")
    }
    private val jsSetup = web.resources.openRawResource(R.raw.setup_interceptor).bufferedReader().readText()

    fun setup() = web.evaluateJavascript(jsSetup, null)

    @JavascriptInterface
    fun register(body: String): String {
        val key = Hashing.sipHash24().hashString(body, Charset.defaultCharset()).asLong()
        synchronized(bodyLookup) { bodyLookup.put(key, body) }
        return key.toULong().toString(36)
    }
    fun extractBody(request: WebResourceRequest) = request.requestHeaders.remove("Body-Digest")?.let { key ->
        synchronized(bodyLookup) {
            val index = bodyLookup.indexOfKey(key.toULong(36).toLong())
            if (index < 0) null else bodyLookup.valueAt(index).also { bodyLookup.removeAt(index) }
        }
    }
    fun clear() = synchronized(bodyLookup) { bodyLookup.clear() }
}
