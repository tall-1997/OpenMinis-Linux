package com.openminis.app.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * [T-plan-card-visual-redesign] Hand-drawn glyph set for the plan/goal card.
 *
 * The card used to borrow Material *filled* defaults — CheckCircle,
 * Error, RadioButtonUnchecked, KeyboardArrowUp/Down, Dashboard — whose solid
 * discs and thick strokes sat wrong next to the app's SF-Symbols-leaning
 * chrome (see M3Icons: filled/outlined pairs, no bespoke weight). Every glyph
 * here is a stroke-only outline on a 24-unit grid with round caps and joins,
 * sized by the caller so a 16dp render lands on a ~1.5dp stroke — the optical
 * weight of a UI text line, which is what makes a glyph read "Apple-like"
 * instead of "Material blob".
 *
 * Strikes are black on purpose: [androidx.compose.material3.Icon] applies its
 * tint as a ColorFilter over the whole vector, so the literal color never
 * reaches the screen and one glyph serves every state color.
 */
internal object PlanBoardGlyphs {

    /** Board identity: two check rows, the checklist mark at its readable floor. */
    val Checklist: ImageVector by lazy {
        glyph("PlanChecklist") {
            strokePath(strokeWidth = 2.3f) {
                // row 1: check + line
                moveTo(4f, 8.2f)
                lineTo(5.8f, 10f)
                lineTo(9f, 6.4f)
                moveTo(12f, 8f)
                lineTo(20f, 8f)
                // row 2: check + line
                moveTo(4f, 15.8f)
                lineTo(5.8f, 17.6f)
                lineTo(9f, 14f)
                moveTo(12f, 15.6f)
                lineTo(20f, 15.6f)
            }
        }
    }

    /** Toggle disclosure mark; the caller rotates it for the expanded state. */
    val ChevronDown: ImageVector by lazy {
        glyph("PlanChevronDown") {
            strokePath(strokeWidth = 2.2f) {
                moveTo(6.5f, 9.5f)
                lineTo(12f, 15f)
                lineTo(17.5f, 9.5f)
            }
        }
    }

    /** Done / all-done: a bare check mark, no disc behind it. */
    val Check: ImageVector by lazy {
        glyph("PlanCheck") {
            strokePath(strokeWidth = 2.6f) {
                moveTo(5.4f, 12.6f)
                lineTo(9.8f, 17f)
                lineTo(18.6f, 7.2f)
            }
        }
    }

    /** Pending: the open ring the old RadioButtonUnchecked drew too fat. */
    val Ring: ImageVector by lazy {
        glyph("PlanRing") {
            strokePath(strokeWidth = 2f) { ring(radius = 8f) }
        }
    }

    /** Terminal success surfaces (all steps done, goal complete). */
    val CheckCircle: ImageVector by lazy {
        glyph("PlanCheckCircle") {
            strokePath(strokeWidth = 1.9f) { ring(radius = 9f) }
            strokePath(strokeWidth = 2.1f) {
                moveTo(8.2f, 12.4f)
                lineTo(11f, 15.2f)
                lineTo(16f, 9.6f)
            }
        }
    }

    /** Failed step / blocked goal. */
    val XCircle: ImageVector by lazy {
        glyph("PlanXCircle") {
            strokePath(strokeWidth = 1.9f) { ring(radius = 8.8f) }
            strokePath(strokeWidth = 2f) {
                moveTo(9.3f, 9.3f)
                lineTo(14.7f, 14.7f)
                moveTo(14.7f, 9.3f)
                lineTo(9.3f, 14.7f)
            }
        }
    }

    /** Budget-limited goal: the exclamation ring, drawn at the same weight. */
    val AlertCircle: ImageVector by lazy {
        glyph("PlanAlertCircle") {
            strokePath(strokeWidth = 1.9f) { ring(radius = 9f) }
            strokePath(strokeWidth = 2.1f) {
                moveTo(12f, 7.4f)
                lineTo(12f, 13.4f)
                // A zero-length segment renders as a dot under the round cap.
                moveTo(12f, 16.8f)
                lineTo(12f, 16.81f)
            }
        }
    }

    /** Paused goal: two bars, no filled square. */
    val Pause: ImageVector by lazy {
        glyph("PlanPause") {
            strokePath(strokeWidth = 2.2f) {
                moveTo(9.2f, 7.2f)
                lineTo(9.2f, 16.8f)
                moveTo(14.8f, 7.2f)
                lineTo(14.8f, 16.8f)
            }
        }
    }
}

/** One stroke-only path on the shared 24-unit grid. */
private fun ImageVector.Builder.strokePath(
    strokeWidth: Float,
    pathBuilder: PathBuilder.() -> Unit,
): ImageVector.Builder = path(
    fill = null,
    stroke = SolidColor(Color.Black),
    strokeLineWidth = strokeWidth,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
    pathBuilder = pathBuilder,
)

/** Circle of [radius] centred in the 24-unit viewport, as two half arcs. */
private fun PathBuilder.ring(radius: Float) {
    val center = 12f
    moveTo(center, center - radius)
    // Two half circles, same sweep direction: top -> bottom -> top.
    arcToRelative(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, radius * 2f)
    arcToRelative(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, -radius * 2f)
    close()
}

private fun glyph(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()