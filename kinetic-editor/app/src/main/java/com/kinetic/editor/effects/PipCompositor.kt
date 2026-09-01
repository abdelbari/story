package com.kinetic.editor.effects

import androidx.media3.common.util.Size
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.VideoCompositorSettings
import com.kinetic.editor.core.model.PipSpec

/**
 * Places each secondary video sequence in the output frame.
 *
 * Media3 composites sequences by input id: 0 is the primary (main track), and
 * every additional VIDEO_OVERLAY sequence follows in the order it was added to
 * the Composition. The default compositor draws every input full-frame, which
 * would simply hide the main track behind the overlay — this maps input ids to
 * the overlay clips' [PipSpec] so a picture-in-picture lands where the editor
 * shows it.
 *
 * The output size is pinned to the composition's own canvas rather than derived
 * from the inputs, so adding a PiP can never change the exported frame size.
 */
class PipCompositorSettings(
    private val outputWidth: Int,
    private val outputHeight: Int,
    /** Placement per secondary input, indexed by (inputId - 1). */
    private val overlayPlacements: List<PipSpec>,
) : VideoCompositorSettings {

    override fun getOutputSize(inputSizes: List<Size>): Size = Size(outputWidth, outputHeight)

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        // Input 0 is the primary sequence: full frame, untouched.
        val spec = overlayPlacements.getOrNull(inputId - 1)
            ?: return StaticOverlaySettings.Builder().build()
        return StaticOverlaySettings.Builder()
            .setScale(spec.scale, spec.scale)
            .setOverlayFrameAnchor(0f, 0f)               // pivot on the overlay's center
            .setBackgroundFrameAnchor(spec.anchorX, spec.anchorY)
            .setRotationDegrees(spec.rotationDeg)
            .build()
    }
}
