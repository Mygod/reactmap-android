package be.mygod.reactmap.webkit

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
        // https://medium.com/androiddevelopers/make-webviews-edge-to-edge-a6ef319adfac#339d
        val safeDrawing = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
        topInset = safeDrawing.top
        bottomInset = safeDrawing.bottom
        apply()
    }

    fun apply() = web.evaluateJavascript("""
        if (!document._injectedMuiStackStyle) {
            document.head.appendChild(document._injectedMuiStackStyle = document.createElement('style'));
        }
        document._injectedMuiStackStyle.innerHTML =
            '.MuiDialog-root, .leaflet-control-container, .MuiDrawer-paperAnchorLeft>:first-child { margin-top: ' +
            $topInset / window.devicePixelRatio + 'px; }' +
            '.MuiDialog-root, .MuiDrawer-paper>:last-child { margin-bottom: ' +
            $bottomInset / window.devicePixelRatio + 'px; }' +
            '.leaflet-control-attribution { min-height: ' +
            ($bottomInset / window.devicePixelRatio - 10) + 'px; }';""", null)
}
