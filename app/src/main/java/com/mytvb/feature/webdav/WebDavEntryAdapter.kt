package com.mytvb.feature.webdav

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.ui.base.BaseAdapter
import com.mytvb.databinding.ItemWebDavGridBinding
import java.text.SimpleDateFormat
import java.util.Locale
import okhttp3.HttpUrl

class WebDavEntryAdapter(
    private val onClick: (WebDavEntry) -> Unit
) : BaseAdapter<WebDavEntry, WebDavEntryAdapter.ViewHolder>() {

    companion object {
        private val REMOTE_DATE_FORMAT =
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        private val LOCAL_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    }

    /** WebDAV 封面需要 Basic 认证头，由页面在配置变化时更新。 */
    var coverHeaders: Map<String, String> = emptyMap()

    /** 列表 diff 尚未提交时先到达的封面，绑定时兜底使用。 */
    private val pendingCovers = mutableMapOf<String, HttpUrl>()

    init {
        setShowLoadMore(false)
    }

    override fun onCreateContentViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {
        val binding = ItemWebDavGridBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindContentViewHolder(holder: ViewHolder, position: Int) {
        val entry = items.getOrNull(position) ?: return
        holder.bind(entry)
    }

    /** 文件夹封面探测完成后，按目录地址原地更新对应卡片。 */
    fun setCover(folderUrl: HttpUrl, coverUrl: HttpUrl) {
        pendingCovers[folderUrl.toString()] = coverUrl
        val index = items.indexOfFirst { it.url == folderUrl }
        if (index < 0) return
        val entry = items[index]
        if (entry.coverUrl != null) return
        items[index] = entry.copy(coverUrl = coverUrl)
        notifyItemChanged(index)
    }

    inner class ViewHolder(
        private val binding: ItemWebDavGridBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: WebDavEntry) {
            // 先标记 tag，使上一个复用的图片请求结果自动失效。
            binding.imageCover.tag = entry.url.toString()
            binding.imageCover.setImageDrawable(null)
            binding.iconPlaceholder.setImageResource(
                if (entry.isDirectory) R.drawable.ic_webdav_folder else R.drawable.ic_webdav_video
            )
            binding.iconPlaceholder.visibility = View.VISIBLE
            binding.textName.text = entry.name
            binding.textMeta.text = buildMeta(binding.root.context, entry)
            binding.root.setOnClickListener { onClick(entry) }

            val coverUrl = entry.coverUrl ?: pendingCovers[entry.url.toString()] ?: return
            WebDavCoverLoader.load(
                imageView = binding.imageCover,
                url = coverUrl.toString(),
                headers = coverHeaders,
                onSuccess = { binding.iconPlaceholder.visibility = View.GONE },
                onError = { binding.iconPlaceholder.visibility = View.VISIBLE }
            )
        }
    }

    private fun buildMeta(context: Context, entry: WebDavEntry): String {
        val typeLabel = context.getString(
            if (entry.isDirectory) R.string.webdav_folder_label else R.string.webdav_video_label
        )
        val parts = mutableListOf(typeLabel)
        if (!entry.isDirectory) {
            formatSize(entry.sizeBytes).takeIf { it.isNotBlank() }?.let(parts::add)
        }
        formatDate(entry.lastModified)?.let(parts::add)
        return parts.joinToString(" · ")
    }

    private fun formatDate(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            REMOTE_DATE_FORMAT.parse(raw)?.let { LOCAL_DATE_FORMAT.format(it) }
        }.getOrNull()
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return if (index == 0) {
            "${bytes} ${units[0]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[index])
        }
    }
}
