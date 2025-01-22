package be.mygod.reactmap.auto

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.support.car.Car
import android.support.car.CarConnectionCallback
import android.support.car.CarInfoManager
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import androidx.fragment.app.commit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import be.mygod.reactmap.R
import be.mygod.reactmap.util.format
import com.google.android.apps.auto.sdk.CarActivity

class MainCarActivity @Keep constructor() : CarActivity(), LifecycleOwner {
    companion object {
        const val ACTION_CLOSE = "be.mygod.reactmap.auto.action.CLOSE"
    }
    override val lifecycle = LifecycleRegistry(this)
    private var currentFragment: CarReactMapFragment? = null

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        lifecycle.currentState = Lifecycle.State.CREATED
        setContentView(R.layout.layout_car)
        (findViewById(R.id.car_agreement) as TextView).apply {
            text = getText(R.string.car_agreement_template).format(resources.configuration.locales[0],
                getText(R.string.app_name))
            movementMethod = ScrollingMovementMethod()
        }
        val proceed = (findViewById(R.id.proceed) as Button).apply {
            setOnClickListener {
                supportFragmentManager.commit {
                    replace(R.id.content, CarReactMapFragment().also { currentFragment = it })
                }
            }
        }
        Car.createCar(this, object : CarConnectionCallback() {
            override fun onConnected(car: Car) {
                val isDriverRight = (car.getCarManager(CarInfoManager::class.java) as CarInfoManager).driverPosition ==
                        CarInfoManager.DRIVER_SIDE_RIGHT
                @SuppressLint("RtlHardcoded")
                (proceed.layoutParams as LinearLayout.LayoutParams).gravity =
                    if (isDriverRight) Gravity.LEFT else Gravity.RIGHT  // make the button away from driver's seat
                proceed.requestLayout()
            }
            override fun onDisconnected(car: Car?) { }
        }).apply {
            connect()
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) = disconnect()
            })
        }
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = killMap()
        }.let {
            ContextCompat.registerReceiver(this, it, IntentFilter(ACTION_CLOSE),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) = unregisterReceiver(it)
            })
        }
    }

    fun killMap() {
        val currentFragment = currentFragment ?: return
        currentFragment.terminate()
        supportFragmentManager.commit { remove(currentFragment) }
        this.currentFragment = null
    }

    override fun onStart() {
        super.onStart()
        lifecycle.currentState = Lifecycle.State.STARTED
    }
    override fun onResume() {
        super.onResume()
        lifecycle.currentState = Lifecycle.State.RESUMED
    }
    override fun onPause() {
        super.onPause()
        lifecycle.currentState = Lifecycle.State.STARTED
    }
    override fun onStop() {
        super.onStop()
        lifecycle.currentState = Lifecycle.State.CREATED
    }
    override fun onDestroy() {
        super.onDestroy()
        lifecycle.currentState = Lifecycle.State.DESTROYED
    }
}
