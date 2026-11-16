package com.actioncut.core.model

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The kind of content a [Clip] holds. Determines which editing tools apply.
 */
@Serializable
enum class ClipType {
    VIDEO,
    IMAGE,
    AUDIO,
    TEXT,
    STICKER,
}

/** Grouping for Main Clip animations (mirrors HTML editor Groups 1–5). */
@Serializable
enum class AnimationGroup(val displayName: String) {
    NONE("None"),
    PAN("Panning"),
    ZOOM("Zooming"),
    FIT("Fit Ken Burns"),
    COMBO("Combo Curve"),
    SPLIT("Zoom In-Out"),
}

/** Main clip motion animations (Pan, Zoom, Fit Ken Burns, Combo Curve, Zoom In-Out). */
@Serializable
enum class MainAnimation(val displayName: String, val group: AnimationGroup) {
    NONE("None", AnimationGroup.NONE),
    PAN_RIGHT("Pan Right", AnimationGroup.PAN),
    PAN_LEFT("Pan Left", AnimationGroup.PAN),
    PAN_UP("Pan Up", AnimationGroup.PAN),
    PAN_DOWN("Pan Down", AnimationGroup.PAN),
    SMOOTH_IN("Smooth In", AnimationGroup.ZOOM),
    SMOOTH_OUT("Smooth Out", AnimationGroup.ZOOM),
    CINE_IN("Cine In", AnimationGroup.ZOOM),
    CINE_OUT("Cine Out", AnimationGroup.ZOOM),
    ZOOM_CLASSIC("Classic In", AnimationGroup.ZOOM),
    NORMAL_ZOOM("Normal In", AnimationGroup.ZOOM),
    FIT_IN_R("In + Right", AnimationGroup.FIT),
    FIT_IN_L("In + Left", AnimationGroup.FIT),
    FIT_IN_U("In + Up", AnimationGroup.FIT),
    FIT_IN_D("In + Down", AnimationGroup.FIT),
    FIT_OUT_R("Out + Right", AnimationGroup.FIT),
    FIT_OUT_L("Out + Left", AnimationGroup.FIT),
    FIT_OUT_U("Out + Up", AnimationGroup.FIT),
    FIT_OUT_D("Out + Down", AnimationGroup.FIT),
    COMBO_CURVE_TR("Curve TR", AnimationGroup.COMBO),
    COMBO_CURVE_TL("Curve TL", AnimationGroup.COMBO),
    COMBO_CURVE_BR("Curve BR", AnimationGroup.COMBO),
    COMBO_CURVE_BL("Curve BL", AnimationGroup.COMBO),
    COMBO_ARC_CW("Arc CW", AnimationGroup.COMBO),
    COMBO_ARC_CCW("Arc CCW", AnimationGroup.COMBO),
    ZOOM_IN_OUT("Zoom In-Out", AnimationGroup.SPLIT);

    val isFitKenBurns: Boolean get() = group == AnimationGroup.FIT
}

/** Built-in animated sticker / news alert / lower-third / arrow templates. */
@Serializable
enum class StickerTemplate(val templateId: Int, val displayName: String) {
    NONE(0, "Text / Emoji"),
    NEWS_BREAKING(1, "Breaking News"),
    NEWS_FLASH(2, "Flash News"),
    NEWS_LATEST(3, "Latest Update"),
    YOUTUBE_SUBSCRIBE(4, "Subscribe"),
    LOWER_THIRD_GOLD(5, "Lower Third 1 (Gold)"),
    LOWER_THIRD_NEON(6, "Lower Third 2 (Neon)"),
    ARROW(7, "Arrow"),
}

