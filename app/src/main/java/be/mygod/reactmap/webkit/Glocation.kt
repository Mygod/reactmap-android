package be.mygod.reactmap.webkit

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.RequiresPermission
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import be.mygod.reactmap.App.Companion.app
import be.mygod.reactmap.R
import be.mygod.reactmap.util.readableMessage
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import kotlinx.coroutines.launch
import timber.log.Timber

class Glocation(private val web: WebView, private val fragment: BaseReactMapFragment) : DefaultLifecycleObserver {
    companion object {
        const val PERMISSION_DENIED = 1
        const val POSITION_UNAVAILABLE = 2
//        const val TIMEOUT = 3

        fun Location.toGeolocationPosition() = """{
            coords: {
                latitude: $latitude,
                longitude: $longitude,
                altitude: ${if (hasAltitude()) altitude else null},
                accuracy: $accuracy,
                altitudeAccuracy: ${if (hasVerticalAccuracy()) verticalAccuracyMeters else null},
                heading: ${if (hasBearing()) bearing else null},
                speed: ${if (hasSpeed()) speed else null},
            },
            timestamp: ${time.toULong()},
        }"""

        fun Exception?.toGeolocationPositionError() = """{
            code: ${if (this is SecurityException) PERMISSION_DENIED else POSITION_UNAVAILABLE},
            message: ${this?.readableMessage?.let { "'$it'" }},
        }"""
    }

    private val context = web.let {
        it.addJavascriptInterface(this, "_glocation")
        fragment.lifecycle.addObserver(this)
        it.context
    }
    private val jsSetup = fragment.resources.openRawResource(R.raw.setup_glocation).bufferedReader().readText()
    private val pendingRequests = mutableSetOf<Long>()
    private var pendingWatch = false
    private val activeListeners = mutableSetOf<Long>()
    private val callback = object : LocationCallback() {
        override fun onLocationAvailability(availability: LocationAvailability) {
            Timber.d("onLocationAvailability $availability")
            if (availability.isLocationAvailable) return
            val ids = activeListeners.joinToString()
            web.evaluateJavascript(
                "navigator.geolocation._watchPositionSuccess([$ids], { code: $POSITION_UNAVAILABLE }))", null)
        }

        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            val ids = activeListeners.joinToString()
            Timber.d("onLocationResult ${location.time}")
            web.evaluateJavascript(
                "navigator.geolocation._watchPositionSuccess([$ids], ${location.toGeolocationPosition()})", null)
        }
    }
    private var requestingLocationUpdates = false

    fun clear() {
        pendingRequests.clear()
        activeListeners.clear()
        requestingLocationUpdates = false
        removeLocationUpdates()
    }

    fun setupGeolocation() = web.evaluateJavascript(jsSetup, null)

    private fun checkPermissions() = if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED) true else fragment.requestLocationPermissions()
    fun onPermissionResult(granted: Boolean) {
        if (pendingRequests.isNotEmpty()) {
            getCurrentPosition(granted, pendingRequests.joinToString())
            pendingRequests.clear()
        }
        if (pendingWatch) {
            watchPosition(granted)
            pendingWatch = false
        }
    }

    @JavascriptInterface
    fun getCurrentPosition(i: Long) {
        Timber.d("getCurrentPosition($i)")
        when (val granted = checkPermissions()) {
            null -> pendingRequests.add(i)
            else -> fragment.lifecycleScope.launch { getCurrentPosition(granted, i.toString()) }
        }
    }

    private fun getCurrentPosition(granted: Boolean, ids: String) {
        @SuppressLint("MissingPermission")
        if (granted) app.fusedLocation.lastLocation.addOnCompleteListener { task ->
            val location = task.result
            web.evaluateJavascript(if (location == null) {
                "navigator.geolocation._getCurrentPositionError([$ids], ${task.exception.toGeolocationPositionError()})"
            } else "navigator.geolocation._getCurrentPositionSuccess([$ids], ${location.toGeolocationPosition()})",
            null)
        } else web.evaluateJavascript(
            "navigator.geolocation._getCurrentPositionError([$ids], { code: $PERMISSION_DENIED })", null)
    }

    @JavascriptInterface
    fun watchPosition(i: Long) {
        Timber.d("watchPosition($i)")
        if (!activeListeners.add(i) || requestingLocationUpdates || pendingWatch) return
        when (val granted = checkPermissions()) {
            null -> pendingWatch = true
            else -> fragment.lifecycleScope.launch { watchPosition(granted) }
        }
    }

    private fun watchPosition(granted: Boolean) {
        if (granted) @SuppressLint("MissingPermission") {
            requestingLocationUpdates = true
            if (fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) requestLocationUpdates()
        } else web.evaluateJavascript("navigator.geolocation._watchPositionError([${activeListeners.joinToString()}]," +
                " { code: $PERMISSION_DENIED })", null)
    }

    @JavascriptInterface
    fun clearWatch(i: Long) {
        Timber.d("clearWatch($i)")
        if (!activeListeners.remove(i) || activeListeners.isNotEmpty() || !requestingLocationUpdates) return
        requestingLocationUpdates = false
        removeLocationUpdates()
    }

    override fun onStart(owner: LifecycleOwner) {
        @SuppressLint("MissingPermission")
        if (requestingLocationUpdates) requestLocationUpdates()
    }
    override fun onStop(owner: LifecycleOwner) {
        if (requestingLocationUpdates) removeLocationUpdates()
    }

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION])
    private fun requestLocationUpdates() {
        app.fusedLocation.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_LOW_POWER, 4000).apply {
            // enableHighAccuracy - PRIORITY_HIGH_ACCURACY
            // expirationTime = timeout
            // maxWaitTime = maximumAge
            setMinUpdateDistanceMeters(5f)
            setMinUpdateIntervalMillis(1000)
        }.build(), callback, Looper.getMainLooper()).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Timber.d("Start watching location")
            } else web.evaluateJavascript("navigator.geolocation._watchPositionError([" +
                    "${activeListeners.joinToString()}], ${task.exception.toGeolocationPositionError()})", null)
        }
    }
    private fun removeLocationUpdates() = app.fusedLocation.removeLocationUpdates(callback).addOnCompleteListener {
        if (it.isSuccessful) Timber.d("Stop watching location")
        else Timber.w("Stop watch failed: ${it.exception}")
    }
}
