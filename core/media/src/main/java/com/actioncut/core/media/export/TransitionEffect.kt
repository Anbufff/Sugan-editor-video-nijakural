package com.actioncut.core.media.export

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.actioncut.core.model.TransitionType

/** Which edge of a clip a transition animates. */
internal enum class TransitionEdge { IN, OUT }

/** Supported per-clip boundary transition shaders. */
internal enum class TransitionKind {
    NONE,
    FADE,
    ZOOM,
    BLUR,
    SLIDE_LEFT,
    SLIDE_RIGHT,
    SLIDE_UP,
    SLIDE_DOWN,
    MOVE_LEFT,
    MOVE_RIGHT,
    MOVE_UP,
    MOVE_DOWN,
    WIPE_LEFT,
    WIPE_RIGHT,
    WIPE_UP,
    WIPE_DOWN,
}

/**
 * Per-clip boundary transition GL effect covering Fade/Crossfade, Slide R/L/U/D,
 * Move R/L/U/D, Wipe R/L/U/D, Zoom, Blur, and Seamless (passthrough).
 */
internal class TransitionEffect(
    private val edge: TransitionEdge,
    private val kind: TransitionKind,
    private val edgeDurationUs: Long,
    private val clipDurationUs: Long,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        TransitionProgram(useHdr, edge, kind, edgeDurationUs, clipDurationUs)

    companion object {
        fun kindFor(type: TransitionType): TransitionKind = when (type) {
            TransitionType.NONE, TransitionType.SEAMLESS -> TransitionKind.NONE
            TransitionType.FADE, TransitionType.DISSOLVE, TransitionType.CROSSFADE -> TransitionKind.FADE
            TransitionType.ZOOM_IN, TransitionType.ZOOM_OUT -> TransitionKind.ZOOM
            TransitionType.BLUR -> TransitionKind.BLUR
            TransitionType.SLIDE_LEFT -> TransitionKind.SLIDE_LEFT
            TransitionType.SLIDE_RIGHT -> TransitionKind.SLIDE_RIGHT
            TransitionType.SLIDE_UP -> TransitionKind.SLIDE_UP
            TransitionType.SLIDE_DOWN -> TransitionKind.SLIDE_DOWN
            TransitionType.MOVE_LEFT -> TransitionKind.MOVE_LEFT
            TransitionType.MOVE_RIGHT -> TransitionKind.MOVE_RIGHT
            TransitionType.MOVE_UP -> TransitionKind.MOVE_UP
            TransitionType.MOVE_DOWN -> TransitionKind.MOVE_DOWN
            TransitionType.WIPE, TransitionType.WIPE_RIGHT -> TransitionKind.WIPE_RIGHT
            TransitionType.WIPE_LEFT -> TransitionKind.WIPE_LEFT
            TransitionType.WIPE_UP -> TransitionKind.WIPE_UP
            TransitionType.WIPE_DOWN -> TransitionKind.WIPE_DOWN
        }
    }
}

