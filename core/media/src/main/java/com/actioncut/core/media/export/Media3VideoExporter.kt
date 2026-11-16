package com.actioncut.core.media.export

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Size
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.VideoCompositorSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.actioncut.core.domain.port.VideoExporter
import com.actioncut.core.model.AspectRatio
import com.actioncut.core.model.Clip
import com.actioncut.core.model.ClipType
import com.actioncut.core.model.ExportSettings
import com.actioncut.core.model.ExportState
import com.actioncut.core.model.FitMode
import com.actioncut.core.model.Keyframes
import com.actioncut.core.model.Project
import com.actioncut.core.model.Resolution
import com.actioncut.core.model.TrackType
import com.actioncut.core.model.Transition
import com.actioncut.core.model.TransitionType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Default [VideoExporter] backed by **AndroidX Media3 Transformer**.
 */
class Media3VideoExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reverser: com.actioncut.core.media.reverse.VideoReverser,
) : VideoExporter {

    override fun export(
        project: Project,
        settings: ExportSettings,
        outputFilePath: String,
    ): Flow<ExportState> = callbackFlow {
        val rawClips = project.timeline.videoTracks.firstOrNull()?.clips.orEmpty()
            .filter { it.mediaUri != null }

        if (rawClips.isEmpty()) {
            trySend(ExportState.Failed("Timeline has no exportable video clips"))
            close()
            return@callbackFlow
        }

        val clips = withContext(Dispatchers.Default) {
            rawClips.map { clip ->
                if (clip.isReversed && clip.type == ClipType.VIDEO && clip.mediaUri != null) {
                    val reversedUri = reverser.reverse(clip.mediaUri!!, clip.sourceInMs, clip.sourceOutMs)
                    if (reversedUri != null) {
                        clip.copy(mediaUri = reversedUri, isReversed = false, sourceInMs = 0L, sourceOutMs = 0L)
                    } else {
                        clip
                    }
                } else {
                    clip
                }
            }
        }

        val aspect = settings.aspectRatio ?: project.aspectRatio
        val (targetWidth, targetHeight) = targetDimensions(aspect, settings.resolution)
        val handler = Handler(Looper.getMainLooper())
        var transformer: Transformer? = null
        var progressRunnable: Runnable? = null

        handler.post {
            runCatching {
                val overlayTrackIds = project.timeline.tracks
                    .filter { it.type == TrackType.OVERLAY }
                    .flatMap { it.clips }
                    .map { it.id }
                    .toSet()
                val overlayClips = project.timeline.allClips.filter {
                    it.type == ClipType.TEXT ||
                        it.type == ClipType.STICKER ||
                        (it.type == ClipType.IMAGE && it.id in overlayTrackIds)
                }
                val presentationLayout = when (project.canvas.fitMode) {
                    FitMode.FIT -> Presentation.LAYOUT_SCALE_TO_FIT
                    FitMode.FILL -> Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                    FitMode.STRETCH -> Presentation.LAYOUT_STRETCH_TO_FIT
                }
                val editedItems = clips.mapIndexed { index, clip ->
                    val incoming = clips.getOrNull(index - 1)?.transitionToNext
                        ?.takeIf { it.type != TransitionType.NONE && it.type != TransitionType.SEAMLESS }
                    val outgoing = clip.transitionToNext
                        ?.takeIf { it.type != TransitionType.NONE && it.type != TransitionType.SEAMLESS }
                    clip.toEditedMediaItem(
                        targetWidth, targetHeight, settings, overlayClips, presentationLayout,
                        incoming, outgoing,
                    )
                }
                val videoSequence = EditedMediaItemSequence(editedItems)
                val sequences = mutableListOf(videoSequence)

                val pipClips = project.timeline.tracks
                    .filter { it.type == TrackType.OVERLAY }
                    .flatMap { it.clips }
                    .filter { it.mediaUri != null && it.type == ClipType.VIDEO }
                    .sortedBy { it.timelineStartMs }
                if (pipClips.isNotEmpty()) {
                    sequences += EditedMediaItemSequence(
                        pipClips.map {
                            it.toEditedMediaItem(
                                targetWidth, targetHeight, settings, emptyList(),
                                Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
                            )
                        },
                    )
                }

                val audioClips = project.timeline.audioTracks
                    .flatMap { it.clips }
                    .filter { it.mediaUri != null && it.volume > 0f }
                    .sortedBy { it.timelineStartMs }
                if (audioClips.isNotEmpty()) {
                    sequences += EditedMediaItemSequence(audioClips.map { it.toAudioEditedMediaItem() })
                }

                val compositionBuilder = Composition.Builder(sequences)
                    .experimentalSetForceAudioTrack(true)
                if (pipClips.isNotEmpty()) {
                    compositionBuilder.setVideoCompositorSettings(pipCompositorSettings(pipClips.first()))
                }
                val composition = compositionBuilder.build()

                val t = Transformer.Builder(context)
                    .setVideoMimeType(settings.format.mimeType)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            trySend(ExportState.Completed(outputFilePath))
                            close()
                        }

                        override fun onError(
                            composition: Composition,
                            result: ExportResult,
                            exception: ExportException,
                        ) {
                            val chain = generateSequence(exception as Throwable) { it.cause }
                                .mapNotNull { it.message?.trim() }
                                .filter { it.isNotEmpty() }
                                .distinct()
                                .toList()
                            trySend(ExportState.Failed(chain.lastOrNull() ?: chain.joinToString(" <- ").ifEmpty { "Export failed" }))
                            close()
                        }
                    })
                    .build()

                transformer = t
                t.start(composition, outputFilePath)
                trySend(ExportState.InProgress(0f))

                val holder = ProgressHolder()
                progressRunnable = object : Runnable {
                    override fun run() {
                        val current = transformer ?: return
                        val stateInt = current.getProgress(holder)
                        if (stateInt != Transformer.PROGRESS_STATE_NOT_STARTED) {
                            trySend(ExportState.InProgress(holder.progress / 100f))
                        }
                        handler.postDelayed(this, 200L)
                    }
                }
                handler.postDelayed(progressRunnable!!, 200L)
            }.onFailure {
                trySend(ExportState.Failed(it.message ?: "Failed to start export"))
                close()
            }
        }

        awaitClose {
            handler.post {
                progressRunnable?.let { handler.removeCallbacks(it) }
                transformer?.cancel()
                transformer = null
            }
        }
    }

    private fun Clip.toEditedMediaItem(
        targetWidth: Int,
        targetHeight: Int,
        settings: ExportSettings,
        overlays: List<Clip>,
        presentationLayout: Int,
        incoming: Transition? = null,
        outgoing: Transition? = null,
    ): EditedMediaItem {
        val mediaItemBuilder = MediaItem.Builder().setUri(mediaUri)
        if (type != ClipType.IMAGE) {
            mediaItemBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(sourceInMs)
                    .setEndPositionMs(
                        if (sourceOutMs > sourceInMs) sourceOutMs else C.TIME_END_OF_SOURCE,
                    )
                    .build(),
            )
        }

        val videoEffects = EffectMapper.videoEffects(
            clip = this,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            overlays = overlays,
            presentationLayout = presentationLayout,
            context = context,
        ).toMutableList()

        val clipDurUs = timelineDurationMs * 1_000L
        incoming?.let { tr ->
            val edgeUs = (tr.durationMs * 1_000L).coerceAtMost(clipDurUs / 2)
            if (edgeUs > 0L) {
                videoEffects += TransitionEffect(
                    TransitionEdge.IN, TransitionEffect.kindFor(tr.type), edgeUs, clipDurUs,
                )
            }
        }
        outgoing?.let { tr ->
            val edgeUs = (tr.durationMs * 1_000L).coerceAtMost(clipDurUs / 2)
            if (edgeUs > 0L) {
                videoEffects += TransitionEffect(
                    TransitionEdge.OUT, TransitionEffect.kindFor(tr.type), edgeUs, clipDurUs,
                )
            }
        }

        val audioProcessors = EffectMapper.audioProcessors(this)
        val builder = EditedMediaItem.Builder(mediaItemBuilder.build())
            .setEffects(Effects(audioProcessors, videoEffects))

        if (volume == 0f && type == ClipType.VIDEO) {
            builder.setRemoveAudio(true)
        }

        if (type == ClipType.IMAGE) {
            builder.setDurationUs(timelineDurationMs * 1_000)
            builder.setFrameRate(settings.frameRate.fps)
        }
        return builder.build()
    }

    private fun Clip.toAudioEditedMediaItem(): EditedMediaItem {
        val mediaItem = MediaItem.Builder()
            .setUri(mediaUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(sourceInMs)
                    .setEndPositionMs(if (sourceOutMs > sourceInMs) sourceOutMs else C.TIME_END_OF_SOURCE)
                    .build(),
            )
            .build()
        return EditedMediaItem.Builder(mediaItem)
            .setRemoveVideo(true)
            .setEffects(Effects(EffectMapper.audioProcessors(this), emptyList()))
            .build()
    }

    private fun pipCompositorSettings(pip: Clip): VideoCompositorSettings =
        object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>): Size = inputSizes.first()
            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings =
                if (inputId == 1) {
                    val props = Keyframes.propsAt(pip, presentationTimeUs / 1000L)
                    val t = props.transform
                    OverlaySettings.Builder()
                        .setScale(t.scale, t.scale)
                        .setOverlayFrameAnchor(0f, 0f)
                        .setBackgroundFrameAnchor(
                            t.offsetX.coerceIn(-1f, 1f),
                            -t.offsetY.coerceIn(-1f, 1f),
                        )
                        .build()
                } else {
                    OverlaySettings.Builder().build()
                }
        }

    private fun targetDimensions(aspect: AspectRatio, resolution: Resolution): Pair<Int, Int> {
        val longEdge = resolution.longEdge
        return if (aspect.value >= 1f) {
            even(longEdge) to even((longEdge / aspect.value).roundToInt())
        } else {
            even((longEdge * aspect.value).roundToInt()) to even(longEdge)
        }
    }

    private fun even(v: Int): Int = if (v % 2 == 0) v else v + 1
}
