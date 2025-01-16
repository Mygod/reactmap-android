package be.mygod.reactmap.webkit

import android.content.DialogInterface
import android.icu.text.DateFormat
import android.icu.text.SimpleDateFormat
import android.icu.util.Calendar
import android.os.Bundle
import android.os.Parcelable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.get
import androidx.core.view.isGone
import androidx.lifecycle.lifecycleScope
import be.mygod.reactmap.R
import be.mygod.reactmap.util.AlertDialogFragment
import be.mygod.reactmap.util.Empty
import be.mygod.reactmap.util.findErrorStream
import be.mygod.reactmap.util.headerLocation
import be.mygod.reactmap.util.readableMessage
import com.google.common.geometry.S2LatLng
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import timber.log.Timber
import java.io.IOException
import java.net.URLConnection
import java.text.ParseException
import java.text.ParsePosition
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

class AccuWeatherDialogFragment : AlertDialogFragment<AccuWeatherDialogFragment.Arg, Empty>() {
    companion object {
        private val weatherForecastMatcher = "^/en/([^/]*/[^/]*/[^/]*)/weather-forecast/(.*)\$".toRegex()
        // raw: <div id="(\d+)" data-qa="\1" class="accordion-item hour".*?data-src="/images/weathericons/(\d+).svg"
        private val hourlyMatcher =
            "<div id=\"(\\d+)\" data-qa=\"\\1\" class=\"accordion-item hour\".*?data-src=\"/images/weathericons/(\\d+).svg\""
                .toPattern(Pattern.DOTALL)
        // raw: <span class="module-header sub date">([^<]*)</span>.*?data-src="/images/weathericons/(\d+).svg"
        private val dailyMatcher =
            "<span class=\"module-header sub date\">([^<]*)</span>.*?data-src=\"/images/weathericons/(\\d+).svg\""
                .toPattern(Pattern.DOTALL)
        private val dayFormat = SimpleDateFormat("M/d", Locale.US)

        private fun URLConnection.setUserAgent() = setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
        suspend fun newInstance(cell: S2LatLng): AccuWeatherDialogFragment {
            val searchResponse = ReactMapHttpEngine.connectCancellable(
                "https://www.accuweather.com/en/search-locations?query=${cell.latDegrees()},${cell.lngDegrees()}") { conn ->
                conn.setUserAgent()
                conn.instanceFollowRedirects = false
                if (conn.responseCode != 302) throw Exception(
                    "${conn.responseCode}: ${conn.findErrorStream.bufferedReader().readText()}")
                conn.headerLocation
            }
            val keyResponse = ReactMapHttpEngine.connectCancellable(
                "https://www.accuweather.com$searchResponse") { conn ->
                conn.setUserAgent()
                conn.instanceFollowRedirects = false
                if (conn.responseCode != 302) throw Exception("$searchResponse returns ${conn.responseCode}: " +
                        conn.findErrorStream.bufferedReader().readText())
                conn.headerLocation
            }
            val match = weatherForecastMatcher.matchEntire(keyResponse)
            if (match == null) throw Exception("Unknown redirect target $searchResponse - $keyResponse")
            return AccuWeatherDialogFragment().apply {
                arg(Arg(match.groupValues[1], match.groupValues[2]))
                key()
            }
        }
    }

    @Parcelize
    data class Arg(val locationDescription: String, val locationKey: String) : Parcelable

    override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
        setView(R.layout.dialog_accuweather)
        setTitle("${arg.locationDescription} (${arg.locationKey})")
    }

    private lateinit var hourFormat: DateFormat
    private val calendar = Calendar.getInstance()
    override fun onCreateDialog(savedInstanceState: Bundle?) = super.onCreateDialog(savedInstanceState).apply {
        create()
        hourFormat = DateFormat.getInstanceForSkeleton(
            if (android.text.format.DateFormat.is24HourFormat(requireContext())) "dEEEEH" else "dEEEEha",
            resources.configuration.locales[0])
        fetchData(R.id.day1, param = "?day=1")
        fetchData(R.id.day2, param = "?day=2")
        fetchData(R.id.day3, param = "?day=3")
        fetchData(R.id.daily, "dai")
    }

    private fun AlertDialog.fetchData(id: Int, type: String = "hour", param: String = "") = lifecycleScope.launch {
        val format = if (param.isEmpty()) {
            DateFormat.getInstanceForSkeleton("MMMMdEEEE", resources.configuration.locales[0])
        } else hourFormat
        val out = try {
            ReactMapHttpEngine.connectCancellable(
                "https://www.accuweather.com/en/${arg.locationDescription}/${type}ly-weather-forecast/${arg.locationKey}$param") { conn ->
                conn.setUserAgent()
                if (conn.responseCode != 200) return@connectCancellable "${conn.responseCode}: ${conn.findErrorStream.bufferedReader().readText()}"
                val response = conn.inputStream.bufferedReader().readText()
                val matcher = (if (param.isEmpty()) dailyMatcher else hourlyMatcher).matcher(response)
                if (!matcher.find()) return@connectCancellable response
                val result = SpannableStringBuilder()
                do {
                    if (result.isNotEmpty()) result.appendLine()
                    // https://github.com/KartulUdus/PoracleJS/blob/5552deaeedb395e35268172e7a789f249b9ee415/src/controllers/weather.js#L68-L74
                    val icon = when (matcher.group(2)!!.toInt()) {
                        1, 2, 30 -> R.drawable.ic_image_wb_sunny
                        33, 34 -> R.drawable.ic_image_bedtime
                        12, 15, 18, 26, 29 -> R.drawable.ic_places_beach_access
                        3, 4, 14, 17, 21 -> R.drawable.ic_partly_cloudy_day
                        35, 36, 39, 41 -> R.drawable.ic_partly_cloudy_night
                        5, 6, 7, 8, 13, 16, 20, 23, 37, 38, 40, 42 -> R.drawable.ic_file_cloud
                        32 -> R.drawable.ic_family_link
                        19, 22, 24, 25, 31, 43, 44 -> R.drawable.ic_places_ac_unit
                        11 -> R.drawable.ic_mist
                        else -> 0
                    }
                    val i = result.length
                    if (icon == 0) result.append("(${matcher.group(2)})) ") else {
                        result.append("  ")
                        result.setSpan(ImageSpan(requireContext(), icon), i, i + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    result.append(if (param.isEmpty()) try {
                        dayFormat.parse(matcher.group(1), calendar, ParsePosition(0))
                        format.format(calendar.time)
                    } catch (e: ParseException) {
                        Timber.w(Exception(matcher.group(1)).initCause(e))
                        matcher.group(1)
                    } else format.format(Date(matcher.group(1)!!.toLong() * 1000)))
                } while (matcher.find())
                result
            }
        } catch (e: IOException) {
            Timber.d(e)
            e.readableMessage
        } catch (e: Exception) {
            Timber.w(e)
            e.readableMessage
        }
        findViewById<ViewGroup>(id)!!.let { frame ->
            (frame[1] as TextView).apply {
                text = out
                isGone = false
            }
            frame.removeViewAt(0)
        }
    }
}
