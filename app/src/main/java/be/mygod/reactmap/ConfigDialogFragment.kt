package be.mygod.reactmap

import android.Manifest
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.os.Parcelable
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.net.toUri
import be.mygod.reactmap.App.Companion.app
import be.mygod.reactmap.follower.BackgroundLocationReceiver
import be.mygod.reactmap.util.AlertDialogFragment
import be.mygod.reactmap.util.Empty
import be.mygod.reactmap.util.readableMessage
import be.mygod.reactmap.webkit.ReactMapHttpEngine
import kotlinx.parcelize.Parcelize

class ConfigDialogFragment : AlertDialogFragment<ConfigDialogFragment.Arg, Empty>() {
    companion object {
        private const val KEY_HISTORY_URL = "url.history"
    }

    @Parcelize
    data class Arg(val welcome: Boolean) : Parcelable

    private lateinit var historyUrl: Set<String?>
    private lateinit var urlEdit: AutoCompleteTextView
    private lateinit var followerSwitch: Switch

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
        historyUrl = app.pref.getStringSet(KEY_HISTORY_URL, null) ?: setOf("https://${BuildConfig.DEFAULT_DOMAIN}")
        val context = requireContext()
        urlEdit = AutoCompleteTextView(context).apply {
            isFocusedByDefault = true
            setAdapter(ArrayAdapter(context, android.R.layout.select_dialog_item, historyUrl.toTypedArray()))
            setText(app.activeUrl)
        }
        followerSwitch = Switch(context).apply {
            text = context.getText(R.string.config_switch_webhook_follow_location)
            isChecked = BackgroundLocationReceiver.enabled && (context.checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED) &&
                    context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) requestLocation.launch(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
        setView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(urlEdit, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(followerSwitch, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        })
        setTitle(R.string.config_dialog_title)
        if (arg.welcome) setMessage(R.string.config_welcome)
        setPositiveButton(android.R.string.ok, listener)
        setNegativeButton(android.R.string.cancel, null)
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
