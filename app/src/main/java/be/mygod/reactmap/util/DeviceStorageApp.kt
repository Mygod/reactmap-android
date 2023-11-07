package be.mygod.reactmap.util

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.Configuration
import be.mygod.reactmap.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class DeviceStorageApp(context: Context) : Application(), Configuration.Provider {
    init {
        attachBaseContext(context.createDeviceProtectedStorageContext())
    }

    /**
     * Thou shalt not get the REAL underlying application context which would no longer be operating under device
     * protected storage.
     */
    override fun getApplicationContext(): Context = this

    /**
     * Fuck you androidx.work.
     */
    override fun isDeviceProtectedStorage() = false

    override val workManagerConfiguration get() = Configuration.Builder().apply {
        setExecutor { GlobalScope.launch(Dispatchers.IO) { it.run() } }
        if (BuildConfig.DEBUG) setMinimumLoggingLevel(Log.VERBOSE)
    }.build()
}
