package com.mytvb.feature.webdav

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.mytvb.core.common.log.AppLog
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import okhttp3.Request

/**
 * WebDAV 封面专用加载器。
 *
 * 现有 ImageLoader 只接受 URL、无法附加 Basic 认证头，因此单独实现：
 * 内存 LruCache -> 磁盘缓存 -> 带认证的 OkHttp 下载。
 */
object WebDavCoverLoader {

    private const val TAG = "WebDavCoverLoader"
    private const val DISK_DIR = "webdav_covers"
    private const val TARGET_SIZE = 512
    private const val THREAD_COUNT = 3

    private val memoryCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val diskExecutor = Executors.newFixedThreadPool(THREAD_COUNT) { runnable ->
        Thread(runnable, "webdav-cover").apply { isDaemon = true }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun load(
        imageView: ImageView,
        url: String,
        headers: Map<String, String>,
        onSuccess: () -> Unit,
        onError: () -> Unit
    ) {
        imageView.tag = url
        memoryCache.get(url)?.let { bitmap ->
            imageView.setImageBitmap(bitmap)
            onSuccess()
            return
        }

        val appContext = imageView.context.applicationContext
        diskExecutor.execute {
            val bitmap = loadFromDisk(appContext, url)
                ?: download(appContext, url, headers)
            mainHandler.post {
                if (imageView.tag != url) return@post
                if (bitmap != null) {
                    memoryCache.put(url, bitmap)
                    imageView.setImageBitmap(bitmap)
                    onSuccess()
                } else {
                    imageView.setImageDrawable(null)
                    onError()
                }
            }
        }
    }

    private fun loadFromDisk(context: Context, url: String): Bitmap? {
        val file = cacheFile(context, url)
        if (!file.exists() || file.length() <= 0L) return null
        return runCatching { decodeSampled(file.path) }.getOrNull()
    }

    private fun download(context: Context, url: String, headers: Map<String, String>): Bitmap? {
        val builder = Request.Builder().url(url)
        headers.forEach { (key, value) -> builder.header(key, value) }
        return runCatching {
            WebDavHttp.coverClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val bytes = response.body?.bytes() ?: return@use null
                val file = cacheFile(context, url)
                file.parentFile?.mkdirs()
                runCatching { file.writeBytes(bytes) }
                decodeSampled(bytes)
            }
        }.onFailure {
            AppLog.w(TAG, "download cover failed: $url", it)
        }.getOrNull()
    }

    private fun decodeSampled(path: String): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight)
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(path, options)
    }

    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight)
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun calculateInSampleSize(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var currentWidth = width
        var currentHeight = height
        while (currentWidth / 2 >= TARGET_SIZE && currentHeight / 2 >= TARGET_SIZE) {
            currentWidth /= 2
            currentHeight /= 2
            sample *= 2
        }
        return sample
    }

    private fun cacheFile(context: Context, url: String): File {
        return File(File(context.cacheDir, DISK_DIR), sha1(url) + ".img")
    }

    private fun sha1(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
        val builder = StringBuilder(bytes.size * 2)
        bytes.forEach { byte ->
            builder.append((byte.toInt() and 0xff).toString(16).padStart(2, '0'))
        }
        return builder.toString()
    }
}
