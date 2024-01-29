package be.mygod.reactmap

import android.Manifest
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Parcelable
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import be.mygod.reactmap.App.Companion.app
import be.mygod.reactmap.follower.BackgroundLocationReceiver
import be.mygod.reactmap.util.AlertDialogFragment
import be.mygod.reactmap.util.Empty
import be.mygod.reactmap.util.readableMessage
import be.mygod.reactmap.webkit.ReactMapHttpEngine
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlinx.parcelize.Parcelize

class ConfigDialogFragment : AlertDialogFragment<ConfigDialogFragment.Arg, Empty>() {
    companion object {
        private const val KEY_HISTORY_URL = "url.history"
    }

    @Parcelize
    data class Arg(val welcome: Boolean) : Parcelable

    private lateinit var historyUrl: Set<String?>
    private lateinit var urlEdit: MaterialAutoCompleteTextView
    private lateinit var followerSwitch: SwitchCompat

    private val requestLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.any { (_, v) -> v }) {
            requestBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else missingLocationPermissions()
    }
    private val requestBackground = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) missingLocationPermissions()
    }
    private fun missingLocationPermissions() {
        Toast.makeText(requireContext(), R.string.error_missing_location_permission, Toast.LENGTH_LONG).show()
        followerSwitch.isChecked = false
    }

    override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
        setView(R.layout.dialog_config)
        setTitle(R.string.config_dialog_title)
        if (arg.welcome) setMessage(R.string.config_welcome)
        setPositiveButton(android.R.string.ok, listener)
        setNegativeButton(android.R.string.cancel, null)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?) = super.onCreateDialog(savedInstanceState).apply {
        create()
        historyUrl = app.pref.getStringSet(KEY_HISTORY_URL, null) ?: setOf("https://${BuildConfig.DEFAULT_DOMAIN}")
        val context = requireContext()
        urlEdit = findViewById(android.R.id.edit)!!
        urlEdit.setSimpleItems(historyUrl.toTypedArray())
        urlEdit.setText(app.activeUrl)
        followerSwitch = findViewById(android.R.id.switch_widget)!!
        followerSwitch.isChecked = BackgroundLocationReceiver.enabled && (context.checkSelfPermission(
            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED) &&
                context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        followerSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) requestLocation.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    override val ret get() = try {
        val uri = urlEdit.text!!.toString().toUri().let {
            require("https".equals(it.scheme, true)) { getText(R.string.error_https_only) }
            it.host!!
            it.toString()
        }
        val oldApiUrl = ReactMapHttpEngine.apiUrl
        app.pref.edit {
            putString(App.KEY_ACTIVE_URL, uri)
            putStringSet(KEY_HISTORY_URL, historyUrl + uri)
        }
        if (oldApiUrl != ReactMapHttpEngine.apiUrl) BackgroundLocationReceiver.onApiChanged()
        Empty()
    } catch (e: Exception) {
        Toast.makeText(requireContext(), e.readableMessage, Toast.LENGTH_LONG).show()
        null
    }.also { BackgroundLocationReceiver.enabled = followerSwitch.isChecked }
}
