package be.mygod.reactmap.follower

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.icu.text.DecimalFormat
import android.location.Location
import android.text.format.DateUtils
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import be.mygod.reactmap.App.Companion.app
import be.mygod.reactmap.MainActivity
import be.mygod.reactmap.R
import be.mygod.reactmap.util.findErrorStream
import be.mygod.reactmap.util.readableMessage
import be.mygod.reactmap.webkit.ReactMapHttpEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException

class LocationSetter(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    companion object {
        const val CHANNEL_ID = "locationSetter"
        const val CHANNEL_ID_ERROR = "locationSetterError"
        const val CHANNEL_ID_SUCCESS = "locationSetterSuccess"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_TIME = "time"
        const val KEY_API_URL = "apiUrl"
        private const val ID_STATUS = 3

        fun notifyError(message: CharSequence) {
            app.nm.notify(ID_STATUS, Notification.Builder(app, CHANNEL_ID_ERROR).apply {
                setCategory(Notification.CATEGORY_ALARM)
                setColor(app.getColor(R.color.main_orange))
                setContentTitle(app.getText(R.string.notification_webhook_failed_title))
                setContentText(message)
                setGroup(CHANNEL_ID)
                setSmallIcon(R.drawable.ic_notification_sync_problem)
                setShowWhen(true)
                setContentIntent(PendingIntent.getActivity(app, 2,
                    Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_CONFIGURE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                style = Notification.BigTextStyle().bigText(message)
            }.build())
        }

        private val secondFormat = DecimalFormat(".###")
        private fun formatTimeSpanFrom(from: Long): String {
            var t = (System.currentTimeMillis() - from) * .001
            if (t < 60) return "${secondFormat.format(t)}s"
            if (t < 60 * 60) {
                val s = (t % 60).toInt()
                if (s == 0) return "${t.toInt()}m"
                return "${(t / 60).toInt()}m ${s}s"
            }
            t /= 60
            if (t < 24 * 60) {
                val m = (t % 60).toInt()
                if (m == 0) return "${t.toInt()}h"
                return "${(t / 60).toInt()}h ${m}m"
            }
            t /= 60
            val h = (t % 24).toInt()
            if (h == 0) return "${t.toInt()}d"
            return "${(t / 24).toInt()}d ${h}h"
        }
    }

    override suspend fun doWork() = try {
        val lat = inputData.getDouble(KEY_LATITUDE, Double.NaN)
        val lon = inputData.getDouble(KEY_LONGITUDE, Double.NaN)
        val time = inputData.getLong(KEY_TIME, 0)
        val apiUrl = inputData.getString(KEY_API_URL)!!
        val conn = ReactMapHttpEngine.connectWithCookie(apiUrl) { conn ->
            conn.doOutput = true
            conn.requestMethod = "POST"
            conn.addRequestProperty("Content-Type", "application/json")
            conn.outputStream.bufferedWriter().use {
                it.write(JSONObject().apply {
                    put("operationName", "Webhook")
                    put("variables", JSONObject().apply {
                        put("category", "setLocation")
                        put("data", JSONArray(arrayOf(lat, lon)))
                        put("status", "POST")
                    })
                    // epic graphql query yay >:(
                    put("query", "mutation Webhook(\$data: JSON, \$category: String!, \$status: String!) {" +
                            "webhook(data: \$data, category: \$category, status: \$status) {" +
                            "human { current_profile_no name type } } }")
                }.toString())
            }
        }
        when (val code = conn.responseCode) {
            200 -> {
                val response = conn.inputStream.bufferedReader().readText()
                val human = try {
                    val webhook = JSONObject(response).getJSONObject("data").getJSONObject("webhook")
                    if (webhook.opt("human") == null) {
                        withContext(Dispatchers.Main) { BackgroundLocationReceiver.stop() }
                        notifyError(app.getText(R.string.error_webhook_human_not_found))
                        throw CancellationException()
                    }
                    val o = webhook.getJSONObject("human")
                    o.getString("type") + '/' + o.getString("name") + '/' + o.getLong("current_profile_no")
                } catch (e: JSONException) {
                    throw Exception(response, e)
                }
                withContext(Dispatchers.Main) {
                    BackgroundLocationReceiver.onLocationSubmitted(apiUrl, Location("bg").apply {
                        latitude = lat
                        longitude = lon
                    })
                }
                app.nm.notify(ID_STATUS, Notification.Builder(app, CHANNEL_ID_SUCCESS).apply {
                    setCategory(Notification.CATEGORY_STATUS)
                    setContentTitle(app.getText(R.string.notification_webhook_updated_title))
                    setColor(app.getColor(R.color.main_blue))
                    setGroup(CHANNEL_ID)
                    setSmallIcon(R.drawable.ic_device_share_location)
                    setShowWhen(true)
                    setContentIntent(PendingIntent.getActivity(app, 2,
                        Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_CONFIGURE),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    setSubText(formatTimeSpanFrom(time))
                    setPublicVersion(build().clone())
                    setContentText(app.getString(R.string.notification_webhook_updated_message, lat, lon, human))
                }.build())
                Result.success()
            }
            else -> {
                val error = conn.findErrorStream.bufferedReader().readText()
                val json = JSONObject(error).getJSONArray("errors")
                notifyError((0 until json.length()).joinToString { json.getJSONObject(it).getString("message") })
                if (code == 401 || code == 511) {
                    withContext(Dispatchers.Main) { BackgroundLocationReceiver.stop() }
                    Result.failure()
                } else {
                    Timber.w(Exception(error + code))
                    Result.retry()
                }
            }
        }
    } catch (e: IOException) {
        Timber.d(e)
        Result.retry()
    } catch (_: CancellationException) {
        Result.failure()
    } catch (e: Exception) {
        Timber.w(e)
        notifyError(e.readableMessage)
        Result.failure()
    }

    override suspend fun getForegroundInfo() = ForegroundInfo(2, Notification.Builder(app, CHANNEL_ID).apply {
        setCategory(Notification.CATEGORY_SERVICE)
        setContentTitle(app.getText(R.string.notification_webhook_updating_title))
        setContentText(app.getString(R.string.notification_webhook_updating_message,
            inputData.getDouble(KEY_LATITUDE, Double.NaN), inputData.getDouble(KEY_LONGITUDE, Double.NaN),
            DateUtils.getRelativeTimeSpanString(inputData.getLong(KEY_TIME, 0), System.currentTimeMillis(),
                0, DateUtils.FORMAT_ABBREV_RELATIVE)))
        setColor(app.getColor(R.color.main_blue))
        setGroup(CHANNEL_ID)
        setVisibility(Notification.VISIBILITY_PUBLIC)
        setSmallIcon(R.drawable.ic_notification_sync)
        setShowWhen(true)
        setProgress(0, 0, true)
        setContentIntent(PendingIntent.getActivity(app, 2,
            Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_CONFIGURE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
}
