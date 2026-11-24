package com.mytvb.feature.webdav

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.mytvb.R
import com.mytvb.core.common.settings.AppSettingsDataStore
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.base.BaseFragment
import com.mytvb.core.ui.base.OnBackPressedHandler
import com.mytvb.core.ui.base.adaptiveSpanCount
import com.mytvb.core.ui.decoration.GridSpacingItemDecoration
import com.mytvb.core.ui.layout.WrapContentGridLayoutManager
import com.mytvb.core.ui.navigation.navigateBackFromUi
import com.mytvb.databinding.FragmentWebDavBrowserBinding
import com.mytvb.ui.activity.WebDavPlayerActivity
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Credentials
import okhttp3.HttpUrl
import org.koin.android.ext.android.inject

/**
 * WebDAV 目录浏览页：只负责服务器配置、目录列举和跳转播放。
 * 播放交给 [com.mytvb.ui.activity.WebDavPlayerActivity]。
 */
class WebDavBrowserFragment : BaseFragment<FragmentWebDavBrowserBinding>(), OnBackPressedHandler {

    companion object {
        fun newInstance(): WebDavBrowserFragment = WebDavBrowserFragment()
    }

    private val appSettings: AppSettingsDataStore by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var adapter: WebDavEntryAdapter
    private var client: WebDavClient? = null
    private val folderStack = mutableListOf<HttpUrl>()
    private var loadJob: Job? = null
    private var coverJob: Job? = null

    override fun useLightBaseContainer(): Boolean = true

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentWebDavBrowserBinding {
        return FragmentWebDavBrowserBinding.inflate(inflater, container, false)
    }

    override fun initView() {
        adapter = WebDavEntryAdapter { entry ->
            if (entry.isDirectory) {
                openFolder(entry.url)
            } else {
                WebDavPlayerActivity.start(requireContext(), entry.url.toString(), entry.name)
            }
        }
        val spanCount = resources.adaptiveSpanCount(base = 3, wide = 5)
        val layoutManager = WrapContentGridLayoutManager(requireContext(), spanCount)
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = adapter
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.addItemDecoration(
            GridSpacingItemDecoration(
                layoutManager.spanCount,
                resources.getDimensionPixelOffset(R.dimen.px14),
                true
            )
        )

        binding.buttonBack.setOnClickListener {
            if (!navigateUp()) navigateBackFromUi()
        }
        binding.buttonSettings.setOnClickListener { showConfigDialog() }
    }

    override fun initData() {
        // 列表加载完成前先给一个可见落点，避免页面覆盖菜单后焦点悬空。
        binding.buttonBack.requestFocus()
        reloadConfig()
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        loadJob = null
        coverJob?.cancel()
        coverJob = null
        scope.cancel()
        super.onDestroyView()
    }

    override fun onBackPressed(): Boolean = navigateUp()

    private fun reloadConfig() {
        val config = WebDavConfigStore.load(appSettings)
        if (config == null) {
            client = null
            adapter.coverHeaders = emptyMap()
            folderStack.clear()
            adapter.setData(emptyList())
            binding.recyclerView.visibility = View.INVISIBLE
            binding.progressBar.visibility = View.GONE
            showStatus(getString(R.string.webdav_not_configured))
            binding.buttonSettings.requestFocus()
            binding.root.post {
                if (view != null) {
                    binding.buttonSettings.requestFocus()
                    showConfigDialog()
                }
            }
            return
        }
        client = WebDavClient(config)
        adapter.coverHeaders = buildCoverHeaders(config)
        folderStack.clear()
        folderStack.add(config.baseUrl)
        updatePathLabel()
        loadFolder(config.baseUrl, clearList = true)
    }

    private fun openFolder(url: HttpUrl) {
        folderStack.add(url)
        updatePathLabel()
        loadFolder(url, clearList = true)
    }

    /** 返回上一级；已在根目录时返回 false，交由宿主关闭页面。 */
    private fun navigateUp(): Boolean {
        if (folderStack.size <= 1) return false
        folderStack.removeAt(folderStack.lastIndex)
        updatePathLabel()
        loadFolder(folderStack.last(), clearList = true)
        return true
    }

