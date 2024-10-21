package io.deepmedia.tools.grease.sample.library

import android.os.Parcel
import android.os.Parcelable

class ActiveResultInfo() : Parcelable {
    constructor(parcel: Parcel) : this()

    override fun writeToParcel(parcel: Parcel, flags: Int) {
    }

    override fun describeContents(): Int = 0

    fun readFromParcel(`in`: Parcel) {
    }

    companion object CREATOR : Parcelable.Creator<ActiveResultInfo> {
        override fun createFromParcel(parcel: Parcel): ActiveResultInfo = ActiveResultInfo(parcel)
        override fun newArray(size: Int): Array<ActiveResultInfo?> = arrayOfNulls(size)
    }
}