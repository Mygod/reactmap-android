package be.mygod.reactmap.follower

import android.location.Location
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class LastLocation(
    val location: Location,
    val submittedLocation: Location? = null,
) : Parcelable
