package be.mygod.reactmap.util

import android.os.Parcel
import android.os.Parcelable
import android.os.RemoteException
import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.core.os.ParcelCompat
import java.lang.reflect.InvocationTargetException
import java.net.HttpURLConnection
import java.net.URLConnection
import java.util.Locale

tailrec fun Throwable.getRootCause(): Throwable {
    if (this is InvocationTargetException || this is RemoteException) return (cause ?: return this).getRootCause()
    return this
}
val Throwable.readableMessage: String get() = getRootCause().run { localizedMessage ?: javaClass.name }

inline fun <T> useParcel(block: (Parcel) -> T) = Parcel.obtain().run {
    try {
        block(this)
    } finally {
        recycle()
    }
}

fun Parcelable?.toByteArray(parcelableFlags: Int = 0) = useParcel { p ->
    p.writeParcelable(this, parcelableFlags)
    p.marshall()
}
inline fun <reified T : Parcelable> ByteArray.toParcelable(classLoader: ClassLoader? = T::class.java.classLoader) =
    useParcel { p ->
        p.unmarshall(this, 0, size)
        p.setDataPosition(0)
        ParcelCompat.readParcelable(p, classLoader, T::class.java)
    }

val HttpURLConnection.findErrorStream get() = errorStream ?: inputStream

private val formatSequence = "%([0-9]+\\$|<?)([^a-zA-z%]*)([[a-zA-Z%]&&[^tT]]|[tT][a-zA-Z])".toPattern()
/**
 * Version of [String.format] that works on [Spanned] strings to preserve rich text formatting.
 * Both the `format` as well as any `%s args` can be Spanned and will have their formatting preserved.
 * Due to the way [Spannable]s work, any argument's spans will can only be included **once** in the result.
 * Any duplicates will appear as text only.
 *
 * See also: https://github.com/george-steel/android-utils/blob/289aff11e53593a55d780f9f5986e49343a79e55/src/org/oshkimaadziig/george/androidutils/SpanFormatter.java
 *
 * @param locale
 * the locale to apply; `null` value means no localization.
 * @param args
 * the list of arguments passed to the formatter.
 * @return the formatted string (with spans).
 * @see String.format
 * @author George T. Steel
 */
fun CharSequence.format(locale: Locale, vararg args: Any) = SpannableStringBuilder(this).apply {
    var i = 0
    var argAt = -1
    while (i < length) {
        val m = formatSequence.matcher(this)
        if (!m.find(i)) break
        i = m.start()
        val exprEnd = m.end()
        val argTerm = m.group(1)!!
        val modTerm = m.group(2)
        val cookedArg = when (val typeTerm = m.group(3)) {
            "%" -> "%"
            "n" -> "\n"
            else -> {
                val argItem = args[when (argTerm) {
                    "" -> ++argAt
                    "<" -> argAt
                    else -> Integer.parseInt(argTerm.substring(0, argTerm.length - 1)) - 1
                }]
                if (typeTerm == "s" && argItem is Spanned) argItem else {
                    String.format(locale, "%$modTerm$typeTerm", argItem)
                }
            }
        }
        replace(i, exprEnd, cookedArg)
        i += cookedArg.length
    }
}

val URLConnection.headerLocation get() = getHeaderField("Location")
