package be.mygod.reactmap.auto

import com.google.android.apps.auto.sdk.CarActivityService

class MainService : CarActivityService() {
    override fun getCarActivity() = MainCarActivity::class.java
}
