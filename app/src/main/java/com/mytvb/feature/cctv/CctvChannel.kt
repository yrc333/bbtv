package com.mytvb.feature.cctv

import java.io.Serializable

data class CctvChannel(
    val number: Int,
    val id: String,
    val title: String,
    // 空串表示无自定义描述，UI 层回退到本地化的"央视官方直播"文案
    val description: String = "",
    val logoUrl: String = ""
) : Serializable
