package be.mygod.reactmap.webkit

import android.content.DialogInterface
import android.icu.text.SimpleDateFormat
import android.icu.util.Calendar
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.i18n.DateTimeFormatter
import androidx.core.i18n.DateTimeFormatterSkeletonOptions
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
import java.util.Locale
import java.util.regex.Pattern

class AccuWeatherDialogFragment : AlertDialogFragment<AccuWeatherDialogFragment.Arg, Empty>() {
    companion object {
        private val weatherForecastMatcher = "^/en/([^/]*/[^/]*/[^/]*)/weather-forecast/([^?]*)".toRegex()
        // raw: <div id="(\d+)" data-qa="\1" class="accordion-item hour".*?data-src="/images/weathericons/(\d+).svg".*?<div class="phrase">([^<]*)</div>.*?(\d+) km/h.*?(\d+) km/h
        private val hourlyMatcher =
            "<div id=\"(\\d+)\" data-qa=\"\\1\" class=\"accordion-item hour\".*?data-src=\"/images/weathericons/(\\d+).svg\".*?<div class=\"phrase\">([^<]*)</div>.*?(\\d+) km/h.*?(\\d+) km/h"
                .toPattern(Pattern.DOTALL)
        // raw: <span class="module-header sub date">([^<]*)</span>.*?data-src="/images/weathericons/(\d+).svg".*?<div class="phrase">([^<]*)</div>.*?(\d+) km/h(?:[^b]*?(\d+) km/h)?
        private val dailyMatcher =
            "<span class=\"module-header sub date\">([^<]*)</span>.*?data-src=\"/images/weathericons/(\\d+).svg\".*?<div class=\"phrase\">([^<]*)</div>.*?(\\d+) km/h(?:[^b]*?(\\d+) km/h)?"
                .toPattern(Pattern.DOTALL)
        private val dayFormat = SimpleDateFormat("M/d", Locale.US)

        private fun URLConnection.setUserAgent() = setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
        suspend fun newInstance(cell: S2LatLng): AccuWeatherDialogFragment {
            val keyResponse = ReactMapHttpEngine.connectCancellable(
                "https://www.accuweather.com/web-api/three-day-redirect?lat=${cell.latDegrees()}&lon=${cell.lngDegrees()}") { conn ->
                conn.setUserAgent()
                conn.instanceFollowRedirects = false
                if (conn.responseCode != 302) throw Exception(
                    "${conn.responseCode}: ${conn.findErrorStream.bufferedReader().readText()}")
                conn.headerLocation
            }
            val match = weatherForecastMatcher.find(keyResponse)
            if (match == null) throw Exception("Unknown redirect target $keyResponse")
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
        setTitle("${Uri.decode(arg.locationDescription)} (${arg.locationKey})")
    }

    override fun onCreateDialog(savedInstanceState: Bundle?) = super.onCreateDialog(savedInstanceState).apply {
        create()
        val hourFormat = DateTimeFormatter(requireContext(), DateTimeFormatterSkeletonOptions.Builder(
            day = DateTimeFormatterSkeletonOptions.Day.NUMERIC,
            weekDay = DateTimeFormatterSkeletonOptions.WeekDay.WIDE,
            period = DateTimeFormatterSkeletonOptions.Period.WIDE,
            hour = DateTimeFormatterSkeletonOptions.Hour.NUMERIC,
        ).build(), resources.configuration.locales[0])
        fetchData(R.id.day1, hourFormat)
        fetchData(R.id.day2, hourFormat, "&day=2")
        fetchData(R.id.day3, hourFormat, "&day=3")
        fetchData(R.id.daily, DateTimeFormatter(requireContext(), DateTimeFormatterSkeletonOptions.Builder(
            month = DateTimeFormatterSkeletonOptions.Month.WIDE,
            day = DateTimeFormatterSkeletonOptions.Day.NUMERIC,
            weekDay = DateTimeFormatterSkeletonOptions.WeekDay.WIDE,
        ).build(), resources.configuration.locales[0]), daily = true)
    }

    private val calendar = Calendar.getInstance()
    private fun AlertDialog.fetchData(id: Int, format: DateTimeFormatter, param: String = "",
                                      daily: Boolean = false) = lifecycleScope.launch {
        val out = try {
            ReactMapHttpEngine.connectCancellable(
                "https://www.accuweather.com/en/${arg.locationDescription}/${if (daily) {
                    "dai"
                } else "hour"}ly-weather-forecast/${arg.locationKey}?unit=c$param") { conn ->
                conn.setUserAgent()
                if (conn.responseCode != 200) return@connectCancellable "${conn.responseCode}: ${conn.findErrorStream.bufferedReader().readText()}"
                val response = conn.inputStream.bufferedReader().readText()
                val matcher = (if (daily) dailyMatcher else hourlyMatcher).matcher(response)
                if (!matcher.find()) return@connectCancellable response
                val result = SpannableStringBuilder()
                do {
                    if (result.isNotEmpty()) result.appendLine()
                    // https://github.com/5310/discord-bot-castform/issues/2#issuecomment-1687783087
                    // https://docs.google.com/spreadsheets/d/1v51qbI1egh6eBTk-NTaRy3Qlx2Y2v9kDYqmvHlmntJE/edit
                    val (icon, windyOverride) = when (matcher.group(2)!!.toInt()) {
                        1, 2 -> R.drawable.ic_image_wb_sunny to true
                        3, 4 -> R.drawable.ic_partly_cloudy_day to true
                        5, 6, 7, 8, 37, 38 -> R.drawable.ic_file_cloud to true
                        11 -> R.drawable.ic_mist to false
                        12, 15, 18, 26, 29 -> R.drawable.ic_places_beach_access to false
                        13, 16, 20, 23, 40, 42 -> R.drawable.ic_file_cloud to false
                        14, 17, 21 -> R.drawable.ic_partly_cloudy_day to false
                        19, 22, 24, 25, 43, 44 -> R.drawable.ic_places_ac_unit to false
                        32 -> R.drawable.ic_family_link to false
                        33, 34 -> R.drawable.ic_image_bedtime to true
                        35, 36 -> R.drawable.ic_partly_cloudy_night to true
                        39, 41 -> R.drawable.ic_partly_cloudy_night to false
                        else -> 0 to false
                    }
                    val i = result.length
                    if (icon != 0) {
                        result.append("  ")
                        result.setSpan(ImageSpan(requireContext(), if (windyOverride &&
                            (matcher.group(4)!!.toInt() > 20 || matcher.group(5)?.run { toInt() > 30 } == true)) {
                            R.drawable.ic_family_link
                        } else icon), i, i + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    result.append("${if (daily) try {
                        dayFormat.parse(matcher.group(1), calendar, ParsePosition(0))
                        format.format(calendar.time)
                    } catch (e: ParseException) {
                        Timber.w(Exception(matcher.group(1)).initCause(e))
                        matcher.group(1)
                    } else {
                        format.format(matcher.group(1)!!.toLong() * 1000)
                    }}\n${matcher.group(3)}. ${matcher.group(4)}")
                    matcher.group(5)?.let { result.append("-$it") }
                    result.append(" km/h")
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
