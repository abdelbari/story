package com.kinetic.editor.effects

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.C
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.kinetic.editor.core.model.CanvasBackground
import com.kinetic.editor.core.model.CanvasFit
import com.kinetic.editor.core.model.canvasScales

/**
 * The canvas-fill effect for a project, or null when there is nothing to fill:
 * a black background is what `Presentation` already draws, and the other fits
 * leave no bars. One factory for both pipelines, so they cannot disagree
 * about when the pass exists.
 */
fun canvasFillEffect(
    width: Int,
    height: Int,
    fit: CanvasFit,
    background: CanvasBackground,
): CanvasFillEffect? =
    if (fit == CanvasFit.FIT && background != CanvasBackground.BLACK) {
        CanvasFillEffect(width, height, background)
    } else {
        null
    }

/**
 * Letterboxes a clip into the canvas over a background that is not black: the
 * clip itself, blown up to cover the canvas and blurred, or a flat colour.
 *
 * It sits where `Presentation` sits in the chain and outputs the canvas size,
 * so the `Presentation` that follows it is a no-op media3 drops at configure
 * time. The picture lands exactly where `Presentation` FIT would have put it
 * ([canvasScales] restates that math), which is what keeps the overlays the
 * preview lays out in canvas coordinates on top of the right pixels.
 */
class CanvasFillEffect(
    private val canvasWidth: Int,
    private val canvasHeight: Int,
    private val background: CanvasBackground,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        CanvasFillShaderProgram(useHdr, canvasWidth, canvasHeight, background)
}

/**
 * The passes, for a blurred background:
 *
 *  1. downsample: the picture at cover geometry into a small texture
 *     (1/[DOWNSAMPLE] of the canvas on each side). Most of the blur is this
 *     step: a Gaussian of a few texels there is tens of pixels at full size.
 *  2. blur: a separable Gaussian, ping-ponging between two small textures,
 *     [BLUR_ROUNDS] times.
 *  3. composite: the picture at fit geometry over the blurred small texture,
 *     bilinearly upsampled by the sampler, into the output.
 *
 * A flat colour skips to step 3. The small textures are the program's own;
 * `BaseGlShaderProgram` focuses the output before `drawFrame`, so the output
 * framebuffer is read back at the start and re-focused before the last pass.
 */
class CanvasFillShaderProgram(
    private val useHdr: Boolean,
    private val canvasWidth: Int,
    private val canvasHeight: Int,
    private val background: CanvasBackground,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

    private val blurring = background == CanvasBackground.BLUR
    private val composite = CompositePass()
    private val downsample = if (blurring) DownsamplePass() else null
    private val blur = if (blurring) BlurPass() else null

    private val smallWidth = (canvasWidth / DOWNSAMPLE).coerceAtLeast(1)
    private val smallHeight = (canvasHeight / DOWNSAMPLE).coerceAtLeast(1)
    private var ping: GlTextureInfo = GlTextureInfo.UNSET
    private var pong: GlTextureInfo = GlTextureInfo.UNSET

    private var fitX = 1f
    private var fitY = 1f
    private var fillX = 1f
    private var fillY = 1f

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val scales = canvasScales(inputWidth, inputHeight, canvasWidth, canvasHeight)
        fitX = scales.fitX
        fitY = scales.fitY
        fillX = scales.fillX
        fillY = scales.fillY
        if (blurring && ping === GlTextureInfo.UNSET) {
            try {
                ping = smallTexture()
                pong = smallTexture()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
        return Size(canvasWidth, canvasHeight)
    }

    private fun smallTexture(): GlTextureInfo {
        val texId = GlUtil.createTexture(smallWidth, smallHeight, useHdr)
        val fboId = GlUtil.createFboForTexture(texId)
        return GlTextureInfo(texId, fboId, /* rboId= */ C.INDEX_UNSET, smallWidth, smallHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            var backTexId = inputTexId
            if (downsample != null && blur != null) {
                val output = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, output, /* offset= */ 0)
                focus(ping)
                downsample.draw(inputTexId, fillX, fillY, 1f / smallWidth, 1f / smallHeight)
                repeat(BLUR_ROUNDS) {
                    focus(pong)
                    blur.draw(ping.texId, 1f / smallWidth, 0f)
                    focus(ping)
                    blur.draw(pong.texId, 0f, 1f / smallHeight)
                }
                backTexId = ping.texId
                GlUtil.focusFramebufferUsingCurrentContext(output[0], canvasWidth, canvasHeight)
            }
            composite.draw(inputTexId, backTexId, fitX, fitY, blurring, background)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    private fun focus(target: GlTextureInfo) =
        GlUtil.focusFramebufferUsingCurrentContext(target.fboId, target.width, target.height)

    override fun release() {
        super.release()
        try {
            ping.release()
            pong.release()
            composite.release()
            downsample?.release()
            blur?.release()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        /** Canvas pixels per small-texture texel, on each side. */
        const val DOWNSAMPLE = 12

        /** Horizontal-plus-vertical Gaussian passes over the small texture. */
        const val BLUR_ROUNDS = 2
    }
}

/** One GL program and the quad it draws; each pass owns exactly one. */
private abstract class Pass(fragmentShader: String) {
    protected val program: GlProgram

    init {
        try {
            program = GlProgram(EditorShaders.VERTEX, fragmentShader)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        program.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
    }

    protected fun drawQuad() {
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
        GlUtil.checkGlError()
    }

    fun release() = program.delete()
}

private class DownsamplePass : Pass(EditorShaders.CANVAS_DOWNSAMPLE) {
    private val scale = FloatArray(2)
    private val texel = FloatArray(2)

    fun draw(texId: Int, fillX: Float, fillY: Float, texelX: Float, texelY: Float) {
        scale[0] = fillX; scale[1] = fillY
        texel[0] = texelX; texel[1] = texelY
        program.use()
        program.setSamplerTexIdUniform("uTexSampler", texId, /* texUnitIndex= */ 0)
        program.setFloatsUniform("uFillScale", scale)
        program.setFloatsUniform("uTexel", texel)
        drawQuad()
    }
}

private class BlurPass : Pass(EditorShaders.CANVAS_BLUR) {
    private val step = FloatArray(2)

    fun draw(texId: Int, stepX: Float, stepY: Float) {
        step[0] = stepX; step[1] = stepY
        program.use()
        program.setSamplerTexIdUniform("uTexSampler", texId, /* texUnitIndex= */ 0)
        program.setFloatsUniform("uStep", step)
        drawQuad()
    }
}

private class CompositePass : Pass(EditorShaders.CANVAS_COMPOSITE) {
    private val scale = FloatArray(2)
    private val colour = FloatArray(3)

    fun draw(
        texId: Int,
        backTexId: Int,
        fitX: Float,
        fitY: Float,
        useBlur: Boolean,
        background: CanvasBackground,
    ) {
        scale[0] = fitX; scale[1] = fitY
        colour[0] = background.red; colour[1] = background.green; colour[2] = background.blue
        program.use()
        program.setSamplerTexIdUniform("uTexSampler", texId, /* texUnitIndex= */ 0)
        // Always a valid texture on the unit, blur or not; some drivers reject
        // an unbound sampler even behind a disabled branch.
        program.setSamplerTexIdUniform("uBackSampler", backTexId, /* texUnitIndex= */ 1)
        program.setFloatsUniform("uFitScale", scale)
        program.setFloatUniform("uUseBlur", if (useBlur) 1f else 0f)
        program.setFloatsUniform("uBackColor", colour)
        drawQuad()
    }
}
