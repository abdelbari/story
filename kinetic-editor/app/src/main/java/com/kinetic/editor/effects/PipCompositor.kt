package com.kinetic.editor.effects

import android.util.Pair
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.effect.StaticOverlaySettings
import com.kinetic.editor.core.model.PipSpec
import com.kinetic.editor.core.model.PipWindow
import com.kinetic.editor.core.model.pipWindowAt

/**
 * Places each secondary video sequence in the output frame.
 *
 * Media3 composites sequences by input id: 0 is the primary (main track), and
 * every additional VIDEO_OVERLAY sequence follows in the order it was added to
 * the Composition. The default compositor draws every input full-frame, which
 * would simply hide the main track behind the overlay.
 *
 * Geometry. The output frame IS the primary frame (same size); the composition
 * level Presentation then letterboxes it onto the canvas, the same fit a
 * single-sequence export gets, so adding a PiP never crops the main picture.
 * The compositor draws an overlay at its native pixel size times `scale`; the
 * scale returned here is derived from the two frame sizes so that
 * `PipSpec.scale` means "fraction of the main picture's width" with the
 * overlay's own proportions kept — exactly how the preview lays out its box.
 *
 * Time. Placement is resolved PER FRAME: `getOverlaySettings` is handed the
 * overlay sequence's own timestamp, and sequence time is editor timeline time
 * (leading gaps are blank frames, see CompositionMapper), so two PiP clips on
 * one track each render in their own corner. Outside a clip — gap frames, and
 * the final frame the compositor keeps re-using once a sequence has ended —
 * the overlay is drawn at alpha 0.
 */
class PipCompositorSettings(
    /** Per secondary input, indexed by (inputId - 1); each list is time-ordered. */
    private val windows: List<List<PipWindow>>,
) : VideoCompositorSettings {

    /**
     * Frame sizes of the composite in progress: primary first, then secondaries
     * in input-id order. DefaultVideoCompositor asks for the output size right
     * before it draws, on its GL thread, and the overlay getters below run
     * during that draw — so they see the sizes of the very frames being drawn.
     */
    @Volatile
    private var inputSizes: List<Size> = emptyList()

    override fun getOutputSize(inputSizes: List<Size>): Size {
        this.inputSizes = inputSizes
        return inputSizes[0]
    }

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        // Input 0 is the primary sequence: full frame, untouched.
        val track = windows.getOrNull(inputId - 1) ?: return FULL_FRAME
        val window = pipWindowAt(track, presentationTimeUs) ?: return HIDDEN
        return Placement(inputId, window.pip)
    }

    private inner class Placement(private val inputId: Int, private val pip: PipSpec) : OverlaySettings {

        override fun getScale(): Pair<Float, Float> {
            val sizes = inputSizes
            val primary = sizes.getOrNull(0)
            val overlay = sizes.getOrNull(inputId)
            val s = if (primary != null && overlay != null && overlay.width > 0) {
                pip.scale * primary.width / overlay.width
            } else {
                pip.scale
            }
            return Pair.create(s, s)
        }

        override fun getBackgroundFrameAnchor(): Pair<Float, Float> = Pair.create(pip.anchorX, pip.anchorY)

        /** Pivot on the overlay's centre, so the anchor is where the box's middle lands. */
        override fun getOverlayFrameAnchor(): Pair<Float, Float> = CENTRE

        override fun getRotationDegrees(): Float = pip.rotationDeg

        override fun getAlphaScale(): Float = pip.opacity
    }

    private companion object {
        val FULL_FRAME: OverlaySettings = StaticOverlaySettings.Builder().build()
        val HIDDEN: OverlaySettings = StaticOverlaySettings.Builder().setAlphaScale(0f).build()
        val CENTRE: Pair<Float, Float> = Pair.create(0f, 0f)
    }
}
