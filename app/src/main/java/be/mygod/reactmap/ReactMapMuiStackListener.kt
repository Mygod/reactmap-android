package be.mygod.reactmap

import android.view.View
import android.webkit.WebView
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class ReactMapMuiStackListener(private val web: WebView) : OnApplyWindowInsetsListener {
    init {
        ViewCompat.setOnApplyWindowInsetsListener(web, this)
    }

    private var topInset = 0

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
        apply()
        return insets
    }

    fun apply() = web.evaluateJavascript("""
        var style = document.createElement('style');
        style.innerHTML = '.MuiStack-root { margin-top: ' + $topInset / window.devicePixelRatio + 'px}';
        document.head.appendChild(style);""", null)
}
