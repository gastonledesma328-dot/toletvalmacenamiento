package com.toletv.app.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class StreamSource(
    val url: String,
    val type: StreamType,
    val headers: Map<String, String> = emptyMap()
) : Parcelable
