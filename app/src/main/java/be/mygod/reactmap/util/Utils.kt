package be.mygod.reactmap.util

import android.os.Parcel
import android.os.Parcelable
import androidx.core.os.ParcelCompat
import java.net.HttpURLConnection

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
