package be.mygod.reactmap.follower

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
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
import java.net.HttpURLConnection

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

        private fun formatTimeSpanFrom(from: Long): String {
            val formatter = MeasureFormat.getInstance(
                app.resources.configuration.locales[0],
                MeasureFormat.FormatWidth.NUMERIC,
            )
            var remaining = (System.currentTimeMillis() - from).coerceAtLeast(0)
            val measures = ArrayList<Measure>(2)
            val units = arrayOf(
                86_400_000L to MeasureUnit.DAY,
                3_600_000L to MeasureUnit.HOUR,
                60_000L to MeasureUnit.MINUTE,
                1_000L to MeasureUnit.SECOND,
                1L to MeasureUnit.MILLISECOND,
            )
            for (i in units.indices) {
                val (unitMillis, unit) = units[i]
                val count = remaining / unitMillis
                if (count <= 0) continue
                measures += Measure(count, unit)
                remaining %= unitMillis
                units.getOrNull(i + 1)?.let { (nextUnitMillis, nextUnit) ->
                    val nextCount = remaining / nextUnitMillis
                    if (nextCount > 0) measures += Measure(nextCount, nextUnit)
                }
                break
            }
            if (measures.isEmpty()) measures += Measure(0, MeasureUnit.MILLISECOND)
            return formatter.formatMeasures(*measures.toTypedArray())
        }
    }

    override suspend fun doWork() = try {
        val lat = inputData.getDouble(KEY_LATITUDE, Double.NaN)
        val lon = inputData.getDouble(KEY_LONGITUDE, Double.NaN)
        val apiUrl = inputData.getString(KEY_API_URL)!!
        doWork(lat, lon, inputData.getLong(KEY_TIME, 0), apiUrl, ReactMapHttpEngine.connectWithCookie(apiUrl) { conn ->
            conn.requestMethod = "POST"
            conn.addRequestProperty("Content-Type", "application/json")
            ReactMapHttpEngine.writeCompressed(conn, JSONObject().apply {
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
        })
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

    private enum class ErrorDisposition { FAIL, RETRY_LOG_ONLY, RETRY_AND_REPORT }
    private data class WebhookErrors(val message: String, val disposition: ErrorDisposition)
    private suspend fun finishWithErrors(response: String, json: JSONObject? = null, statusCode: Int = 200): Result {
        val errors = try {
            val errors = (json ?: JSONObject(response)).getJSONArray("errors")
            val parsed = List(errors.length()) { errors.getJSONObject(it) }
            if (parsed.isEmpty()) WebhookErrors(response, ErrorDisposition.RETRY_AND_REPORT) else {
                val codes = parsed.map { it.optJSONObject("extensions")?.optString("code") }
                val disposition = when {
                    "PERMS_CHANGED" in codes -> ErrorDisposition.FAIL
                    codes.all { it == "INTERNAL_SERVER_ERROR" || it == "TOO_MANY_SESSIONS" } ->
                        ErrorDisposition.RETRY_LOG_ONLY
                    else -> ErrorDisposition.RETRY_AND_REPORT
                }
                WebhookErrors(parsed.joinToString("\n") { it.getString("message") }, disposition)
            }
        } catch (_: JSONException) {
            WebhookErrors(response, ErrorDisposition.RETRY_AND_REPORT)
        }
        notifyError(errors.message)
        if (statusCode == 401 || statusCode == 511 || errors.disposition == ErrorDisposition.FAIL) {
            withContext(Dispatchers.Main) { BackgroundLocationReceiver.stop() }
            return Result.failure()
        }
        val logMessage = if (statusCode == 200) response else "$statusCode $response"
        return if (errors.disposition != ErrorDisposition.RETRY_LOG_ONLY) Result.retry().also {
            when (statusCode) {
                404, 500, 502, 503, 520, 522, 523, 530 -> Timber.d(logMessage)
                else -> Timber.w(Exception(logMessage))
            }
        } else Result.retry().also { Timber.w(logMessage) }
    }

    private suspend fun doWork(lat: Double, lon: Double, time: Long, apiUrl: String, conn: HttpURLConnection): Result {
        return when (val code = conn.responseCode) {
            200 -> {
                val response = conn.inputStream.bufferedReader().readText()
                val human = try {
                    val obj = JSONObject(response)
                    val webhook = obj.getJSONObject("data").optJSONObject("webhook")
                        ?: return finishWithErrors(response, obj)
                    if (webhook["human"] == JSONObject.NULL) {
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
            302 -> {
                ReactMapHttpEngine.detectBrotliError(conn)?.let { notifyError(it) }
                Result.retry()
            }
            else -> finishWithErrors(conn.findErrorStream.bufferedReader().readText(), statusCode = code)
        }
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
