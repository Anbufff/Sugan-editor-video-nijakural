package com.actioncut.feature.editor.ui.timeline

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.actioncut.core.designsystem.component.clickableNoRipple
import com.actioncut.core.designsystem.theme.LaneAudio
import com.actioncut.core.designsystem.theme.LaneOverlay
import com.actioncut.core.designsystem.theme.LaneText
import com.actioncut.core.designsystem.theme.LaneVideo
import com.actioncut.core.model.Clip
import com.actioncut.core.model.ClipType
import com.actioncut.core.model.Timeline
import com.actioncut.core.model.Track
import com.actioncut.core.model.TrackType
import com.actioncut.core.model.TransitionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private const val TRACK_HEIGHT = 52
private const val RULER_HEIGHT = 22
private const val HANDLE_WIDTH = 14

/** Tiny ultra-low-res thumbnail cache for smooth timeline scrolling without memory pressure. */
private object TimelineThumbCache {
    private val cache = LruCache<String, ImageBitmap>(40)

    fun get(uri: String): ImageBitmap? = cache.get(uri)

    fun loadTiny(context: Context, uri: String, isVideo: Boolean): ImageBitmap? {
        cache.get(uri)?.let { return it }
        return runCatching {
            val parsed = Uri.parse(uri)
            val bmp = if (isVideo) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, parsed)
                    val frame = r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: return@runCatching null
                    val scale = 96f / max(frame.width, frame.height).coerceAtLeast(1)
                    if (scale < 1f) {
                        android.graphics.Bitmap.createScaledBitmap(
                            frame,
                            (frame.width * scale).roundToInt().coerceAtLeast(16),
                            (frame.height * scale).roundToInt().coerceAtLeast(16),
                            true,
                        ).also { frame.recycle() }
                    } else frame
                } finally {
                    runCatching { r.release() }
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(parsed)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                var sample = 1
                val longest = max(bounds.outWidth, bounds.outHeight)
                while (longest / sample > 128) sample *= 2
                context.contentResolver.openInputStream(parsed)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            } ?: return@runCatching null
            val img = bmp.asImageBitmap()
            cache.put(uri, img)
            img
        }.getOrNull()
    }
}

/**
 * CapCut-style timeline with low-res smooth thumbnails, touch-to-pause,
 * 2-finger pinch-to-zoom, transition diamonds, capture marks, lock badges & keyframes.
 */
