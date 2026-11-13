package com.mytvb.feature.home

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mytvb.event.AppEventHub
import com.mytvb.network.NetworkManager
import com.mytvb.core.ui.base.RecyclerViewPoolPrewarmer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class RecommendListFragment : VideoFeedFragment() {

    companion object {
        fun newInstance(): RecommendListFragment {
            return RecommendListFragment()
        }
    }

    private val appEventHub: AppEventHub by inject()
    private val viewModel: RecommendViewModel by viewModel()

    override val feedViewModel: VideoFeedViewModel
        get() = viewModel
    override val secondaryTabPosition: Int = 0
    override val dispatchHomeContentReady: Boolean = true
    override val enableTvListFocusController: Boolean = true
    override val deferInitialLoadUntilFirstDraw: Boolean = true
    override val showInitialLoadingIndicator: Boolean = true

    /**
     * 等待首屏数据的 ~500ms 里主线程基本空闲（请求已在 Application 层预加载发出），
     * 用这段空闲逐帧预建视频卡 ViewHolder：首批 16 张卡命中池子只需 bind，
     * 省掉一半 inflate。initialDelay 取 150ms 避开 Fragment 创建/首帧的忙碌期；
     * 数据到达（adapter 有数据）自动停，不会与真实渲染争时间片。
     * （133b814a 禁用是因为当时请求串行在 UI 之后、预热会抢壳首帧；预加载恢复后时序已反转。）
     */
    override val initialViewHolderPrewarmPlan: RecyclerViewPoolPrewarmer.Plan =
        RecyclerViewPoolPrewarmer.Plan(
            count = 12,
            budgetMs = 450L,
            maxPoolSize = 60,
            initialDelayMs = 150L,
            stopWhenAdapterHasItems = true
        )

    /**
     * 会话身份锚点：UserSessionChanged 是全局广播，-101 软清/自动恢复等会话抖动
     * （userInfo 短暂置空、cookie 仍在、mid 不变）也会触发。旧逻辑收到广播即整页
     * refresh + 回顶部，用户浏览中（按 DOWN 翻页时）会偶发"内容全部刷新"。
     * 此处仅在登录身份（mid）真实跳变（登录/登出/换号）时才刷新。
     */
    private var lastSessionMid: Long? = null
    private var sessionIdentityInitialized = false

    override fun initObserver() {
        super.initObserver()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                appEventHub.events.collectLatest { event ->
                    if (event == AppEventHub.Event.UserSessionChanged) {
                        onUserSessionChanged()
                    }
                }
            }
        }
    }

    private fun onUserSessionChanged() {
        val currentMid = NetworkManager.getUserInfo()?.mid?.takeIf { it > 0 }
        if (!sessionIdentityInitialized) {
            sessionIdentityInitialized = true
            lastSessionMid = currentMid
            return
        }
        // 软清中间态：userInfo 暂缺但会话 cookie 仍在，属于会话抖动，忽略；
        // 且不更新 lastSessionMid，后续恢复回同一 mid 时也不会触发刷新。
        if (currentMid == null && NetworkManager.isLoggedIn()) {
            return
        }
        if (currentMid == lastSessionMid) {
            return
        }
        lastSessionMid = currentMid
        // 隐藏（用户已退出推荐页）时不再自动刷新，避免"回头一看内容全变了"
        if (isResumed && !isHidden && !isLoading) {
            refresh()
        }
    }
}
