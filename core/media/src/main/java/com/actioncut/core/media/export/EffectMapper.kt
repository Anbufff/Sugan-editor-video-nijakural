package com.actioncut.core.media.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.opengl.GLES20
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.LruCache
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.Crop
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SingleColorLut
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import com.actioncut.core.model.Clip
import com.actioncut.core.model.ClipType
import com.actioncut.core.model.CropRect
import com.actioncut.core.model.Keyframes
import com.actioncut.core.model.MainAnimation
import com.actioncut.core.model.MainAnimations
import com.actioncut.core.model.SpeedRamp
import com.actioncut.core.model.SpeedRamps
import com.actioncut.core.model.StickerTemplate
import com.actioncut.core.model.VisualEffect
import com.actioncut.core.model.VisualEffectType
import com.google.android.gms.tasks.Tasks
import com.google.common.collect.ImmutableList
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-device ML Kit Selfie Segmentation helper used by both live preview and export
 * when a PIP image has AI Cutout enabled.
 */
object SelfieCutout {
    private val cache = LruCache<String, Bitmap>(16)
    private val segmenter by lazy {
        val opts = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        Segmentation.getClient(opts)
    }

    fun getCached(key: String): Bitmap? = cache.get(key)

    fun segmentBitmap(source: Bitmap, cacheKey: String? = null): Bitmap? {
        if (cacheKey != null) cache.get(cacheKey)?.let { return it }
        return runCatching {
            val copy = if (source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, false)
            val mask = Tasks.await(segmenter.process(InputImage.fromBitmap(copy, 0)))
            val mw = mask.width
            val mh = mask.height
            val buf = mask.buffer
            buf.rewind()
            val floats = buf.order(ByteOrder.nativeOrder()).asFloatBuffer()

            val scaledMask = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
            val maskPixels = IntArray(mw * mh)
            for (i in maskPixels.indices) {
                val conf = floats.get(i).coerceIn(0f, 1f)
                // Smooth soft edge feathering between 0.25 and 0.75 confidence
                val alpha = (((conf - 0.25f) / 0.50f).coerceIn(0f, 1f) * 255f).toInt()
                maskPixels[i] = (alpha shl 24)
            }
            scaledMask.setPixels(maskPixels, 0, mw, 0, 0, mw, mh)
            val fullMask = if (mw == copy.width && mh == copy.height) {
                scaledMask
            } else {
                Bitmap.createScaledBitmap(scaledMask, copy.width, copy.height, true).also {
                    scaledMask.recycle()
                }
            }

            val out = Bitmap.createBitmap(copy.width, copy.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawBitmap(fullMask, 0f, 0f, paint)
            paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(copy, 0f, 0f, paint)
            fullMask.recycle()
            if (cacheKey != null) cache.put(cacheKey, out)
            out
        }.getOrNull()
    }
}

/**
 * Translates a [Clip]'s editing properties into a Media3 GPU [Effect] chain (and audio
 * processors) applied in both live WYSIWYG preview and export.
 */
object EffectMapper {

    fun videoEffects(clip: Clip, targetWidth: Int, targetHeight: Int): List<Effect> =
        videoEffects(clip, targetWidth, targetHeight, emptyList())

