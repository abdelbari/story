package com.kinetic.editor.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The icon set, drawn rather than depended on.
 *
 * `material-icons-extended` is several thousand vectors to use fifteen, and its
 * Material shapes would not match this app's language anyway. These are line
 * icons on a common 24-unit grid with one stroke weight, which is what makes a
 * set look like a set.
 *
 * Every colour here is a placeholder: `Icon` tints the whole vector, so the
 * declared colour never reaches the screen.
 */
private const val W = 1.8f
private val INK = SolidColor(Color.Black)

private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()

/** Outlined shapes share one stroke; fills are reserved for solid glyphs. */
private fun ImageVector.Builder.stroke(block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
    path(
        stroke = INK,
        strokeLineWidth = W,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    )

private fun ImageVector.Builder.fill(block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
    path(fill = INK, pathFillType = PathFillType.NonZero, pathBuilder = block)

object KineticIcons {

    /** Filled, because a play button reads as a solid mark at any size. */
    val Play: ImageVector by lazy {
        icon("Play") {
            fill {
                moveTo(8f, 5.2f); lineTo(18.5f, 12f); lineTo(8f, 18.8f); close()
            }
        }
    }

    val Pause: ImageVector by lazy {
        icon("Pause") {
            fill {
                moveTo(8f, 5.5f); horizontalLineTo(10.6f); verticalLineTo(18.5f)
                horizontalLineTo(8f); close()
                moveTo(13.4f, 5.5f); horizontalLineTo(16f); verticalLineTo(18.5f)
                horizontalLineTo(13.4f); close()
            }
        }
    }

    val Undo: ImageVector by lazy {
        icon("Undo") {
            stroke {
                moveTo(4f, 9f); horizontalLineTo(14f)
                arcTo(5f, 5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 14f, 19f)
                horizontalLineTo(8f)
            }
            stroke { moveTo(7.5f, 5.5f); lineTo(4f, 9f); lineTo(7.5f, 12.5f) }
        }
    }

    val Redo: ImageVector by lazy {
        icon("Redo") {
            stroke {
                moveTo(20f, 9f); horizontalLineTo(10f)
                arcTo(5f, 5f, 0f, isMoreThanHalf = false, isPositiveArc = false, 10f, 19f)
                horizontalLineTo(16f)
            }
            stroke { moveTo(16.5f, 5.5f); lineTo(20f, 9f); lineTo(16.5f, 12.5f) }
        }
    }

    /** Split: the cut line, with the two halves stepping away from it. */
    val Split: ImageVector by lazy {
        icon("Split") {
            stroke { moveTo(12f, 3.5f); verticalLineTo(6.5f) }
            stroke { moveTo(12f, 10.5f); verticalLineTo(13.5f) }
            stroke { moveTo(12f, 17.5f); verticalLineTo(20.5f) }
            stroke { moveTo(8.5f, 8f); horizontalLineTo(4.5f); verticalLineTo(16f); horizontalLineTo(8.5f) }
            stroke { moveTo(15.5f, 8f); horizontalLineTo(19.5f); verticalLineTo(16f); horizontalLineTo(15.5f) }
        }
    }

    val Trash: ImageVector by lazy {
        icon("Trash") {
            stroke { moveTo(4.5f, 6.5f); horizontalLineTo(19.5f) }
            stroke { moveTo(9.5f, 6.5f); verticalLineTo(4.5f); horizontalLineTo(14.5f); verticalLineTo(6.5f) }
            stroke {
                moveTo(6.5f, 6.5f); verticalLineTo(19f)
                arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = false, 8f, 20.5f)
                horizontalLineTo(16f)
                arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = false, 17.5f, 19f)
                verticalLineTo(6.5f)
            }
            stroke { moveTo(10.5f, 10f); verticalLineTo(17f) }
            stroke { moveTo(13.5f, 10f); verticalLineTo(17f) }
        }
    }

    val Duplicate: ImageVector by lazy {
        icon("Duplicate") {
            stroke {
                moveTo(9f, 3.5f); horizontalLineTo(19f); verticalLineTo(13.5f)
            }
            stroke {
                moveTo(6.5f, 7.5f); horizontalLineTo(15f); verticalLineTo(20.5f)
                horizontalLineTo(6.5f); close()
            }
        }
    }

    /** A strip of film: the sprockets are what make it read at 20dp. */
    val Film: ImageVector by lazy {
        icon("Film") {
            stroke { moveTo(3.5f, 5.5f); horizontalLineTo(20.5f); verticalLineTo(18.5f); horizontalLineTo(3.5f); close() }
            stroke { moveTo(8f, 5.5f); verticalLineTo(18.5f) }
            stroke { moveTo(16f, 5.5f); verticalLineTo(18.5f) }
            stroke { moveTo(3.5f, 12f); horizontalLineTo(8f) }
            stroke { moveTo(16f, 12f); horizontalLineTo(20.5f) }
        }
    }

    val Music: ImageVector by lazy {
        icon("Music") {
            stroke { moveTo(9.5f, 17f); verticalLineTo(5.5f); lineTo(19f, 3.5f); verticalLineTo(15f) }
            stroke {
                moveTo(9.5f, 17f)
                arcTo(2.4f, 2.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 9.49f, 17f); close()
            }
            stroke {
                moveTo(19f, 15f)
                arcTo(2.4f, 2.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 18.99f, 15f); close()
            }
        }
    }

    val TypeT: ImageVector by lazy {
        icon("Text") {
            stroke { moveTo(5f, 6f); horizontalLineTo(19f) }
            stroke { moveTo(12f, 6f); verticalLineTo(19f) }
            stroke { moveTo(9f, 19f); horizontalLineTo(15f) }
        }
    }

    val Sticker: ImageVector by lazy {
        icon("Sticker") {
            stroke {
                moveTo(12f, 3.5f); lineTo(14.6f, 9f); lineTo(20.5f, 9.8f); lineTo(16.2f, 14f)
                lineTo(17.3f, 20f); lineTo(12f, 17.1f); lineTo(6.7f, 20f); lineTo(7.8f, 14f)
                lineTo(3.5f, 9.8f); lineTo(9.4f, 9f); close()
            }
        }
    }

    /** Picture in picture: the frame, and the inset that sits inside it. */
    val Pip: ImageVector by lazy {
        icon("Pip") {
            stroke { moveTo(3.5f, 5.5f); horizontalLineTo(20.5f); verticalLineTo(18.5f); horizontalLineTo(3.5f); close() }
            fill {
                moveTo(12.5f, 11.5f); horizontalLineTo(18.5f); verticalLineTo(16.5f)
                horizontalLineTo(12.5f); close()
            }
        }
    }

    val Mic: ImageVector by lazy {
        icon("Mic") {
            stroke {
                moveTo(12f, 3.5f)
                arcTo(2.6f, 2.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 14.6f, 6.1f)
                verticalLineTo(11.4f)
                arcTo(2.6f, 2.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 9.4f, 11.4f)
                verticalLineTo(6.1f)
                arcTo(2.6f, 2.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 12f, 3.5f); close()
            }
            stroke {
                moveTo(5.8f, 11f)
                arcTo(6.2f, 6.2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 18.2f, 11f)
            }
            stroke { moveTo(12f, 17.2f); verticalLineTo(20.5f) }
        }
    }

    /** Stop: a square, so it reads as the opposite of the record dot. */
    val Stop: ImageVector by lazy {
        icon("Stop") {
            fill { moveTo(6.5f, 6.5f); horizontalLineTo(17.5f); verticalLineTo(17.5f); horizontalLineTo(6.5f); close() }
        }
    }

    val Export: ImageVector by lazy {
        icon("Export") {
            stroke { moveTo(12f, 15.5f); verticalLineTo(3.5f) }
            stroke { moveTo(7.8f, 7.7f); lineTo(12f, 3.5f); lineTo(16.2f, 7.7f) }
            stroke { moveTo(4.5f, 14f); verticalLineTo(19f); horizontalLineTo(19.5f); verticalLineTo(14f) }
        }
    }

    /** Canvas: corner brackets, the universal mark for a frame. */
    val Frame: ImageVector by lazy {
        icon("Frame") {
            stroke { moveTo(4f, 9f); verticalLineTo(4f); horizontalLineTo(9f) }
            stroke { moveTo(15f, 4f); horizontalLineTo(20f); verticalLineTo(9f) }
            stroke { moveTo(20f, 15f); verticalLineTo(20f); horizontalLineTo(15f) }
            stroke { moveTo(9f, 20f); horizontalLineTo(4f); verticalLineTo(15f) }
        }
    }

    /** Freeze: a frame holding still, with a pause mark inside it. */
    val Freeze: ImageVector by lazy {
        icon("Freeze") {
            stroke {
                moveTo(3.5f, 5.5f); horizontalLineTo(20.5f); verticalLineTo(18.5f)
                horizontalLineTo(3.5f); close()
            }
            stroke { moveTo(10f, 9f); verticalLineTo(15f) }
            stroke { moveTo(14f, 9f); verticalLineTo(15f) }
        }
    }

    /** Backdrop: a frame with a letterboxed picture inside it. */
    val Backdrop: ImageVector by lazy {
        icon("Backdrop") {
            stroke {
                moveTo(3.5f, 4.5f); horizontalLineTo(20.5f); verticalLineTo(19.5f)
                horizontalLineTo(3.5f); close()
            }
            stroke { moveTo(3.5f, 9f); horizontalLineTo(20.5f) }
            stroke { moveTo(3.5f, 15f); horizontalLineTo(20.5f) }
        }
    }

    /** Detach: a note leaving the frame it came from. */
    val Detach: ImageVector by lazy {
        icon("Detach") {
            stroke { moveTo(3.5f, 5.5f); horizontalLineTo(12.5f); verticalLineTo(14.5f); horizontalLineTo(3.5f); close() }
            stroke { moveTo(16f, 19.5f); verticalLineTo(10f); lineTo(21f, 8.6f); verticalLineTo(18f) }
            stroke {
                moveTo(16f, 19.5f)
                arcTo(1.9f, 1.9f, 0f, isMoreThanHalf = true, isPositiveArc = true, 15.99f, 19.5f); close()
            }
        }
    }

    val Plus: ImageVector by lazy {
        icon("Plus") {
            stroke { moveTo(12f, 5.5f); verticalLineTo(18.5f) }
            stroke { moveTo(5.5f, 12f); horizontalLineTo(18.5f) }
        }
    }
}