private class TransitionProgram(
    useHdr: Boolean,
    private val edge: TransitionEdge,
    private val kind: TransitionKind,
    private val edgeDurationUs: Long,
    private val clipDurationUs: Long,
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
        val t = presentationTimeUs.coerceIn(0L, clipDurationUs.coerceAtLeast(1L))
        val f = when (edge) {
            TransitionEdge.IN ->
                if (edgeDurationUs > 0L) (t.toFloat() / edgeDurationUs).coerceIn(0f, 1f) else 1f
            TransitionEdge.OUT ->
                if (edgeDurationUs > 0L) ((clipDurationUs - t).toFloat() / edgeDurationUs).coerceIn(0f, 1f) else 1f
        }
        val rem = 1f - f
        var alpha = 1f
        var scale = 1f
        var blur = 0f
        var offX = 0f
        var offY = 0f
        var wipeDir = 0f

        when (kind) {
            TransitionKind.NONE -> Unit
            TransitionKind.FADE -> alpha = f
            TransitionKind.ZOOM -> scale = 1f + rem * 0.25f
            TransitionKind.BLUR -> blur = rem * 8f
            TransitionKind.SLIDE_RIGHT -> offX = if (edge == TransitionEdge.IN) rem else -rem
            TransitionKind.SLIDE_LEFT -> offX = if (edge == TransitionEdge.IN) -rem else rem
            TransitionKind.SLIDE_UP -> offY = if (edge == TransitionEdge.IN) rem else -rem
            TransitionKind.SLIDE_DOWN -> offY = if (edge == TransitionEdge.IN) -rem else rem
            TransitionKind.MOVE_RIGHT -> {
                alpha = 0.7f + 0.3f * f
                offX = (if (edge == TransitionEdge.IN) rem else -rem) * 0.30f
            }
            TransitionKind.MOVE_LEFT -> {
                alpha = 0.7f + 0.3f * f
                offX = (if (edge == TransitionEdge.IN) -rem else rem) * 0.30f
            }
            TransitionKind.MOVE_UP -> {
                alpha = 0.7f + 0.3f * f
                offY = (if (edge == TransitionEdge.IN) rem else -rem) * 0.30f
            }
            TransitionKind.MOVE_DOWN -> {
                alpha = 0.7f + 0.3f * f
                offY = (if (edge == TransitionEdge.IN) -rem else rem) * 0.30f
            }
            TransitionKind.WIPE_RIGHT -> wipeDir = 1f
            TransitionKind.WIPE_LEFT -> wipeDir = 2f
            TransitionKind.WIPE_UP -> wipeDir = 3f
            TransitionKind.WIPE_DOWN -> wipeDir = 4f
        }

        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            program.setFloatsUniformIfPresent("uAlpha", floatArrayOf(alpha))
            program.setFloatsUniformIfPresent("uScale", floatArrayOf(scale))
            program.setFloatsUniformIfPresent("uBlur", floatArrayOf(blur))
            program.setFloatsUniformIfPresent("uOffset", floatArrayOf(offX, offY))
            program.setFloatsUniformIfPresent("uWipeDir", floatArrayOf(wipeDir))
            program.setFloatsUniformIfPresent("uProgress", floatArrayOf(f))
            program.setFloatsUniformIfPresent("uResolution", floatArrayOf(width.toFloat(), height.toFloat()))
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
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
            uniform float uAlpha;
            uniform float uScale;
            uniform float uBlur;
            uniform vec2 uOffset;
            uniform float uWipeDir;
            uniform float uProgress;
            uniform vec2 uResolution;
            varying vec2 vTexSamplingCoord;
            void main() {
              if (uWipeDir > 0.5 && uWipeDir < 1.5 && vTexSamplingCoord.x > uProgress) {
                gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return;
              }
              if (uWipeDir > 1.5 && uWipeDir < 2.5 && vTexSamplingCoord.x < (1.0 - uProgress)) {
                gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return;
              }
              if (uWipeDir > 2.5 && uWipeDir < 3.5 && vTexSamplingCoord.y > uProgress) {
                gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return;
              }
              if (uWipeDir > 3.5 && vTexSamplingCoord.y < (1.0 - uProgress)) {
                gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return;
              }
              vec2 uv = (vTexSamplingCoord - 0.5) / uScale + 0.5 + uOffset;
              if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
                gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return;
              }
              vec4 c;
              if (uBlur > 0.0) {
                vec2 px = uBlur / uResolution;
                c  = texture2D(uTexSampler, uv);
                c += texture2D(uTexSampler, uv + vec2(px.x, 0.0));
                c += texture2D(uTexSampler, uv - vec2(px.x, 0.0));
                c += texture2D(uTexSampler, uv + vec2(0.0, px.y));
                c += texture2D(uTexSampler, uv - vec2(0.0, px.y));
                c /= 5.0;
              } else {
                c = texture2D(uTexSampler, uv);
              }
              gl_FragColor = vec4(c.rgb * uAlpha, c.a);
            }
        """
    }
}
