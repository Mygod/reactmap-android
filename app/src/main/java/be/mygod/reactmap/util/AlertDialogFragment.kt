package be.mygod.reactmap.util

import android.app.Activity
import android.content.DialogInterface
import android.os.Bundle
import android.os.Parcelable
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.parcelize.Parcelize

/**
 * Based on: https://android.googlesource.com/platform/packages/apps/ExactCalculator/+/8c43f06/src/com/android/calculator2/AlertDialogFragment.java
 */
abstract class AlertDialogFragment<Arg : Parcelable, Ret : Parcelable> :
    DialogFragment(), DialogInterface.OnClickListener {
    companion object {
        private const val KEY_RESULT = "result"
        private const val KEY_ARG = "arg"
        private const val KEY_RET = "ret"
        private const val KEY_WHICH = "which"

        fun <Ret : Parcelable> setResultListener(activity: FragmentActivity, requestKey: String,
                                                 listener: (Int, Ret?) -> Unit) {
            activity.supportFragmentManager.setFragmentResultListener(requestKey, activity) { _, bundle ->
                listener(bundle.getInt(KEY_WHICH, Activity.RESULT_CANCELED), bundle.getParcelable(KEY_RET))
            }
        }
        fun <Ret : Parcelable> setResultListener(fragment: Fragment, requestKey: String,
                                                 listener: (Int, Ret?) -> Unit) {
            fragment.setFragmentResultListener(requestKey) { _, bundle ->
                listener(bundle.getInt(KEY_WHICH, Activity.RESULT_CANCELED), bundle.getParcelable(KEY_RET))
            }
        }
        inline fun <reified T : AlertDialogFragment<*, Ret>, Ret : Parcelable> setResultListener(
            activity: FragmentActivity, noinline listener: (Int, Ret?) -> Unit) =
            setResultListener(activity, T::class.java.name, listener)
        inline fun <reified T : AlertDialogFragment<*, Ret>, Ret : Parcelable> setResultListener(
            fragment: Fragment, noinline listener: (Int, Ret?) -> Unit) =
            setResultListener(fragment, T::class.java.name, listener)
    }
    protected abstract fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener)

    private val resultKey get() = requireArguments().getString(KEY_RESULT)
    protected val arg by lazy { requireArguments().getParcelable<Arg>(KEY_ARG)!! }
    protected open val ret: Ret? get() = null

    private fun args() = arguments ?: Bundle().also { arguments = it }
    fun arg(arg: Arg) = args().putParcelable(KEY_ARG, arg)
    fun key(resultKey: String = javaClass.name) = args().putString(KEY_RESULT, resultKey)

    override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog =
        MaterialAlertDialogBuilder(requireContext()).also { it.prepare(this) }.create()

    override fun onClick(dialog: DialogInterface?, which: Int) {
        setFragmentResult(resultKey ?: return, Bundle().apply {
            putInt(KEY_WHICH, which)
            putParcelable(KEY_RET, ret ?: return@apply)
        })
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        setFragmentResult(resultKey ?: return, bundleOf(KEY_WHICH to Activity.RESULT_CANCELED))
    }
}

@Parcelize
class Empty : Parcelable
