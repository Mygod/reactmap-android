package be.mygod.reactmap.auto

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import be.mygod.reactmap.R
import com.google.android.apps.auto.sdk.CarActivity
import com.google.android.apps.auto.sdk.SearchCallback
import com.google.android.apps.auto.sdk.SearchItem
import kotlinx.coroutines.launch
import org.json.JSONStringer

class CarKeyboard(private val web: WebView, private val lifecycleOwner: LifecycleOwner) : SearchCallback() {
    companion object {
        private val string by lazy {
            JSONStringer::class.java.getDeclaredMethod("string", String::class.java).apply { isAccessible = true }
        }
    }

    private val activity = (web.context as CarActivity).also {
        it.carUiController.searchController.setSearchCallback(this)
        web.addJavascriptInterface(this, "_autoKeyboard")
    }
    private val jsSetup = activity.resources.openRawResource(R.raw.setup_keyboard).bufferedReader().readText()
    fun setup() {
        isActive = false
        web.evaluateJavascript(jsSetup, null)
    }

    private var isActive = false

    @JavascriptInterface
    fun request(text: String?, hint: CharSequence?): Boolean {
        if (isActive) return false
        isActive = true
        lifecycleOwner.lifecycleScope.launch {
            activity.carUiController.searchController.setSearchHint(hint)
//        searchController.setSearchItems()
            activity.carUiController.searchController.startSearch(text)
        }
        return true
    }

    override fun onSearchTextChanged(searchTerm: String?) { }

    override fun onSearchSubmitted(searchTerm: String?) = true.also {
        isActive = false
        val value = searchTerm?.let {
            JSONStringer().apply { string(this, it) }.toString()
        }.toString()
        web.evaluateJavascript("window._autoKeyboardCallback.valueReady($value)", null)
    }

    override fun onSearchItemSelected(searchItem: SearchItem?) { }

    override fun onSearchStop() {
        if (!isActive) return
        isActive = false
        web.evaluateJavascript("window._autoKeyboardCallback.dismiss()", null)
    }
}
