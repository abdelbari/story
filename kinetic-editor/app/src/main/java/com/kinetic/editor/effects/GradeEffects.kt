package com.kinetic.editor.effects

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.kinetic.editor.core.model.ColorGradeSpec
import com.kinetic.editor.core.model.TransitionType

/**
 * Mutable uniform buffer, filled in-place every frame. This is the GC contract
 * for the render path: the GL thread never allocates per frame — providers write
 * into this reused instance.
 */
class GradeUniformsBuffer {
    var brightness = 0f
    var contrast = 1f
    var saturation = 1f
    var temperature = 0f
    var lutBitmap: Bitmap? = null
    var lutIntensity = 0f
    var transType = 0f
    var transProgress = 0f

    fun reset() {
        brightness = 0f; contrast = 1f; saturation = 1f; temperature = 0f
        lutBitmap = null; lutIntensity = 0f; transType = 0f; transProgress = 0f
    }

    fun setGrade(grade: ColorGradeSpec) {
        brightness = grade.brightness
        contrast = grade.contrast
        saturation = grade.saturation
        temperature = grade.temperature
    }
}

/** Fills [out] for the frame at [presentationTimeUs]. Called on the GL thread. */
fun interface GradeUniformsProvider {
    fun fill(presentationTimeUs: Long, out: GradeUniformsBuffer)
}

/**
 * GlEffect wrapper usable in two places with two providers:
 *  - preview: ExoPlayer.setVideoEffects(listOf(GradeGlEffect(previewProvider)))
 *  - export:  per-EditedMediaItem effects with a clip-local provider
 */
class GradeGlEffect(private val provider: GradeUniformsProvider) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        GradeShaderProgram(useHdr, provider)
}

