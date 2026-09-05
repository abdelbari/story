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
import com.kinetic.editor.core.model.ChromaKeySpec
import com.kinetic.editor.core.model.ClipEffect
import com.kinetic.editor.core.model.ClipMotion
import com.kinetic.editor.core.model.ColorGradeSpec
import com.kinetic.editor.core.model.MaskSpec
import com.kinetic.editor.core.model.TransformSpec
import com.kinetic.editor.core.model.motionAt
import com.kinetic.editor.core.model.transformAt
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
    var xfScale = 1f
    var xfOffsetX = 0f
    var xfOffsetY = 0f
    var xfRotRad = 0f
    var grain = 0f
    var grainSeed = 0f
    var vignette = 0f
    var keyR = 0f
    var keyG = 0f
    var keyB = 0f

    /** Zero means no key at all, which is what an absent [ChromaKeySpec] sets. */
    var keyTolerance = 0f
    var keySoftness = 0f

    /** 1 leaves an axis alone, -1 mirrors it. */
    var flipX = 1f
    var flipY = 1f

    /** Zero means no mask, which is what an absent [MaskSpec] sets. */
    var maskType = 0f
    var maskCenterX = 0.5f
    var maskCenterY = 0.5f
    var maskSize = 0f
    var maskAspect = 1f
    var maskRound = 0f
    var maskRotRad = 0f
    var maskFeather = 0f
    var maskInvert = 0f

    var fxType = 0f
    var fxAmount = 0f

    /** Clip-local seconds, wrapped to [FX_PERIOD_US]. */
    var time = 0f

    /** 1 composites onto black (the main track), 0 keeps straight alpha (an overlay). */
    var opaque = 0f

    fun reset() {
        brightness = 0f; contrast = 1f; saturation = 1f; temperature = 0f
        lutBitmap = null; lutIntensity = 0f; transType = 0f; transProgress = 0f
        xfScale = 1f; xfOffsetX = 0f; xfOffsetY = 0f; xfRotRad = 0f
        grain = 0f; grainSeed = 0f; vignette = 0f
        keyR = 0f; keyG = 0f; keyB = 0f; keyTolerance = 0f; keySoftness = 0f
        flipX = 1f; flipY = 1f
        maskType = 0f; maskCenterX = 0.5f; maskCenterY = 0.5f; maskSize = 0f
        maskAspect = 1f; maskRound = 0f; maskRotRad = 0f; maskFeather = 0f; maskInvert = 0f
        fxType = 0f; fxAmount = 0f; time = 0f; opaque = 0f
    }

    fun setFlip(x: Boolean, y: Boolean) {
        flipX = if (x) -1f else 1f
        flipY = if (y) -1f else 1f
    }

    fun setMask(spec: MaskSpec?) {
        if (spec == null) {
            maskType = 0f
            return
        }
        maskType = (spec.shape.ordinal + 1).toFloat()
        // NDC anchor -> texture space, the same mapping the overlays use.
        maskCenterX = spec.centerX * 0.5f + 0.5f
        maskCenterY = spec.centerY * 0.5f + 0.5f
        maskSize = spec.size
        maskAspect = spec.aspect
        maskRound = spec.roundness
        maskRotRad = spec.rotationDeg * DEG_TO_RAD
        maskFeather = spec.feather
        maskInvert = if (spec.invert) 1f else 0f
    }

    /**
     * The frame effect, and the clock it animates on. [localUs] is clip-local:
     * an effect starts its animation with the clip in both pipelines. It is
     * wrapped to a period every animation in the shader divides evenly, so the
     * wrap is invisible and no intermediate outgrows a mediump float.
     */
    fun setEffect(effect: ClipEffect, amount: Float, localUs: Long) {
        fxType = effect.ordinal.toFloat()
        fxAmount = amount
        time = Math.floorMod(localUs, FX_PERIOD_US) / 1_000_000f
    }

    fun setChroma(spec: ChromaKeySpec?) {
        if (spec == null) {
            keyTolerance = 0f
            return
        }
        keyR = ((spec.argb shr 16) and 0xFF).toFloat() / 255f
        keyG = ((spec.argb shr 8) and 0xFF).toFloat() / 255f
        keyB = (spec.argb and 0xFF).toFloat() / 255f
        keyTolerance = spec.tolerance
        keySoftness = spec.softness
    }

    fun setTransform(xf: TransformSpec) {
        xfScale = xf.scale
        xfOffsetX = xf.offsetX
        xfOffsetY = xf.offsetY
        xfRotRad = xf.rotationDeg * DEG_TO_RAD
    }

    companion object {
        private const val DEG_TO_RAD = (Math.PI / 180.0).toFloat()

        /** The effects' clock wraps here; see [setEffect]. */
        const val FX_PERIOD_US = 20_000_000L
    }

    fun setGrade(grade: ColorGradeSpec) {
        brightness = grade.brightness
        contrast = grade.contrast
        saturation = grade.saturation
        temperature = grade.temperature
        grain = grade.grain
        vignette = grade.vignette
    }

    /**
     * Re-seeds the grain for one frame. Quantised to about a frame's worth of
     * time so the pattern holds still within a frame and changes between them:
     * grain that is re-randomised per *pixel read* would shimmer, and grain
     * that never changes reads as dirt on the lens rather than film.
     */
    fun seedGrainAt(presentationTimeUs: Long) {
        grainSeed = ((presentationTimeUs / 33_000L) % 977L).toFloat()
    }
}

