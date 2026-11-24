package com.mytvb.feature.webdav

import okhttp3.HttpUrl

/** 一个 WebDAV 条目的展示信息；[url] 已经是可直接请求的完整地址。 */
data class WebDavEntry(
    val name: String,
    val url: HttpUrl,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: String?
)

/** WebDAV 服务器配置；[baseUrl] 保证以 "/" 结尾，便于相对路径拼接。 */
data class WebDavConfig(
    val baseUrl: HttpUrl,
    val username: String,
    val password: String
)
