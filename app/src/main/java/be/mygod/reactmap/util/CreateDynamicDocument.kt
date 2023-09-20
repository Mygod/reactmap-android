package be.mygod.reactmap.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

class CreateDynamicDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>) = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .setType(input.first)
        .putExtra(Intent.EXTRA_TITLE, input.second)

    override fun getSynchronousResult(context: Context, input: Pair<String, String>) = null

    override fun parseResult(resultCode: Int, intent: Intent?) =
        intent.takeIf { resultCode == Activity.RESULT_OK }?.data
}
