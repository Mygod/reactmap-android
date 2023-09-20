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
import androidx.core.view.isGone
import be.mygod.reactmap.App.Companion.app
import be.mygod.reactmap.follower.BackgroundLocationReceiver
import be.mygod.reactmap.util.AlertDialogFragment
import be.mygod.reactmap.util.Empty
import be.mygod.reactmap.util.readableMessage
import kotlinx.parcelize.Parcelize

class ConfigDialogFragment : AlertDialogFragment<Empty, ConfigDialogFragment.Ret>() {
    companion object {
        private const val KEY_HISTORY_URL = "url.history"
    }

    @Parcelize
    data class Ret(val hostname: String?) : Parcelable

    private lateinit var historyUrl: Set<String?>
    private lateinit var urlEdit: AutoCompleteTextView
    private lateinit var followerSwitch: Switch

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!it) followerSwitch.isChecked = false
    }

    override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
        historyUrl = app.pref.getStringSet(KEY_HISTORY_URL, null) ?: setOf(App.URL_DEFAULT)
        val context = requireContext()
        urlEdit = AutoCompleteTextView(context).apply {
            setAdapter(ArrayAdapter(context, android.R.layout.select_dialog_item, historyUrl.toTypedArray()))
            setText(app.activeUrl)
        }
        followerSwitch = Switch(context).apply {
            text = "Make alerts follow location in background\n(beware that you would be sharing your location with the map)"
            isChecked = BackgroundLocationReceiver.enabled
            isGone = true
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) requestPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
        setView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(urlEdit, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(followerSwitch, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        })
        setTitle("ReactMap URL:")
        setMessage("You can return to this dialog later by clicking on the notification.")
        setPositiveButton(android.R.string.ok, listener)
        setNegativeButton(android.R.string.cancel, null)
    }

    override fun onResume() {
        super.onResume()
        val context = requireContext()
        followerSwitch.isGone = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
    }

    override val ret get() = Ret(try {
        val (uri, host) = urlEdit.text!!.toString().toUri().let {
            require("https".equals(it.scheme, true)) { "Only HTTPS is allowed" }
            it.toString() to it.host!!
        }
        val oldApiUrl = ReactMapHttpEngine.apiUrl
        app.pref.edit {
            putString(App.KEY_ACTIVE_URL, uri)
            putStringSet(KEY_HISTORY_URL, historyUrl + uri)
        }
        if (oldApiUrl != ReactMapHttpEngine.apiUrl) BackgroundLocationReceiver.onApiChanged()
        host
    } catch (e: Exception) {
        Toast.makeText(requireContext(), e.readableMessage, Toast.LENGTH_LONG).show()
        null
    }).also { BackgroundLocationReceiver.enabled = followerSwitch.isChecked }
}