class GradeShaderProgram(
    useHdr: Boolean,
    private val provider: GradeUniformsProvider,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

    private val program: GlProgram
    private val uniforms = GradeUniformsBuffer()

    private var lutTexId = -1
    private var loadedLut: Bitmap? = null

    init {
        try {
            program = GlProgram(EditorShaders.VERTEX, EditorShaders.FRAGMENT)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        program.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            provider.fill(presentationTimeUs, uniforms)
            syncLutTexture(uniforms.lutBitmap)

            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            // Always bind a valid sampler; some drivers reject unbound units even
            // behind a disabled branch.
            program.setSamplerTexIdUniform(
                "uLutSampler",
                if (lutTexId > 0) lutTexId else inputTexId,
                /* texUnitIndex= */ 1,
            )
            program.setFloatUniform("uLutEnabled", if (lutTexId > 0 && uniforms.lutIntensity > 0f) 1f else 0f)
            program.setFloatUniform("uLutIntensity", uniforms.lutIntensity)
            program.setFloatUniform("uBrightness", uniforms.brightness)
            program.setFloatUniform("uContrast", uniforms.contrast)
            program.setFloatUniform("uSaturation", uniforms.saturation)
            program.setFloatUniform("uTemperature", uniforms.temperature)
            program.setFloatUniform("uTransType", uniforms.transType)
            program.setFloatUniform("uTransProgress", uniforms.transProgress)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    /** Uploads the LUT once per bitmap INSTANCE change; steady-state cost is zero. */
    private fun syncLutTexture(bitmap: Bitmap?) {
        if (bitmap === loadedLut) return
        if (lutTexId > 0) {
            GLES20.glDeleteTextures(1, intArrayOf(lutTexId), 0)
            lutTexId = -1
        }
        loadedLut = bitmap
        if (bitmap == null) return
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        lutTexId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    override fun release() {
        super.release()
        try {
            if (lutTexId > 0) GLES20.glDeleteTextures(1, intArrayOf(lutTexId), 0)
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }
}

/* ------------------------- provider implementations ------------------------ */

/**
 * Export-side provider: clip-local time domain (timestamps restart near zero for
 * each EditedMediaItem, BEFORE SpeedChangeEffect in the chain). Transition
 * windows are pre-computed by CompositionMapper in that same domain.
 */
class ClipGradeProvider(
    private val grade: ColorGradeSpec,
    private val lutBitmap: Bitmap?,
    private val lutIntensity: Float,
    private val transOutType: TransitionType,
    private val transOutStartUs: Long,
    private val transOutEndUs: Long,
    private val transInType: TransitionType,
    private val transInEndUs: Long,
) : GradeUniformsProvider {

    override fun fill(presentationTimeUs: Long, out: GradeUniformsBuffer) {
        out.reset()
        out.setGrade(grade)
        out.lutBitmap = lutBitmap
        out.lutIntensity = lutIntensity

        // Incoming half first (segment start), then outgoing half (segment end).
        if (transInType != TransitionType.NONE && presentationTimeUs < transInEndUs && transInEndUs > 0) {
            val f = presentationTimeUs.toFloat() / transInEndUs
            out.transType = transInType.ordinal.toFloat()
            out.transProgress = 0.5f + 0.5f * f.coerceIn(0f, 1f)
        } else if (
            transOutType != TransitionType.NONE &&
            presentationTimeUs >= transOutStartUs && transOutEndUs > transOutStartUs
        ) {
            val f = (presentationTimeUs - transOutStartUs).toFloat() / (transOutEndUs - transOutStartUs)
            out.transType = transOutType.ordinal.toFloat()
            out.transProgress = 0.5f * f.coerceIn(0f, 1f)
        }
    }
}

/**
 * Preview-side provider: PREVIEW time domain (the concatenated single-window
 * position). PreviewEngine swaps in a fresh immutable snapshot on every cosmetic
 * commit; the GL thread reads the volatile reference and binary-searches — no
 * locks, no allocation, effect sliders update mid-playback at frame rate.
 */
class PreviewFxProvider : GradeUniformsProvider {

    @Volatile
    var timeline: PreviewFxTimeline = PreviewFxTimeline.EMPTY

    override fun fill(presentationTimeUs: Long, out: GradeUniformsBuffer) {
        out.reset()
        val seg = timeline.segmentAt(presentationTimeUs) ?: return
        out.brightness = seg.brightness
        out.contrast = seg.contrast
        out.saturation = seg.saturation
        out.temperature = seg.temperature
        out.lutBitmap = seg.lutBitmap
        out.lutIntensity = seg.lutIntensity

        if (seg.transInType != 0f && presentationTimeUs < seg.transInEndUs) {
            val span = (seg.transInEndUs - seg.startUs).coerceAtLeast(1L)
            out.transType = seg.transInType
            out.transProgress =
                0.5f + 0.5f * ((presentationTimeUs - seg.startUs).toFloat() / span).coerceIn(0f, 1f)
        } else if (seg.transOutType != 0f && presentationTimeUs >= seg.transOutStartUs) {
            val span = (seg.endUs - seg.transOutStartUs).coerceAtLeast(1L)
            out.transType = seg.transOutType
            out.transProgress =
                0.5f * ((presentationTimeUs - seg.transOutStartUs).toFloat() / span).coerceIn(0f, 1f)
        }
    }
}

/** Immutable per-clip FX segment in preview-µs. Plain fields, binary-searchable. */
class FxSegment(
    val startUs: Long,
    val endUs: Long,
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    val temperature: Float,
    val lutBitmap: Bitmap?,
    val lutIntensity: Float,
    val transOutType: Float,
    val transOutStartUs: Long,
    val transInType: Float,
    val transInEndUs: Long,
)

class PreviewFxTimeline(private val segments: List<FxSegment>) {

    fun segmentAt(timeUs: Long): FxSegment? {
        var lo = 0
        var hi = segments.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = segments[mid]
            when {
                timeUs < s.startUs -> hi = mid - 1
                timeUs >= s.endUs -> lo = mid + 1
                else -> return s
            }
        }
        return null
    }

    companion object {
        val EMPTY = PreviewFxTimeline(emptyList())
    }
}
