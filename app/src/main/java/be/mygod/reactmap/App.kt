package be.mygod.reactmap

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ext.SdkExtensions
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import com.google.firebase.crashlytics.FirebaseCrashlytics
import timber.log.Timber

class App : Application() {
    companion object {
        lateinit var app: App
    }

    override fun onCreate() {
        super.onCreate()
        app = this
        FirebaseCrashlytics.getInstance().apply {
            setCustomKey("build", Build.DISPLAY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setCustomKey("extension_s",
                SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S))
        }
        Timber.plant(object : Timber.DebugTree() {
            @SuppressLint("LogNotTimber")
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (t == null) {
                    if (priority != Log.DEBUG || BuildConfig.DEBUG) Log.println(priority, tag, message)
                    FirebaseCrashlytics.getInstance().log("${"XXVDIWEF".getOrElse(priority) { 'X' }}/$tag: $message")
                } else {
                    if (priority >= Log.WARN || priority == Log.DEBUG) {
                        Log.println(priority, tag, message)
                        Log.w(tag, message, t)
                    }
                    if (priority >= Log.INFO) FirebaseCrashlytics.getInstance().recordException(t)
                }
            }
        })
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
    }

    private val customTabsIntent by lazy {
        CustomTabsIntent.Builder().apply {
            setColorScheme(CustomTabsIntent.COLOR_SCHEME_SYSTEM)
            setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_LIGHT, CustomTabColorSchemeParams.Builder().apply {
                setToolbarColor(getColor(R.color.main_blue))
            }.build())
            setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_DARK, CustomTabColorSchemeParams.Builder().apply {
                setToolbarColor(getColor(R.color.main_blue))
            }.build())
        }.build()
    }
    fun launchUrl(context: Context, url: Uri) {
        try {
            return app.customTabsIntent.launchUrl(context, url)
        } catch (e: RuntimeException) {
            Timber.d(e)
        }
        Toast.makeText(context, url.toString(), Toast.LENGTH_LONG).show()
    }
}
