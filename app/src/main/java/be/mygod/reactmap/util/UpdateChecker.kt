package be.mygod.reactmap.util

import android.app.Notification
import android.app.PendingIntent
import android.net.Uri
import android.text.format.DateUtils
import androidx.core.content.edit
import be.mygod.reactmap.App.Companion.app
import be.mygod.reactmap.BuildConfig
import be.mygod.reactmap.R
import be.mygod.reactmap.webkit.ReactMapHttpEngine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import timber.log.Timber
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlin.math.min

object UpdateChecker {
    const val CHANNEL_ID = "updateAvailable"
    private const val ID_AVAILABLE = 4
    private const val KEY_LAST_FETCHED = "update.lastFetched"
    private const val KEY_VERSION = "update.version"
    private const val KEY_PUBLISHED = "update.published"
    private const val KEY_URL = "update.url"
    private const val UPDATE_INTERVAL = 1000 * 60 * 60

    data class GitHubUpdate(val version: String, val published: Long, val url: String)

    private data class SemVer(val major: Int, val minor: Int, val revision: Int) : Comparable<SemVer> {
        override fun compareTo(other: SemVer): Int {
            var result = major - other.major
            if (result != 0) return result
            result = minor - other.minor
            if (result != 0) return result
            return revision - other.revision
        }
    }
    private val semverParser = "^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-|$)".toPattern()
    private fun CharSequence.toSemVer() = semverParser.matcher(this).let { matcher ->
        require(matcher.find()) { app.getString(R.string.error_unrecognized_version, this) }
        SemVer(matcher.group(1)!!.toInt(), matcher.group(2)!!.toInt(), matcher.group(3)!!.toInt())
    }
    private val myVer = BuildConfig.VERSION_NAME.toSemVer()

    private fun findUpdate(response: JSONArray): GitHubUpdate? {
        var latest: Pair<String, String>? = null
        var latestVer = myVer
        var earliest = Long.MAX_VALUE
        for (i in 0 until response.length()) {
            val obj = response.getJSONObject(i)
            val name = obj.getString("name")
            val semver = try {
                name.toSemVer()
            } catch (e: IllegalArgumentException) {
                Timber.w(e)
                continue
            }
            if (semver <= myVer) continue
            if (semver > latestVer) {
                latest = name to obj.getString("html_url")
                latestVer = semver
            }
            earliest = min(earliest, Instant.parse(obj.getString("published_at")).toEpochMilli())
        }
        return latest?.let { GitHubUpdate(it.first, earliest, it.second) }
    }
    suspend fun check() {
        @Suppress("KotlinRedundantDiagnosticSuppress", "USELESS_ELVIS")
        val url = BuildConfig.GITHUB_RELEASES ?: return
        process(app.pref.getString(KEY_VERSION, null)?.let {
            if (myVer < it.toSemVer()) {
                GitHubUpdate(it, app.pref.getLong(KEY_PUBLISHED, -1), app.pref.getString(KEY_URL, null)!!)
            } else null
        })
        while (true) {
            val now = System.currentTimeMillis()
            val lastFetched = app.pref.getLong(KEY_LAST_FETCHED, -1)
            if (lastFetched in 0..now) delay(lastFetched + UPDATE_INTERVAL - now)
            currentCoroutineContext().ensureActive()
            var reset: Long? = null
            app.pref.edit {
                try {
                    val update = findUpdate(JSONArray(ReactMapHttpEngine.connectCancellable(url) { conn ->
                        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                        reset = conn.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()
                        conn.inputStream.bufferedReader().readText()
                    }))
                    putString(KEY_VERSION, update?.let {
                        putLong(KEY_PUBLISHED, update.published)
                        it.version
                    })
                    process(update)
                } catch (_: CancellationException) {
                    return
                } catch (e: IOException) {
                    Timber.d(e)
                } catch (e: Exception) {
                    Timber.w(e)
                } finally {
                    putLong(KEY_LAST_FETCHED, System.currentTimeMillis())
                }
            }
            reset?.let { delay(System.currentTimeMillis() - it * 1000) }
        }
    }

    private fun process(update: GitHubUpdate?) {
        if (update == null) return app.nm.cancel(ID_AVAILABLE)
        app.nm.notify(ID_AVAILABLE, Notification.Builder(app, CHANNEL_ID).apply {
            setCategory(Notification.CATEGORY_ALARM)
            setColor(app.getColor(R.color.main_blue))
            setContentTitle(app.getString(R.string.notification_update_available_title, update.version))
            setContentText(app.getString(R.string.notification_update_available_message,
                DateUtils.getRelativeTimeSpanString(update.published, System.currentTimeMillis(), 0)))
            setGroup(CHANNEL_ID)
            setVisibility(Notification.VISIBILITY_PUBLIC)
            setSmallIcon(R.drawable.ic_action_update)
            setShowWhen(true)
            setContentIntent(PendingIntent.getActivity(app, 3,
                app.customTabsIntent.intent.setData(Uri.parse(update.url.substringBefore("/tag/"))),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }.build())
    }
}