    fun previewVideoEffects(clip: Clip): List<Effect> {
        val effects = mutableListOf<Effect>()

        // --- Geometry ---
        val rotation = clip.rotationDegrees + clip.transform.rotationDegrees
        if (rotation % 360f != 0f) {
            effects += ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(rotation)
                .build()
        }
        clip.crop?.takeUnless { it.isFull }?.let { effects += cropEffect(it) }

        // --- Main Motion / Pan / Zoom / Fit Ken Burns / Flip ---
        val needsMotion = clip.mainAnimation != MainAnimation.NONE ||
            clip.flipHorizontal ||
            clip.transform.scale != 1f ||
            clip.transform.offsetX != 0f ||
            clip.transform.offsetY != 0f
        if (needsMotion) {
            effects += MainMotionEffect(
                clipDurationUs = (clip.timelineDurationMs.coerceAtLeast(100L)) * 1_000L,
                animation = clip.mainAnimation,
                manualScale = clip.transform.scale,
                manualOffsetX = clip.transform.offsetX,
                manualOffsetY = clip.transform.offsetY,
                flipH = clip.flipHorizontal,
                blurBg = clip.blurBackground,
            )
        }

        // --- Colour adjustments ---
        val adj = clip.adjustments
        if (adj.brightness != 0f) effects += Brightness(adj.brightness.coerceIn(-1f, 1f))
        if (adj.contrast != 0f) effects += Contrast(adj.contrast.coerceIn(-1f, 1f))
        if (adj.saturation != 0f) {
            effects += HslAdjustment.Builder()
                .adjustSaturation((adj.saturation * 100f).coerceIn(-100f, 100f))
                .build()
        }
        if (adj.warmth != 0f) {
            val w = adj.warmth.coerceIn(-1f, 1f)
            effects += RgbAdjustment.Builder()
                .setRedScale(1f + w * 0.3f)
                .setBlueScale(1f - w * 0.3f)
                .build()
        }

        // --- Filter (real 3D LUT) ---
        clip.filter?.let { filter ->
            FilterLuts.cubeFor(filter.id, filter.intensity)?.let { cube ->
                effects += SingleColorLut.createFromCube(cube)
            }
        }

        // --- Stylistic visual effects ---
        clip.effects.forEach { ve -> visualEffect(ve)?.let { effects += it } }

        return effects
    }

    fun videoEffects(
        clip: Clip,
        targetWidth: Int,
        targetHeight: Int,
        overlays: List<Clip> = emptyList(),
        presentationLayout: Int = Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
        context: Context? = null,
    ): List<Effect> {
        val effects = previewVideoEffects(clip).toMutableList()

        // --- Speed (video): constant or variable ramp ---
        if (clip.type == ClipType.VIDEO) {
            if (clip.speedRamp != SpeedRamp.NONE) {
                val durUs = (clip.sourceDurationMs.takeIf { it > 0L } ?: clip.timelineDurationMs) * 1000L
                effects += SpeedChangeEffect(RampSpeedProvider(clip.speedRamp, clip.speed, durUs))
            } else if (clip.speed != 1f) {
                effects += SpeedChangeEffect(clip.speed)
            }
        }

        // --- Output scaling ---
        effects += Presentation.createForWidthAndHeight(
            targetWidth,
            targetHeight,
            presentationLayout,
        )

        // --- Sticker / Text / PIP-Image overlays ---
        overlayEffect(clip, overlays, targetWidth, targetHeight, context)?.let { effects += it }
        return effects
    }

