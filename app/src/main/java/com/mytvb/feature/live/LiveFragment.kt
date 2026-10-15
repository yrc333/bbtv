package com.mytvb.feature.live

import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.mytvb.R
import com.mytvb.databinding.FragmentLiveBinding
import com.mytvb.model.live.LiveAreaCategoryParent
import com.mytvb.core.ui.base.BaseFragment
import com.mytvb.ui.fragment.main.MainNavigationViewModel
import com.mytvb.ui.fragment.main.MainTabFocusTarget
import com.mytvb.core.ui.tab.enableTouchNavigation
import com.mytvb.core.ui.tab.focusNearestTabTo
import com.mytvb.core.ui.tab.disableAdjacentPagePrefetch
import com.mytvb.core.common.ext.toast
import com.mytvb.core.common.log.AppLog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class LiveFragment : BaseFragment<FragmentLiveBinding>(), MainTabFocusTarget {
    companion object {
        fun newInstance(): LiveFragment = LiveFragment()
    }

    private val viewModel: LiveViewModel by viewModel()
    private val liveRepository: com.mytvb.repository.LiveRepository by inject()
    private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels()
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var adapter: LiveFragmentAdapter
    private val categories = mutableListOf<LiveAreaCategoryParent>()
    private var tabSelectedListener: TabLayout.OnTabSelectedListener? = null

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentLiveBinding {
        return FragmentLiveBinding.inflate(inflater, container, false)
    }

    override fun initView() {
        tabLayout = binding.tabLayout
        viewPager = binding.viewPager

        adapter = LiveFragmentAdapter(
            childFragmentManager,
            lifecycle
        )
        viewPager.adapter = adapter
        viewPager.disableAdjacentPagePrefetch()

        // 立即展示"推荐"tab，不等分区列表返回
        val recommendCategory = LiveAreaCategoryParent(id = 0, name = getString(R.string.recommend))
        categories.clear()
        categories.add(recommendCategory)
        adapter.setCategories(categories)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = adapter.getPageTitle(position)
        }.attach()
        tabLayout.enableTouchNavigation(
            viewPager = viewPager,
            onNavigateDown = ::focusCurrentPagePrimaryContent,
            onTabReselected = {
                // 重选当前 tab 不再刷新数据（数据只在搜索/重开 App 时更新），仅回顶
                adapter.getCurrentFragment(viewPager.currentItem)?.scrollToTop()
            }
        )
        tabSelectedListener = object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = Unit

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) {
            }
        }.also { tabLayout.addOnTabSelectedListener(it) }
    }

    override fun initData() {
        preloadLatestRecommend()
        AppLog.d("LivePerf", "LiveFragment.initData: 触发加载分区")
        viewModel.loadLiveAreas()
    }

    private fun preloadLatestRecommend() {
        viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val startMs = android.os.SystemClock.elapsedRealtime()
            AppLog.d("LivePerf", "LiveFragment.preloadLatestRecommend: start")
            liveRepository.getLiveRecommend(forceRefresh = true)
                .onSuccess {
                    AppLog.d("LivePerf", "LiveFragment.preloadLatestRecommend: success elapsed=${android.os.SystemClock.elapsedRealtime() - startMs}ms")
                }
                .onFailure {
                    AppLog.e("LivePerf", "LiveFragment.preloadLatestRecommend: failed elapsed=${android.os.SystemClock.elapsedRealtime() - startMs}ms ${it.message}")
                }
        }
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.categories.collectLatest { list ->
                    if (list.isNotEmpty()) {
                        AppLog.d("LivePerf", "LiveFragment: 收到分区数据, 数量=${list.size}, 首个=${list.first().name}")
                        val previousItem = viewPager.currentItem
                        categories.clear()
                        categories.addAll(list)
                        adapter.setCategories(categories)
                        tabLayout.enableTouchNavigation(
                            viewPager = viewPager,
                            onNavigateDown = ::focusCurrentPagePrimaryContent,
                            onTabReselected = {
                                // 重选当前 tab 不再刷新数据，仅回顶
                                adapter.getCurrentFragment(viewPager.currentItem)?.scrollToTop()
                            }
                        )
                        viewPager.currentItem = previousItem.coerceIn(0, categories.lastIndex)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.error.collectLatest { error ->
                    if (!error.isNullOrBlank()) {
                        requireContext().toast(error)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainNavigationViewModel.events.collectLatest { event ->
                    if (isHidden) {
                        return@collectLatest
                    }
                    when (event) {
                        is MainNavigationViewModel.Event.MainTabSelected ->
                            if (event.index == 3) {
                                // 切回直播主标签不再按 TTL 重拉分区；仅对还没有数据的分区页做一次加载
                                adapter.getCurrentFragment(viewPager.currentItem)?.onTabSelected()
                            }

                        is MainNavigationViewModel.Event.MainTabReselected ->
                            if (event.index == 3) {
                                adapter.getCurrentFragment(viewPager.currentItem)?.onExplicitRefresh()
                            }

                        MainNavigationViewModel.Event.MenuPressed ->
                            adapter.getCurrentFragment(viewPager.currentItem)?.onExplicitRefresh()

                        MainNavigationViewModel.Event.BackPressed -> Unit
                        else -> Unit
                    }
                }
            }
        }
    }

    fun scrollToTop() {
        adapter.getCurrentFragment(viewPager.currentItem)?.scrollToTop()
    }

    fun focusCurrentTab(anchorView: View? = view?.findFocus() ?: activity?.currentFocus): Boolean {
        return binding.tabLayout.focusNearestTabTo(anchorView)
    }

    override fun focusEntryFromMainTab(): Boolean {
        AppLog.d("LiveFocus", "focusEntryFromMainTab: noAnchor")
        return focusEntryFromMainTab(anchorView = null, preferSpatialEntry = false)
    }

    override fun focusEntryFromMainTab(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        AppLog.d("LiveFocus", "focusEntryFromMainTab: anchor=${anchorView?.javaClass?.simpleName} spatial=$preferSpatialEntry page=${viewPager.currentItem}")
        if (preferSpatialEntry && anchorView != null && isVerticallyAlignedWith(anchorView, tabLayout)) {
            if (focusCurrentTab(anchorView)) {
                AppLog.d("LiveFocus", "focusEntryFromMainTab: spatial→tab OK")
                return true
            }
        }
        val contentHandled = focusCurrentPagePrimaryContent(anchorView, preferSpatialEntry)
        AppLog.d("LiveFocus", "focusEntryFromMainTab: contentHandled=$contentHandled")
        val handled = contentHandled || focusCurrentTab(anchorView)
        AppLog.d("LiveFocus", "focusEntryFromMainTab: final=$handled")
        return handled
    }

    private fun focusCurrentPagePrimaryContent(anchorView: View? = null, preferSpatialEntry: Boolean = false): Boolean {
        val page = adapter.getCurrentFragment(viewPager.currentItem)
        AppLog.d("LiveFocus", "focusCurrentPagePrimaryContent: page=${page?.javaClass?.simpleName} anchor=${anchorView?.javaClass?.simpleName} spatial=$preferSpatialEntry")
        return page?.focusPrimaryContent(anchorView, preferSpatialEntry) == true
    }

    override fun onDestroyView() {
        tabSelectedListener?.let { tabLayout.removeOnTabSelectedListener(it) }
        tabSelectedListener = null
        binding.viewPager.adapter = null
        super.onDestroyView()
    }

    private fun isVerticallyAlignedWith(anchor: View, target: View): Boolean {
        val anchorRect = Rect()
        val targetRect = Rect()
        if (!anchor.getGlobalVisibleRect(anchorRect)) return false
        if (!target.getGlobalVisibleRect(targetRect)) return false
        return maxOf(anchorRect.top, targetRect.top) < minOf(anchorRect.bottom, targetRect.bottom)
    }
}
