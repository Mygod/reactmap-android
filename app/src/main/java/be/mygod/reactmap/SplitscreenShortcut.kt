package be.mygod.reactmap

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class SplitscreenShortcut : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // https://developer.android.com/develop/ui/views/launch/shortcuts/managing-shortcuts#start-one
        startActivity(Intent(this, MainActivity::class.java).setAction(intent.action))
        finish()
    }
}
