package be.mygod.reactmap.follower

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import androidx.annotation.MainThread
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkRequest
import androidx.work.workDataOf
import be.mygod.reactmap.App.Companion.app
import be.mygod.reactmap.util.toByteArray
import be.mygod.reactmap.util.toParcelable
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit

class BackgroundLocationReceiver : BroadcastReceiver() {
    companion object {
//        private const val KEY = "backgroundLocation.enabled"
        private const val ACTION_LOCATION = "location"
        const val MIN_UPDATE_THRESHOLD_METER = 40f

        private val componentName by lazy { ComponentName(app, BackgroundLocationReceiver::class.java) }
        var enabled: Boolean
            get() = app.packageManager.getComponentEnabledSetting(componentName) ==
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            set(value) {
                app.packageManager.setComponentEnabledSetting(componentName, if (value) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                if (value) setup() else stop()
            }
//        private val userEnabled get() = app.pref.getBoolean(KEY, true)

        private val locationPendingIntent by lazy {
            PendingIntent.getBroadcast(app, 0, Intent(app, BackgroundLocationReceiver::class.java).setAction(
                ACTION_LOCATION
            ), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        }
        private val persistedLastLocationFile = File(app.deviceStorage.noBackupFilesDir, "lastLocation")
        var persistedLastLocation: LastLocation?
            get() = try {
                persistedLastLocationFile.readBytes().toParcelable<LastLocation>()
            } catch (_: FileNotFoundException) {
                null
            } catch (e: Exception) {
                Timber.w(e)
                null
            }
            set(value) {
                try {
                    if (value != null) {
                        persistedLastLocationFile.writeBytes(value.toByteArray())
                    } else if (!persistedLastLocationFile.delete()) persistedLastLocationFile.deleteOnExit()
                } catch (e: Exception) {
                    Timber.w(e)
                }
            }

        private var active = false
        fun setup() {
            if (active || !enabled) return
            if (app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED &&
                app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED ||
                app.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) !=
                PackageManager.PERMISSION_GRANTED) {
                LocationSetter.notifyError("Background location permission missing")
                return
            }
            app.fusedLocation.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_PASSIVE,
                5 * 60 * 1000).apply {
                setMaxUpdateAgeMillis(0)
                setMinUpdateDistanceMeters(MIN_UPDATE_THRESHOLD_METER)
                setMinUpdateIntervalMillis(60 * 1000)
            }.build(), locationPendingIntent).addOnCompleteListener { task ->
                if (task.isSuccessful) active = true else Timber.w(task.exception)
            }
        }
        private fun stop() {
            if (active) app.fusedLocation.removeLocationUpdates(locationPendingIntent).addOnCompleteListener { task ->
                if (task.isSuccessful) active = false else Timber.w(task.exception)
            }
        }

        @MainThread
        fun onLocationSubmitted(location: Location) {
            persistedLastLocation = LastLocation(persistedLastLocation?.location ?: location, location)
        }
    }

    override fun onReceive(context: Context?, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // already handled during App init
            }
            ACTION_LOCATION -> {
                val locations = LocationResult.extractResult(intent)?.locations
                if (locations.isNullOrEmpty()) return
                val lastLocation = persistedLastLocation
                var bestLocation = lastLocation?.location
                for (location in locations) {
                    if (bestLocation != null) {
                        if (bestLocation.time > location.time) continue
                        if (bestLocation.hasAccuracy()) {
                            if (!location.hasAccuracy()) continue
                            // keep the old more accurate location if new estimate does not contradict old estimate
                            if (bestLocation.accuracy < location.accuracy &&
                                location.distanceTo(bestLocation) <= bestLocation.accuracy + location.accuracy) continue
                        }
                    }
                    bestLocation = location
                }
                if (bestLocation!! == lastLocation?.location) return
                val shouldSet = lastLocation?.submittedLocation
                    ?.run { distanceTo(bestLocation) < MIN_UPDATE_THRESHOLD_METER } != true
                Timber.d("Updating $lastLocation -> $bestLocation (submitting $shouldSet)")
                persistedLastLocation = LastLocation(bestLocation, lastLocation?.submittedLocation)
                if (shouldSet) app.work.enqueueUniqueWork("LocationSetter", ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<LocationSetter>().apply {
                        setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                        setConstraints(Constraints.Builder().apply {
                            setRequiredNetworkType(NetworkType.CONNECTED)
                            // Expedited jobs only support network and storage constraints
//                            setRequiresBatteryNotLow(true)
                        }.build())
                        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        setInputData(workDataOf(LocationSetter.KEY_LATITUDE to bestLocation.latitude,
                            LocationSetter.KEY_LONGITUDE to bestLocation.longitude))
                    }.build())
            }
        }
    }
}
