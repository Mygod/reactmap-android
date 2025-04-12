package be.mygod.reactmap.auto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.core.net.toUri
import be.mygod.reactmap.App.Companion.app
import be.mygod.reactmap.R
import be.mygod.reactmap.webkit.BaseReactMapFragment
import com.google.android.material.snackbar.Snackbar
import timber.log.Timber

class CarReactMapFragment : BaseReactMapFragment() {
    private val mainActivity by lazy { context as MainCarActivity }
    private lateinit var carKeyboard: CarKeyboard
    private lateinit var siteController: CarSiteController

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        carKeyboard = CarKeyboard(web, this)
        siteController = CarSiteController(this)
    }

    override fun onPageStarted() = carKeyboard.setup()

    override fun onShowFileChooser(filePathCallback: ValueCallback<Array<Uri>>?,
                                   fileChooserParams: WebChromeClient.FileChooserParams) {
        Snackbar.make(web, R.string.car_toast_complicated_action, Snackbar.LENGTH_SHORT).show()
        mainActivity.killMap()
    }
    override fun onDownloadStart(url: String?, userAgent: String?, contentDisposition: String?, mimetype: String?,
                                 contentLength: Long) =
        Snackbar.make(web, R.string.car_toast_complicated_action, Snackbar.LENGTH_SHORT).show()

    override fun onReceiveTitle(title: String?) {
        siteController.title = title
    }

    override fun onRenderProcessGone() = mainActivity.killMap()

    override fun requestLocationPermissions() = requireContext().checkSelfPermission(
        Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onUnsupportedUri(uri: Uri) {
        val s = uri.toString()
        if (s.startsWith("https://maps.google.com/maps/place/")) {
            return mainActivity.startCarActivity(Intent(Intent.ACTION_VIEW,
                "google.navigation:q=${s.substring(35)}".toUri()))
        }
        // support ACTION_DIAL tel:URL or starting other car apps?
        try {
            app.startActivity(Intent(app.customTabsIntent.intent).apply {
                data = uri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, app.customTabsIntent.startAnimationBundle)
        } catch (e: RuntimeException) {
            Timber.d(e)
            return Snackbar.make(web, s, Snackbar.LENGTH_SHORT).show()
        }
        Snackbar.make(web, R.string.car_toast_unsupported_url, Snackbar.LENGTH_SHORT).show()
    }

    override fun onJsAlert(message: String?, result: JsResult) = true.also {
        Snackbar.make(web, message.toString(), Snackbar.LENGTH_INDEFINITE).apply {
            addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) = result.cancel()
            })
        }.show()
    }
}
