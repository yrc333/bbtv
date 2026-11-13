package com.mytvb.ui.activity

import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.OptIn
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.commit
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import com.mytvb.R
import com.mytvb.MyBLBLApplication
import com.mytvb.databinding.ActivityMainBinding
import com.mytvb.event.AppEventHub
import com.mytvb.network.NetworkManager
import com.mytvb.network.session.NetworkSessionGateway
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.base.BaseActivity
import com.mytvb.core.ui.base.OnBackPressedHandler
import com.mytvb.feature.home.RecommendListFragment
import com.mytvb.feature.me.MeListFragment
import com.mytvb.feature.search.SearchNewFragment
import com.mytvb.feature.settings.SettingsFragment
import com.mytvb.feature.settings.SignInFragment
import com.mytvb.feature.user.FollowUserListFragment
import com.mytvb.ui.dialog.UsageTipDialog
import com.mytvb.ui.dialog.UserInfoDialog
import com.mytvb.feature.player.PlayerInstancePool
import com.mytvb.feature.player.VideoPlayerFragment
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.net.NetworkRecoveryMonitor
import com.mytvb.core.startup.AppStartupScheduler
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.ui.fragment.main.MainMenuFragment
import com.mytvb.ui.fragment.main.MainNavigationViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.lang.ref.WeakReference

@OptIn(UnstableApi::class)
class MainActivity : BaseActivity<ActivityMainBinding>() {

    private data class FocusRestoreAnchor(
        val viewRef: WeakReference<View>
    )

    companion object {
        private const val SETTINGS_OVERLAY_TAG = "settings"
        private const val SETTINGS_OVERLAY_EXIT_ANIM_MS = 275L
        private const val MENU_TAG = "main_menu"
        private const val MENU_PAGE_TAG_PREFIX = "menu_page_"
        private const val STARTUP_TAG = "AppStartup"
        private const val TEEN_REST_DIAG_TAG = "TeenRestDiag"
    }

    /** 青少年模式：休息遮罩倒计时 Handler，每秒刷新剩余时间。 */
    private val teenRestHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val teenRestTicker = object : Runnable {
        override fun run() {
            updateTeenRestCountdown()
            // 还有剩余时间才继续，否则 updateTeenRestCountdown 内部会撤掉遮罩并停止
            if (binding.teenRestOverlay.visibility == View.VISIBLE) {
                teenRestHandler.postDelayed(this, 1000L)
            }
        }
    }

    private val appEventHub: AppEventHub by inject()
    private val mainNavigationViewModel: MainNavigationViewModel by viewModels()
    private val sessionGateway: NetworkSessionGateway by inject()
    private val personalFeedPrewarmer: com.mytvb.repository.PersonalFeedPrewarmer by inject()
    private var exitTime: Long = 0
    private val exitInterval = 2000L
    private val focusRestoreAnchors = ArrayDeque<FocusRestoreAnchor>()
    private val pageFocusAnchors = mutableMapOf<String, WeakReference<View>>()
    private var lastBackStackEntryCount = 0
    private var pendingFocusRestoreDelayMs = 0L
    private var startupTasksScheduled = false
    private var startupShellRevealed = false
    private var startupInitialContentAttached = false
    private var homeContentReadyLogged = false
    private val activityCreateStartMs = SystemClock.elapsedRealtime()
    private var lastKnownLoggedIn = false
    private var restoredFromSavedState = false

    override fun getViewBinding(): ActivityMainBinding {
        val t0 = SystemClock.elapsedRealtime()
        AppLog.i(STARTUP_TAG, "STARTUP activity_main inflate start")
        val binding = ActivityMainBinding.inflate(layoutInflater)
        AppLog.i(STARTUP_TAG, "STARTUP getViewBinding elapsed=${SystemClock.elapsedRealtime() - t0}ms")
        return binding
    }

