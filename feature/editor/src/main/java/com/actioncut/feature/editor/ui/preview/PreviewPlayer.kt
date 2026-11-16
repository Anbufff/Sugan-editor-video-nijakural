package com.actioncut.feature.editor.ui.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.actioncut.core.designsystem.component.clickableNoRipple
import com.actioncut.core.media.export.SelfieCutout
import com.actioncut.core.model.AspectRatio
import com.actioncut.core.model.Clip
import com.actioncut.core.model.ClipType
import com.actioncut.core.model.ColorAdjustments
import com.actioncut.core.model.FitMode
import com.actioncut.core.model.Keyframes
import com.actioncut.core.model.MainAnimations
import com.actioncut.core.model.StickerTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.max

/** In-memory low-res cache for fast 60fps photo preview scrubbing (export uses full res). */
private object PreviewBitmapCache {
    private val cache = LruCache<String, ImageBitmap>(24)

    fun get(uri: String): ImageBitmap? = cache.get(uri)

    fun loadDownsampled(context: Context, uri: String, maxDim: Int = 854): ImageBitmap? {
        cache.get(uri)?.let { return it }
        return runCatching {
            val parsed = Uri.parse(uri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(parsed)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            val longest = max(bounds.outWidth, bounds.outHeight)
            while (longest / sample > maxDim * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = context.contentResolver.openInputStream(parsed)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return@runCatching null
            val imgBmp = bmp.asImageBitmap()
            cache.put(uri, imgBmp)
            imgBmp
        }.getOrNull()
    }
}

@Composable
fun PreviewPlayer(
    player: ExoPlayer,
    aspectRatio: AspectRatio,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    overlays: List<Clip> = emptyList(),
    playheadMs: Long = 0L,
    selectedClipId: String? = null,
    onSelectOverlay: (String) -> Unit = {},
    onMoveOverlay: (String, Float, Float) -> Unit = { _, _, _ -> },
    pipPlayer: ExoPlayer? = null,
    pipClips: List<Clip> = emptyList(),
    onScaleOverlay: (String, Float) -> Unit = { _, _ -> },
    fitMode: FitMode = FitMode.FILL,
    backgroundColorArgb: Int = 0xFF000000.toInt(),
    activeMainClip: Clip? = null,
    onPanZoomMainClip: (String, Float, Float, Float) -> Unit = { _, _, _, _ -> },
    onRotateOverlay: (String, Float) -> Unit = { _, _ -> },
    onFlipArrow: (String) -> Unit = {},
    onDoubleTapOverlay: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val resizeMode = when (fitMode) {
        FitMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        FitMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        FitMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    }
    val isImageMain = activeMainClip?.type == ClipType.IMAGE && activeMainClip.mediaUri != null

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(backgroundColorArgb))
                .pointerInput(activeMainClip?.id) {
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    awaitEachGesture {
                        var moved = false
                        var curX = activeMainClip?.transform?.offsetX ?: 0f
                        var curY = activeMainClip?.transform?.offsetY ?: 0f
                        var curScale = activeMainClip?.transform?.scale ?: 1f
                        do {
                            val event = awaitPointerEvent()
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom != 1f || pan.getDistance() > 3f) {
                                moved = true
                                val clip = activeMainClip
                                if (clip != null) {
                                    curScale = (curScale * zoom).coerceIn(0.5f, 4.0f)
                                    curX = (curX + pan.x / (w / 2f)).coerceIn(-1f, 1f)
                                    curY = (curY + pan.y / (h / 2f)).coerceIn(-1f, 1f)
                                    onPanZoomMainClip(clip.id, curX, curY, curScale)
                                }
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (!moved) {
                            onTogglePlay()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // Keep ExoPlayer surface attached for smooth switching, hidden cleanly when displaying a Photo/Capture
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        this.player = player
                        this.resizeMode = resizeMode
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                },
                update = {
                    it.player = player
                    it.resizeMode = resizeMode
                },
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (isImageMain) 0f else 1f),
            )

            // Fast, lag-free Photo/Screenshot renderer (fixes black screen on capture_*.jpg & Home resume)
            if (isImageMain && activeMainClip != null) {
                MainImagePreviewLayer(
                    clip = activeMainClip,
                    playheadMs = playheadMs,
                    fitMode = fitMode,
                    boxW = constraints.maxWidth.toFloat().coerceAtLeast(1f),
                    boxH = constraints.maxHeight.toFloat().coerceAtLeast(1f),
                )
            }

            PipLayer(
                pipPlayer = pipPlayer,
                pipClips = pipClips,
                playheadMs = playheadMs,
                selectedClipId = selectedClipId,
                onSelect = onSelectOverlay,
                onMove = onMoveOverlay,
                onScale = onScaleOverlay,
                modifier = Modifier.fillMaxSize(),
            )

            OverlayLayer(
                overlays = overlays,
                playheadMs = playheadMs,
                selectedClipId = selectedClipId,
                onSelect = onSelectOverlay,
                onMove = onMoveOverlay,
                onRotate = onRotateOverlay,
                onFlipArrow = onFlipArrow,
                onDoubleTap = onDoubleTapOverlay,
                modifier = Modifier.fillMaxSize(),
            )

            if (!isPlaying) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp).padding(start = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MainImagePreviewLayer(
    clip: Clip,
    playheadMs: Long,
    fitMode: FitMode,
    boxW: Float,
    boxH: Float,
) {
    val context = LocalContext.current
    val uri = clip.mediaUri ?: return
    var imgBmp by remember(uri) { mutableStateOf(PreviewBitmapCache.get(uri)) }

    LaunchedEffect(uri) {
        if (imgBmp == null || PreviewBitmapCache.get(uri) == null) {
            imgBmp = withContext(Dispatchers.IO) {
                PreviewBitmapCache.loadDownsampled(context, uri, maxDim = 854)
            }
        }
    }

    val bmp = imgBmp ?: return
    val dur = clip.timelineDurationMs.coerceAtLeast(1L)
    val progress = ((playheadMs - clip.timelineStartMs).toFloat() / dur.toFloat()).coerceIn(0f, 1f)
    val animFrame = MainAnimations.evaluate(
        anim = clip.mainAnimation,
        progress = progress,
        srcW = bmp.width.toFloat(),
        srcH = bmp.height.toFloat(),
        frameW = boxW,
        frameH = boxH,
    )
    val totalScale = (animFrame.scale * clip.transform.scale).coerceIn(0.25f, 4f)
    val tx = (animFrame.offsetX + clip.transform.offsetX) * (boxW / 2f)
    val ty = (animFrame.offsetY + clip.transform.offsetY) * (boxH / 2f)
    val colorFilter = remember(clip.adjustments) { adjustmentsColorFilter(clip.adjustments) }
    val scaleType = when (fitMode) {
        FitMode.FIT -> ContentScale.Fit
        FitMode.FILL -> ContentScale.Crop
        FitMode.STRETCH -> ContentScale.FillBounds
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (clip.blurBackground) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(20.dp)
                    .alpha(0.55f),
            )
        }
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = scaleType,
            colorFilter = colorFilter,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = totalScale * (if (clip.flipHorizontal) -1f else 1f)
                    scaleY = totalScale
                    translationX = tx
                    translationY = ty
                    rotationZ = clip.rotationDegrees.toFloat() + clip.transform.rotationDegrees
                },
        )
    }
}