/** How far through a clip a frame sits, 0..1; 0 for a clip with no span. */
internal fun progressOf(localUs: Long, spanUs: Long): Float =
    if (spanUs <= 0L) 0f else (localUs.toFloat() / spanUs).coerceIn(0f, 1f)

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

    /**
     * Frame shape, so a rotation turns the picture instead of shearing it.
     * configure() always runs before the first drawFrame.
     */
    private var aspect = 1f

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

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        aspect = if (inputHeight > 0) inputWidth.toFloat() / inputHeight else 1f
        return Size(inputWidth, inputHeight)
    }

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
            program.setFloatUniform("uXfScale", uniforms.xfScale)
            program.setFloatsUniform("uXfOffset", floatArrayOf(uniforms.xfOffsetX, uniforms.xfOffsetY))
            program.setFloatUniform("uXfRot", uniforms.xfRotRad)
            program.setFloatUniform("uAspect", aspect)
            program.setFloatUniform("uGrain", uniforms.grain)
            program.setFloatUniform("uGrainSeed", uniforms.grainSeed)
            program.setFloatUniform("uVignette", uniforms.vignette)
            program.setFloatsUniform(
                "uKeyColor",
                floatArrayOf(uniforms.keyR, uniforms.keyG, uniforms.keyB),
            )
            program.setFloatUniform("uKeyTolerance", uniforms.keyTolerance)
            program.setFloatUniform("uKeySoftness", uniforms.keySoftness)
            program.setFloatsUniform("uFlip", floatArrayOf(uniforms.flipX, uniforms.flipY))
            program.setFloatUniform("uMaskType", uniforms.maskType)
            program.setFloatsUniform(
                "uMaskCenter",
                floatArrayOf(uniforms.maskCenterX, uniforms.maskCenterY),
            )
            program.setFloatUniform("uMaskSize", uniforms.maskSize)
            program.setFloatUniform("uMaskAspect", uniforms.maskAspect)
            program.setFloatUniform("uMaskRound", uniforms.maskRound)
            program.setFloatUniform("uMaskRot", uniforms.maskRotRad)
            program.setFloatUniform("uMaskFeather", uniforms.maskFeather)
            program.setFloatUniform("uMaskInvert", uniforms.maskInvert)
            program.setFloatUniform("uFxType", uniforms.fxType)
            program.setFloatUniform("uFxAmount", uniforms.fxAmount)
            program.setFloatUniform("uTime", uniforms.time)
            program.setFloatUniform("uOpaque", uniforms.opaque)
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
 * Export-side provider: clip-local SOURCE time, BEFORE SpeedChangeEffect in the
 * chain, the domain CompositionMapper pre-computes the transition windows in.
 *
 * Transformer does not hand per-item effects clip-local timestamps: the frame
 * processor adds each item's sequence offset (the summed durations of the items
 * before it) at the input stage, ahead of the item's own effects. media3's own
 * SpeedChangeEffect copes by measuring from the first frame it sees, and so
 * does this: one provider serves exactly one EditedMediaItem, whose first frame
 * is the clip's first frame.
 */
