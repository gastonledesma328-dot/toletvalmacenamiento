package com.toletv.app.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ZappingChannel(
    val id: Int,
    val name: String,
    val logo: String,
    val url: String,
    val type: StreamType
) : Parcelable