private fun adjustmentsColorFilter(adj: ColorAdjustments): ColorFilter? {
    if (adj.isIdentity) return null
    val matrix = ColorMatrix().apply {
        if (adj.saturation != 0f) setToSaturation((1f + adj.saturation).coerceIn(0f, 2f))
    }
    val c = (1f + adj.contrast).coerceIn(0.2f, 2f)
    val t = adj.brightness * 255f + (1f - c) * 128f
    val warmR = 1f + adj.warmth * 0.25f
    val warmB = 1f - adj.warmth * 0.25f
    val values = matrix.values
    values[0] *= c * warmR
    values[6] *= c
    values[12] *= c * warmB
    values[4] += t
    values[9] += t
    values[14] += t
    return ColorFilter.colorMatrix(matrix)
}

@Composable
private fun PipLayer(
    pipPlayer: ExoPlayer?,
    pipClips: List<Clip>,
    playheadMs: Long,
    selectedClipId: String?,
    onSelect: (String) -> Unit,
    onMove: (String, Float, Float) -> Unit,
    onScale: (String, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activeClips = pipClips.filter {
        playheadMs >= it.timelineStartMs && playheadMs < it.timelineEndMs
    }
    if (activeClips.isEmpty()) return

    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val density = LocalDensity.current

        activeClips.forEach { active ->
            val props = Keyframes.propsAt(active, playheadMs - active.timelineStartMs)
            val t = props.transform
            val timeLeft = active.timelineEndMs - playheadMs
            val fadeOut = if (timeLeft in 0L..500L) timeLeft / 500f else 1f
            val pipW = with(density) { (w * t.scale).toDp() }
            val pipH = with(density) { (h * t.scale).toDp() }
            val isSelected = active.id == selectedClipId

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(pipW, pipH)
                    .graphicsLayer {
                        translationX = (t.offsetX / 2f) * w
                        translationY = (t.offsetY / 2f) * h
                        rotationZ = t.rotationDegrees
                        alpha = props.opacity * fadeOut
                    }
                    .border(
                        if (isSelected) 2.dp else 1.dp,
                        if (isSelected) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.4f),
                    )
                    .pointerInput(active.id, w, h) {
                        var ox = t.offsetX
                        var oy = t.offsetY
                        detectDragGestures(
                            onDragStart = {
                                onSelect(active.id)
                                ox = active.transform.offsetX
                                oy = active.transform.offsetY
                            },
                            onDrag = { change, drag ->
                                change.consume()
                                ox = (ox + drag.x / (w / 2f)).coerceIn(-1f, 1f)
                                oy = (oy + drag.y / (h / 2f)).coerceIn(-1f, 1f)
                                onMove(active.id, ox, oy)
                            },
                        )
                    }
                    .clickableNoRipple { onSelect(active.id) },
            ) {
                if (active.type == ClipType.IMAGE && active.mediaUri != null) {
                    var imgBitmap by remember(active.mediaUri, active.cutoutEnabled, active.crop) {
                        mutableStateOf<ImageBitmap?>(null)
                    }
                    LaunchedEffect(active.mediaUri, active.cutoutEnabled, active.crop) {
                        imgBitmap = withContext(Dispatchers.IO) {
                            runCatching {
                                val parsed = Uri.parse(active.mediaUri)
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                context.contentResolver.openInputStream(parsed)?.use {
                                    BitmapFactory.decodeStream(it, null, bounds)
                                }
                                var sample = 1
                                val longest = max(bounds.outWidth, bounds.outHeight)
                                while (longest / sample > 960) sample *= 2
                                val raw = context.contentResolver.openInputStream(parsed)?.use {
                                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                                } ?: return@runCatching null
                                val segmented = if (active.cutoutEnabled) {
                                    SelfieCutout.segmentBitmap(raw, active.mediaUri) ?: raw
                                } else raw
                                val c = active.crop
                                val out = if (c != null && !c.isFull) {
                                    val l = (c.left * segmented.width).toInt().coerceIn(0, segmented.width - 2)
                                    val tp = (c.top * segmented.height).toInt().coerceIn(0, segmented.height - 2)
                                    val cw = ((c.right - c.left) * segmented.width).toInt().coerceIn(2, segmented.width - l)
                                    val ch = ((c.bottom - c.top) * segmented.height).toInt().coerceIn(2, segmented.height - tp)
                                    Bitmap.createBitmap(segmented, l, tp, cw, ch)
                                } else segmented
                                out.asImageBitmap()
                            }.getOrNull()
                        }
                    }
                    imgBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp,
                            contentDescription = null,
                            contentScale = if (active.crop != null && !active.crop!!.isFull) ContentScale.Crop else ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else if (pipPlayer != null) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                this.player = pipPlayer
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                setBackgroundColor(android.graphics.Color.BLACK)
                            }
                        },
                        update = { it.player = pipPlayer },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(2.dp, Color(0xFF00E5FF), CircleShape)
                        .pointerInput(active.id, w) {
                            var s = t.scale
                            detectDragGestures(
                                onDragStart = { onSelect(active.id); s = active.transform.scale },
                                onDrag = { change, drag ->
                                    change.consume()
                                    s = (s + drag.x / w).coerceIn(0.1f, 1.5f)
                                    onScale(active.id, s)
                                },
                            )
                        },
                )
            }
        }
    }
}

