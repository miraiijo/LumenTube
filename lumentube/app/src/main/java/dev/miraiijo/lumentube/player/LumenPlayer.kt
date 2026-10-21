package dev.miraiijo.lumentube.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.ExoPlayerFactory
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.RendererCapabilities
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.audio.AudioListener
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.TrackGroupArray
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.trackselection.TrackSelectionArray
import java.util.Locale
import kotlin.math.log10
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import dev.miraiijo.lumentube.player.upstream.ExoMediaSourceFactory

/**
 * The ExoPlayer of SmartTube's fork (it reads SABR), with its format choice, and two fixes for
 * "the video starts slowly / needs OK again to really start":
 *  - playback starts after 1 s of buffer (2.5 s in SmartTube), the rest keeps buffering while it plays;
 *  - audio focus handled here, not by ExoPlayer (its handling left the player READY but paused
 *    when the box answered the request late or refused it): asked when playback starts, paused on
 *    a loss; a refused or delayed request never blocks playback. And a watchdog: READY + should
 *    play + not playing for [STUCK_MS] → play again.
 */
class LumenPlayer(context: Context) {
    private val sources = ExoMediaSourceFactory(context)
    private val handler = Handler(Looper.getMainLooper())
    private val selector = DefaultTrackSelector(AdaptiveTrackSelection.Factory())

    /**
     * Connection speed as measured on the video's downloads: our own meter (the same as ExoPlayer's
     * default one) so "Statistiques pour les curieux" can read it.
     */
    private val bandwidth = com.google.android.exoplayer2.upstream.DefaultBandwidthMeter.Builder(context).build()

