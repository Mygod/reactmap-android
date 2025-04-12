package be.mygod.reactmap.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.IBinder
import android.system.Os
import androidx.annotation.RequiresApi
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.FileDescriptor

@SuppressLint("BlockedPrivateApi", "DiscouragedPrivateApi")
object UnblockCentral {
    /**
     * Retrieve this property before doing dangerous shit.
     */
    private val init by lazy { if (Build.VERSION.SDK_INT >= 28) check(HiddenApiBypass.setHiddenApiExemptions("")) }

    @get:RequiresApi(29)
    val fdsanGetOwnerTag by lazy {
        init
        Class.forName("libcore.io.Os").getDeclaredMethod("android_fdsan_get_owner_tag", FileDescriptor::class.java)
    }

    val getsockoptInt by lazy {
        init
        Os::class.java.getDeclaredMethod("getsockoptInt", FileDescriptor::class.java, Int::class.java, Int::class.java)
    }

    val shizukuActivity by lazy {
        init
        Class.forName("android.app.IActivityManager\$Stub").getDeclaredMethod("asInterface", IBinder::class.java)(null,
            ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.ACTIVITY_SERVICE)))
    }
}
