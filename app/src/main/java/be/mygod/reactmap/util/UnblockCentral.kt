package be.mygod.reactmap.util

import android.annotation.SuppressLint
import android.os.Build
import android.system.Os
import androidx.annotation.RequiresApi
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.FileDescriptor

@SuppressLint("BlockedPrivateApi", "DiscouragedPrivateApi")
object UnblockCentral {
    /**
     * Retrieve this property before doing dangerous shit.
     */
    private val init by lazy { if (Build.VERSION.SDK_INT >= 28) check(HiddenApiBypass.setHiddenApiExemptions("")) }

    @get:RequiresApi(29)
    val fdsanGetOwnerTag by lazy {
        init.let {
            Class.forName("libcore.io.Os").getDeclaredMethod("android_fdsan_get_owner_tag", FileDescriptor::class.java)
        }
    }

    val getsockoptInt by lazy {
        init.let {
            Os::class.java.getDeclaredMethod("getsockoptInt", FileDescriptor::class.java, Int::class.java,
                Int::class.java)
        }
    }
    private val classStructLinger by lazy { init.let { Class.forName("android.system.StructLinger") } }
    val lingerReset by lazy {
        classStructLinger.getDeclaredConstructor(Int::class.java, Int::class.java).newInstance(1, 0)
    }
    val setsockoptLinger by lazy {
        Os::class.java.getDeclaredMethod("setsockoptLinger", FileDescriptor::class.java, Int::class.java,
            Int::class.java, classStructLinger)
    }
}
