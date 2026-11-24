package com.mytvb.feature.webdav

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.ui.base.BaseAdapter
import com.mytvb.databinding.ItemWebDavEntryBinding
import java.text.SimpleDateFormat
import java.util.Locale

class WebDavEntryAdapter(
    private val onClick: (WebDavEntry) -> Unit
) : BaseAdapter<WebDavEntry, WebDavEntryAdapter.ViewHolder>() {

    companion object {
        private val REMOTE_DATE_FORMAT =
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        private val LOCAL_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    }

    init {
        setShowLoadMore(false)
    }

    override fun onCreateContentViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {
        val binding = ItemWebDavEntryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindContentViewHolder(holder: ViewHolder, position: Int) {
        val entry = items.getOrNull(position) ?: return
        holder.bind(entry)
    }

    inner class ViewHolder(
        private val binding: ItemWebDavEntryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: WebDavEntry) {
            binding.icon.setImageResource(
                if (entry.isDirectory) R.drawable.ic_webdav_folder else R.drawable.ic_webdav_video
            )
            binding.textName.text = entry.name
            binding.textMeta.text = buildMeta(binding.root.context, entry)
            binding.textSize.text = if (entry.isDirectory) "" else formatSize(entry.sizeBytes)
            binding.root.setOnClickListener { onClick(entry) }
        }
    }

    private fun buildMeta(context: android.content.Context, entry: WebDavEntry): String {
        val typeLabel = context.getString(
            if (entry.isDirectory) R.string.webdav_folder_label else R.string.webdav_video_label
        )
        val date = formatDate(entry.lastModified)
        return if (date.isNullOrBlank()) typeLabel else "$typeLabel · $date"
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