    private fun overlayEffect(
        clip: Clip,
        overlays: List<Clip>,
        targetWidth: Int,
        targetHeight: Int,
        context: Context?,
    ): Effect? {
        val active = overlays.filter { ov ->
            ov.timelineStartMs < clip.timelineEndMs && ov.timelineEndMs > clip.timelineStartMs
        }
        if (active.isEmpty()) return null

        val textureOverlays = mutableListOf<TextureOverlay>()
        for (ov in active) {
            val relStartMs = (ov.timelineStartMs - clip.timelineStartMs)
            val ovDurMs = ov.timelineDurationMs.coerceAtLeast(100L)
            when {
                ov.type == ClipType.IMAGE && ov.mediaUri != null && context != null -> {
                    loadPipImageOverlay(context, ov, relStartMs, ovDurMs, targetWidth, targetHeight)?.let {
                        textureOverlays += it
                    }
                }
                ov.type == ClipType.STICKER && ov.stickerTemplate != StickerTemplate.NONE -> {
                    textureOverlays += AnimatedStickerOverlay(ov, relStartMs, ovDurMs, targetWidth, targetHeight)
                }
                !ov.text?.text.isNullOrEmpty() -> {
                    val props = ov.text!!
                    val span = SpannableString(props.text)
                    val sizePx = (props.fontSizeSp * ov.transform.scale * 2.5f).toInt().coerceAtLeast(24)
                    span.setSpan(AbsoluteSizeSpan(sizePx), 0, span.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                    span.setSpan(ForegroundColorSpan(props.colorArgb), 0, span.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                    val settings = OverlaySettings.Builder()
                        .setBackgroundFrameAnchor(
                            ov.transform.offsetX.coerceIn(-1f, 1f),
                            -ov.transform.offsetY.coerceIn(-1f, 1f),
                        )
                        .setRotationDegrees(ov.transform.rotationDegrees)
                        .build()
                    textureOverlays += TextOverlay.createStaticTextOverlay(span, settings)
                }
            }
        }
        if (textureOverlays.isEmpty()) return null
        return OverlayEffect(ImmutableList.copyOf(textureOverlays))
    }

    private fun loadPipImageOverlay(
        context: Context,
        ov: Clip,
        relStartMs: Long,
        ovDurMs: Long,
        targetWidth: Int,
        targetHeight: Int,
    ): TextureOverlay? {
        val uri = ov.mediaUri ?: return null
        val raw = runCatching {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull() ?: return null

        val processed = if (ov.cutoutEnabled) {
            SelfieCutout.segmentBitmap(raw, cacheKey = uri) ?: raw
        } else {
            raw
        }

        // Apply CropRect if any
        val cropped = ov.crop?.takeUnless { it.isFull }?.let { c ->
            val l = (c.left * processed.width).toInt().coerceIn(0, processed.width - 2)
            val t = (c.top * processed.height).toInt().coerceIn(0, processed.height - 2)
            val w = ((c.right - c.left) * processed.width).toInt().coerceIn(2, processed.width - l)
            val h = ((c.bottom - c.top) * processed.height).toInt().coerceIn(2, processed.height - t)
            Bitmap.createBitmap(processed, l, t, w, h)
        } ?: processed

        val baseScale = min((targetWidth * 0.45f) / cropped.width, (targetHeight * 0.45f) / cropped.height)
            .coerceAtMost(1f)
        val finalBmp = if (baseScale < 1f) {
            Bitmap.createScaledBitmap(
                cropped,
                max(2, (cropped.width * baseScale).roundToInt()),
                max(2, (cropped.height * baseScale).roundToInt()),
                true,
            )
        } else {
            cropped
        }

        return object : BitmapOverlay() {
            override fun getBitmap(presentationTimeUs: Long): Bitmap = finalBmp

            override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
                val clipLocalMs = (presentationTimeUs / 1000L) - relStartMs
                if (clipLocalMs < 0L || clipLocalMs > ovDurMs) {
                    return OverlaySettings.Builder().setAlphaScale(0f).build()
                }
                val props = Keyframes.propsAt(ov, clipLocalMs)
                val timeLeftMs = ovDurMs - clipLocalMs
                val fadeOut = if (timeLeftMs in 0L..500L) timeLeftMs / 500f else 1f
                return OverlaySettings.Builder()
                    .setBackgroundFrameAnchor(
                        props.transform.offsetX.coerceIn(-1f, 1f),
                        -props.transform.offsetY.coerceIn(-1f, 1f),
                    )
                    .setScale(props.transform.scale, props.transform.scale)
                    .setRotationDegrees(-props.transform.rotationDegrees)
                    .setAlphaScale((props.opacity * fadeOut).coerceIn(0f, 1f))
                    .build()
            }
        }
    }

    fun audioProcessors(clip: Clip): List<AudioProcessor> {
        val processors = mutableListOf<AudioProcessor>()
        if (clip.speed != 1f && (clip.type == ClipType.VIDEO || clip.type == ClipType.AUDIO)) {
            processors += SonicAudioProcessor().apply { setSpeed(clip.speed) }
        }
        val volume = clip.volume
        if (volume != 1f && volume != 0f) {
            processors += ChannelMixingAudioProcessor().apply {
                putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1).scaleBy(volume))
                putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2).scaleBy(volume))
            }
        }
        return processors
    }

    private fun cropEffect(crop: CropRect): Crop {
        val left = crop.left * 2f - 1f
        val right = crop.right * 2f - 1f
        val top = 1f - crop.top * 2f
        val bottom = 1f - crop.bottom * 2f
        return Crop(left, right, bottom, top)
    }

    private fun visualEffect(effect: VisualEffect): Effect? = when (effect.type) {
        VisualEffectType.GAUSSIAN_BLUR,
        VisualEffectType.BOKEH,
        -> GaussianBlur(1f + effect.intensity * 8f)
        VisualEffectType.RADIAL_BLUR -> GaussianBlur(1f + effect.intensity * 6f)
        else -> Shaders.fragmentFor(effect.type)?.let { frag ->
            ShaderEffect(frag, effect.intensity.coerceIn(0f, 1f))
        }
    }
}