/** Evaluated animation transform (scale + normalized frame-fraction offset). */
data class AnimFrame(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

/** Shared pure-Kotlin motion math for both live preview and Media3 GPU export. */
object MainAnimations {
    val panPool = MainAnimation.entries.filter { it.group == AnimationGroup.PAN }
    val zoomPool = MainAnimation.entries.filter { it.group == AnimationGroup.ZOOM }
    val fitPool = MainAnimation.entries.filter { it.group == AnimationGroup.FIT }
    val comboPool = MainAnimation.entries.filter { it.group == AnimationGroup.COMBO }

    fun evaluate(
        anim: MainAnimation,
        progress: Float,
        srcW: Float = 16f,
        srcH: Float = 9f,
        frameW: Float = 16f,
        frameH: Float = 9f,
    ): AnimFrame {
        val p = progress.coerceIn(0f, 1f)
        if (anim == MainAnimation.NONE) return AnimFrame()
        if (anim.group == AnimationGroup.FIT) {
            return evalFitKenBurns(anim, p, srcW, srcH, frameW, frameH)
        }
        return when (anim) {
            MainAnimation.PAN_RIGHT -> AnimFrame(1.10f, -0.05f + 0.10f * p, 0f)
            MainAnimation.PAN_LEFT -> AnimFrame(1.10f, 0.05f - 0.10f * p, 0f)
            MainAnimation.PAN_UP -> AnimFrame(1.10f, 0f, 0.05f - 0.10f * p)
            MainAnimation.PAN_DOWN -> AnimFrame(1.10f, 0f, -0.05f + 0.10f * p)
            MainAnimation.SMOOTH_IN -> AnimFrame(1.00f + 0.08f * p, 0f, 0f)
            MainAnimation.SMOOTH_OUT -> AnimFrame(1.08f - 0.08f * p, 0f, 0f)
            MainAnimation.CINE_IN -> AnimFrame(1.00f + 0.04f * p, 0f, 0f)
            MainAnimation.CINE_OUT -> AnimFrame(1.04f - 0.04f * p, 0f, 0f)
            MainAnimation.ZOOM_CLASSIC -> AnimFrame(1.00f + 0.10f * p, 0f, 0f)
            MainAnimation.NORMAL_ZOOM -> AnimFrame(1.00f + 0.06f * p, 0f, 0f)
            MainAnimation.ZOOM_IN_OUT -> {
                val s = if (p < 0.5f) 1.0f + 0.10f * (p / 0.5f) else 1.10f - 0.10f * ((p - 0.5f) / 0.5f)
                AnimFrame(s, 0f, 0f)
            }
            MainAnimation.COMBO_CURVE_TR -> AnimFrame(1f + 0.12f * p, -0.06f + 0.12f * p, 0.06f - 0.12f * (p * p))
            MainAnimation.COMBO_CURVE_TL -> AnimFrame(1f + 0.12f * p, 0.06f - 0.12f * p, 0.06f - 0.12f * (p * p))
            MainAnimation.COMBO_CURVE_BR -> AnimFrame(1f + 0.12f * p, -0.06f + 0.12f * p, -0.06f + 0.12f * (p * p))
            MainAnimation.COMBO_CURVE_BL -> AnimFrame(1f + 0.12f * p, 0.06f - 0.12f * p, -0.06f + 0.12f * (p * p))
            MainAnimation.COMBO_ARC_CW, MainAnimation.COMBO_ARC_CCW -> {
                val ang = p * (PI.toFloat() * 0.6f)
                val sign = if (anim == MainAnimation.COMBO_ARC_CW) 1f else -1f
                AnimFrame(
                    scale = 1f + 0.12f * p,
                    offsetX = sign * 0.06f * sin(ang),
                    offsetY = -0.06f * (1f - cos(ang)),
                )
            }
            else -> AnimFrame()
        }
    }

    private fun evalFitKenBurns(
        anim: MainAnimation,
        p: Float,
        srcW: Float,
        srcH: Float,
        frameW: Float,
        frameH: Float,
    ): AnimFrame {
        val isOut = anim == MainAnimation.FIT_OUT_R || anim == MainAnimation.FIT_OUT_L ||
            anim == MainAnimation.FIT_OUT_U || anim == MainAnimation.FIT_OUT_D
        val s = if (isOut) 1.0f - 0.10f * p else 1.0f + 0.10f * p
        val safeW = max(1f, srcW)
        val safeH = max(1f, srcH)
        val containRatio = min(frameW / safeW, frameH / safeH)
        val coverRatio = max(frameW / safeW, frameH / safeH)
        val baseAs = if (containRatio > 0f) coverRatio / containRatio else 1f
        val totalAs = baseAs * s

        val fWfrac = (safeW * coverRatio * s) / frameW
        val fHfrac = (safeH * coverRatio * s) / frameH
        val slackX = max(0f, (fWfrac - 1f) / 2f)
        val slackY = max(0f, (fHfrac - 1f) / 2f)

        val dirX = when (anim) {
            MainAnimation.FIT_IN_R, MainAnimation.FIT_OUT_R -> 1f
            MainAnimation.FIT_IN_L, MainAnimation.FIT_OUT_L -> -1f
            else -> 0f
        }
        val dirY = when (anim) {
            MainAnimation.FIT_IN_D, MainAnimation.FIT_OUT_D -> 1f
            MainAnimation.FIT_IN_U, MainAnimation.FIT_OUT_U -> -1f
            else -> 0f
        }
        return AnimFrame(
            scale = totalAs,
            offsetX = dirX * slackX * p,
            offsetY = dirY * slackY * p,
        )
    }
}

/**
 * A single placed segment on a [Track] — the atomic editable unit of the timeline.
 */
@Serializable
data class Clip(
    val id: String,
    val type: ClipType,
    val mediaUri: String? = null,
    val timelineStartMs: Long,
    val timelineEndMs: Long,
    val sourceInMs: Long = 0L,
    val sourceOutMs: Long = 0L,
    val speed: Float = 1f,
    val isReversed: Boolean = false,
    val volume: Float = 1f,
    val rotationDegrees: Int = 0,
    val opacity: Float = 1f,
    val crop: CropRect? = null,
    val transform: Transform = Transform(),
    val adjustments: ColorAdjustments = ColorAdjustments(),
    val filter: Filter? = null,
    val effects: List<VisualEffect> = emptyList(),
    val text: TextProperties? = null,
    val audioFade: AudioFade = AudioFade(),
    val transitionToNext: Transition? = null,
    val keyframes: List<Keyframe> = emptyList(),
    val speedRamp: SpeedRamp = SpeedRamp.NONE,
    val mainAnimation: MainAnimation = MainAnimation.NONE,
    val stickerTemplate: StickerTemplate = StickerTemplate.NONE,
    val flipHorizontal: Boolean = false,
    val isLocked: Boolean = false,
    val captureMarksMs: List<Long> = emptyList(),
    val cutoutEnabled: Boolean = false,
    val cropPanX: Float = 0.5f,
    val cropPanY: Float = 0.5f,
    val blurBackground: Boolean = true,
) {
    /** Length occupied on the timeline. */
    val timelineDurationMs: Long get() = (timelineEndMs - timelineStartMs).coerceAtLeast(0L)

    /** Length of the trimmed source window. */
    val sourceDurationMs: Long get() = (sourceOutMs - sourceInMs).coerceAtLeast(0L)

    val hasAudio: Boolean get() = type == ClipType.VIDEO || type == ClipType.AUDIO

    val isVisual: Boolean
        get() = type == ClipType.VIDEO || type == ClipType.IMAGE ||
            type == ClipType.TEXT || type == ClipType.STICKER

    fun contains(timeMs: Long): Boolean = timeMs in timelineStartMs until timelineEndMs
}