@Composable
fun EditorTimeline(
    timeline: Timeline,
    playheadMs: Long,
    durationMs: Long,
    pxPerSecond: Float,
    isPlaying: Boolean,
    selectedClipId: String?,
    onScrub: (Long) -> Unit,
    onSelectClip: (String?) -> Unit,
    onTrimStart: (String, Long) -> Unit,
    onTrimEnd: (String, Long) -> Unit,
    onMoveClip: (String, Long) -> Unit = { _, _ -> },
    onMoveCommit: () -> Unit = {},
    onLoadWaveform: (suspend (String) -> FloatArray)? = null,
    onTransitionClick: (String) -> Unit = {},
    onLongPressClip: (String) -> Unit = {},
    onSelectKeyframe: (Long) -> Unit = {},
    onPausePlayback: () -> Unit = {},
    onZoomChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    val layoutDurationMs = durationMs.coerceAtLeast(2_000)

    val currentPxPerSec by rememberUpdatedState(pxPerSecond)
    val currentIsPlaying by rememberUpdatedState(isPlaying)
    val currentOnPause by rememberUpdatedState(onPausePlayback)
    val currentOnZoom by rememberUpdatedState(onZoomChange)

    fun msToPxF(ms: Long): Float = ms / 1000f * pxPerSecond
    fun pxToMs(px: Int): Long = (px / pxPerSecond * 1000f).toLong()

    // User scroll -> seek ONLY when paused (ignores programmatic follow-scroll during playback).
    LaunchedEffect(scrollState, pxPerSecond, durationMs) {
        snapshotFlow { scrollState.value to scrollState.isScrollInProgress }
            .collect { (value, inProgress) ->
                if (inProgress && !currentIsPlaying) {
                    onScrub(pxToMs(value).coerceIn(0, durationMs))
                }
            }
    }
    LaunchedEffect(playheadMs, isPlaying) {
        if (!scrollState.isScrollInProgress) {
            val target = msToPxF(playheadMs).roundToInt()
            if (abs(scrollState.value - target) > 1) scrollState.scrollTo(target)
        }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            // Actual finger touch on timeline -> immediately pause playback
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.changes.any { it.pressed } && currentIsPlaying) {
                            currentOnPause()
                        }
                    }
                }
            }
            // Two-finger pinch-to-zoom on timeline (leaves 1-finger horizontal scroll untouched)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f && abs(zoom - 1f) > 0.005f) {
                                currentOnZoom(currentPxPerSec * zoom)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        val viewportPx = constraints.maxWidth
        val halfViewportDp = with(density) { (viewportPx / 2).toDp() }
        val contentWidthDp = with(density) { msToPxF(layoutDurationMs).toDp() }

        Row(modifier = Modifier.horizontalScroll(scrollState)) {
            Spacer(Modifier.width(halfViewportDp))
            Column {
                TimeRuler(layoutDurationMs, pxPerSecond, contentWidthDp)
                val orderedTracks = timeline.tracks.sortedBy { it.index }
                if (orderedTracks.isEmpty()) {
                    Spacer(Modifier.width(contentWidthDp).height(TRACK_HEIGHT.dp))
                }
                orderedTracks.forEach { track ->
                    TrackLane(
                        track = track,
                        playheadMs = playheadMs,
                        pxPerSecond = pxPerSecond,
                        contentWidthDp = contentWidthDp,
                        selectedClipId = selectedClipId,
                        onSelectClip = onSelectClip,
                        onTrimStart = onTrimStart,
                        onTrimEnd = onTrimEnd,
                        onMoveClip = onMoveClip,
                        onMoveCommit = onMoveCommit,
                        onLoadWaveform = onLoadWaveform,
                        onTransitionClick = onTransitionClick,
                        onLongPressClip = onLongPressClip,
                        onSelectKeyframe = onSelectKeyframe,
                    )
                }
            }
            Spacer(Modifier.width(halfViewportDp))
        }

        // Fixed center playhead.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(2.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun TimeRuler(durationMs: Long, pxPerSecond: Float, widthDp: androidx.compose.ui.unit.Dp) {
    val totalSeconds = (durationMs / 1000).toInt()
    val step = when {
        pxPerSecond >= 120 -> 1
        pxPerSecond >= 60 -> 2
        else -> 5
    }
    Box(
        modifier = Modifier
            .width(widthDp)
            .height(RULER_HEIGHT.dp),
    ) {
        val density = LocalDensity.current
        for (second in 0..totalSeconds step step) {
            val xDp = with(density) { (second * pxPerSecond).toDp() }
            Text(
                text = "${second}s",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.offset(x = xDp).padding(start = 2.dp),
            )
        }
    }
}

