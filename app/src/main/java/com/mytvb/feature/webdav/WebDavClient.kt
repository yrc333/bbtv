package com.mytvb.feature.webdav

import android.util.Xml
import com.mytvb.core.common.log.AppLog
import java.io.IOException
import java.io.StringReader
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser

/** 服务器返回了非 2xx 状态码。 */
class WebDavHttpException(val code: Int) : IOException("HTTP $code")

/**
 * 极简 WebDAV 客户端：只做 PROPFIND 目录列举。
 * 播放由 ExoPlayer + OkHttpDataSource 直接请求视频 URL 完成。
 */
class WebDavClient(private val config: WebDavConfig) {

    companion object {
        private const val TAG = "WebDavClient"

        private val PROPFIND_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()

        private val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:displayname/>
                <d:resourcetype/>
                <d:getcontentlength/>
                <d:getlastmodified/>
              </d:prop>
            </d:propfind>
        """.trimIndent()

        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "ts", "m2ts", "mts", "avi", "mov", "flv",
            "m4v", "m3u8", "wmv", "rmvb", "mpg", "mpeg", "3gp", "ogv"
        )
    }

    suspend fun list(folder: HttpUrl): List<WebDavEntry> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(folder)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(PROPFIND_MEDIA_TYPE))
            .header("Depth", "1")
            .header("Accept", "application/xml, text/xml")
            .apply {
                if (config.username.isNotBlank()) {
                    header(
                        "Authorization",
                        Credentials.basic(config.username, config.password, Charsets.UTF_8)
                    )
                }
            }
            .build()

        // 网络/协议异常直接向上抛，由 UI 层统一转成可读文案。
        WebDavHttp.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                AppLog.w(TAG, "PROPFIND ${folder} failed: HTTP ${response.code}")
                throw WebDavHttpException(response.code)
            }
            val body = response.body?.string().orEmpty()
            parseEntries(request.url, body)
        }
    }

    private fun parseEntries(requestUrl: HttpUrl, xml: String): List<WebDavEntry> {
        if (xml.isBlank()) return emptyList()
        val entries = mutableListOf<WebDavEntry>()
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))

        var inResponse = false
        var href: String? = null
        var displayName: String? = null
        var isDirectory = false
        var sizeBytes = -1L
        var lastModified: String? = null

        fun flushResponse() {
            val rawHref = href?.trim().orEmpty()
            if (rawHref.isBlank()) return
            val url = resolveHref(requestUrl, rawHref) ?: return
            // 服务器通常会把请求目录自身也放进结果里，跳过。
            if (url.toString().trimEnd('/') == requestUrl.toString().trimEnd('/')) return
            val name = displayName?.takeIf { it.isNotBlank() } ?: decodeLastSegment(rawHref)
            if (!isDirectory && !isVideoFile(name, rawHref)) return
            entries.add(
                WebDavEntry(
                    name = name,
                    url = url,
                    isDirectory = isDirectory,
                    sizeBytes = sizeBytes,
                    lastModified = lastModified
                )
            )
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (tagName(parser)) {
                    "response" -> {
                        inResponse = true
                        href = null
                        displayName = null
                        isDirectory = false
                        sizeBytes = -1L
                        lastModified = null
                    }

                    "href" -> if (inResponse) href = readText(parser)
                    "displayname" -> if (inResponse) displayName = readText(parser)
                    "collection" -> if (inResponse) isDirectory = true
                    "getcontentlength" -> if (inResponse) {
                        sizeBytes = readText(parser).toLongOrNull() ?: -1L
                    }

                    "getlastmodified" -> if (inResponse) lastModified = readText(parser)
                }

                XmlPullParser.END_TAG -> if (tagName(parser) == "response") {
                    flushResponse()
                    inResponse = false
                }
            }
            event = parser.next()
        }

        val collator = java.text.Collator.getInstance(Locale.CHINA).apply {
            strength = java.text.Collator.PRIMARY
        }
        entries.sortWith(
            compareByDescending<WebDavEntry> { it.isDirectory }
                .thenComparator { a, b -> collator.compare(a.name, b.name) }
        )
        return entries
    }

    private fun tagName(parser: XmlPullParser): String {
        return (parser.localName ?: parser.name.substringAfter(':')).lowercase(Locale.ROOT)
    }

    private fun readText(parser: XmlPullParser): String {
        return runCatching { parser.nextText().trim() }.getOrDefault("")
    }

    private fun resolveHref(base: HttpUrl, href: String): HttpUrl? {
        val absolute = href.startsWith("http://", ignoreCase = true) ||
            href.startsWith("https://", ignoreCase = true)
        return if (absolute) {
            href.toHttpUrlOrNull()
        } else {
            base.resolve(href.replace(" ", "%20"))
        }
    }

    private fun decodeLastSegment(href: String): String {
        val segment = href.trimEnd('/').substringAfterLast('/')
        return android.net.Uri.decode(segment).ifBlank { href }
    }

    private fun isVideoFile(name: String, href: String): Boolean {
        val fromName = name.substringAfterLast('.', "")
        val extension = fromName.ifBlank { href.substringBefore('?').substringAfterLast('.', "") }
        return extension.lowercase(Locale.ROOT) in VIDEO_EXTENSIONS
    }
}

/**
 * 独立的 OkHttpClient：不复用 B 站网络栈，避免 Cookie / 签名侧写头泄漏到用户自建服务器。
 * readTimeout 放宽到 30s，兼顾大目录列举与 NAS 的慢响应。
 */
object WebDavHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
