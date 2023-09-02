package be.mygod.reactmap

import android.view.View
import android.webkit.WebView
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class ReactMapMuiMarginListener(private val web: WebView) : OnApplyWindowInsetsListener {
    init {
        ViewCompat.setOnApplyWindowInsetsListener(web, this)
    }

    private var topInset = 0
    private var bottomInset = 0

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat) = insets.apply {
        val tappable = insets.getInsets(WindowInsetsCompat.Type.tappableElement())
        topInset = tappable.top
        bottomInset = tappable.bottom
        apply()
    }

    fun apply() = web.evaluateJavascript("""
        if (!document._injectedMuiStackStyle) {
            document.head.appendChild(document._injectedMuiStackStyle = document.createElement('style'));
        }
        document._injectedMuiStackStyle.innerHTML =
            '.MuiDialog-root, .MuiStack-root, .MuiDrawer-paper>:first-child { margin-top: ' +
            $topInset / window.devicePixelRatio + 'px; }' +
            '.MuiDialog-root, .MuiDrawer-paper>:last-child { margin-bottom: ' +
            $bottomInset / window.devicePixelRatio + 'px; }' +
            '.leaflet-control-attribution { min-height: ' +
            Math.max(0, $bottomInset / window.devicePixelRatio - 10) + 'px; }';""", null)
}
