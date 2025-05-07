package be.mygod.reactmap.webkit

import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import be.mygod.reactmap.App.Companion.app
import be.mygod.reactmap.MainActivity
import be.mygod.reactmap.R
import be.mygod.reactmap.util.CreateDynamicDocument
import be.mygod.reactmap.util.readableMessage
import com.google.android.material.snackbar.Snackbar
import com.google.common.geometry.S2CellId
import com.google.common.geometry.S2LatLng
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener
import timber.log.Timber
import java.io.IOException
import java.net.URLDecoder

class ReactMapFragment : BaseReactMapFragment() {
    companion object {
        private const val HOST_APPLE_MAPS = "maps.apple.com"
        private val Uri.appleMapCoordinate get() = getQueryParameter("daddr") ?: getQueryParameter("coordinate")

        private val filenameExtractor = "filename=(\"([^\"]+)\"|[^;]+)".toRegex(RegexOption.IGNORE_CASE)
        private val flyToMatcher = "/@/([0-9.-]+)/([0-9.-]+)(?:/([0-9.-]+))?/?".toRegex()
    }

    private lateinit var siteController: SiteController
    private val mainActivity by lazy { activity as MainActivity }
    private val windowInsetsController by lazy { WindowCompat.getInsetsController(mainActivity.window, web) }

    private val requestLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        glocation.onPermissionResult(permissions.any { (_, v) -> v })
    }
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private val getContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        pendingFileCallback?.onReceiveValue(if (uri == null) emptyArray() else arrayOf(uri))
        pendingFileCallback = null
    }
    private var pendingJson: String? = null
    private val createDocument = registerForActivityResult(CreateDynamicDocument()) { uri ->
        val json = pendingJson
        if (json != null && uri != null) {
            requireContext().contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(json) }
        }
        pendingJson = null
    }

    override fun onShowFileChooser(filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams) {
        require(fileChooserParams.mode == FileChooserParams.MODE_OPEN)
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = filePathCallback
        getContent.launch(fileChooserParams.acceptTypes.single())
    }
    override fun onDownloadStart(url: String, userAgent: String?, contentDisposition: String, mimetype: String,
                                 contentLength: Long) {
        if (!url.startsWith("data:", true)) {
            Snackbar.make(web, requireContext().getString(R.string.error_unsupported_download, url),
                Snackbar.LENGTH_LONG).show()
            return
        }
        pendingJson = URLDecoder.decode(url.substringAfter(','), "utf-8")
        createDocument.launch(mimetype to (filenameExtractor.find(contentDisposition)?.run {
            groupValues[2].ifEmpty { groupValues[1] }
        } ?: "settings.json"))
    }
    override fun onReceiveTitle(title: String?) {
        siteController.title = title
    }
    override fun requestLocationPermissions() = null.also {
        requestLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private lateinit var onBackPressedCallback: OnBackPressedCallback
    override fun onHistoryUpdated() {
        onBackPressedCallback.isEnabled = web.canGoBack()
    }
    override fun onPageFinished() {
        mainActivity.pendingOverrideUri = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        siteController = SiteController(this)
        onBackPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = web.goBack()
        }
        mainActivity.onBackPressedDispatcher.addCallback(viewLifecycleOwner, onBackPressedCallback)
    }

    override fun findActiveUrl(): String {
        val overrideUrl = mainActivity.pendingOverrideUri
        if (HOST_APPLE_MAPS.equals(overrideUrl?.host, true)) {
            val daddr = overrideUrl?.appleMapCoordinate
            if (!daddr.isNullOrBlank()) {
                hostname = app.activeUrl.toUri().host!!
                return "https://$hostname/@/${daddr.replace(',', '/')}"
            }
        }
        return if (overrideUrl != null) {
            hostname = overrideUrl.host!!
            overrideUrl.toString()
        } else app.activeUrl.also { hostname = it.toUri().host!! }
    }

    override fun onConfigAvailable(config: JSONObject) {
        val tileServers = config.getJSONArray("tileServers")
        lifecycleScope.launch {
            web.evaluateJavascript("JSON.parse(localStorage.getItem('local-state')).state.settings.tileServers") {
                val name = JSONTokener(it).nextValue() as? String
                windowInsetsController.isAppearanceLightStatusBars =
                    (tileServers.length() - 1 downTo 0).asSequence().map { i -> tileServers.getJSONObject(i) }
                        .firstOrNull { obj -> obj.getString("name") == name }?.optString("style") != "dark"
            }
        }
    }

    override fun onRenderProcessGone() {
        mainActivity.currentFragment = null
        // WebView cannot be reused but keep the process alive if possible. pretend to ask to recreate
        if (isAdded) setFragmentResult("ReactMapFragment", Bundle())
    }

    private fun flyToUrl(destination: String, zoom: String? = null, urlOnFail: () -> String) {
        val script = StringBuilder("!!window._hijackedMap.flyTo([$destination]")
        zoom?.let { script.append(", $it") }
        script.append(')')
        web.evaluateJavascript(script.toString()) {
            if (it == true.toString()) mainActivity.pendingOverrideUri = null else {
                Timber.w(Exception(it))
                web.loadUrl(urlOnFail())
            }
        }
    }
    fun handleUri(uri: Uri?) = uri?.host?.let { host ->
        if (view == null || !loaded) return false
        if (HOST_APPLE_MAPS.equals(host, true)) {
            val daddr = uri.appleMapCoordinate
            if (daddr.isNullOrBlank()) return true
            flyToUrl(daddr) { "https://$hostname/@/${daddr.replace(',', '/')}" }
            return false
        }
        if (!host.equals(hostname, true)) {
            hostname = host
            web.loadUrl(uri.toString())
            return false
        }
        val path = uri.path
        if (path.isNullOrEmpty() || path == "/") return true
        val match = flyToMatcher.matchEntire(path)
        if (match == null) {
            web.loadUrl(uri.toString())
            return false
        }
        flyToUrl("${match.groupValues[1]}, ${match.groupValues[2]}", match.groups[3]?.value) { uri.toString() }
        false
    }

    fun accuWeather() = web.evaluateJavascript("window._hijackedMap.getCenter()") { evalResult ->
        val center = JSONObject(evalResult)
        val cell = S2CellId.fromLatLng(S2LatLng.fromDegrees(center.getDouble("lat"), center.getDouble("lng")))
            .parent(10).toLatLng()
        lifecycleScope.launch {
            try {
                AccuWeatherDialogFragment.newInstance(cell).show(parentFragmentManager, null)
            } catch (e: IOException) {
                Timber.d(e)
                Snackbar.make(web, e.readableMessage, Snackbar.LENGTH_LONG).show()
            } catch (e: Exception) {
                Timber.w(e)
                Snackbar.make(web, e.readableMessage, Snackbar.LENGTH_LONG).show()
            }
        }
    }
}
