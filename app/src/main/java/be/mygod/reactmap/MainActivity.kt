package be.mygod.reactmap

import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import be.mygod.reactmap.App.Companion.app
import be.mygod.reactmap.util.AlertDialogFragment
import be.mygod.reactmap.util.Empty
import be.mygod.reactmap.util.UpdateChecker
import be.mygod.reactmap.util.readableMessage
import be.mygod.reactmap.webkit.ReactMapFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        handleIntent(intent)
        if (currentFragment == null) reactMapFragment(null)
        if (app.pref.getBoolean(KEY_WELCOME, true)) {
            startConfigure(true)
            app.pref.edit { putBoolean(KEY_WELCOME, false) }
        }
        AlertDialogFragment.setResultListener<ConfigDialogFragment, Empty>(this) { which, _ ->
            if (which != DialogInterface.BUTTON_POSITIVE) return@setResultListener
            currentFragment?.terminate()
            reactMapFragment(null)
        }
        supportFragmentManager.setFragmentResultListener("ReactMapFragment", this) { _, _ ->
            reactMapFragment(null)
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { UpdateChecker.check() } }
    }
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private var currentFragment: ReactMapFragment? = null
    private fun reactMapFragment(overrideUri: Uri?) = supportFragmentManager.commit {
        replace(R.id.content, ReactMapFragment(overrideUri).also { currentFragment = it })
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_CONFIGURE -> startConfigure(false)
            ACTION_RESTART_GAME -> AlertDialog.Builder(this).apply {
                setTitle("Pick game version to restart")
                setMessage("This feature requires root")
                setPositiveButton("Standard") { _, _ -> restartGame("com.nianticlabs.pokemongo") }
                setNegativeButton("Samsung") { _, _ -> restartGame("com.nianticlabs.pokemongo.ares") }
                setNeutralButton(android.R.string.cancel, null)
            }.show()
            Intent.ACTION_VIEW -> {
                val currentFragment = currentFragment
                if (currentFragment == null) reactMapFragment(intent.data) else currentFragment.handleUri(intent.data)
            }
        }
    }
    private fun startConfigure(welcome: Boolean) = ConfigDialogFragment().apply {
        arg(ConfigDialogFragment.Arg(welcome))
        key()
    }.show(supportFragmentManager, null)
    private fun restartGame(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val process = ProcessBuilder("su", "-c", "am force-stop $packageName").start()
                val exit = process.waitFor()
                if (exit != 0) Timber.w("su exited with $exit")
                startActivity(intent)
            } catch (e: Exception) {
                Timber.w(e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, e.readableMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