    private fun loadFolder(url: HttpUrl, clearList: Boolean) {
        val client = client ?: return
        loadJob?.cancel()
        coverJob?.cancel()
        binding.progressBar.visibility = View.VISIBLE
        binding.textStatus.visibility = View.GONE
        if (clearList) {
            binding.recyclerView.visibility = View.INVISIBLE
            adapter.setData(emptyList())
        }
        loadJob = scope.launch {
            try {
                val entries = client.list(url)
                if (view == null) return@launch
                binding.progressBar.visibility = View.GONE
                binding.recyclerView.visibility = View.VISIBLE
                adapter.setData(entries)
                probeFolderCovers(entries)
                if (entries.isEmpty()) {
                    binding.recyclerView.visibility = View.INVISIBLE
                    showStatus(getString(R.string.webdav_empty_dir))
                    binding.buttonSettings.requestFocus()
                } else {
                    focusFirstEntry()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (view == null) return@launch
                binding.progressBar.visibility = View.GONE
                binding.recyclerView.visibility = View.INVISIBLE
                showStatus(describeError(e))
                binding.buttonSettings.requestFocus()
            }
        }
    }

    /**
     * 列表先展示，再并发探测文件夹封面（固定 poster.jpg）。
     * 探测结果只更新对应卡片，不重建整个列表。
     */
    private fun probeFolderCovers(entries: List<WebDavEntry>) {
        coverJob?.cancel()
        val client = client ?: return
        val folders = entries.filter { it.isDirectory && it.coverUrl == null }
        if (folders.isEmpty()) return
        coverJob = scope.launch {
            val tasks = folders.map { folder ->
                async(Dispatchers.IO) { folder.url to client.probeFolderCover(folder.url) }
            }
            tasks.forEach { task ->
                val (folderUrl, coverUrl) = task.await()
                if (view == null) return@launch
                if (coverUrl != null) {
                    binding.recyclerView.post { adapter.setCover(folderUrl, coverUrl) }
                }
            }
        }
    }

    private fun buildCoverHeaders(config: WebDavConfig): Map<String, String> {
        if (config.username.isBlank()) return emptyMap()
        return mapOf(
            "Authorization" to Credentials.basic(config.username, config.password, Charsets.UTF_8)
        )
    }

    private fun focusFirstEntry(attempt: Int = 0) {
        binding.recyclerView.postDelayed({
            if (view == null) return@postDelayed
            val recycler = binding.recyclerView
            for (index in 0 until recycler.childCount) {
                val child = recycler.getChildAt(index)
                if (child.isShown && child.isFocusable && child.requestFocus()) return@postDelayed
            }
            if (attempt < 2) {
                focusFirstEntry(attempt + 1)
            }
        }, if (attempt == 0) 50L else 150L)
    }

    private fun updatePathLabel() {
        val current = folderStack.lastOrNull() ?: return
        binding.textPath.text = current.host + Uri.decode(current.encodedPath)
    }

    private fun showStatus(message: String) {
        binding.textStatus.text = message
        binding.textStatus.visibility = View.VISIBLE
    }

    private fun showConfigDialog() {
        if (!isAdded) return
        WebDavConfigDialog(
            context = requireContext(),
            current = WebDavConfigStore.load(appSettings),
            onSave = { url, username, password ->
                if (WebDavConfigStore.save(appSettings, url, username, password)) {
                    reloadConfig()
                } else {
                    AppToast.show(requireContext(), R.string.webdav_url_invalid)
                }
            }
        ).show()
    }

    private fun describeError(e: Throwable): String {
        val causeChain = generateSequence(e) { it.cause }.toList()
        return when {
            e is WebDavHttpException && e.code == 401 -> getString(R.string.webdav_auth_failed)
            e is WebDavHttpException && e.code == 404 -> getString(R.string.webdav_not_found)
            e is WebDavHttpException -> getString(R.string.webdav_http_error_format, e.code)
            causeChain.any { it is UnknownHostException } -> getString(R.string.webdav_host_unresolved)
            causeChain.any { it is ConnectException } -> getString(R.string.webdav_connect_failed)
            causeChain.any { it is SocketTimeoutException } -> getString(R.string.webdav_timeout)
            e is IOException -> getString(R.string.webdav_load_failed_format, e.message.orEmpty())
            else -> getString(R.string.webdav_load_failed_format, e.javaClass.simpleName)
        }
    }
}
