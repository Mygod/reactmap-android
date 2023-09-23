package be.mygod.reactmap

import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commit
import be.mygod.reactmap.App.Companion.app
import be.mygod.reactmap.util.AlertDialogFragment
import be.mygod.reactmap.util.readableMessage
import be.mygod.reactmap.webkit.ReactMapFragment
import timber.log.Timber

class MainActivity : FragmentActivity() {
    companion object {
        const val ACTION_CONFIGURE = "be.mygod.reactmap.action.CONFIGURE"
        const val ACTION_RESTART_GAME = "be.mygod.reactmap.action.RESTART_GAME"
        private const val KEY_WELCOME = "welcome"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        setContentView(R.layout.layout_main)
        reactMapFragment()
        if (app.pref.getBoolean(KEY_WELCOME, true)) {
            startConfigure(true)
            app.pref.edit { putBoolean(KEY_WELCOME, false) }
        }
        AlertDialogFragment.setResultListener<ConfigDialogFragment, ConfigDialogFragment.Ret>(this) { which, _ ->
            if (which == DialogInterface.BUTTON_POSITIVE && currentFragment?.terminate() != true) reactMapFragment()
        }
        supportFragmentManager.setFragmentResultListener("ReactMapFragment", this) { _, _ ->
            reactMapFragment()
        }
    }
    private var currentFragment: ReactMapFragment? = null
    private fun reactMapFragment() = supportFragmentManager.commit {
        replace(R.id.content, ReactMapFragment().also { currentFragment = it })
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        when (intent?.action) {
            ACTION_CONFIGURE -> startConfigure(false)
            ACTION_RESTART_GAME -> AlertDialog.Builder(this).apply {
                setTitle("Pick game version to restart")
                setMessage("This feature requires root")
                setPositiveButton("Standard") { _, _ -> restartGame("com.nianticlabs.pokemongo") }
                setNegativeButton("Samsung") { _, _ -> restartGame("com.nianticlabs.pokemongo.ares") }
                setNeutralButton(android.R.string.cancel, null)
            }.show()
        }
    }
    private fun startConfigure(welcome: Boolean) = ConfigDialogFragment().apply {
        arg(ConfigDialogFragment.Arg(welcome))
        key()
    }.show(supportFragmentManager, null)
    private fun restartGame(packageName: String) {
        try {
            ProcessBuilder("su", "-c", "am force-stop $packageName &&" +
                    "am start -n $packageName/com.nianticproject.holoholo.libholoholo.unity.UnityMainActivity").start()
        } catch (e: Exception) {
            Timber.w(e)
            Toast.makeText(this@MainActivity, e.readableMessage, Toast.LENGTH_LONG).show()
        }
    }
}