@Composable
private fun OverlayLayer(
    overlays: List<Clip>,
    playheadMs: Long,
    selectedClipId: String?,
    onSelect: (String) -> Unit,
    onMove: (String, Float, Float) -> Unit,
    onRotate: (String, Float) -> Unit,
    onFlipArrow: (String) -> Unit,
    onDoubleTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        overlays
            .filter { playheadMs >= it.timelineStartMs && playheadMs < it.timelineEndMs }
            .forEach { clip ->
                val props = Keyframes.propsAt(clip, playheadMs - clip.timelineStartMs)
                val t = props.transform
                val timeLeft = clip.timelineEndMs - playheadMs
                val fadeOut = if (timeLeft in 0L..500L) timeLeft / 500f else 1f
                val isSelected = clip.id == selectedClipId

                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .graphicsLayer {
                            translationX = (t.offsetX / 2f) * w
                            translationY = (t.offsetY / 2f) * h
                            rotationZ = t.rotationDegrees
                            alpha = props.opacity * fadeOut
                        }
                        .then(
                            if (isSelected) Modifier.border(1.5.dp, Color(0xFF00E5FF), RoundedCornerShape(4.dp))
                            else Modifier,
                        )
                        .pointerInput(clip.id, w, h) {
                            var ox = t.offsetX
                            var oy = t.offsetY
                            detectDragGestures(
                                onDragStart = {
                                    onSelect(clip.id)
                                    ox = clip.transform.offsetX
                                    oy = clip.transform.offsetY
                                },
                                onDrag = { change, drag ->
                                    change.consume()
                                    ox = (ox + drag.x / (w / 2f)).coerceIn(-1f, 1f)
                                    oy = (oy + drag.y / (h / 2f)).coerceIn(-1f, 1f)
                                    onMove(clip.id, ox, oy)
                                },
                            )
                        }
                        .pointerInput(clip.id, clip.stickerTemplate) {
                            detectTapGestures(
                                onTap = {
                                    if (clip.stickerTemplate == StickerTemplate.ARROW) {
                                        onFlipArrow(clip.id)
                                    } else {
                                        onSelect(clip.id)
                                    }
                                },
                                onDoubleTap = { onDoubleTap(clip.id) },
                            )
                        },
                ) {
                    when (clip.stickerTemplate) {
                        StickerTemplate.NEWS_BREAKING -> BreakingNewsPreview(clip.text?.text ?: "BREAKING NEWS")
                        StickerTemplate.NEWS_FLASH -> FlashNewsPreview(clip.text?.text ?: "FLASH NEWS", isRight = true)
                        StickerTemplate.NEWS_LATEST -> FlashNewsPreview(clip.text?.text ?: "LATEST UPDATE", isRight = false)
                        StickerTemplate.YOUTUBE_SUBSCRIBE -> YoutubeSubscribePreview(clip.text?.text ?: "NEWS CRiCK")
                        StickerTemplate.LOWER_THIRD_GOLD -> LowerThirdGoldPreview(clip.text?.text ?: "LOWER THIRD 1")
                        StickerTemplate.LOWER_THIRD_NEON -> LowerThirdNeonPreview(clip.text?.text ?: "LOWER THIRD 2")
                        StickerTemplate.ARROW -> ArrowPreview(
                            flipHorizontal = clip.flipHorizontal,
                            isSelected = isSelected,
                            onRotateDrag = { dx, dy ->
                                val angle = (atan2(dy.toDouble(), dx.toDouble()) * 180.0 / Math.PI).toFloat()
                                onRotate(clip.id, angle)
                            },
                        )
                        StickerTemplate.NONE -> {
                            val glyph = clip.text?.text ?: ""
                            val baseSp = clip.text?.fontSizeSp ?: 24f
                            Text(
                                text = glyph,
                                fontSize = (baseSp * t.scale).sp,
                                color = Color(clip.text?.colorArgb ?: 0xFFFFFFFF.toInt()),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
    }
}

@Composable
private fun BreakingNewsPreview(text: String) {
    Box(
        modifier = Modifier
            .width(210.dp)
            .padding(top = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Brush.linearGradient(listOf(Color(0xFFD30000), Color(0xFFA00000))))
                .border(1.5.dp, Color.White, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = Color.White, fontWeight = FontWeight.Black, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        Row(
            modifier = Modifier
                .offset(x = 8.dp, y = (-8).dp)
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, Color.White, RoundedCornerShape(4.dp)),
        ) {
            Text("CRiCK", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black, modifier = Modifier.background(Color.Black).padding(horizontal = 4.dp, vertical = 1.dp))
            Text("NEWS", color = Color(0xFFCC0000), fontSize = 9.sp, fontWeight = FontWeight.Black, modifier = Modifier.background(Color.White).padding(horizontal = 4.dp, vertical = 1.dp))
        }
    }
}

@Composable
private fun FlashNewsPreview(text: String, isRight: Boolean) {
    val accent = if (isRight) Color(0xFFFFCC00) else Color(0xFF00E5FF)
    val bg = if (isRight) listOf(Color(0xEEB40000), Color(0xEE500000)) else listOf(Color(0xEE001E50), Color(0xEE000A28))
    Column(
        modifier = Modifier
            .width(180.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Brush.linearGradient(bg))
            .border(1.5.dp, accent, RoundedCornerShape(6.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .background(Color.Black)
                .border(1.dp, accent, RoundedCornerShape(3.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text("NEWS", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Black)
            Text("CRiCK", color = accent, fontSize = 8.sp, fontWeight = FontWeight.Black)
        }
        Text(
            text = text,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun YoutubeSubscribePreview(channelText: String) {
    val inf = rememberInfiniteTransition(label = "yt")
    val phase by inf.animateFloat(
        initialValue = 0f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(tween(5000, easing = LinearEasing), RepeatMode.Restart),
        label = "ytPhase",
    )
    Column(
        modifier = Modifier
            .width(175.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xF5FFFFFF))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(if (phase in 2.5f..4.5f) Color(0xFF282828) else Color.Red)
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (phase >= 2.25f) channelText else "SUBSCRIBE",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp,
            )
        }
        Text("👍 Like   💬 Comment   ↗ Share", color = Color.DarkGray, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LowerThirdGoldPreview(text: String) {
    Row(
        modifier = Modifier
            .width(230.dp)
            .height(34.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xE60A1428), Color(0x99000000))))
            .border(1.dp, Color(0x4DFFD700), RoundedCornerShape(4.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(Color(0xFFFFD700)))
        Text(
            text = text,
            color = Color(0xFFFFD700),
            fontWeight = FontWeight.Black,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
    }
}

@Composable
private fun LowerThirdNeonPreview(text: String) {
    Row(
        modifier = Modifier
            .width(230.dp)
            .height(34.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xF2111111), Color(0xCC333333))))
            .border(1.dp, Color(0xFF00E5FF), RoundedCornerShape(20.dp))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "INFO",
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFFF0055))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
        Text(
            text = text,
            color = Color(0xFF00E5FF),
            fontWeight = FontWeight.Black,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun ArrowPreview(
    flipHorizontal: Boolean,
    isSelected: Boolean,
    onRotateDrag: (Float, Float) -> Unit,
) {
    val inf = rememberInfiniteTransition(label = "arrow")
    val bounce by inf.animateFloat(
        initialValue = 0f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "bounce",
    )
    Box(contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .size(width = 64.dp, height = 44.dp)
                .graphicsLayer {
                    scaleX = if (flipHorizontal) -1f else 1f
                    translationX = if (flipHorizontal) -bounce else bounce
                },
        ) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w * 0.05f, h * 0.35f)
                lineTo(w * 0.60f, h * 0.35f)
                lineTo(w * 0.60f, h * 0.10f)
                lineTo(w * 0.95f, h * 0.50f)
                lineTo(w * 0.60f, h * 0.90f)
                lineTo(w * 0.60f, h * 0.65f)
                lineTo(w * 0.05f, h * 0.65f)
                close()
            }
            drawPath(path, Color(0xFFFFCC00))
            drawPath(path, Color.Black, style = Stroke(width = 3f))
        }
        if (isSelected) {
            Box(
                modifier = Modifier
                    .offset(y = (-30).dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(2.dp, Color(0xFFFFCC00), CircleShape)
                    .pointerInput(Unit) {
                        var ax = 0f
                        var ay = -40f
                        detectDragGestures { change, drag ->
                            change.consume()
                            ax += drag.x
                            ay += drag.y
                            onRotateDrag(ax, ay)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Rotate", tint = Color(0xFFFFCC00), modifier = Modifier.size(12.dp))
            }
        }
    }
}
