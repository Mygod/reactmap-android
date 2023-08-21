package be.mygod.reactmap

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.RequiresPermission
import androidx.core.app.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class Glocation(private val web: WebView, private val permissionRequestCode: Int) : DefaultLifecycleObserver {
    companion object {
        private const val TAG = "Glocation"
        const val PERMISSION_DENIED = 1
        const val POSITION_UNAVAILABLE = 2
        const val TIMEOUT = 3

        fun Location.toGeolocationPosition() = """{
            coords: {
                latitude: $latitude,
                longitude: $longitude,
                altitude: ${if (hasAltitude()) altitude else null},
                accuracy: $accuracy,
                altitudeAccuracy: ${
                    if (Build.VERSION.SDK_INT >= 26 && hasVerticalAccuracy()) verticalAccuracyMeters else null
                },
                heading: ${if (hasBearing()) bearing else null},
                speed: ${if (hasSpeed()) speed else null},
            },
            timestamp: ${time.toULong()},
        }"""

        fun Exception?.toGeolocationPositionError() = """{
            code: ${if (this is SecurityException) PERMISSION_DENIED else POSITION_UNAVAILABLE},
            message: ${this?.message?.let { "'$it'" }},
        }"""
    }

    private val activity = web.let {
        it.addJavascriptInterface(this, "_glocation")
        it.context as ComponentActivity
    }.also { it.lifecycle.addObserver(this) }
    private val jsSetup = activity.resources.openRawResource(R.raw.setup).bufferedReader().readText()
    private val client = LocationServices.getFusedLocationProviderClient(activity)
    private val pendingRequests = mutableSetOf<Long>()
    private var pendingWatch = false
    private val activeListeners = mutableSetOf<Long>()
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            val ids = activeListeners.joinToString()
            Log.d(TAG, "onLocationResult ${location.time}")
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

    private fun checkPermissions() = when {
        activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED -> true
        else -> {
            activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), permissionRequestCode)
            null
        }
    }

    fun onRequestPermissionsResult(granted: Boolean) {
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
        Log.d(TAG, "getCurrentPosition($i)")
        when (val granted = checkPermissions()) {
            null -> pendingRequests.add(i)
            else -> getCurrentPosition(granted, i.toString())
        }
    }

    private fun getCurrentPosition(granted: Boolean, ids: String) {
        @SuppressLint("MissingPermission")
        if (granted) client.lastLocation.addOnCompleteListener { task ->
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
        Log.d(TAG, "watchPosition($i)")
        if (!activeListeners.add(i) || requestingLocationUpdates || pendingWatch) return
        when (val granted = checkPermissions()) {
            null -> pendingWatch = true
            else -> watchPosition(granted)
        }
    }

    private fun watchPosition(granted: Boolean) {
        if (granted) @SuppressLint("MissingPermission") {
            requestingLocationUpdates = true
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) requestLocationUpdates()
        } else web.evaluateJavascript("navigator.geolocation._watchPositionError([${activeListeners.joinToString()}]," +
                " { code: $PERMISSION_DENIED })", null)
    }

    @JavascriptInterface
    fun clearWatch(i: Long) {
        Log.d(TAG, "clearWatch($i)")
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
    private fun requestLocationUpdates() = client.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_LOW_POWER, 4000).apply {
        // enableHighAccuracy - PRIORITY_HIGH_ACCURACY
        // expirationTime = timeout
        // maxWaitTime = maximumAge
        setMinUpdateIntervalMillis(1000)
        setMinUpdateDistanceMeters(5f)
    }.build(), callback, Looper.getMainLooper()).addOnCompleteListener { task ->
        if (task.isSuccessful) {
            Log.d(TAG, "Start watching location")
        } else web.evaluateJavascript("navigator.geolocation._watchPositionError([${activeListeners.joinToString()}]," +
                " ${task.exception.toGeolocationPositionError()})", null)
    }
    private fun removeLocationUpdates() = client.removeLocationUpdates(callback).addOnCompleteListener { task ->
        if (task.isSuccessful) Log.d(TAG, "Stop watching location")
        else Log.w(TAG, "Stop watch failed: ${task.exception}")
    }
}
