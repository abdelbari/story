package app.morpho.engine.pdf

import app.morpho.engine.layout.pdf.PdfDrawing
import app.morpho.engine.layout.pdf.PdfRule
import org.apache.pdfbox.contentstream.PDFStreamEngine
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.contentstream.operator.OperatorProcessor
import org.apache.pdfbox.cos.COSBase
import org.apache.pdfbox.cos.COSNumber
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.util.Matrix
import kotlin.math.abs

/**
 * The rules a page draws.
 *
 * A line across a page means something — under a paper's dates, above the
 * note at its foot, between the rows of a table — and a reader that keeps
 * only the words loses all of it. Nothing in a PDF calls such a line a
 * rule: it is a path, stroked or filled, and what makes it a rule is that
 * it is horizontal, long enough to be seen and thin enough not to be a box.
 *
 * The same operators say where a page draws rather than places: a chart,
 * a diagram, a signature. Those are collected too, as the box each
 * painted path covers, because a reader that gathers only the pictures a
 * file holds converts the text of a report and loses every figure in it.
 *
 * A text engine is given the operators it needs and no others, so the
 * paths go past it unread. This installs the few that matter on any engine
 * that wants them, and collects what they draw.
 */
internal class RuleCatcher(private val page: () -> PDPage?, private val pageNumber: () -> Int) {

    /** Thicker than this and a filled rectangle is a box, not a rule. */
    private val maxThicknessPt = 4f

    /** Shorter than this and a line is a tick or a hyphen, not a rule. */
    private val minLengthPt = 20f

    /** Off horizontal by more than this and a line is a slope, not a rule. */
    private val levelPt = 0.5f

    val rules = mutableListOf<PdfRule>()

    /** The box every painted path covered, rules among them. */
    val drawings = mutableListOf<PdfDrawing>()

    private var subpathStart: FloatArray? = null
    private var current: FloatArray? = null
    private val pendingSegments = mutableListOf<PdfRule>()
    private val pendingSlivers = mutableListOf<PdfRule>()

    /** What the path being built covers so far, as left, top, right, bottom. */
    private var reach: FloatArray? = null

    private fun reaches(point: FloatArray) {
        val box = reach
        if (box == null) {
            reach = floatArrayOf(point[0], point[1], point[0], point[1])
            return
        }
        box[0] = minOf(box[0], point[0])
        box[1] = minOf(box[1], point[1])
        box[2] = maxOf(box[2], point[0])
        box[3] = maxOf(box[3], point[1])
    }

    /** Adds the path operators to [engine], so what it draws is seen as well as read. */
    fun installOn(engine: PDFStreamEngine) {
        engine.addOperator(operator("m") { operands ->
            val point = point(engine, operands, 0) ?: return@operator
            reaches(point)
            subpathStart = point
            current = point
        })
        engine.addOperator(operator("l") { operands ->
            val from = current
            val to = point(engine, operands, 0) ?: return@operator
            reaches(to)
            if (from != null) segment(from, to)
            current = to
        })
        engine.addOperator(operator("h") { _ ->
            val from = current
            val to = subpathStart
            if (from != null && to != null) segment(from, to)
            current = to
        })
        engine.addOperator(operator("re") { operands ->
            if (operands.size < 4) return@operator
            val x = number(operands[0]) ?: return@operator
            val y = number(operands[1]) ?: return@operator
            val w = number(operands[2]) ?: return@operator
            val h = number(operands[3]) ?: return@operator
            val a = transform(engine, x, y)
            val b = transform(engine, x + w, y + h)
            reaches(a)
            reaches(b)
            val top = minOf(a[1], b[1])
            val bottom = maxOf(a[1], b[1])
            if (bottom - top <= maxThicknessPt) {
                pendingSlivers += rule((top + bottom) / 2, minOf(a[0], b[0]), maxOf(a[0], b[0]))
            }
            current = null
            subpathStart = null
        })
        // A curve reaches wherever its points do. Its control points are
        // not on the curve, so the box is a little generous — which is the
        // right way to be wrong about where a figure ends.
        for (name in listOf("c", "v", "y")) {
            engine.addOperator(
                operator(name) { operands ->
                    var at = 0
                    while (at + 1 < operands.size) {
                        point(engine, operands, at)?.let { reaches(it); current = it }
                        at += 2
                    }
                },
            )
        }
        for (name in listOf("S", "s", "B", "B*", "b", "b*")) {
            engine.addOperator(operator(name) { _ -> paint(strokes = true, fills = name != "S" && name != "s") })
        }
        for (name in listOf("f", "F", "f*")) {
            engine.addOperator(operator(name) { _ -> paint(strokes = false, fills = true) })
        }
        engine.addOperator(operator("n") { _ -> paint(strokes = false, fills = false) })
    }

    private fun operator(name: String, body: (List<COSBase>) -> Unit) = object : OperatorProcessor() {
        override fun getName() = name
        override fun process(operator: Operator, operands: List<COSBase>) = body(operands)
    }

    private fun rule(y: Float, left: Float, right: Float) = PdfRule(pageNumber(), y, left, right)

    private fun number(operand: COSBase): Float? = (operand as? COSNumber)?.floatValue()

    private fun point(engine: PDFStreamEngine, operands: List<COSBase>, index: Int): FloatArray? {
        if (operands.size < index + 2) return null
        val x = number(operands[index]) ?: return null
        val y = number(operands[index + 1]) ?: return null
        return transform(engine, x, y)
    }

    /** User space through the current transformation, then top-down page points as glyphs are measured. */
    private fun transform(engine: PDFStreamEngine, x: Float, y: Float): FloatArray {
        val ctm: Matrix = runCatching { engine.graphicsState.currentTransformationMatrix }.getOrNull() ?: Matrix()
        val point = ctm.transformPoint(x, y)
        val box = page()?.cropBox
        return floatArrayOf(
            point.x - (box?.lowerLeftX ?: 0f),
            (box?.upperRightY ?: point.y) - point.y,
        )
    }

    private fun segment(from: FloatArray, to: FloatArray) {
        if (abs(from[1] - to[1]) > levelPt) return
        pendingSegments += rule((from[1] + to[1]) / 2, minOf(from[0], to[0]), maxOf(from[0], to[0]))
    }

    private fun paint(strokes: Boolean, fills: Boolean) {
        if (strokes) rules += pendingSegments.filter { it.right - it.left >= minLengthPt }
        if (fills) rules += pendingSlivers.filter { it.right - it.left >= minLengthPt }
        // A path that was painted covered what it covered, whether or not
        // any of it was a rule. A path merely closed off — the "n" that
        // sets a clip and draws nothing — covered nothing.
        reach?.takeIf { strokes || fills }?.let {
            drawings += PdfDrawing(pageNumber(), it[0], it[1], it[2], it[3], paths = 1)
        }
        reach = null
        pendingSegments.clear()
        pendingSlivers.clear()
        current = null
        subpathStart = null
    }
}