/**
 * Double-buffered [BitmapOverlay] rendering animated HTML-style News Alerts (1, 2, 3),
 * YouTube Subscribe (4), Lower Thirds (5, 6), and Bouncing Arrow (7) on every frame.
 */
private class AnimatedStickerOverlay(
    private val clip: Clip,
    private val relStartMs: Long,
    private val ovDurMs: Long,
    targetWidth: Int,
    targetHeight: Int,
) : BitmapOverlay() {

    private val bmpW = when (clip.stickerTemplate) {
        StickerTemplate.NEWS_FLASH, StickerTemplate.NEWS_LATEST -> max(320, (targetWidth * 0.65f).roundToInt())
        StickerTemplate.LOWER_THIRD_GOLD, StickerTemplate.LOWER_THIRD_NEON -> max(420, (targetWidth * 0.70f).roundToInt())
        StickerTemplate.ARROW -> max(160, (targetWidth * 0.16f).roundToInt())
        else -> max(380, (targetWidth * 0.55f).roundToInt())
    }
    private val bmpH = when (clip.stickerTemplate) {
        StickerTemplate.NEWS_FLASH, StickerTemplate.NEWS_LATEST -> max(240, targetHeight)
        StickerTemplate.LOWER_THIRD_GOLD, StickerTemplate.LOWER_THIRD_NEON -> max(90, (targetHeight * 0.15f).roundToInt())
        StickerTemplate.ARROW -> max(110, (targetHeight * 0.12f).roundToInt())
        else -> max(180, (targetHeight * 0.28f).roundToInt())
    }

    // Two alternating buffers ensure Media3 sees a distinct Bitmap reference and refreshes the GL texture each frame.
    private val bufA = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
    private val bufB = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
    private var toggle = false

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        toggle = !toggle
        val bmp = if (toggle) bufA else bufB
        bmp.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bmp)
        val localMs = (presentationTimeUs / 1000L) - relStartMs
        if (localMs in 0L..ovDurMs) {
            StickerCanvasRenderer.draw(
                canvas = canvas,
                w = bmpW.toFloat(),
                h = bmpH.toFloat(),
                timeSec = localMs / 1000f,
                template = clip.stickerTemplate,
                text = clip.text?.text ?: "",
                flipHorizontal = clip.flipHorizontal,
            )
        }
        return bmp
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val localMs = (presentationTimeUs / 1000L) - relStartMs
        if (localMs < 0L || localMs > ovDurMs) {
            return OverlaySettings.Builder().setAlphaScale(0f).build()
        }
        val props = Keyframes.propsAt(clip, localMs)
        val timeLeft = ovDurMs - localMs
        val fadeOut = if (timeLeft in 0L..500L) timeLeft / 500f else 1f
        return OverlaySettings.Builder()
            .setBackgroundFrameAnchor(
                props.transform.offsetX.coerceIn(-1f, 1f),
                -props.transform.offsetY.coerceIn(-1f, 1f),
            )
            .setScale(props.transform.scale, props.transform.scale)
            .setRotationDegrees(-props.transform.rotationDegrees)
            .setAlphaScale((props.opacity * fadeOut).coerceIn(0f, 1f))
            .build()
    }
}

