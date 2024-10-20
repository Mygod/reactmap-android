package be.mygod.reactmap

import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.content.res.use
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
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException

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
        if (savedInstanceState == null) {
            handleIntent(intent)
            reactMapFragment()
            if (app.pref.getBoolean(KEY_WELCOME, true)) {
                startConfigure(true)
                app.pref.edit { putBoolean(KEY_WELCOME, false) }
            }
        } else currentFragment = supportFragmentManager.findFragmentById(R.id.content) as ReactMapFragment?
        AlertDialogFragment.setResultListener<ConfigDialogFragment, Empty>(this) { which, _ ->
            if (which != DialogInterface.BUTTON_POSITIVE) return@setResultListener
            currentFragment?.terminate()
            reactMapFragment()
        }
        supportFragmentManager.setFragmentResultListener("ReactMapFragment", this) { _, _ ->
            reactMapFragment()
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { UpdateChecker.check() } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    var currentFragment: ReactMapFragment? = null
    var pendingOverrideUri: Uri? = null
    private fun reactMapFragment() = supportFragmentManager.commit {
        replace(R.id.content, ReactMapFragment().also { currentFragment = it })
    }

    private fun handleIntent(intent: Intent?) {
        setIntent(null)
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY ==
            Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) return
        when (intent.action) {
            ACTION_CONFIGURE -> startConfigure(false)
            ACTION_RESTART_GAME -> AlertDialog.Builder(this).apply {
                setTitle(R.string.restart_game_dialog_title)
                setMessage(R.string.restart_game_dialog_message)
                val switch = MaterialSwitch(this@MainActivity).apply {
                    setText(R.string.pip_phone_enter_split)
                    isChecked = isInMultiWindowMode
                    val padding = context.obtainStyledAttributes(intArrayOf(
                        com.google.android.material.R.attr.dialogPreferredPadding)).use {
                        it.getDimensionPixelOffset(0, 0)
                    }
                    setPadding(padding, 0, padding, 0)
                }
                setView(switch)
                setPositiveButton(R.string.restart_game_standard) { _, _ ->
                    restartGame("com.nianticlabs.pokemongo", switch.isChecked)
                }
                setNegativeButton(R.string.restart_game_samsung) { _, _ ->
                    restartGame("com.nianticlabs.pokemongo.ares", switch.isChecked)
                }
                setNeutralButton(android.R.string.cancel, null)
            }.show()
            Intent.ACTION_VIEW -> {
                Timber.d("Handling URI ${intent.data}")
                if (currentFragment?.handleUri(intent.data) != true) pendingOverrideUri = intent.data
            }
        }
    }
    private fun startConfigure(welcome: Boolean) = ConfigDialogFragment().apply {
        arg(ConfigDialogFragment.Arg(welcome))
        key()
    }.show(supportFragmentManager, null)
    private fun restartGame(packageName: String, splitScreen: Boolean) {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val process = ProcessBuilder("su", "-c", "am force-stop $packageName").start()
                val exit = process.waitFor()
                if (exit != 0) Timber.w("su exited with $exit")
                if (splitScreen) {
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
                    delay(1000) // wait a second for animations
                }
                startActivity(intent)
            } catch (e: Exception) {
                if (e is IOException) Timber.d(e) else Timber.w(e)
                withContext(Dispatchers.Main) {
                    Snackbar.make(findViewById(android.R.id.content), e.readableMessage, Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }
}
