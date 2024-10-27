package be.mygod.reactmap.auto

import androidx.annotation.Keep
import com.google.android.apps.auto.sdk.CarActivityService

class MainService @Keep constructor() : CarActivityService() {
    override fun getCarActivity() = MainCarActivity::class.java
}
