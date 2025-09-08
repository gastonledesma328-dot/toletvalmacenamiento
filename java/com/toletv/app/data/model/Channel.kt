package com.toletv.app.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Channel(
    val id: String,
    val name: String,
    val logoUrl: String,
    val streamUrl: String,
    val streamType: StreamType = StreamType.M3U8,
    val isLive: Boolean = true,
    val category: String = "General",
    val description: String = "",
    val variants: ArrayList<StreamSource> = arrayListOf()
) : Parcelable

