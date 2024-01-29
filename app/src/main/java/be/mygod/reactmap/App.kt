package be.mygod.reactmap

import android.annotation.SuppressLint
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.UserManager
import android.os.ext.SdkExtensions
import android.util.Log
import android.widget.Toast
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.getSystemService
import androidx.work.WorkManager
import be.mygod.reactmap.follower.BackgroundLocationReceiver
import be.mygod.reactmap.follower.LocationSetter
import be.mygod.reactmap.util.DeviceStorageApp
import be.mygod.reactmap.util.UpdateChecker
import be.mygod.reactmap.webkit.SiteController
import com.google.android.gms.location.LocationServices
import com.google.android.material.color.DynamicColors
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.DEBUG_PROPERTY_NAME
import kotlinx.coroutines.DEBUG_PROPERTY_VALUE_ON
import timber.log.Timber

class App : Application() {
    companion object {
        private const val PREF_NAME = "reactmap"
        const val KEY_ACTIVE_URL = "url.active"

        lateinit var app: App
    }

    lateinit var deviceStorage: Application
    lateinit var work: WorkManager
    val pref by lazy { deviceStorage.getSharedPreferences(PREF_NAME, MODE_PRIVATE) }
    val fusedLocation by lazy { LocationServices.getFusedLocationProviderClient(deviceStorage) }
    val nm by lazy { getSystemService<NotificationManager>()!! }
    val userManager by lazy { getSystemService<UserManager>()!! }

    val activeUrl get() = pref.getString(KEY_ACTIVE_URL, null) ?: "https://${BuildConfig.DEFAULT_DOMAIN}"

    override fun onCreate() {
        super.onCreate()
        app = this
        deviceStorage = DeviceStorageApp(this)
        deviceStorage.moveSharedPreferencesFrom(this, PREF_NAME)
        // overhead of debug mode is minimal: https://github.com/Kotlin/kotlinx.coroutines/blob/f528898/docs/debugging.md#debug-mode
        System.setProperty(DEBUG_PROPERTY_NAME, DEBUG_PROPERTY_VALUE_ON)
        FirebaseApp.initializeApp(deviceStorage)
        FirebaseCrashlytics.getInstance().apply {
            setCustomKey("applicationId", BuildConfig.APPLICATION_ID)
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

        nm.createNotificationChannels(mutableListOf(
            NotificationChannel(SiteController.CHANNEL_ID, getText(R.string.notification_channel_site_controller),
                NotificationManager.IMPORTANCE_LOW).apply {
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                setShowBadge(false)
            },
            NotificationChannel(LocationSetter.CHANNEL_ID, getText(R.string.notification_channel_webhook_updating),
                NotificationManager.IMPORTANCE_LOW).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            },
            NotificationChannel(LocationSetter.CHANNEL_ID_SUCCESS,
                getText(R.string.notification_channel_webhook_updated), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            },
            NotificationChannel(LocationSetter.CHANNEL_ID_ERROR, getText(R.string.notification_channel_webhook_failed),
                NotificationManager.IMPORTANCE_HIGH).apply {
                enableLights(true)
                lightColor = getColor(R.color.main_orange)
            },
        ).apply {
            if (BuildConfig.GITHUB_RELEASES != null) add(NotificationChannel(UpdateChecker.CHANNEL_ID,
                getText(R.string.notification_channel_update_available), NotificationManager.IMPORTANCE_HIGH).apply {
                enableLights(true)
                lightColor = getColor(R.color.main_blue)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        })
        work = WorkManager.getInstance(deviceStorage)
        BackgroundLocationReceiver.setup()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    val customTabsIntent by lazy {
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
