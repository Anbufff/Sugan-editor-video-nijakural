package com.actioncut.core.media.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import com.actioncut.core.media.export.EffectMapper
import com.actioncut.core.model.Clip
import com.actioncut.core.model.ClipType
import com.actioncut.core.model.Timeline
import com.actioncut.core.model.TrackType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.abs

/**
 * Thin wrapper around an [ExoPlayer] for the editor preview. Uses low-res (480p)
 * preview scaling for maximum smoothness while leaving export quality 100% untouched.
 */
class PlayerController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        playWhenReady = false
        repeatMode = Player.REPEAT_MODE_OFF
        trackSelectionParameters = trackSelectionParameters.buildUpon()
            .setMaxVideoSize(854, 480)
            .build()
    }

    private val audioPlayer: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        playWhenReady = false
        repeatMode = Player.REPEAT_MODE_OFF
    }

    val pipPlayer: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        playWhenReady = false
        repeatMode = Player.REPEAT_MODE_OFF
        volume = 0f
        trackSelectionParameters = trackSelectionParameters.buildUpon()
            .setMaxVideoSize(640, 360)
            .build()
    }

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var clipVolumes: List<Float> = emptyList()
    private var previewClips: List<Clip> = emptyList()
    private var lastPreviewSignature: String? = null

    private var videoStartsMs: List<Long> = emptyList()
    private var audioStartsMs: List<Long> = emptyList()
    private var pipStartsMs: List<Long> = emptyList()
    private var totalPreviewMs: Long = 0L

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            mirrorSecondaryPlayers(isPlaying)
            pushState()
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                audioPlayer.pause()
                pipPlayer.pause()
            }
            pushState()
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            applyCurrentVolume()
            applyCurrentClipSpeed()
            applyPreviewEffects()
            pushState()
        }
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) = pushState()
    }

    init {
        player.addListener(listener)
    }

    fun setTimeline(timeline: Timeline) {
        val clips = previewClips(timeline)
        previewClips = clips
        clipVolumes = clips.map { it.volume }
        videoStartsMs = cumulativeStarts(clips.map { previewDurationMs(it) })
        totalPreviewMs = (videoStartsMs.lastOrNull() ?: 0L) + (clips.lastOrNull()?.let { previewDurationMs(it) } ?: 0L)
        val items = clips.map { clip ->
            val builder = MediaItem.Builder().setUri(clip.mediaUri)
            if (clip.type == ClipType.IMAGE) {
                builder.setImageDurationMs(clip.timelineDurationMs)
            } else {
                builder.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.sourceInMs)
                        .setEndPositionMs(
                            if (clip.sourceOutMs > clip.sourceInMs) clip.sourceOutMs
                            else C.TIME_END_OF_SOURCE,
                        )
                        .build(),
                )
            }
            builder.build()
        }
        player.setMediaItems(items)
        player.prepare()
        applyCurrentVolume()
        applyCurrentClipSpeed()
        applyPreviewEffects(force = true)

        val audioClips = audioClipsOf(timeline)
        audioStartsMs = cumulativeStarts(audioClips.map { previewDurationMs(it) })
        audioPlayer.setMediaItems(audioClips.map { it.toClippedMediaItem() })
        audioPlayer.prepare()

        val pipClips = pipClipsOf(timeline)
        pipStartsMs = cumulativeStarts(pipClips.map { previewDurationMs(it) })
        pipPlayer.setMediaItems(pipClips.map { it.toClippedMediaItem() })
        pipPlayer.prepare()

        pushState()
    }

    /** Restores preview decoders/surfaces after Home button or screen-off resume. */
    fun restoreAfterResume(timeline: Timeline, positionMs: Long) {
        if (player.playbackState == Player.STATE_IDLE) {
            setTimeline(timeline)
        }
        seekTo(positionMs)
        applyPreviewEffects(force = true)
    }

    private fun previewDurationMs(clip: Clip): Long = clip.timelineDurationMs.coerceAtLeast(1L)

    private fun cumulativeStarts(durations: List<Long>): List<Long> {
        var acc = 0L
        return durations.map { val start = acc; acc += it; start }
    }

    fun masterPositionMs(): Long {
        if (player.mediaItemCount == 0) return 0L
        val base = videoStartsMs.getOrElse(player.currentMediaItemIndex) { 0L }
        return (base + player.currentPosition.coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    private fun seekAbsolute(p: ExoPlayer, starts: List<Long>, absMs: Long) {
        if (p.mediaItemCount == 0) return
        var idx = 0
        for (i in 0 until p.mediaItemCount) {
            if (absMs >= (starts.getOrElse(i) { 0L })) idx = i else break
        }
        val within = (absMs - starts.getOrElse(idx) { 0L }).coerceAtLeast(0L)
        p.seekTo(idx, within)
    }

    private fun Clip.toClippedMediaItem(): MediaItem =
        MediaItem.Builder()
            .setUri(mediaUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(sourceInMs)
                    .setEndPositionMs(if (sourceOutMs > sourceInMs) sourceOutMs else C.TIME_END_OF_SOURCE)
                    .build(),
            )
            .build()

    private fun audioClipsOf(timeline: Timeline): List<Clip> =
        timeline.audioTracks
            .flatMap { it.clips }
            .filter { it.mediaUri != null && it.volume > 0f }
            .sortedBy { it.timelineStartMs }

    private fun pipClipsOf(timeline: Timeline): List<Clip> =
        timeline.tracks
            .filter { it.type == TrackType.OVERLAY }
            .flatMap { it.clips }
            .filter { it.mediaUri != null && it.type == ClipType.VIDEO }
            .sortedBy { it.timelineStartMs }

    private fun mirrorSecondaryPlayers(isPlaying: Boolean) {
        val master = masterPositionMs()
        if (audioPlayer.mediaItemCount > 0) {
            if (isPlaying) { seekAbsolute(audioPlayer, audioStartsMs, master); audioPlayer.play() } else audioPlayer.pause()
        }
        if (pipPlayer.mediaItemCount > 0) {
            if (isPlaying) { seekAbsolute(pipPlayer, pipStartsMs, master); pipPlayer.play() } else pipPlayer.pause()
        }
    }

    fun updateVolumes(timeline: Timeline) {
        clipVolumes = previewClips(timeline).map { it.volume }
        applyCurrentVolume()
    }

    fun refreshPreviewEffects(timeline: Timeline) {
        previewClips = previewClips(timeline)
        applyCurrentClipSpeed()
        applyPreviewEffects()
    }

    private fun applyCurrentClipSpeed() {
        val speed = previewClips.getOrNull(player.currentMediaItemIndex)?.speed?.coerceIn(0.1f, 4f) ?: 1f
        runCatching {
            if (player.playbackParameters.speed != speed) {
                player.playbackParameters = PlaybackParameters(speed)
            }
        }
    }

    private fun applyPreviewEffects(force: Boolean = false) {
        val clip = previewClips.getOrNull(player.currentMediaItemIndex)
        val signature = previewEffectSignature(clip)
        if (!force && signature == lastPreviewSignature) return
        lastPreviewSignature = signature
        // Downscale preview processing to 480p for smooth playback without affecting export quality.
        val effects: List<Effect> = clip?.let {
            if (it.type == ClipType.VIDEO) {
                listOf(Presentation.createForHeight(480)) + EffectMapper.previewVideoEffects(it)
            } else {
                EffectMapper.previewVideoEffects(it)
            }
        } ?: emptyList()
        runCatching {
            player.setVideoEffects(effects)
            if (!player.isPlaying && player.playbackState == Player.STATE_READY) {
                val pos = player.currentPosition.coerceAtLeast(0L)
                val dur = player.duration.takeIf { it > 0L } ?: (pos + 100L)
                val target = if (pos + 1L < dur) pos + 1L else (pos - 1L).coerceAtLeast(0L)
                if (target != pos) player.seekTo(target)
            }
        }
    }

    private fun previewEffectSignature(clip: Clip?): String = if (clip == null) "" else
        "${clip.id}|${clip.rotationDegrees}|${clip.transform}|${clip.mainAnimation}|" +
            "${clip.flipHorizontal}|${clip.blurBackground}|${clip.crop}|${clip.adjustments}|${clip.filter}|${clip.effects}"

    private fun previewClips(timeline: Timeline): List<Clip> =
        timeline.videoTracks.firstOrNull()?.clips.orEmpty()
            .filter { it.mediaUri != null && it.type != ClipType.TEXT }

    private fun applyCurrentVolume() {
        val index = player.currentMediaItemIndex
        player.volume = clipVolumes.getOrElse(index) { 1f }.coerceIn(0f, 1f)
    }

    fun play() {
        if (player.playbackState == Player.STATE_ENDED) seekTo(0)
        player.play()
    }

    fun pause() {
        player.pause()
        audioPlayer.pause()
        pipPlayer.pause()
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) seekTo(0)
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val pos = positionMs.coerceAtLeast(0)
        seekAbsolute(player, videoStartsMs, pos)
        seekAbsolute(audioPlayer, audioStartsMs, pos)
        seekAbsolute(pipPlayer, pipStartsMs, pos)
        pushState()
    }

    fun setSpeed(speed: Float) {
        val params = PlaybackParameters(speed.coerceIn(0.1f, 10f))
        player.playbackParameters = params
        audioPlayer.playbackParameters = params
        pipPlayer.playbackParameters = params
    }

    /**
     * Captures a high-res JPEG frame from [clip] into permanent `filesDir/captures` so it
     * never disappears on Home/screen-off.
     */
    suspend fun captureFrame(clip: Clip, playheadMs: Long): String? = withContext(Dispatchers.IO) {
        val uri = clip.mediaUri ?: return@withContext null
        val retriever = MediaMetadataRetriever()
        runCatching {
            retriever.setDataSource(context, Uri.parse(uri))
            val relMs = (playheadMs - clip.timelineStartMs).coerceAtLeast(0L)
            val sourceUs = (clip.sourceInMs + (relMs * clip.speed).toLong()) * 1_000L
            val bmp = retriever.getFrameAtTime(sourceUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.getFrameAtTime(sourceUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return@runCatching null
            val capDir = File(context.filesDir, "captures").apply { mkdirs() }
            val outFile = File(capDir, "capture_${System.currentTimeMillis()}.jpg")
            outFile.outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            bmp.recycle()
            Uri.fromFile(outFile).toString()
        }.getOrNull().also { runCatching { retriever.release() } }
    }

    fun positionFlow(intervalMs: Long = 33L): Flow<Long> = flow {
        while (true) {
            val master = masterPositionMs()
            if (audioPlayer.isPlaying) {
                val audioAbs = audioStartsMs.getOrElse(audioPlayer.currentMediaItemIndex) { 0L } + audioPlayer.currentPosition
                if (abs(audioAbs - master) > 300) seekAbsolute(audioPlayer, audioStartsMs, master)
            }
            if (pipPlayer.isPlaying) {
                val pipAbs = pipStartsMs.getOrElse(pipPlayer.currentMediaItemIndex) { 0L } + pipPlayer.currentPosition
                if (abs(pipAbs - master) > 300) seekAbsolute(pipPlayer, pipStartsMs, master)
            }
            emit(master)
            delay(intervalMs)
        }
    }

    fun release() {
        player.removeListener(listener)
        player.release()
        audioPlayer.release()
        pipPlayer.release()
    }

    private fun pushState() {
        _state.value = PlaybackState(
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = masterPositionMs(),
            durationMs = if (totalPreviewMs > 0L) totalPreviewMs else {
                player.duration.let { if (it == C.TIME_UNSET) 0L else it.coerceAtLeast(0) }
            },
            isEnded = player.playbackState == Player.STATE_ENDED,
        )
    }
}