    override fun deferInitialFullscreenMode(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        val createStartMs = SystemClock.elapsedRealtime()
        AppLog.i(STARTUP_TAG, "STARTUP T1b MainActivity.onCreate start")
        if (savedInstanceState == null && shouldFinishDuplicateLauncherLaunch()) {
            super.onCreate(savedInstanceState)
            finish()
            return
        }
        (application as? MyBLBLApplication)?.ensureSessionRuntimeReady("MainActivity.onCreate/sessionState")
        restoredFromSavedState = savedInstanceState != null
        val t0 = SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        AppLog.i(STARTUP_TAG, "STARTUP super.onCreate elapsed=${SystemClock.elapsedRealtime() - t0}ms")
        lastKnownLoggedIn = sessionGateway.isLoggedIn()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPressed()
            }
        })
        supportFragmentManager.addOnBackStackChangedListener {
            val currentCount = supportFragmentManager.backStackEntryCount
            if (currentCount < lastBackStackEntryCount) {
                restoreFocusAfterOverlayPop()
            }
            lastBackStackEntryCount = currentCount
        }
        lastBackStackEntryCount = supportFragmentManager.backStackEntryCount
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainNavigationViewModel.events.collect { event ->
                    if (event == MainNavigationViewModel.Event.HomeContentReady) {
                        onHomeContentReady()
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                coroutineScope {
                    launch {
                        // 订阅会话单一状态源：登录/登出跳变时补广播给各页面
                        sessionGateway.sessionState.collect { state ->
                            val loggedIn = state.isLoggedIn
                            if (loggedIn != lastKnownLoggedIn) {
                                lastKnownLoggedIn = loggedIn
                                appEventHub.dispatch(AppEventHub.Event.UserSessionChanged)
                            }
                        }
                    }
                    launch {
                        appEventHub.events.collect { event ->
                            if (event == AppEventHub.Event.UserSessionChanged) {
                                lastKnownLoggedIn = sessionGateway.isLoggedIn()
                            }
                            if (event is AppEventHub.Event.VideoBlockedByMinorProtection) {
                                dispatchVideoBlockedEventToCurrentFragment(event)
                            }
                        }
                    }
                }
            }
        }
        AppLog.i(STARTUP_TAG, "STARTUP T1c MainActivity.onCreate end elapsed=${SystemClock.elapsedRealtime() - createStartMs}ms totalFromActivityMs=${SystemClock.elapsedRealtime() - activityCreateStartMs}ms")
    }

    override fun initView() {
        val t0 = SystemClock.elapsedRealtime()
        binding.root.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            AppLog.d(
                "FocusTrace",
                "globalFocusChange: old=${old?.javaClass?.simpleName}#${old?.id} " +
                    "new=${new?.javaClass?.simpleName}#${new?.id}"
            )
        }
        NetworkRecoveryMonitor.start(this) {
            appEventHub.dispatch(AppEventHub.Event.NetworkRecovered)
        }
        applyBackgroundImage()
        AppLog.i(STARTUP_TAG, "STARTUP MainActivity.initView elapsed=${SystemClock.elapsedRealtime() - t0}ms")
    }

    override fun initData() {
        val startMs = SystemClock.elapsedRealtime()
        AppLog.i(STARTUP_TAG, "MainActivity.initData start")
        ensureMenuFragmentAttached()
        binding.root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (binding.root.viewTreeObserver.isAlive) {
                    binding.root.viewTreeObserver.removeOnPreDrawListener(this)
                }
                AppLog.i(
                    STARTUP_TAG,
                    "STARTUP T3a firstPreDraw elapsed=${SystemClock.elapsedRealtime() - activityCreateStartMs}ms"
                )
                revealStartupShell("first_pre_draw")
                // 壳首帧已提交，立刻在 IO 线程预热网络并发出推荐首页请求。
                // 用户从菜单进入「推荐」时 loadSharedFirstPage 直接命中在途请求，
                // 500ms 级网络 RTT 与菜单首帧并行。
                (application as? MyBLBLApplication)?.scheduleStartupFirstPagePreload(delayMillis = 0L)
                Choreographer.getInstance().postFrameCallback {
                    binding.root.post {
                        attachInitialContentAfterShellDraw()
                    }
                }
                return true
            }
        })
        // 壳层先出第一帧；菜单页、网络预加载和提示弹窗下一帧再进入。
        revealStartupShell("shell_initialized")
        binding.root.post {
            AppLog.i(STARTUP_TAG, "MainActivity first root post elapsed=${SystemClock.elapsedRealtime() - activityCreateStartMs}ms")
            revealStartupShell("first_root_post")
        }
        AppLog.i(STARTUP_TAG, "MainActivity.initData end elapsed=${SystemClock.elapsedRealtime() - startMs}ms")
    }

    private fun attachInitialContentAfterShellDraw() {
        if (startupInitialContentAttached) return
        startupInitialContentAttached = true
        val startMs = SystemClock.elapsedRealtime()
        AppLog.i(
            STARTUP_TAG,
            "STARTUP initialContentPipeline start reason=after_shell_first_frame elapsed=${startMs - activityCreateStartMs}ms"
        )
        ensureMenuFragmentAttached()
        scheduleDeferredStartupTasks()
        showUsageTipIfNeeded()
        AppLog.i(
            STARTUP_TAG,
            "STARTUP initialContentPipeline end reason=after_shell_first_frame elapsed=${SystemClock.elapsedRealtime() - startMs}ms"
        )
    }

    private fun applyBackgroundImage() {
        val themeIndex = appSettings.getCachedInt("theme", 1)
        if (themeIndex == 3) {
            binding.mainBackgroundImage.apply {
                setBackgroundResource(R.drawable.background_image)
                visibility = View.VISIBLE
            }
        } else {
            binding.mainBackgroundImage.apply {
                background = null
                visibility = View.GONE
            }
        }
    }

    /** 菜单页作为唯一常驻页面，全屏功能页都以返回栈叠加在其上。 */
    private fun ensureMenuFragmentAttached() {
        if (supportFragmentManager.findFragmentByTag(MENU_TAG) != null) return
        supportFragmentManager.commitNow {
            setReorderingAllowed(true)
            add(R.id.container, MainMenuFragment.newInstance(), MENU_TAG)
        }
        binding.root.post { focusMenu() }
    }

    fun openMainMenuEntry(entry: MainMenuFragment.Entry) {
        when (entry) {
            MainMenuFragment.Entry.FOLLOWING -> {
                val mid = sessionGateway.getUserInfo()?.mid ?: 0L
                if (sessionGateway.isLoggedIn() && mid > 0L) {
                    showMenuPage(entry)
                } else {
                    showMenuPage(MainMenuFragment.Entry.SIGN_IN)
                }
            }

            MainMenuFragment.Entry.HISTORY -> {
                if (sessionGateway.isLoggedIn()) {
                    showMenuPage(entry)
                } else {
                    showMenuPage(MainMenuFragment.Entry.SIGN_IN)
                }
            }

            MainMenuFragment.Entry.SIGN_IN -> {
                if (sessionGateway.isLoggedIn()) {
                    showUserInfoDialog()
                } else {
                    showMenuPage(entry)
                }
            }

            else -> showMenuPage(entry)
        }
    }

    /**
     * 功能页常驻：首次选中时创建并加载；之后再次选中只做 show/hide，
     * 不重建 Fragment、不重新请求首屏（内容保持上次离开时的状态），
     * 列表触底翻页仍由各页面自己的分页逻辑负责。
     */
    private fun showMenuPage(entry: MainMenuFragment.Entry) {
        val tag = menuPageTag(entry)
        val target = supportFragmentManager.findFragmentByTag(tag) ?: createMenuPage(entry)
        if (target.isVisible) return
        val isCachedPage = target.isAdded

        // 记录当前页退出前的焦点，下次进入恢复到同一控件
        visibleMenuPage()?.let { (currentEntry, page) ->
            page.view?.findFocus()?.let { focused ->
                pageFocusAnchors[menuPageTag(currentEntry)] = WeakReference(focused)
            }
        }

        supportFragmentManager.commitNow {
            setReorderingAllowed(true)
            supportFragmentManager.fragments
                .filter { it.isAdded && it.isVisible }
                .forEach { hide(it) }
            if (target.isAdded) {
                show(target)
            } else {
                add(R.id.container, target, tag)
            }
        }
        // 缓存页在同一帧内直接恢复焦点；新页面交给自身的首焦逻辑，避免抢焦
        if (!requestPageFocus(entry, target) && isCachedPage) {
            binding.root.post { requestPageFocus(entry, target) }
        }
    }

    private fun createMenuPage(entry: MainMenuFragment.Entry): Fragment = when (entry) {
        MainMenuFragment.Entry.RECOMMEND -> RecommendListFragment.newInstance()
        MainMenuFragment.Entry.SEARCH -> SearchNewFragment.newInstance()
        MainMenuFragment.Entry.FOLLOWING -> FollowUserListFragment.newInstance(
            sessionGateway.getUserInfo()?.mid ?: 0L,
            FollowUserListFragment.TYPE_FOLLOWING
        )
        MainMenuFragment.Entry.HISTORY -> MeListFragment.newInstance(MeListFragment.TYPE_HISTORY)
        MainMenuFragment.Entry.SIGN_IN -> SignInFragment.newInstance()
        MainMenuFragment.Entry.SETTINGS -> SettingsFragment.newInstance()
    }

    private fun menuPageTag(entry: MainMenuFragment.Entry): String =
        MENU_PAGE_TAG_PREFIX + entry.name

    private fun visibleMenuPage(): Pair<MainMenuFragment.Entry, Fragment>? {
        val page = supportFragmentManager.fragments.firstOrNull {
            it.isVisible && it.tag?.startsWith(MENU_PAGE_TAG_PREFIX) == true
        } ?: return null
        val entryName = page.tag?.removePrefix(MENU_PAGE_TAG_PREFIX) ?: return null
        val entry = MainMenuFragment.Entry.values().firstOrNull { it.name == entryName } ?: return null
        return entry to page
    }

    /** BACK / 页面返回键：隐藏当前功能页（保留实例与内容），回到菜单并聚焦对应入口。 */
    private fun closeMenuPage(entry: MainMenuFragment.Entry, page: Fragment) {
        page.view?.findFocus()?.let { focused ->
            pageFocusAnchors[menuPageTag(entry)] = WeakReference(focused)
        }
        val menu = supportFragmentManager.findFragmentByTag(MENU_TAG)
        supportFragmentManager.commitNow {
            setReorderingAllowed(true)
            hide(page)
            if (menu != null) {
                show(menu)
            } else {
                add(R.id.container, MainMenuFragment.newInstance(), MENU_TAG)
            }
        }
        // 同一帧内直接落焦：避免系统先恢复到首项、下一帧再跳到目标项，造成两段高亮闪烁
        val menuFragment = supportFragmentManager.findFragmentByTag(MENU_TAG) as? MainMenuFragment
        if (menuFragment?.focusItem(entry) == true) {
            return
        }
        binding.root.post {
            val fallbackMenu = supportFragmentManager.findFragmentByTag(MENU_TAG) as? MainMenuFragment
            if (fallbackMenu?.focusItem(entry) != true) {
                focusMenu()
            }
        }
    }

    /** 恢复功能页内的焦点；返回是否已成功落到页面内。 */
    private fun requestPageFocus(entry: MainMenuFragment.Entry, page: Fragment): Boolean {
        val saved = pageFocusAnchors[menuPageTag(entry)]?.get()
        if (saved != null && saved.isAttachedToWindow && saved.isShown && saved.requestFocus()) {
            return true
        }
        if (saved == null) {
            return false
        }
        val root = page.view ?: return false
        val focusTarget = root.findFocus() ?: root.focusSearch(View.FOCUS_DOWN) ?: return false
        return focusTarget.isShown && focusTarget.isFocusable && focusTarget.requestFocus()
    }

    private fun revealStartupShell(reason: String) {
        if (startupShellRevealed) return
        startupShellRevealed = true
        AppLog.i(
            STARTUP_TAG,
            "STARTUP T3 revealShell reason=$reason elapsed=${SystemClock.elapsedRealtime() - activityCreateStartMs}ms"
        )
        window.setBackgroundDrawableResource(R.color.systemBackgroundColor)
    }

    private fun onHomeContentReady() {
        if (!homeContentReadyLogged) {
            homeContentReadyLogged = true
            AppLog.i(
                STARTUP_TAG,
                "STARTUP T4 homeContentReady elapsed=${SystemClock.elapsedRealtime() - activityCreateStartMs}ms"
            )
        }
        revealStartupShell("home_content_ready")
    }

    private fun scheduleDeferredStartupTasks() {
        if (startupTasksScheduled) {
            return
        }
        startupTasksScheduled = true
        AppStartupScheduler()
            .addTask("imageCdnPrewarm", AppStartupScheduler.Phase.DELAYED, delayMs = 2500L) {
                ImageLoader.prewarmCdn()
            }
            // 个人数据预取：历史/稍后观看/收藏夹列表都是点进 tab 才发请求，真机 RTT 下
            // 用户要干等整个网络往返。首屏稳定后（1.8s）后台预取第一页，进"我的"直接渲染。
            .addTask("personalFeedPrewarm", AppStartupScheduler.Phase.DELAYED, delayMs = 1800L) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        (application as? MyBLBLApplication)?.ensureDataRuntimeReady("personalFeedPrewarm")
                    }
                    if (sessionGateway.isLoggedIn()) {
                        personalFeedPrewarmer.prewarm()
                    }
                }
            }
            // player 与 security 预热放 IDLE 阶段：首页 firstPreDraw 后的空闲间隙立即触发，
            // 远早于 DELAYED 3-3.5s。实测用户常在启动后 3s 左右点视频，DELAYED 3s/3.5s 会
            // 导致预热赶不上首播（security 仍串行阻塞 playinfo ~120ms，player 仍冷建 ~56ms）。
            // IDLE 在主线程空闲时跑，不抢首页渲染。
            .addTask("playerPrewarm", AppStartupScheduler.Phase.IDLE) {
                PlayerInstancePool.prewarm(this@MainActivity)
            }
            .addTask("securityPrewarm", AppStartupScheduler.Phase.IDLE) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { NetworkManager.ensureHealthyForPlay() }
                    }
                }
            }
            .addTask("wbiKeys", AppStartupScheduler.Phase.IDLE) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        (application as? MyBLBLApplication)?.ensureDataRuntimeReady("wbiKeys")
                    }
                    runCatching { sessionGateway.ensureWbiKeys() }
                }
            }
            .execute()
    }

    /**
     * 菜单模式下没有左侧功能栏，列表左边缘不再有可聚焦的落点。
     * 保留该入口供各列表的 LEFT 边缘逻辑调用，行为为「不处理」，交由框架默认焦点搜索。
     */
    fun focusLeftFunctionArea(sourceView: View? = currentFocus): Boolean = false

    private fun handleBackPressed() {
        if (dispatchBackPressedToVisibleFragment()) {
            schedulePostBackFocusRestore()
            return
        }

        if (supportFragmentManager.backStackEntryCount > 0) {
            pendingFocusRestoreDelayMs =
                if (isSettingsOverlayVisible()) SETTINGS_OVERLAY_EXIT_ANIM_MS else 0L
            supportFragmentManager.popBackStack()
            return
        }

        val menuPage = visibleMenuPage()
        if (menuPage != null) {
            closeMenuPage(menuPage.first, menuPage.second)
            return
        }

        mainNavigationViewModel.dispatch(MainNavigationViewModel.Event.BackPressed)

        if (System.currentTimeMillis() - exitTime <= exitInterval) {
            finish()
            return
        }

        exitTime = System.currentTimeMillis()
        AppToast.show(this, getString(R.string.app_exit))
        schedulePostBackFocusRestore()
    }

    private fun schedulePostBackFocusRestore() {
        binding.root.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val currentFocus = currentFocus
            if (currentFocus != null && currentFocus.isAttachedToWindow && currentFocus.isShown && currentFocus.isFocusable) {
                return@postDelayed
            }
            restoreFocusForCurrentScreen()
        }, 100L)
    }

    fun closeTopOverlayFromUi() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            pendingFocusRestoreDelayMs =
                if (isSettingsOverlayVisible()) SETTINGS_OVERLAY_EXIT_ANIM_MS else 0L
            supportFragmentManager.popBackStack()
            return
        }
        val menuPage = visibleMenuPage()
        if (menuPage != null) {
            closeMenuPage(menuPage.first, menuPage.second)
            return
        }
        handleBackPressed()
    }

    private fun dispatchBackPressedToVisibleFragment(): Boolean {
        val topFragment = supportFragmentManager.fragments
            .asReversed()
            .firstOrNull { it.isVisible }
            ?: return false
        return topFragment.findBackPressedHandler()?.onBackPressed() == true
    }

    private fun Fragment.findBackPressedHandler(): OnBackPressedHandler? {
        childFragmentManager.fragments
            .asReversed()
            .firstOrNull { it.isVisible }
            ?.findBackPressedHandler()
            ?.let { return it }
        return this as? OnBackPressedHandler
    }

    fun openOverlayFragment(fragment: Fragment, tag: String) {
        openFragmentWithReferenceBehavior(fragment, tag, addToBackStack = true)
    }

    fun openInHostContainer(fragment: Fragment, addToBackStack: Boolean = true) {
        openFragmentWithReferenceBehavior(
            fragment = fragment,
            tag = fragment::class.java.name,
            addToBackStack = addToBackStack
        )
    }

    private fun openFragmentWithReferenceBehavior(
        fragment: Fragment,
        tag: String,
        addToBackStack: Boolean
    ) {
        if (addToBackStack) {
            currentFocus?.let { activeFocus ->
                focusRestoreAnchors.addLast(
                    FocusRestoreAnchor(
                        viewRef = WeakReference(activeFocus)
                    )
                )
            }
        }
        val isSettingsOverlay = isSettingsOverlay(fragment = fragment, tag = tag)

        supportFragmentManager.fragments
            .asReversed()
            .firstOrNull { it.isVisible }
            ?.view
            ?.clearFocus()

        supportFragmentManager.commit {
            if (isSettingsOverlay) {
                setCustomAnimations(
                    R.anim.m3_side_sheet_enter_from_right,
                    0,
                    0,
                    R.anim.m3_side_sheet_exit_to_right
                )
            } else {
                setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            }

            supportFragmentManager.fragments
                .filter { it.isAdded && it.isVisible }
                .forEach { visibleFragment ->
                    hide(visibleFragment)
                }

            add(R.id.container, fragment, tag)
            if (addToBackStack) {
                addToBackStack(tag)
            }
        }
    }

    fun showUserInfoDialog() {
        if (isFinishing || isDestroyed) {
            return
        }
        UserInfoDialog(this).show()
    }

    private fun showUsageTipIfNeeded() {
        if (restoredFromSavedState) return
        if (appSettings.getCachedBoolean("usage_tip_shown")) return
        if (isFinishing || isDestroyed) return
        appSettings.putBooleanAsync("usage_tip_shown", true)
        UsageTipDialog(this).show()
    }

    private fun dispatchVideoBlockedEventToCurrentFragment(event: AppEventHub.Event.VideoBlockedByMinorProtection) {
        supportFragmentManager.fragments.forEach { fragment ->
            if (fragment is OnVideoBlockedListener) {
                (fragment as OnVideoBlockedListener).onVideoBlocked(event.aid, event.bvid)
            }
            fragment.childFragmentManager.fragments.forEach { child ->
                if (child is OnVideoBlockedListener) {
                    (child as OnVideoBlockedListener).onVideoBlocked(event.aid, event.bvid)
                }
            }
        }
    }

    interface OnVideoBlockedListener {
        fun onVideoBlocked(aid: Long, bvid: String)
    }

    private fun restoreFocusAfterOverlayPop() {
        val anchor = focusRestoreAnchors.removeLastOrNull()
        val delayMs = pendingFocusRestoreDelayMs
        pendingFocusRestoreDelayMs = 0L
        binding.root.postDelayed({
            val target = anchor?.viewRef?.get()
            if (target?.isAttachedToWindow == true && target.isShown && target.isFocusable) {
                val result = target.requestFocus()
                if (result) {
                    return@postDelayed
                }
            }
            restoreFocusForCurrentScreen()
        }, delayMs)
    }

    fun skipNextFocusRestore() {
        if (focusRestoreAnchors.isNotEmpty()) {
            focusRestoreAnchors.removeLastOrNull()
        }
    }

    private fun isSettingsOverlay(fragment: Fragment, tag: String): Boolean {
        return tag == SETTINGS_OVERLAY_TAG || fragment is SettingsFragment
    }

    private fun isVideoPlayerOverlay(fragment: Fragment, tag: String): Boolean {
        return fragment is VideoPlayerFragment || tag.startsWith("video_player:")
    }

    private fun isSettingsOverlayVisible(): Boolean {
        val topTag = supportFragmentManager.fragments
            .asReversed()
            .firstOrNull { it.isVisible }
            ?.tag
        return topTag == SETTINGS_OVERLAY_TAG ||
            topTag == menuPageTag(MainMenuFragment.Entry.SETTINGS)
    }

    /** 焦点兜底落到菜单首个入口（返回后锚点失效、或菜单首次展示时）。 */
    private fun focusMenu(): Boolean {
        if (supportFragmentManager.backStackEntryCount > 0) {
            return false
        }
        if (visibleMenuPage() != null) {
            return false
        }
        val menu = supportFragmentManager.findFragmentByTag(MENU_TAG) as? MainMenuFragment
        return menu?.focusFirstItem() == true
    }

    /** 按当前可见界面恢复焦点：功能页优先，其次菜单。 */
    private fun restoreFocusForCurrentScreen() {
        val menuPage = visibleMenuPage()
        if (menuPage != null) {
            requestPageFocus(menuPage.first, menuPage.second)
            return
        }
        focusMenu()
    }

    private fun shouldFinishDuplicateLauncherLaunch(): Boolean {
        val launchIntent = intent
        return !isTaskRoot &&
            launchIntent?.action == android.content.Intent.ACTION_MAIN &&
            launchIntent.hasCategory(android.content.Intent.CATEGORY_LAUNCHER)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        // 青少年模式：休息遮罩可见时，拦截除返回键外的所有按键，防止操作背后内容
        if (binding.teenRestOverlay.visibility == View.VISIBLE &&
            keyCode != android.view.KeyEvent.KEYCODE_BACK
        ) {
            return true
        }
        // MENU 键不再触发刷新：页面数据只在真正需要时加载（见各 tab 的 onTabSelected），
        // 不再由按键主动重拉，避免"切标签/按键就自动更新"。
        return super.onKeyDown(keyCode, event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        AppLog.i(TEEN_REST_DIAG_TAG, "onResume start")
        super.onResume()
        AppLog.i(TEEN_REST_DIAG_TAG, "onResume after super, before refresh")
        refreshTeenRestOverlay()
    }

    override fun onPause() {
        super.onPause()
        teenRestHandler.removeCallbacks(teenRestTicker)
        AppLog.i(TEEN_REST_DIAG_TAG, "onPause")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        AppLog.i(TEEN_REST_DIAG_TAG, "onWindowFocusChanged hasFocus=$hasFocus")
        dumpTeenRestOverlayState("onWindowFocusChanged($hasFocus)")
    }

    /** 检查青少年模式休息状态：休息中显示遮罩并启动倒计时，否则确保遮罩隐藏。 */
    private fun refreshTeenRestOverlay() {
        val resting = com.mytvb.core.common.content.TeenModeTimer.isResting()
        AppLog.i(TEEN_REST_DIAG_TAG, "refreshTeenRestOverlay isResting=$resting")
        if (resting) {
            showTeenRestOverlay()
        } else {
            hideTeenRestOverlay()
        }
    }

    private fun showTeenRestOverlay() {
        binding.teenRestOverlay.visibility = View.VISIBLE
        updateTeenRestCountdown()
        teenRestHandler.removeCallbacks(teenRestTicker)
        teenRestHandler.postDelayed(teenRestTicker, 1000L)
        // 延迟一帧后再 dump 一次，看 measure/layout 后的真实尺寸（排除未触发布局）
        binding.teenRestOverlay.post { dumpTeenRestOverlayState("showTeenRestOverlay+postFrame") }
        dumpTeenRestOverlayState("showTeenRestOverlay")
    }

    private fun hideTeenRestOverlay() {
        binding.teenRestOverlay.visibility = View.GONE
        teenRestHandler.removeCallbacks(teenRestTicker)
        dumpTeenRestOverlayState("hideTeenRestOverlay")
    }

    /** 用墙钟算剩余时间并刷新文字，到期则撤掉遮罩。 */
    private fun updateTeenRestCountdown() {
        val restStart = com.mytvb.core.common.content.TeenModeTimer.getRestStartMs()
        val restLimitMs = com.mytvb.core.common.content.TeenModeTimer.getRestLimitMs()
        if (restStart <= 0L || restLimitMs <= 0L) {
            AppLog.i(TEEN_REST_DIAG_TAG, "updateTeenRestCountdown early-return: restStart=$restStart restLimitMs=$restLimitMs")
            hideTeenRestOverlay()
            return
        }
        val remainingMs = restLimitMs - (System.currentTimeMillis() - restStart)
        if (remainingMs <= 0L) {
            // 休息到期：撤掉遮罩（isResting 下次调用会自动清零累计）
            AppLog.i(TEEN_REST_DIAG_TAG, "updateTeenRestCountdown rest expired, remainingMs=$remainingMs")
            hideTeenRestOverlay()
            return
        }
        val totalSec = (remainingMs / 1000L).toInt()
        val min = totalSec / 60
        val sec = totalSec % 60
        binding.teenRestCountdown.text = getString(R.string.teen_rest_countdown, min, sec)
    }

    /**
     * 诊断专用：dump 遮罩及其两个 TextView 的可见性/尺寸/绘制状态，以及它在 DecorView 中的层级。
     * 只读，不改动任何状态。用于定位"纯黑屏、倒计时不显示"问题。
     */
    private fun dumpTeenRestOverlayState(reason: String) {
        try {
            val overlay = binding.teenRestOverlay
            val title = binding.teenRestTitle
            val countdown = binding.teenRestCountdown
            val sb = StringBuilder()
            sb.append("dump[$reason]: ")
            sb.append("overlay{vis=").append(visibilityName(overlay.visibility))
                .append(",shown=").append(overlay.isShown)
                .append(",w=").append(overlay.width).append(",h=").append(overlay.height)
                .append(",alpha=").append(overlay.alpha)
                .append(",bg=").append(overlay.background?.javaClass?.simpleName)
                .append(",layer=").append(findViewLayerIndex(overlay)).append("}")
            sb.append(" title{vis=").append(visibilityName(title.visibility))
                .append(",shown=").append(title.isShown)
                .append(",w=").append(title.width).append(",h=").append(title.height)
                .append(",text=").append(title.text).append("}")
            sb.append(" countdown{vis=").append(visibilityName(countdown.visibility))
                .append(",shown=").append(countdown.isShown)
                .append(",w=").append(countdown.width).append(",h=").append(countdown.height)
                .append(",text=").append(countdown.text).append("}")
            // window 层状态
            val decor = window.decorView
            sb.append(" decor{childCount=").append((decor as? android.view.ViewGroup)?.childCount ?: -1)
                .append(",decorHasFocus=").append(decor.hasWindowFocus())
                .append(",actionBarShown=").append(supportActionBar?.isShowing ?: "null").append("}")
            AppLog.i(TEEN_REST_DIAG_TAG, sb.toString())
        } catch (t: Throwable) {
            AppLog.e(TEEN_REST_DIAG_TAG, "dump failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun visibilityName(v: Int): String = when (v) {
        View.VISIBLE -> "VISIBLE"
        View.INVISIBLE -> "INVISIBLE"
        View.GONE -> "GONE"
        else -> v.toString()
    }

    /** 返回 overlay 在 DecorView 树中"自顶向下"的 z 序索引（0=最底层）。值越大越靠上层。 */
    private fun findViewLayerIndex(target: View): Int {
        val decor = window.decorView
        val flat = ArrayList<View>()
        flattenDescendants(decor as? android.view.ViewGroup, flat)
        flat.forEachIndexed { idx, v -> if (v === target) return idx }
        return -1
    }

    private fun flattenDescendants(group: android.view.ViewGroup?, out: ArrayList<View>) {
        if (group == null) return
        for (i in 0 until group.childCount) {
            val c = group.getChildAt(i)
            out.add(c)
            if (c is android.view.ViewGroup) flattenDescendants(c, out)
        }
    }
}