@Composable
private fun TrackLane(
    track: Track,
    playheadMs: Long,
    pxPerSecond: Float,
    contentWidthDp: androidx.compose.ui.unit.Dp,
    selectedClipId: String?,
    onSelectClip: (String?) -> Unit,
    onTrimStart: (String, Long) -> Unit,
    onTrimEnd: (String, Long) -> Unit,
    onMoveClip: (String, Long) -> Unit,
    onMoveCommit: () -> Unit,
    onLoadWaveform: (suspend (String) -> FloatArray)?,
    onTransitionClick: (String) -> Unit,
    onLongPressClip: (String) -> Unit,
    onSelectKeyframe: (Long) -> Unit,
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .width(contentWidthDp)
            .height(TRACK_HEIGHT.dp)
            .padding(vertical = 3.dp),
    ) {
        track.clips.forEach { clip ->
            val xDp = with(density) { (clip.timelineStartMs / 1000f * pxPerSecond).toDp() }
            Box(modifier = Modifier.offset(x = xDp).fillMaxHeight()) {
                ClipBlock(
                    clip = clip,
                    playheadMs = playheadMs,
                    pxPerSecond = pxPerSecond,
                    selected = clip.id == selectedClipId,
                    onSelect = { onSelectClip(clip.id) },
                    onTrimStart = onTrimStart,
                    onTrimEnd = onTrimEnd,
                    onMoveClip = onMoveClip,
                    onMoveCommit = onMoveCommit,
                    onLoadWaveform = onLoadWaveform,
                    onLongPressClip = onLongPressClip,
                    onSelectKeyframe = onSelectKeyframe,
                )
            }
        }

        if (track.type == TrackType.VIDEO && track.clips.size > 1) {
            for (i in 0 until track.clips.lastIndex) {
                val clip = track.clips[i]
                val hasTransition = clip.transitionToNext != null && clip.transitionToNext!!.type != TransitionType.NONE
                val boundaryX = with(density) { (clip.timelineEndMs / 1000f * pxPerSecond).toDp() }
                Box(
                    modifier = Modifier
                        .offset(x = boundaryX - 9.dp)
                        .align(Alignment.CenterStart)
                        .size(18.dp)
                        .rotate(45f)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (hasTransition) Color(0xFF00E5FF) else Color.White)
                        .border(1.dp, Color.Black, RoundedCornerShape(3.dp))
                        .clickableNoRipple { onTransitionClick(clip.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (hasTransition) Icons.Filled.AutoFixHigh else Icons.Filled.Edit,
                        contentDescription = "Transition",
                        tint = Color.Black,
                        modifier = Modifier.size(10.dp).rotate(-45f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipBlock(
    clip: Clip,
    playheadMs: Long,
    pxPerSecond: Float,
    selected: Boolean,
    onSelect: () -> Unit,
    onTrimStart: (String, Long) -> Unit,
    onTrimEnd: (String, Long) -> Unit,
    onMoveClip: (String, Long) -> Unit = { _, _ -> },
    onMoveCommit: () -> Unit = {},
    onLoadWaveform: (suspend (String) -> FloatArray)? = null,
    onLongPressClip: (String) -> Unit = {},
    onSelectKeyframe: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val widthDp = with(density) { (clip.timelineDurationMs / 1000f * pxPerSecond).toDp() }
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    var moveAnchor by remember(clip.id) { mutableLongStateOf(clip.timelineStartMs) }
    var moveAcc by remember(clip.id) { mutableFloatStateOf(0f) }

    var thumb by remember(clip.mediaUri) {
        mutableStateOf(clip.mediaUri?.let { TimelineThumbCache.get(it) })
    }
    if ((clip.type == ClipType.VIDEO || clip.type == ClipType.IMAGE) && clip.mediaUri != null) {
        LaunchedEffect(clip.mediaUri) {
            if (thumb == null) {
                thumb = withContext(Dispatchers.IO) {
                    TimelineThumbCache.loadTiny(context, clip.mediaUri!!, clip.type == ClipType.VIDEO)
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .width(widthDp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .background(laneColor(clip.type))
            .border(2.dp, borderColor, RoundedCornerShape(6.dp))
            .clickableNoRipple { onSelect() }
            .pointerInput(clip.id, pxPerSecond, clip.isLocked) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        onSelect()
                        moveAnchor = clip.timelineStartMs
                        moveAcc = 0f
                    },
                    onDrag = { _, drag ->
                        if (!clip.isLocked) {
                            moveAcc += drag.x
                            onMoveClip(clip.id, moveAnchor + (moveAcc / pxPerSecond * 1000f).toLong())
                        }
                    },
                    onDragEnd = {
                        if (!clip.isLocked) {
                            if (abs(moveAcc) < 4f) onLongPressClip(clip.id) else onMoveCommit()
                        }
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        thumb?.let { bmp ->
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.50f),
            )
        }

        if (clip.type == ClipType.AUDIO && clip.mediaUri != null && onLoadWaveform != null) {
            AudioWaveform(
                uri = clip.mediaUri!!,
                loader = onLoadWaveform,
                modifier = Modifier.matchParentSize(),
            )
        }

        if (clip.captureMarksMs.isNotEmpty()) {
            Canvas(modifier = Modifier.matchParentSize()) {
                clip.captureMarksMs.forEach { relMs ->
                    val x = (relMs / 1000f * pxPerSecond).coerceIn(0f, size.width)
                    drawLine(
                        color = Color(0xFFFFE300),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 3f,
                    )
                    drawCircle(
                        color = Color(0xFFFFE300),
                        radius = 5f,
                        center = Offset(x, 5f),
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .background(Color.Black.copy(alpha = if (thumb != null) 0.45f else 0f), RoundedCornerShape(4.dp))
                .padding(horizontal = if (thumb != null) 4.dp else 0.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = clipIcon(clip.type),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = clipLabel(clip),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (clip.isLocked) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = "Locked",
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
            }
        }

        clip.keyframes.forEach { kf ->
            val kfX = with(density) { (kf.timeMs / 1000f * pxPerSecond).toDp() }
            val isNear = abs(playheadMs - (clip.timelineStartMs + kf.timeMs)) < 200L
            Box(
                modifier = Modifier
                    .offset(x = kfX - 5.dp)
                    .align(Alignment.BottomStart)
                    .padding(bottom = 3.dp)
                    .size(9.dp)
                    .rotate(45f)
                    .background(if (isNear) Color(0xFFFF3B5C) else Color(0xFFFFE300))
                    .border(1.dp, Color.Black)
                    .clickableNoRipple { onSelectKeyframe(clip.timelineStartMs + kf.timeMs) },
            )
        }

        if (selected && !clip.isLocked) {
            ClipTrimHandles(
                clip = clip,
                pxPerSecond = pxPerSecond,
                onTrimStart = onTrimStart,
                onTrimEnd = onTrimEnd,
            )
        }
    }
}

@Composable
private fun AudioWaveform(
    uri: String,
    loader: suspend (String) -> FloatArray,
    modifier: Modifier = Modifier,
) {
    var amplitudes by remember(uri) { mutableStateOf(FloatArray(0)) }
    LaunchedEffect(uri) {
        amplitudes = runCatching { loader(uri) }.getOrNull() ?: FloatArray(0)
    }
    val waveColor = Color.White.copy(alpha = 0.38f)
    Canvas(modifier = modifier.padding(horizontal = 6.dp, vertical = 8.dp)) {
        val amps = amplitudes
        if (amps.isEmpty()) return@Canvas
        val barWidth = size.width / amps.size
        val midY = size.height / 2f
        for (i in amps.indices) {
            val barHeight = (amps[i] * size.height).coerceAtLeast(2f)
            val x = i * barWidth + barWidth / 2f
            drawLine(
                color = waveColor,
                start = Offset(x, midY - barHeight / 2f),
                end = Offset(x, midY + barHeight / 2f),
                strokeWidth = (barWidth * 0.55f).coerceAtLeast(1f),
            )
        }
    }
}

@Composable
private fun ClipTrimHandles(
    clip: Clip,
    pxPerSecond: Float,
    onTrimStart: (String, Long) -> Unit,
    onTrimEnd: (String, Long) -> Unit,
) {
    Box(Modifier.fillMaxWidth().fillMaxHeight()) {
        DragHandle(
            modifier = Modifier.align(Alignment.CenterStart),
            initialValue = clip.timelineStartMs,
            pxPerSecond = pxPerSecond,
            onValue = { onTrimStart(clip.id, it) },
        )
        DragHandle(
            modifier = Modifier.align(Alignment.CenterEnd),
            initialValue = clip.timelineEndMs,
            pxPerSecond = pxPerSecond,
            onValue = { onTrimEnd(clip.id, it) },
        )
    }
}

@Composable
private fun DragHandle(
    modifier: Modifier,
    initialValue: Long,
    pxPerSecond: Float,
    onValue: (Long) -> Unit,
) {
    var anchor by remember { mutableLongStateOf(initialValue) }
    var acc by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = modifier
            .width(HANDLE_WIDTH.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.primary)
            .pointerInput(initialValue, pxPerSecond) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        anchor = initialValue
                        acc = 0f
                    },
                    onHorizontalDrag = { _, delta ->
                        acc += delta
                        onValue(anchor + (acc / pxPerSecond * 1000f).toLong())
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 18.dp)
                .background(Color.White, RoundedCornerShape(2.dp)),
        )
    }
}

private fun laneColor(type: ClipType): Color = when (type) {
    ClipType.VIDEO -> LaneVideo
    ClipType.IMAGE -> LaneVideo
    ClipType.AUDIO -> LaneAudio
    ClipType.TEXT -> LaneText
    ClipType.STICKER -> LaneOverlay
}

private fun clipIcon(type: ClipType) = when (type) {
    ClipType.VIDEO -> Icons.Filled.Movie
    ClipType.IMAGE -> Icons.Filled.Image
    ClipType.AUDIO -> Icons.Filled.Audiotrack
    ClipType.TEXT -> Icons.Filled.TextFields
    ClipType.STICKER -> Icons.Filled.Image
}

private fun clipLabel(clip: Clip): String = when (clip.type) {
    ClipType.TEXT -> clip.text?.text ?: "Text"
    ClipType.STICKER -> clip.text?.text?.takeIf { it.isNotBlank() } ?: clip.stickerTemplate.displayName
    ClipType.AUDIO -> "Audio"
    else -> clip.mediaUri?.substringAfterLast('/')?.take(14) ?: clip.type.name
}