    val exo: SimpleExoPlayer = ExoPlayerFactory.newSimpleInstance(
        context,
        DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true),
        selector,
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS)
            // Capped in bytes too (the fork defaults to time first): 30 s of 4K is 100+ MB of heap.
            .setTargetBufferBytes(MAX_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .createDefaultLoadControl(),
        null,
        bandwidth,
    ).apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.CONTENT_TYPE_MOVIE).build(), false)
    }


    /**
     * "Statistiques pour les curieux": what is being played and how (label to value), read again by
     * the overlay every second.
     */
    fun stats(): List<Pair<String, String>> = buildList {
        val video = exo.videoFormat
        val audio = exo.audioFormat
        if (video != null) {
            val fps = video.frameRate.takeIf { it > 0f }?.let { " · ${"%.0f".format(Locale.US, it)} i/s" }.orEmpty()
            add("Vidéo" to "${video.width}×${video.height}$fps · ${codecName(video)}")
            if (video.bitrate > 0) add("Débit vidéo" to kbps(video.bitrate))
        }
        if (audio != null) {
            val channels = audio.channelCount.takeIf { it > 0 }?.let { " · $it canaux" }.orEmpty()
            val rate = audio.sampleRate.takeIf { it > 0 }?.let { " · ${it / 1000} kHz" }.orEmpty()
            add("Audio" to "${codecName(audio)}$rate$channels" + (audio.language?.let { " · $it" } ?: ""))
            if (audio.bitrate > 0) add("Débit audio" to kbps(audio.bitrate))
        }
        exo.videoDecoderCounters?.let { counters ->
            counters.ensureUpdated()
            add("Images perdues" to "${counters.droppedBufferCount} / ${counters.renderedOutputBufferCount + counters.droppedBufferCount}")
            if (freezesRecovered > 0) add("Gels relancés" to freezesRecovered.toString())
        }
        add("Tampon" to "%.1f s".format(Locale.US, (exo.bufferedPosition - exo.currentPosition).coerceAtLeast(0L) / 1000f))
        bandwidth.bitrateEstimate.takeIf { it > 0 }?.let { add("Connexion" to kbps(it)) }
        add("Volume" to "%.0f %%".format(Locale.US, exo.volume * 100) + if (boostMb > 0) " + ${"%.1f".format(Locale.US, boostMb / 100f)} dB" else "")
    }

    private fun codecName(format: Format): String {
        val codec = format.codecs?.substringBefore('.')?.lowercase(Locale.US)
        return when {
            codec == null -> format.sampleMimeType?.substringAfter('/') ?: "?"
            codec == "avc1" -> "H.264 ($codec)"
            codec == "vp09" || codec == "vp9" -> "VP9"
            codec == "av01" -> "AV1"
            codec == "mp4a" -> "AAC"
            else -> codec.replaceFirstChar { it.uppercase() }
        }
    }

    private fun kbps(bits: Long): String = if (bits >= 1_000_000) "%.1f Mb/s".format(Locale.US, bits / 1_000_000f) else "${bits / 1000} kb/s"

    private fun kbps(bits: Int): String = kbps(bits.toLong())

    // ---- Audio focus ----------------------------------------------------------------------------

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    private var hasFocus = false
    /** Paused by a short loss (call, voice assistant…): playback resumes when the focus is back. */
    private var pausedByTransientLoss = false

    private val focusListener = android.media.AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            android.media.AudioManager.AUDIOFOCUS_LOSS -> {
                // Another app plays for good: pause, no automatic resume.
                hasFocus = false
                pausedByTransientLoss = false
                exo.playWhenReady = false
            }
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasFocus = false
                if (exo.playWhenReady) {
                    pausedByTransientLoss = true
                    exo.playWhenReady = false
                }
            }
            android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                if (pausedByTransientLoss) {
                    pausedByTransientLoss = false
                    exo.playWhenReady = true
                }
            }
            // "Can duck": the system lowers the volume by itself (Android 8+), playback goes on.
            else -> Unit
        }
    }

    private val focusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
        )
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(focusListener, handler)
        .build()

    /** Playback starting (or resuming): the focus, once. Refused or delayed: it plays anyway. */
    private fun requestFocus() {
        if (hasFocus) return
        val result = runCatching { audioManager.requestAudioFocus(focusRequest) }.getOrDefault(android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        hasFocus = result == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /** Last error, once per error ("403": the caller switches YouTube client and reloads). */
    var onError: ((ExoPlaybackException) -> Unit)? = null

    /** The video's tracks are known or their selection changed: menus can be (re)built. */
    var onTracksChanged: (() -> Unit)? = null

    /** Volume boost (millibels) applied on top of the player's volume, for quiet videos. */
    private var boostMb = 0
    private var enhancer: android.media.audiofx.LoudnessEnhancer? = null

    /** Subtitle track chosen in the menu (index in [subtitleTracks]), -1 = off. */
    var selectedSubtitle = -1
        private set

    /** Audio track chosen in the menu (index in [audioTracks]), -1 = the selector's choice. */
    var selectedAudio = -1
        private set

    /** Video frozen while the sound goes on: frames rendered and position at the last checks. */
    private var lastRendered = -1
    private var lastPositionMs = 0L
    private var stillChecks = 0
    /** Freezes caught and recovered (shown in the stats). */
    var freezesRecovered = 0
        private set

    private val watchdog = object : Runnable {
        override fun run() {
            if (exo.playbackState == Player.STATE_READY && exo.playWhenReady && !exo.isPlaying) {
                // Stuck in READY: nudge it (same as pressing play again).
                exo.playWhenReady = false
                exo.playWhenReady = true
            }
            checkVideoFrozen()
            handler.postDelayed(this, STUCK_MS)
        }
    }

    /**
     * The box's video decoder sometimes stalls (seen on Amlogic, VP9 above all) while the audio
     * plays on: no new frame for [FROZEN_CHECKS] checks (~4.5 s) while the position moves → the
     * decoders are flushed by a seek to where we are (the picture comes back, nothing is skipped).
     */
    private fun checkVideoFrozen() {
        val counters = exo.videoDecoderCounters
        if (!exo.isPlaying || exo.videoFormat == null || counters == null) {
            stillChecks = 0
            lastRendered = -1
            return
        }
        counters.ensureUpdated()
        val rendered = counters.renderedOutputBufferCount
        val position = exo.currentPosition
        val moved = position - lastPositionMs > STUCK_MS / 2
        stillChecks = if (lastRendered >= 0 && rendered == lastRendered && moved) stillChecks + 1 else 0
        lastRendered = rendered
        lastPositionMs = position
        if (stillChecks >= FROZEN_CHECKS) {
            stillChecks = 0
            lastRendered = -1
            freezesRecovered++
            android.util.Log.w(FREEZE_TAG, "video frozen at $position ms (${exo.videoFormat?.let { "${it.codecs} ${it.width}x${it.height}@${it.frameRate}" }}), recovering")
            exo.seekTo(position)
        }
    }

    init {
        exo.addListener(object : Player.EventListener {
            override fun onPlayerError(error: ExoPlaybackException) {
                onError?.invoke(error)
            }

            override fun onTracksChanged(trackGroups: TrackGroupArray, trackSelections: TrackSelectionArray) {
                onTracksChanged?.invoke()
            }

            override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
                if (playWhenReady) {
                    // Played again by hand after a short loss: no automatic resume pending anymore.
                    pausedByTransientLoss = false
                    requestFocus()
                }
            }
        })
        exo.addAudioListener(object : AudioListener {
            override fun onAudioSessionId(audioSessionId: Int) = attachEnhancer(audioSessionId)
        })
        handler.postDelayed(watchdog, STUCK_MS)
    }

    /**
     * YouTube's loudness for this video (1 = reference, above = the video is quiet). Loud videos are
     * turned down with the player's volume; quiet ones up with a LoudnessEnhancer, as ExoPlayer's
     * volume can't go above 1. Capped at +3.5 dB to avoid distortion.
     */
    fun setVolumeLevel(level: Float) {
        val safe = if (level.isNaN() || level <= 0f) 1f else level.coerceAtMost(MAX_LEVEL)
        exo.volume = safe.coerceAtMost(1f)
        boostMb = if (safe > 1f) (2000 * log10(safe)).toInt() else 0
        applyBoost()
    }

    private var audioSessionId = C.AUDIO_SESSION_ID_UNSET

    private fun attachEnhancer(audioSessionId: Int) {
        this.audioSessionId = audioSessionId
        enhancer?.release()
        enhancer = null
        applyBoost()
    }

    /** The effect only exists while a boost is needed (an idle one can still sit in the audio path). */
    private fun applyBoost() {
        if (boostMb <= 0 || audioSessionId == C.AUDIO_SESSION_ID_UNSET) {
            enhancer?.let { runCatching { it.release() } }
            enhancer = null
            return
        }
        val effect = enhancer ?: runCatching { android.media.audiofx.LoudnessEnhancer(audioSessionId) }.getOrNull()?.also { enhancer = it } ?: return
        runCatching {
            effect.setTargetGain(boostMb)
            effect.enabled = true
        }
    }

    /**
     * Opens a video like SmartTube's VideoLoaderController: DASH formats, else SABR, else a live
     * DASH / HLS address, else plain URLs. False when there's nothing playable.
     */
    suspend fun open(info: MediaItemFormatInfo, startMs: Long = 0L): Boolean {
        val adaptive = !(info.isLive && info.startTimeMs == 0L)
        // Building the source parses the DASH / SABR manifest (and creates Cronet the first time):
        // done off the main thread so the screen never freezes; only prepare() runs on it.
        val source: MediaSource = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            when {
                adaptive && info.containsDashFormats() -> sources.fromDashFormatInfo(info)
                adaptive && info.containsSabrFormats() && !info.isLive -> sources.fromSabrFormatInfo(info)
                info.isLive && info.containsDashUrl() -> sources.fromDashManifestUrl(info.dashManifestUrl)
                info.isLive && info.containsHlsUrl() -> sources.fromHlsPlaylist(info.hlsManifestUrl)
                info.containsUrlFormats() -> sources.fromUrlList(info.createUrlList())
                else -> null
            }
        } ?: return false
        exo.playWhenReady = true
        if (startMs > 0 && !info.isLive) {
            // Position first, then prepare without resetting it: the first segments loaded are the
            // ones at the resume point (seeking after prepare loaded the start, then threw it away).
            exo.seekTo(startMs)
            exo.prepare(source, false, true)
        } else {
            exo.prepare(source, true, true)
        }
        return true
    }

    // ---- Quality, subtitles, audio, speed (menus of the player screen) ---------------------------

    /**
     * Qualities this box can decode for the current video, highest first, as YouTube names them
     * (1080 = 1080p). Tracks the decoder can't handle (4K on some boxes) are left out.
     */
    fun qualities(): List<Int> {
        val (renderer, groups) = renderer(C.TRACK_TYPE_VIDEO) ?: return emptyList()
        val info = selector.currentMappedTrackInfo ?: return emptyList()
        val found = sortedSetOf<Int>(reverseOrder())
        for (g in 0 until groups.length) for (t in 0 until groups[g].length) {
            if (info.getTrackSupport(renderer, g, t) == RendererCapabilities.FORMAT_HANDLED) qualityOf(groups[g].getFormat(t))?.let(found::add)
        }
        return found.toList()
    }

    /** Quality being played right now (null before the first frame). */
    val playingQuality: Int? get() = exo.videoFormat?.let(::qualityOf)

    /**
     * 0 = Auto (adapts to the connection). Otherwise the chosen quality, held: the highest track up to
     * it, without dropping lower when the bandwidth varies. Both sides capped so vertical videos work too.
     */
    fun setQuality(quality: Int) {
        // Auto stops at 1080p: 4K (often 60 i/s) drops frames on a 2 GB box; it stays selectable.
        val side = LONG_SIDE[if (quality == 0) AUTO_MAX else quality] ?: Int.MAX_VALUE
        selector.setParameters(
            selector.buildUponParameters()
                .setMaxVideoSize(side, side)
                .setForceHighestSupportedBitrate(quality != 0),
        )
    }

    /** Subtitle tracks by name: the video's own, then YouTube's automatic translations. */
    fun subtitleTracks(): List<String> {
        val (_, groups) = renderer(C.TRACK_TYPE_TEXT) ?: return emptyList()
        return (0 until groups.length).map { trackName(groups[it].getFormat(0), it) }
    }

    /** [index] in [subtitleTracks], -1 = off. */
    fun selectSubtitle(index: Int) {
        val (renderer, groups) = renderer(C.TRACK_TYPE_TEXT) ?: return
        val valid = index in 0 until groups.length
        selectedSubtitle = if (valid) index else -1
        val builder = selector.buildUponParameters().clearSelectionOverrides(renderer).setRendererDisabled(renderer, !valid)
        if (valid) builder.setSelectionOverride(renderer, groups, DefaultTrackSelector.SelectionOverride(index, 0))
        selector.setParameters(builder)
    }

    /**
     * Audio tracks by language (dubbed videos). YouTube lists each language once per codec: one entry
     * per name, each pointing at its first group.
     */
    fun audioTracks(): List<String> = audioGroups().map { it.first }

    fun selectAudio(index: Int) {
        val (renderer, groups) = renderer(C.TRACK_TYPE_AUDIO) ?: return
        val group = audioGroups().getOrNull(index)?.second ?: return
        val info = selector.currentMappedTrackInfo ?: return
        val tracks = (0 until groups[group].length).filter { info.getTrackSupport(renderer, group, it) == RendererCapabilities.FORMAT_HANDLED }
        if (tracks.isEmpty()) return
        selectedAudio = index
        selector.setParameters(
            selector.buildUponParameters().setSelectionOverride(renderer, groups, DefaultTrackSelector.SelectionOverride(group, *tracks.toIntArray())),
        )
    }

    /** Audio language being played (index in [audioTracks]). */
    val playingAudio: Int
        get() {
            if (selectedAudio >= 0) return selectedAudio
            val language = exo.audioFormat?.let { trackName(it, 0) } ?: return -1
            return audioTracks().indexOf(language)
        }

    var speed: Float
        get() = exo.playbackParameters.speed
        set(value) {
            exo.playbackParameters = PlaybackParameters(value)
        }

    private fun audioGroups(): List<Pair<String, Int>> {
        val (_, groups) = renderer(C.TRACK_TYPE_AUDIO) ?: return emptyList()
        return (0 until groups.length)
            .map { trackName(groups[it].getFormat(0), it) to it }
            .distinctBy { it.first }
            // The original language first (YouTube marks it "(original)"), then the dubs.
            .sortedByDescending { isOriginalAudio(it.first) }
    }

    /** Index of the original audio in [audioTracks] (-1: unknown, or a single track). */
    fun originalAudio(): Int = audioTracks().takeIf { it.size > 1 }?.indexOfFirst(::isOriginalAudio) ?: -1

    /** The player's renderer for [type] and its track groups (null before the tracks are known). */
    private fun renderer(type: Int): Pair<Int, TrackGroupArray>? {
        val info = selector.currentMappedTrackInfo ?: return null
        for (i in 0 until info.rendererCount) {
            if (info.getRendererType(i) == type && info.getTrackGroups(i).length > 0) return i to info.getTrackGroups(i)
        }
        return null
    }

    fun release() {
        handler.removeCallbacks(watchdog)
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
        hasFocus = false
        onError = null
        onTracksChanged = null
        enhancer?.release()
        enhancer = null
        sources.release()
        exo.release()
    }

    private companion object {
        /** YouTube's quality names and the long side of their frame (1080p = 1920 × 1080). */
        val LONG_SIDE = linkedMapOf(2160 to 3840, 1440 to 2560, 1080 to 1920, 720 to 1280, 480 to 854, 360 to 640, 240 to 426, 144 to 256)

        /** Quality name of a video track, from its long side (an ultra-wide 1920 × 800 is still 1080p). */
        fun qualityOf(format: Format): Int? {
            val side = maxOf(format.width, format.height)
            if (side <= 0) return null
            return LONG_SIDE.entries.firstOrNull { side >= it.value * 0.9 }?.key ?: 144
        }

        /**
         * Name shown for an audio or subtitle track. YouTube's manifests put the name in the language
         * field ("Français (générés automatiquement)"); a bare code ("fr", "en-US") is spelled out.
         */
        /** YouTube names audio tracks "Langue (original | dubbed | dubbed-auto | descriptive | secondary)". */
        fun isOriginalAudio(name: String) = name.contains("(originale)") || name.contains("(original)")

        private val AUDIO_KINDS = mapOf(
            "(original)" to "(originale)",
            "(dubbed-auto)" to "(doublage automatique)",
            "(dubbed)" to "(doublée)",
            "(descriptive)" to "(audiodescription)",
            "(secondary)" to "(secondaire)",
        )

        fun trackName(format: Format, index: Int): String {
            val named = trackNameRaw(format, index)
            return AUDIO_KINDS.entries.fold(named) { name, (en, fr) -> name.replace(en, fr) }
        }

        private fun trackNameRaw(format: Format, index: Int): String {
            val raw = format.label ?: format.language ?: return "Piste ${index + 1}"
            val isCode = raw.length <= 6 && raw.none { it == ' ' } && Regex("[a-zA-Z]{2,3}([-_][a-zA-Z0-9]+)?").matches(raw)
            if (!isCode) return raw
            val name = Locale.forLanguageTag(raw.replace('_', '-')).getDisplayName(Locale.FRENCH)
            return name.ifBlank { raw }.replaceFirstChar { it.titlecase(Locale.FRENCH) }
        }

        /** Highest loudness correction: +3.5 dB. */
        const val MAX_LEVEL = 1.5f

        const val MIN_BUFFER_MS = 30_000
        const val MAX_BUFFER_MS = 30_000
        const val START_BUFFER_MS = 1_000
        const val REBUFFER_MS = 2_500
        const val STUCK_MS = 1_500L
        const val FROZEN_CHECKS = 3
        const val FREEZE_TAG = "LumenTube.Freeze"
        const val MAX_BUFFER_BYTES = 40 shl 20
        const val AUTO_MAX = 1080
    }
}
