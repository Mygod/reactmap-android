package be.mygod.reactmap.webkit

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import be.mygod.reactmap.App.Companion.app
import be.mygod.reactmap.MainActivity
import be.mygod.reactmap.R

class SiteController(private val fragment: Fragment) : DefaultLifecycleObserver {
    companion object {
        const val CHANNEL_ID = "control"
    }
    init {
        fragment.lifecycle.addObserver(this)
    }

    private val requestPermission = fragment.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!it || !started) return@registerForActivityResult
        val context = fragment.requireContext()
        app.nm.notify(1, Notification.Builder(context, CHANNEL_ID).apply {
            setWhen(0)
            setCategory(Notification.CATEGORY_SERVICE)
            setContentTitle(title)
            setContentText("Tap to configure")
            setColor(context.getColor(R.color.main_blue))
            setGroup(CHANNEL_ID)
            setSmallIcon(R.drawable.ic_reactmap)
            setOngoing(true)
            setVisibility(Notification.VISIBILITY_SECRET)
            setContentIntent(PendingIntent.getActivity(context, 0,
                Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_CONFIGURE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_notification_sync),
                "Restart game", PendingIntent.getActivity(context, 1,
                    Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_RESTART_GAME),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
        }.build())
    }
    private var started = false

    var title: String? = null
        set(value) {
            field = value
            if (started) requestPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

    override fun onStart(owner: LifecycleOwner) {
        started = true
        if (title != null) requestPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onStop(owner: LifecycleOwner) {
        started = false
        app.nm.cancel(1)
    }
}
