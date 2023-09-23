package be.mygod.reactmap.webkit

import android.app.PendingIntent
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
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
        app.nm.notify(1, NotificationCompat.Builder(context, CHANNEL_ID).apply {
            setWhen(0)
            color = context.getColor(R.color.main_blue)
            setCategory(NotificationCompat.CATEGORY_SERVICE)
            setContentTitle(title)
            setContentText("Tap to configure")
            setGroup(CHANNEL_ID)
            setSmallIcon(R.drawable.ic_reactmap)
            setOngoing(true)
            priority = NotificationCompat.PRIORITY_LOW
            setVisibility(NotificationCompat.VISIBILITY_SECRET)
            setContentIntent(PendingIntent.getActivity(context, 0,
                Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_CONFIGURE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            addAction(
                R.drawable.ic_notification_sync, "Restart game", PendingIntent.getActivity(context,
                1, Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_RESTART_GAME),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
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
