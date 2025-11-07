package be.mygod.reactmap

import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.enableEdgeToEdge
import androidx.core.content.edit
import androidx.core.content.res.use
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import be.mygod.reactmap.App.Companion.app
import be.mygod.reactmap.util.AlertDialogFragment
import be.mygod.reactmap.util.Empty
import be.mygod.reactmap.util.UnblockCentral
import be.mygod.reactmap.util.readableMessage
import be.mygod.reactmap.webkit.ReactMapFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber

class MainActivity : FragmentActivity(), Shizuku.OnRequestPermissionResultListener {
    companion object {
        const val ACTION_CONFIGURE = "be.mygod.reactmap.action.CONFIGURE"
        const val ACTION_RESTART_GAME = "be.mygod.reactmap.action.RESTART_GAME"
        const val ACTION_ACCUWEATHER = "be.mygod.reactmap.action.ACCUWEATHER"
        private const val KEY_WELCOME = "welcome"
        private const val PACKAGE_POKEMON_GO = "com.nianticlabs.pokemongo"
        private const val PACKAGE_POKEMON_GO_ARES = "com.nianticlabs.pokemongo.ares"

        private val forceStopPackage by lazy {
            Class.forName("android.app.IActivityManager").getDeclaredMethod(
                "forceStopPackage", String::class.java, Int::class.java)
        }
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
        Shizuku.addRequestPermissionResultListener(this)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                Shizuku.removeRequestPermissionResultListener(this@MainActivity)
            }
        })
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
            ACTION_ACCUWEATHER -> currentFragment?.accuWeather()
            ACTION_RESTART_GAME -> try {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    startRestart()
                } else Shizuku.requestPermission(0)
            } catch (_: IllegalStateException) {
                Snackbar.make(findViewById(R.id.content), R.string.restart_game_dialog_message,
                    Snackbar.LENGTH_LONG).show()
            }
            "be.mygod.reactmap.action.SPLITSCREEN" -> pendingSplitscreen = true
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
    private fun startRestart() = MaterialAlertDialogBuilder(this).apply {
        setTitle(R.string.restart_game_dialog_title)
        val switch = MaterialSwitch(this@MainActivity).apply {
            setText(R.string.pip_phone_enter_split)
            isChecked = isInMultiWindowMode
            val padding = context.obtainStyledAttributes(intArrayOf(
                androidx.appcompat.R.attr.dialogPreferredPadding)).use {
                it.getDimensionPixelOffset(0, 0)
            }
            setPadding(padding, 0, padding, 0)
        }
        setView(switch)
        setPositiveButton(R.string.restart_game_standard) { _, _ -> restartGame(PACKAGE_POKEMON_GO, switch.isChecked) }
        setNegativeButton(R.string.restart_game_samsung) { _, _ ->
            restartGame(PACKAGE_POKEMON_GO_ARES, switch.isChecked)
        }
        setNeutralButton(android.R.string.cancel, null)
    }.show()
    private var pendingSplitscreen = false
    override fun onEnterAnimationComplete() {
        if (!pendingSplitscreen) return
        startActivity(listOf(PACKAGE_POKEMON_GO, PACKAGE_POKEMON_GO_ARES)
            .mapNotNull(app.packageManager::getLaunchIntentForPackage).let { list ->
                when (list.size) {
                    0 -> Intent(Intent.ACTION_CHOOSER).putExtra(Intent.EXTRA_INTENT, Intent())
                    1 -> list.first()
                    else -> Intent(Intent.ACTION_CHOOSER).apply {
                        putExtra(Intent.EXTRA_INTENT, list.first())
                        putExtra(Intent.EXTRA_ALTERNATE_INTENTS, list.drop(1).toTypedArray())
                    }
                }
            }.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT))
        pendingSplitscreen = false
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        if (grantResult != PackageManager.PERMISSION_GRANTED) {
            Snackbar.make(findViewById(R.id.content), R.string.restart_game_dialog_message, Snackbar.LENGTH_LONG).show()
        } else startRestart()
    }

    private fun restartGame(packageName: String, splitScreen: Boolean) {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                forceStopPackage(UnblockCentral.shizukuActivity, packageName, app.userId)
                if (splitScreen) {
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
                    delay(1000) // wait a second for animations
                }
                startActivity(intent)
            } catch (e: Exception) {
                Timber.w(e)
                withContext(Dispatchers.Main) {
                    Snackbar.make(findViewById(android.R.id.content), e.readableMessage, Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }
}
