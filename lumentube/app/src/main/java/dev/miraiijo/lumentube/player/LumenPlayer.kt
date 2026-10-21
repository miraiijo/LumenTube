package dev.miraiijo.lumentube.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.ExoPlayerFactory
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import dev.miraiijo.lumentube.player.upstream.ExoMediaSourceFactory

/**
 * The ExoPlayer of SmartTube's fork (it reads SABR), with its format choice, and two fixes for
 * "the video starts slowly / needs OK again to really start":
 *  - playback starts after 1 s of buffer (2.5 s in SmartTube), the rest keeps buffering while it plays;
 *  - no audio focus handling (a TV box has no competing audio; a focus request answered late or
 *    refused left the player READY but paused), and a watchdog: READY + should play + not playing
 *    for [STUCK_MS] → play again.
 */
class LumenPlayer(context: Context) {
    private val sources = ExoMediaSourceFactory(context)
    private val handler = Handler(Looper.getMainLooper())

    val exo: SimpleExoPlayer = ExoPlayerFactory.newSimpleInstance(
        context,
        DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true),
        DefaultTrackSelector(AdaptiveTrackSelection.Factory()),
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS)
            .createDefaultLoadControl(),
    ).apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.CONTENT_TYPE_MOVIE).build(), false)
    }

    /** Last error, once per error ("403": the caller switches YouTube client and reloads). */
    var onError: ((ExoPlaybackException) -> Unit)? = null

    private val watchdog = object : Runnable {
        override fun run() {
            if (exo.playbackState == Player.STATE_READY && exo.playWhenReady && !exo.isPlaying) {
                // Stuck in READY: nudge it (same as pressing play again).
                exo.playWhenReady = false
                exo.playWhenReady = true
            }
            handler.postDelayed(this, STUCK_MS)
        }
    }

    init {
        exo.addListener(object : Player.EventListener {
            override fun onPlayerError(error: ExoPlaybackException) {
                onError?.invoke(error)
            }
        })
        handler.postDelayed(watchdog, STUCK_MS)
    }

    /**
     * Opens a video like SmartTube's VideoLoaderController: DASH formats, else SABR, else a live
     * DASH / HLS address, else plain URLs. False when there's nothing playable.
     */
    fun open(info: MediaItemFormatInfo, startMs: Long = 0L): Boolean {
        val adaptive = !(info.isLive && info.startTimeMs == 0L)
        val source: MediaSource = when {
            adaptive && info.containsDashFormats() -> sources.fromDashFormatInfo(info)
            adaptive && info.containsSabrFormats() && !info.isLive -> sources.fromSabrFormatInfo(info)
            info.isLive && info.containsDashUrl() -> sources.fromDashManifestUrl(info.dashManifestUrl)
            info.isLive && info.containsHlsUrl() -> sources.fromHlsPlaylist(info.hlsManifestUrl)
            info.containsUrlFormats() -> sources.fromUrlList(info.createUrlList())
            else -> return false
        } ?: return false
        exo.playWhenReady = true
        exo.prepare(source, true, true)
        if (startMs > 0 && !info.isLive) exo.seekTo(startMs)
        return true
    }

    fun release() {
        handler.removeCallbacks(watchdog)
        onError = null
        sources.release()
        exo.release()
    }

    private companion object {
        const val MIN_BUFFER_MS = 30_000
        const val MAX_BUFFER_MS = 30_000
        const val START_BUFFER_MS = 1_000
        const val REBUFFER_MS = 2_500
        const val STUCK_MS = 1_500L
    }
}
