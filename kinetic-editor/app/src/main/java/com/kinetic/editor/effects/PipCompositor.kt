package com.kinetic.editor.effects

import androidx.media3.common.util.Size
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.VideoCompositorSettings
import com.kinetic.editor.core.model.PipWindow
import com.kinetic.editor.core.model.pipSpecAt

/**
 * Places each secondary video sequence in the output frame.
 *
 * Media3 composites sequences by input id: 0 is the primary (main track), and
 * every additional VIDEO_OVERLAY sequence follows in the order it was added to
 * the Composition. The default compositor draws every input full-frame, which
 * would simply hide the main track behind the overlay.
 *
 * Placement is resolved PER FRAME rather than per track: `getOverlaySettings`
 * is handed a composition timestamp, and composition time is editor timeline
 * time, so a track holding two PiP clips framed differently renders each in its
 * own position instead of both inheriting the first one's.
 *
 * The output size is pinned to the composition's own canvas rather than derived
 * from the inputs, so adding a PiP can never change the exported frame size.
 */
class PipCompositorSettings(
    private val outputWidth: Int,
    private val outputHeight: Int,
    /** Per secondary input, indexed by (inputId - 1); each list is time-ordered. */
    private val windows: List<List<PipWindow>>,
) : VideoCompositorSettings {

    override fun getOutputSize(inputSizes: List<Size>): Size = Size(outputWidth, outputHeight)

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        // Input 0 is the primary sequence: full frame, untouched.
        val track = windows.getOrNull(inputId - 1)
            ?: return StaticOverlaySettings.Builder().build()
        val spec = pipSpecAt(track, presentationTimeUs)
            ?: return StaticOverlaySettings.Builder().build()
        return StaticOverlaySettings.Builder()
            .setScale(spec.scale, spec.scale)
            .setOverlayFrameAnchor(0f, 0f)               // pivot on the overlay's center
            .setBackgroundFrameAnchor(spec.anchorX, spec.anchorY)
            .setRotationDegrees(spec.rotationDeg)
            .build()
    }
}