/** Shared native Android Canvas renderer matching `index.html`'s `drawNativeSticker`. */
internal object StickerCanvasRenderer {
    fun draw(
        canvas: Canvas,
        w: Float,
        h: Float,
        timeSec: Float,
        template: StickerTemplate,
        text: String,
        flipHorizontal: Boolean,
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val pulse = (1f - cos(timeSec * PI.toFloat())) / 2f
        when (template) {
            StickerTemplate.NEWS_BREAKING -> {
                val mY = w * 0.06f
                val mH = h - mY
                p.shader = LinearGradient(0f, mY, w, h, Color.parseColor("#D30000"), Color.parseColor("#A00000"), Shader.TileMode.CLAMP)
                canvas.drawRoundRect(RectF(0f, mY, w, h), w * 0.02f, w * 0.02f, p)
                p.shader = null
                p.style = Paint.Style.STROKE; p.color = Color.WHITE; p.strokeWidth = max(2f, w * 0.01f)
                canvas.drawRoundRect(RectF(0f, mY, w, h), w * 0.02f, w * 0.02f, p)
                p.style = Paint.Style.FILL; p.color = Color.parseColor("#0055FF")
                canvas.drawRect(0f, mY, w * 0.03f, h, p)
                // CRiCK NEWS badge
                val bx = w * 0.05f
                val badgeH = w * 0.10f
                p.color = Color.BLACK
                canvas.drawRect(bx, 0f, bx + w * 0.17f, badgeH, p)
                p.color = Color.WHITE
                canvas.drawRect(bx + w * 0.17f, 0f, bx + w * 0.34f, badgeH, p)
                val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = w * 0.055f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
                }
                tp.color = Color.WHITE
                canvas.drawText("CRiCK", bx + w * 0.085f, badgeH * 0.72f, tp)
                tp.color = Color.parseColor("#CC0000")
                canvas.drawText("NEWS", bx + w * 0.255f, badgeH * 0.72f, tp)
                // Scrolling ticker bar at bottom
                val barH = h * 0.09f
                p.color = Color.argb(90, 0, 0, 0)
                canvas.drawRect(0f, h - barH, w, h, p)
                val sx = ((timeSec % 2f) / 2f) * w * 1.5f - w * 0.5f
                p.color = Color.parseColor("#FFCC00")
                canvas.drawRect(sx.coerceAtLeast(0f), h - barH, (sx + w * 0.5f).coerceAtMost(w), h, p)
                // Main text
                drawCenteredText(canvas, text, w * 0.5f, mY + (mH - barH) * 0.55f, w * 0.85f, w * 0.11f * (1f + 0.04f * pulse), Color.WHITE)
            }
            StickerTemplate.NEWS_FLASH, StickerTemplate.NEWS_LATEST -> {
                val isRight = template == StickerTemplate.NEWS_FLASH
                val path = Path()
                if (isRight) {
                    path.moveTo(w * 0.15f, 0f); path.lineTo(w, 0f); path.lineTo(w, h); path.lineTo(0f, h); path.close()
                } else {
                    path.moveTo(0f, 0f); path.lineTo(w * 0.85f, 0f); path.lineTo(w, h); path.lineTo(0f, h); path.close()
                }
                canvas.save()
                canvas.clipPath(path)
                p.shader = if (isRight) {
                    LinearGradient(0f, 0f, w, h, Color.argb(242, 180, 0, 0), Color.argb(242, 80, 0, 0), Shader.TileMode.CLAMP)
                } else {
                    LinearGradient(0f, 0f, w, h, Color.argb(242, 0, 30, 80), Color.argb(242, 0, 10, 40), Shader.TileMode.CLAMP)
                }
                canvas.drawRect(0f, 0f, w, h, p)
                p.shader = null
                p.color = if (isRight) Color.parseColor("#FFCC00") else Color.parseColor("#00E5FF")
                p.strokeWidth = max(4f, w * 0.015f); p.style = Paint.Style.STROKE
                if (isRight) canvas.drawLine(w * 0.15f, 0f, 0f, h, p) else canvas.drawLine(w * 0.85f, 0f, w, h, p)
                p.style = Paint.Style.FILL
                val zoomScale = 1f + 0.12f * ((1f - cos((timeSec % 3f) / 3f * PI.toFloat() * 2f)) / 2f)
                drawCenteredText(canvas, text, w * (if (isRight) 0.56f else 0.44f), h * 0.55f, w * 0.72f, h * 0.12f * zoomScale, Color.WHITE)
                canvas.restore()
            }
            StickerTemplate.YOUTUBE_SUBSCRIBE -> {
                val t = timeSec % 5f
                p.color = Color.argb(245, 255, 255, 255)
                canvas.drawRoundRect(RectF(0f, 0f, w, h), w * 0.05f, w * 0.05f, p)
                val btnCol = if (t in 2.5f..4.5f) Color.parseColor("#282828") else Color.RED
                p.color = btnCol
                val btnRect = RectF(w * 0.18f, h * 0.14f, w * 0.82f, h * 0.50f)
                canvas.drawRoundRect(btnRect, w * 0.03f, w * 0.03f, p)
                val label = if (t >= 2.25f) text else "SUBSCRIBE"
                drawCenteredText(canvas, label, w * 0.5f, btnRect.centerY(), btnRect.width() * 0.85f, h * 0.16f, Color.WHITE)
                p.color = Color.parseColor("#DDDDDD"); p.strokeWidth = 2f
                canvas.drawLine(w * 0.08f, h * 0.62f, w * 0.92f, h * 0.62f, p)
                drawCenteredText(canvas, "👍 Like    💬 Comment    ↗ Share", w * 0.5f, h * 0.80f, w * 0.86f, h * 0.11f, Color.DKGRAY)
            }
            StickerTemplate.LOWER_THIRD_GOLD -> {
                p.shader = LinearGradient(0f, 0f, w, 0f, Color.argb(230, 10, 20, 40), Color.argb(155, 0, 0, 0), Shader.TileMode.CLAMP)
                canvas.drawRoundRect(RectF(0f, 0f, w, h), w * 0.02f, w * 0.02f, p)
                p.shader = null
                p.color = Color.parseColor("#FFD700")
                canvas.drawRect(0f, 0f, w * 0.03f, h, p)
                val tm = timeSec % 4f
                val alpha = when {
                    tm < 0.4f -> 0f
                    tm < 0.8f -> (tm - 0.4f) / 0.4f
                    tm > 3.6f -> 0f
                    tm > 3.2f -> (3.6f - tm) / 0.4f
                    else -> 1f
                }
                drawCenteredText(canvas, text, w * 0.52f, h * 0.5f, w * 0.85f, h * 0.42f, Color.argb((alpha * 255).toInt(), 255, 215, 0))
            }
            StickerTemplate.LOWER_THIRD_NEON -> {
                p.shader = LinearGradient(0f, 0f, w, 0f, Color.argb(242, 17, 17, 17), Color.argb(204, 51, 51, 51), Shader.TileMode.CLAMP)
                canvas.drawRoundRect(RectF(0f, 0f, w, h), h * 0.5f, h * 0.5f, p)
                p.shader = null
                p.style = Paint.Style.STROKE; p.color = Color.parseColor("#00E5FF"); p.strokeWidth = max(3f, w * 0.008f)
                canvas.drawRoundRect(RectF(0f, 0f, w, h), h * 0.5f, h * 0.5f, p)
                p.style = Paint.Style.FILL; p.color = Color.parseColor("#FF0055")
                val pill = RectF(w * 0.02f, h * 0.14f, w * 0.22f, h * 0.86f)
                canvas.drawRoundRect(pill, h * 0.36f, h * 0.36f, p)
                drawCenteredText(canvas, "INFO", pill.centerX(), pill.centerY(), pill.width() * 0.85f, h * 0.36f, Color.WHITE)
                val fadeZoom = 0.95f + 0.10f * ((1f - cos((timeSec % 3f) / 3f * PI.toFloat() * 2f)) / 2f)
                drawCenteredText(canvas, text, w * 0.60f, h * 0.5f, w * 0.72f, h * 0.40f * fadeZoom, Color.parseColor("#00E5FF"))
            }
            StickerTemplate.ARROW -> {
                canvas.save()
                if (flipHorizontal) {
                    canvas.translate(w, 0f); canvas.scale(-1f, 1f)
                }
                val bounce = ((1f - cos(timeSec * PI.toFloat() * 2f)) / 2f) * (w * 0.10f)
                canvas.translate(bounce, 0f)
                val arrow = Path().apply {
                    moveTo(w * 0.05f, h * 0.35f)
                    lineTo(w * 0.58f, h * 0.35f)
                    lineTo(w * 0.58f, h * 0.10f)
                    lineTo(w * 0.90f, h * 0.50f)
                    lineTo(w * 0.58f, h * 0.90f)
                    lineTo(w * 0.58f, h * 0.65f)
                    lineTo(w * 0.05f, h * 0.65f)
                    close()
                }
                p.style = Paint.Style.FILL; p.color = Color.parseColor("#FFCC00")
                canvas.drawPath(arrow, p)
                p.style = Paint.Style.STROKE; p.color = Color.BLACK; p.strokeWidth = max(3f, min(w, h) * 0.04f)
                canvas.drawPath(arrow, p)
                canvas.restore()
            }
            StickerTemplate.NONE -> Unit
        }
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        cx: Float,
        cy: Float,
        maxW: Float,
        baseSize: Float,
        color: Int,
    ) {
        if (text.isBlank()) return
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            typeface = Typeface.DEFAULT_BOLD
            textSize = baseSize
            setShadowLayer(4f, 2f, 2f, Color.argb(190, 0, 0, 0))
        }
        val measured = tp.measureText(text)
        if (measured > maxW && measured > 0f) {
            tp.textSize = (baseSize * (maxW / measured)).coerceAtLeast(12f)
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, maxW.toInt().coerceAtLeast(40))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(3)
            .build()
        canvas.save()
        canvas.translate(cx - layout.width / 2f, cy - layout.height / 2f)
        layout.draw(canvas)
        canvas.restore()
    }
}