class ClipGradeProvider(
    private val grade: ColorGradeSpec,
    private val chroma: ChromaKeySpec?,
    private val transform: TransformSpec,
    private val transformEnd: TransformSpec?,
    private val motion: ClipMotion,
    /** The clip's own span, so a motion knows how far through it the frame is. */
    private val spanUs: Long,
    private val flipX: Boolean,
    private val flipY: Boolean,
    private val mask: MaskSpec?,
    private val effect: ClipEffect,
    private val effectAmount: Float,
    /** True on the main track, whose frames composite onto black; false for an overlay. */
    private val opaque: Boolean,
    private val lutBitmap: Bitmap?,
    private val lutIntensity: Float,
    private val transOutType: TransitionType,
    private val transOutStartUs: Long,
    private val transOutEndUs: Long,
    private val transInType: TransitionType,
    private val transInEndUs: Long,
) : GradeUniformsProvider {

    private var baseUs = UNSET

    override fun fill(presentationTimeUs: Long, out: GradeUniformsBuffer) {
        if (baseUs == UNSET) baseUs = presentationTimeUs
        val localUs = presentationTimeUs - baseUs

        out.reset()
        out.setGrade(grade)
        out.setChroma(chroma)
        out.seedGrainAt(presentationTimeUs)
        out.setTransform(
            transformAt(transform, transformEnd, motion, progressOf(localUs, spanUs)),
        )
        out.setFlip(flipX, flipY)
        out.setMask(mask)
        out.setEffect(effect, effectAmount, localUs)
        out.opaque = if (opaque) 1f else 0f
        out.lutBitmap = lutBitmap
        out.lutIntensity = lutIntensity

        // Incoming half first (segment start), then outgoing half (segment end).
        if (transInType != TransitionType.NONE && localUs < transInEndUs && transInEndUs > 0) {
            val f = localUs.toFloat() / transInEndUs
            out.transType = transInType.ordinal.toFloat()
            out.transProgress = 0.5f + 0.5f * f.coerceIn(0f, 1f)
        } else if (
            transOutType != TransitionType.NONE &&
            localUs >= transOutStartUs && transOutEndUs > transOutStartUs
        ) {
            val f = (localUs - transOutStartUs).toFloat() / (transOutEndUs - transOutStartUs)
            out.transType = transOutType.ordinal.toFloat()
            out.transProgress = 0.5f * f.coerceIn(0f, 1f)
        }
    }

    private companion object {
        const val UNSET = Long.MIN_VALUE
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
        out.grain = seg.grain
        out.vignette = seg.vignette
        out.setChroma(seg.chroma)
        out.seedGrainAt(presentationTimeUs)
        out.setTransform(
            transformAt(
                seg.transform,
                seg.transformEnd,
                seg.motion,
                progressOf(presentationTimeUs - seg.startUs, seg.endUs - seg.startUs),
            ),
        )
        out.setFlip(seg.flipX, seg.flipY)
        out.setMask(seg.mask)
        out.setEffect(seg.effect, seg.effectAmount, presentationTimeUs - seg.startUs)
        // This provider serves the main track only; its surface ignores alpha.
        out.opaque = 1f
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

/** Immutable uniform snapshot for one clip. */
class ClipFx(
    val grade: ColorGradeSpec,
    val chroma: ChromaKeySpec?,
    val transform: TransformSpec,
    val flipX: Boolean,
    val flipY: Boolean,
    val mask: MaskSpec?,
    val effect: ClipEffect,
    val effectAmount: Float,
    val lutBitmap: Bitmap?,
    val lutIntensity: Float,
)

/**
 * Preview-side provider for a picture-in-picture player.
 *
 * A PiP carries no transitions — a transition is a cut between neighbours on
 * one track, and a PiP has no cut to sit on — so its uniforms are constant
 * across a clip. There is nothing to look up by timestamp, only one snapshot to
 * swap when the playing clip changes. A single volatile reference, so the GL
 * thread can never read a new grade paired with the previous clip's LUT.
 */
class ClipSnapshotFxProvider : GradeUniformsProvider {

    @Volatile
    var snapshot: ClipFx? = null

    override fun fill(presentationTimeUs: Long, out: GradeUniformsBuffer) {
        out.reset()
        val s = snapshot ?: return
        out.setGrade(s.grade)
        out.setChroma(s.chroma)
        out.seedGrainAt(presentationTimeUs)
        out.setTransform(s.transform)
        out.setFlip(s.flipX, s.flipY)
        out.setMask(s.mask)
        // The player's own clock rather than a clip-local one: the effects it
        // drives are noise and drift, whose phase nobody can see. An overlay,
        // so it keeps straight alpha for the surface to blend.
        out.setEffect(s.effect, s.effectAmount, presentationTimeUs)
        out.opaque = 0f
        out.lutBitmap = s.lutBitmap
        out.lutIntensity = s.lutIntensity
    }
}

/** Immutable per-clip FX segment in preview-µs. Plain fields, binary-searchable. */
class FxSegment(
    val startUs: Long,
    val endUs: Long,
    val transform: TransformSpec,
    val transformEnd: TransformSpec?,
    val motion: ClipMotion,
    val chroma: ChromaKeySpec?,
    val flipX: Boolean,
    val flipY: Boolean,
    val mask: MaskSpec?,
    val effect: ClipEffect,
    val effectAmount: Float,
    val grain: Float,
    val vignette: Float,
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
