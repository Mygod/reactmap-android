package be.mygod.reactmap.auto

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import be.mygod.reactmap.App.Companion.app
import be.mygod.reactmap.R

class CarSiteController(private val fragment: Fragment) : DefaultLifecycleObserver {
    companion object {
        const val CHANNEL_ID = "carcontrol"
        private const val NOTIFICATION_ID = 2
    }
    init {
        fragment.lifecycle.addObserver(this)
    }

    private fun postNotification() = fragment.requireContext().let { context ->
        app.nm.notify(NOTIFICATION_ID, Notification.Builder(context, CHANNEL_ID).apply {
            setWhen(0)
            setCategory(Notification.CATEGORY_SERVICE)
            setContentTitle(title ?: context.getText(R.string.title_loading))
            setContentText(context.getText(R.string.notification_car_site_controller_message))
            setColor(context.getColor(R.color.main_blue))
            setGroup(CHANNEL_ID)
            setSmallIcon(R.drawable.ic_maps_directions_car)
            setOngoing(true)
            setVisibility(Notification.VISIBILITY_SECRET)
            setContentIntent(PendingIntent.getBroadcast(context, 0,
                Intent(MainCarActivity.ACTION_CLOSE).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }.build())
    }

    var title: CharSequence? = null
        set(value) {
            field = value
            if (fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) postNotification()
        }

    override fun onStart(owner: LifecycleOwner) = postNotification()
    override fun onStop(owner: LifecycleOwner) = app.nm.cancel(NOTIFICATION_ID)
}
