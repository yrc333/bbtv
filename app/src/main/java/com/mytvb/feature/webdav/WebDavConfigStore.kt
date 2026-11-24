package com.mytvb.feature.webdav

import com.mytvb.core.common.settings.AppSettingsDataStore
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * WebDAV 配置与播放进度的持久化。
 *
 * 仅做最小闭环：账号密码随其它设置一起存 DataStore（应用私有目录），
 * 不做额外加密；清空设置即一并清除。
 */
object WebDavConfigStore {

    private const val KEY_BASE_URL = "webdav_base_url"
    private const val KEY_USERNAME = "webdav_username"
    private const val KEY_PASSWORD = "webdav_password"

    private const val RESUME_KEY_PREFIX = "webdav_resume:"

    fun load(settings: AppSettingsDataStore): WebDavConfig? {
        val raw = settings.getCachedString(KEY_BASE_URL)?.trim().orEmpty()
        if (raw.isBlank()) return null
        val baseUrl = normalizeBaseUrl(raw) ?: return null
        return WebDavConfig(
            baseUrl = baseUrl,
            username = settings.getCachedString(KEY_USERNAME).orEmpty(),
            password = settings.getCachedString(KEY_PASSWORD).orEmpty()
        )
    }

    fun save(
        settings: AppSettingsDataStore,
        rawUrl: String,
        username: String,
        password: String
    ): Boolean {
        val baseUrl = normalizeBaseUrl(rawUrl) ?: return false
        settings.putStringAsync(KEY_BASE_URL, baseUrl.toString())
        settings.putStringAsync(KEY_USERNAME, username.takeIf { it.isNotBlank() })
        settings.putStringAsync(KEY_PASSWORD, password.takeIf { it.isNotBlank() })
        return true
    }

    /**
     * 把用户输入规范成可请求的根地址：补协议头、补结尾斜杠。
     * 末尾斜杠很重要：HttpUrl.resolve 会以它作为目录基准解析服务器返回的相对 href。
     */
    fun normalizeBaseUrl(raw: String): HttpUrl? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        if (!text.startsWith("http://", ignoreCase = true) &&
            !text.startsWith("https://", ignoreCase = true)
        ) {
            text = "http://$text"
        }
        if (!text.endsWith("/")) {
            text += "/"
        }
        return text.toHttpUrlOrNull()
    }

    fun resumePosition(settings: AppSettingsDataStore, url: String): Long {
        return settings.getCachedLong(RESUME_KEY_PREFIX + url, 0L)
    }

    fun saveResumePosition(settings: AppSettingsDataStore, url: String, positionMs: Long) {
        if (positionMs <= 0L) return
        settings.putLongAsync(RESUME_KEY_PREFIX + url, positionMs)
    }

    fun clearResumePosition(settings: AppSettingsDataStore, url: String) {
        settings.removeAsync(RESUME_KEY_PREFIX + url)
    }
}
