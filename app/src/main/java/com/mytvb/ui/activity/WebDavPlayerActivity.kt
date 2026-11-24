package com.mytvb.ui.activity

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.base.BaseActivity
import com.mytvb.databinding.ActivityWebDavPlayerBinding
import com.mytvb.feature.player.PlayerInstancePool
import com.mytvb.feature.player.PlayerPlaybackPolicy
import com.mytvb.feature.player.cache.PlayerMediaCache
import com.mytvb.feature.webdav.WebDavConfigStore
import com.mytvb.feature.webdav.WebDavHttp
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * WebDAV 单文件播放页。
 *
 * 不走 [PlayerActivity]：后者绑定 bvid/cid、playinfo、弹幕与进度上报，
 * 这里只复用 Media3 播放内核、项目缓存的 SimpleCache 与播放器渲染配置。
 */
@OptIn(UnstableApi::class)
class WebDavPlayerActivity : BaseActivity<ActivityWebDavPlayerBinding>() {

    companion object {
        private const val TAG = "WebDavPlayer"
        private const val EXTRA_URL = "webdav_url"
        private const val EXTRA_TITLE = "webdav_title"

        private const val MIN_RESUME_POSITION_MS = 10_000L
        private const val PROGRESS_SAVE_INTERVAL_MS = 10_000L
        private const val DOUBLE_BACK_INTERVAL_MS = 2_000L
        private const val BUFFER_MIN_MS = 15_000
        private const val BUFFER_MAX_MS = 50_000
        private const val BUFFER_FOR_PLAYBACK_MS = 1_500
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000
        private const val TARGET_BUFFER_BYTES = 32 * 1024 * 1024

        fun start(context: Context, url: String, title: String) {
            context.startActivity(
                Intent(context, WebDavPlayerActivity::class.java).apply {
                    putExtra(EXTRA_URL, url)
                    putExtra(EXTRA_TITLE, title)
                }
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var mediaUrl: String = ""
    private var mediaTitle: String = ""
    private var exitTime: Long = 0L

    private val progressSaver = object : Runnable {
        override fun run() {
            saveProgress()
            handler.removeCallbacks(this)
            if (player != null) {
                handler.postDelayed(this, PROGRESS_SAVE_INTERVAL_MS)
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> binding.progressBar.visibility = View.VISIBLE
                Player.STATE_READY -> {
                    binding.progressBar.visibility = View.GONE
                    binding.textError.visibility = View.GONE
                }

                Player.STATE_ENDED -> {
                    binding.progressBar.visibility = View.GONE
                    WebDavConfigStore.clearResumePosition(appSettings, mediaUrl)
                }

                else -> binding.progressBar.visibility = View.GONE
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            AppLog.e(TAG, "playback error: ${error.errorCodeName} ${error.message}", error)
            binding.progressBar.visibility = View.GONE
            binding.textError.text = getString(
                R.string.webdav_play_failed_format,
                describePlaybackError(error)
            )
            binding.textError.visibility = View.VISIBLE
        }
    }

    override fun getViewBinding(): ActivityWebDavPlayerBinding =
        ActivityWebDavPlayerBinding.inflate(layoutInflater)

    override fun initData() {
        mediaUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        mediaTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (mediaUrl.isBlank()) {
            finish()
            return
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (System.currentTimeMillis() - exitTime <= DOUBLE_BACK_INTERVAL_MS) {
                    finish()
                } else {
                    exitTime = System.currentTimeMillis()
                    AppToast.show(applicationContext, R.string.activity_exit_player_hint)
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        startPlayback()
    }

    override fun onStop() {
        saveProgress()
        handler.removeCallbacks(progressSaver)
        binding.playerView.setPlayer(null)
        player?.release()
        player = null
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressSaver)
        super.onDestroy()
    }

    private fun startPlayback() {
        if (player != null || mediaUrl.isBlank()) return

        val config = WebDavConfigStore.load(appSettings)
        val requestHeaders = if (config != null && config.username.isNotBlank()) {
            mapOf(
                "Authorization" to Credentials.basic(
                    config.username,
                    config.password,
                    Charsets.UTF_8
                )
            )
        } else {
            emptyMap()
        }

        // 上游直连 WebDAV，再套项目统一的播放缓存（SimpleCache + LRU），实现边下边播与拖动。
        val upstreamFactory = OkHttpDataSource.Factory(WebDavHttp.client)
            .setUserAgent("MyTVB-WebDAV/1.0")
            .setDefaultRequestProperties(requestHeaders)
        val cachedFactory = PlayerMediaCache.buildDataSourceFactory(this, upstreamFactory)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                BUFFER_MIN_MS,
                BUFFER_MAX_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(DefaultLoadControl.DEFAULT_MAX_BUFFER_MS, true)
            .build()

        val exoPlayer = ExoPlayer.Builder(this)
            .setRenderersFactory(PlayerInstancePool.createRenderersFactory(this))
            .setMediaSourceFactory(DefaultMediaSourceFactory(cachedFactory))
            .setLoadControl(loadControl)
            .build()
            .also { PlayerPlaybackPolicy.apply(it) }
        player = exoPlayer

        binding.playerView.setupDanmakuEngine()
        binding.playerView.setControllerAutoShow(true)
        binding.playerView.showHideFfRe(true)
        binding.playerView.showHideTimeBar(true)
        binding.playerView.showHideTimeText(true)
        binding.playerView.showHideNextPrevious(false)
        binding.playerView.showHideEpisodeButton(false)
        binding.playerView.showHideActionButton(false)
        binding.playerView.showHideRelatedButton(false)
        binding.playerView.showHideCommentButton(false)
        binding.playerView.showHideRepeatButton(false)
        binding.playerView.showHideSubtitleButton(false)
        binding.playerView.showHideDmSwitchButton(false)
        binding.playerView.showSettingButton(false)
        binding.playerView.showHideLiveSettingButton(false)
        binding.playerView.showHideRefreshButton(false)
        binding.playerView.showHideMirrorButton(false)
        binding.playerView.showHideLineButton(false)
        binding.playerView.setShowHideOwnerInfo(false)
        binding.playerView.setPlayer(exoPlayer)
        binding.playerView.requestFocus()

        exoPlayer.addListener(playerListener)

        val savedPosition = WebDavConfigStore.resumePosition(appSettings, mediaUrl)
        val startPositionMs = if (savedPosition > MIN_RESUME_POSITION_MS) savedPosition else 0L
        val mediaItem = MediaItem.Builder()
            .setUri(mediaUrl)
            .setMediaId(mediaUrl)
            .setCustomCacheKey(cacheKeyFor(mediaUrl))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(mediaTitle).build())
            .build()

        binding.progressBar.visibility = View.VISIBLE
        exoPlayer.setMediaItem(mediaItem, startPositionMs)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        handler.post(progressSaver)
    }

    private fun saveProgress() {
        val currentPlayer = player ?: return
        if (currentPlayer.playbackState == Player.STATE_ENDED) return
        val position = currentPlayer.currentPosition
        if (position > MIN_RESUME_POSITION_MS) {
            WebDavConfigStore.saveResumePosition(appSettings, mediaUrl, position)
        }
    }

    private fun cacheKeyFor(url: String): String {
        val parsed = url.toHttpUrlOrNull()
        return if (parsed != null) {
            "webdav:${parsed.host}${parsed.encodedPath}"
        } else {
            "webdav:$url"
        }
    }

    private fun describePlaybackError(error: PlaybackException): String {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> getString(R.string.webdav_play_http_error)
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> getString(R.string.webdav_connect_failed)
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> getString(R.string.webdav_timeout)
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> getString(R.string.webdav_codec_unsupported)
            else -> error.errorCodeName
        }
    }
}