/**
 * Custom GL effect applying Main Clip animations (Pan, Zoom, Fit Ken Burns, Combo Curve,
 * Zoom In-Out), manual Pan/Pinch-Zoom, Flip Horizontal, and Blurred Border Fill.
 */
private class MainMotionEffect(
    private val clipDurationUs: Long,
    private val animation: MainAnimation,
    private val manualScale: Float,
    private val manualOffsetX: Float,
    private val manualOffsetY: Float,
    private val flipH: Boolean,
    private val blurBg: Boolean,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        MainMotionProgram(useHdr, clipDurationUs, animation, manualScale, manualOffsetX, manualOffsetY, flipH, blurBg)
}

private class MainMotionProgram(
    useHdr: Boolean,
    private val clipDurationUs: Long,
    private val animation: MainAnimation,
    private val manualScale: Float,
    private val manualOffsetX: Float,
    private val manualOffsetY: Float,
    private val flipH: Boolean,
    private val blurBg: Boolean,
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    private val program = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    private var width = 1
    private var height = 1

    init {
        program.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth
        height = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val progress = if (clipDurationUs > 0L) {
            (presentationTimeUs.toFloat() / clipDurationUs.toFloat()).coerceIn(0f, 1f)
        } else 0f
        val frame = MainAnimations.evaluate(
            anim = animation,
            progress = progress,
            srcW = width.toFloat(),
            srcH = height.toFloat(),
            frameW = width.toFloat(),
            frameH = height.toFloat(),
        )
        val totalScale = (frame.scale * manualScale).coerceIn(0.25f, 4.0f)
        val totalOffX = frame.offsetX + manualOffsetX
        val totalOffY = frame.offsetY + manualOffsetY
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatsUniformIfPresent("uScale", floatArrayOf(totalScale))
            program.setFloatsUniformIfPresent("uOffset", floatArrayOf(totalOffX, totalOffY))
            program.setFloatsUniformIfPresent("uFlipH", floatArrayOf(if (flipH) 1f else 0f))
            program.setFloatsUniformIfPresent("uBlurBg", floatArrayOf(if (blurBg) 1f else 0f))
            program.setFloatsUniformIfPresent("uResolution", floatArrayOf(width.toFloat(), height.toFloat()))
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        const val VERTEX_SHADER = """
            #version 100
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
              gl_Position = aFramePosition;
              vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
            }
        """

        const val FRAGMENT_SHADER = """
            #version 100
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform float uScale;
            uniform vec2 uOffset;
            uniform float uFlipH;
            uniform float uBlurBg;
            uniform vec2 uResolution;
            varying vec2 vTexSamplingCoord;
            void main() {
              vec2 coord = vTexSamplingCoord;
              if (uFlipH > 0.5) coord.x = 1.0 - coord.x;
              vec2 uv = (coord - 0.5 - vec2(uOffset.x, -uOffset.y)) / uScale + 0.5;
              if (uv.x >= 0.0 && uv.x <= 1.0 && uv.y >= 0.0 && uv.y <= 1.0) {
                gl_FragColor = texture2D(uTexSampler, uv);
              } else if (uBlurBg > 0.5) {
                vec2 buv = clamp(uv, 0.02, 0.98);
                vec2 px = 12.0 / max(uResolution, vec2(1.0));
                vec4 b = texture2D(uTexSampler, buv);
                b += texture2D(uTexSampler, clamp(buv + vec2(px.x, 0.0), 0.0, 1.0));
                b += texture2D(uTexSampler, clamp(buv - vec2(px.x, 0.0), 0.0, 1.0));
                b += texture2D(uTexSampler, clamp(buv + vec2(0.0, px.y), 0.0, 1.0));
                b += texture2D(uTexSampler, clamp(buv - vec2(0.0, px.y), 0.0, 1.0));
                b /= 5.0;
                gl_FragColor = vec4(b.rgb * 0.55, 1.0);
              } else {
                gl_FragColor = vec4(0.02, 0.02, 0.02, 1.0);
              }
            }
        """
    }
}

private class RampSpeedProvider(
    private val ramp: SpeedRamp,
    private val base: Float,
    private val durationUs: Long,
    private val stepUs: Long = 100_000L,
) : SpeedProvider {

    override fun getSpeed(timeUs: Long): Float {
        if (durationUs <= 0L) return base.coerceIn(0.1f, 8f)
        val stepStart = (timeUs / stepUs) * stepUs
        val progress = stepStart.toFloat() / durationUs.toFloat()
        return SpeedRamps.multiplierAt(ramp, progress, base)
    }

    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
        if (durationUs <= 0L) return C.TIME_UNSET
        val next = ((timeUs / stepUs) + 1) * stepUs
        return if (next >= durationUs) C.TIME_UNSET else next
    }
}
