package com.mytvb.model.video

import androidx.annotation.StringRes
import com.mytvb.R

/**
 * UP 主空间投稿视频排序。空间接口仅支持这三个值：
 * pubdate=最新发布 / click=最多播放 / stow=最多收藏（无按点赞排序）。
 */
enum class SpaceVideoOrder(
    val orderValue: String,
    @StringRes val nameRes: Int
) {
    PubDate("pubdate", R.string.model_search_order_pub_date),
    Click("click", R.string.space_order_most_played),
    Stow("stow", R.string.model_search_order_most_collection)
}
