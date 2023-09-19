package be.mygod.reactmap.util

import android.os.Parcel
import android.os.Parcelable
import android.os.RemoteException
import androidx.core.os.ParcelCompat
import java.lang.reflect.InvocationTargetException
import java.net.HttpURLConnection

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
