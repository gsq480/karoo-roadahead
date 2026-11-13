package au.com.fjellconsulting.roadahead.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A grade colour scheme: the colour for each band of climbing grade, steepest first. */
enum class Palette(val label: String, private val bands: List<Pair<Double, Int>>) {
    KAROO("Karoo", listOf(
        20.0 to 0xFF9020A0.toInt(), 14.0 to 0xFFD01020.toInt(), 11.0 to 0xFFF06020.toInt(),
        8.0 to 0xFFF08868.toInt(), 5.0 to 0xFFF0D800.toInt(), 2.0 to 0xFF40D078.toInt(), 0.0 to 0xFF1A8C3A.toInt())),
    WAHOO("Wahoo", listOf(
        20.0 to 0xFF540000.toInt(), 12.0 to 0xFFAA0200.toInt(), 8.0 to 0xFFFF5501.toInt(),
        4.0 to 0xFFFEFF00.toInt(), 0.0 to 0xFF04FE00.toInt()));

    /** Downhill is grey in every scheme, so the climbs are what you see. */
    fun colour(gradePct: Double): Int =
        if (gradePct < 0.0) DOWNHILL else (bands.firstOrNull { gradePct >= it.first } ?: bands.last()).second

    companion object {
        const val DOWNHILL = 0xFF46505C.toInt()
    }
}

/** What a rider can change about the field, from the app's settings screen. */
data class FieldSettings(
    val palette: Palette = Palette.KAROO,
    val showClimb: Boolean = true,
    val showSteepest: Boolean = true,
    val imperial: Boolean = false,
)

// The picture is a list of plain shapes, so the Karoo and the Mac draw exactly the same thing.

sealed interface Shape
class Fill(val xs: FloatArray, val ys: FloatArray, val argb: Int) : Shape
class Stroke(val xs: FloatArray, val ys: FloatArray, val argb: Int, val width: Float, val dashed: Boolean = false) : Shape
class Dot(val x: Float, val y: Float, val r: Float, val argb: Int) : Shape
class Run(val text: String, val size: Float, val argb: Int, val bold: Boolean = true)
enum class Align { LEFT, CENTRE, RIGHT }

/** A line of text made of runs in different sizes and colours, such as a number and its unit. */
class Label(val runs: List<Run>, val x: Float, val y: Float, val align: Align) : Shape

class Picture(val w: Int, val h: Int, val shapes: List<Shape>)

/** Distances and heights the way the rider's Karoo shows them. */
object Units {
    fun distance(m: Double, imperial: Boolean): Pair<String, String> = if (imperial) {
        val mi = m / 1609.344
        if (mi < 0.19) "${(m * 3.28084 / 10).roundToInt() * 10}" to "ft" else "%.1f".format(mi) to "mi"
    } else {
        if (m < 950.0) "${(m / 10).roundToInt() * 10}" to "m" else "%.1f".format(m / 1000.0) to "km"
    }

    fun climb(m: Double, imperial: Boolean): Pair<String, String> =
        if (imperial) "+${(m * 3.28084).roundToInt()}" to "ft" else "+${m.roundToInt()}" to "m"
}

/**
 * Rule 4: draw it. The road ahead as a smooth profile, filled in blocks coloured by grade, with
 * the rider at the left edge. Sizes are in the field's own pixels, and the layout tightens as the
 * field gets shorter or narrower.
 */
object FieldPainter {
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val GREY = 0xFF9AA5A0.toInt()
    private const val SOFT = 0xFFB9C2BD.toInt()

    fun paint(look: Look, w: Int, h: Int, settings: FieldSettings): Picture = when (look) {
        is Look.Road -> road(look.ahead, w, h, settings)
        Look.Waiting -> message(w, h, "Start riding", "to see the road ahead")
        Look.NoRoad -> message(w, h, "No road here", "nothing mapped nearby")
        Look.NoMap -> message(w, h, "No map here", "download this region's map")
        is Look.NoHeights -> message(w, h, "No elevation here", "connect to Wi-Fi to fetch it")
    }

    /** A field with words in place of a picture. */
    fun message(w: Int, h: Int, big: String, small: String): Picture {
        val shapes = ArrayList<Shape>()
        val scale = min(1f, w / 480f)
        if (h >= 110) shapes += Label(listOf(Run("ROAD AHEAD", 13f, GREY)), 10f, 17f, Align.LEFT)
        val mid = h / 2f
        shapes += Label(listOf(Run(big, 30f * scale, WHITE)), w / 2f, mid + (if (h < 90) 2f else 6f), Align.CENTRE)
        if (h >= 90) shapes += Label(listOf(Run(small, 17f * scale, GREY, bold = false)), w / 2f, mid + 32f, Align.CENTRE)
        return Picture(w, h, shapes)
    }

    private fun road(a: Ahead, w: Int, h: Int, s: FieldSettings): Picture {
        val shapes = ArrayList<Shape>()
        val tall = h >= 220
        val tiny = h < 110
        val narrow = w < 360

        // The words and numbers along the top.
        val small = if (tall) 15f else 13f
        val big = when { tall -> 34f; tiny -> 22f; else -> 26f }
        val labelY = if (tall) 20f else 17f
        val numberY = when { tall -> 54f; tiny -> 24f; else -> 40f }
        val top = when { tall -> 66f; tiny -> 30f; else -> 46f }
        if (!tiny) {
            shapes += Label(listOf(Run("ROAD AHEAD", small, GREY)), 10f, labelY, Align.LEFT)
            val name = a.roadName
            if (name != null && !narrow) {
                val short = if (name.length > 26) name.take(25).trimEnd() + "…" else name
                shapes += Label(listOf(Run(short, small, GREY, bold = false)), w - 10f, labelY, Align.RIGHT)
            }
        }
        fun number(value: Pair<String, String>, x: Float, align: Align) {
            shapes += Label(listOf(Run(value.first, big, WHITE), Run(" " + value.second, big * 0.55f, GREY)), x, numberY, align)
        }
        number(Units.distance(a.wantM, s.imperial), 10f, Align.LEFT)
        val steepest = a.steepestPct(a.wantM / 20.0)?.let { max(0.0, it) }
        val showSteepest = s.showSteepest && steepest != null
        if (s.showClimb) {
            val lone = narrow || !showSteepest
            number(Units.climb(a.climbM, s.imperial), if (lone) w - 10f else w / 2f, if (lone) Align.RIGHT else Align.CENTRE)
        }
        if (showSteepest && !(narrow && s.showClimb)) number("%.0f".format(steepest) to "% max", w - 10f, Align.RIGHT)

        // The profile. The scale never zooms in past a minimum, so a flat road looks flat.
        val base = h - (if (tiny) 3f else 8f)
        val plotH = base - top
        val lo0 = a.heights.min()
        val hi0 = a.heights.max()
        val span = max(hi0 - lo0, max(40.0, a.wantM * 0.02))
        val lo = lo0 - span * 0.12
        val hi = lo + span * 1.2
        fun x(distM: Double) = (distM / a.wantM * w).toFloat()
        fun y(height: Double) = (top + plotH - (height - lo) / (hi - lo) * plotH).toFloat()

        for (b in a.blocks(a.wantM / 20.0)) {
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            xs += x(b.fromM); ys += base
            var d = b.fromM
            while (d < b.toM) {
                xs += x(d); ys += y(a.heightAt(d))
                d += a.stepM
            }
            xs += x(b.toM); ys += y(a.heightAt(b.toM))
            // A hair of overlap, so no black line shows between two blocks.
            xs += x(b.toM) + 0.6f; ys += y(a.heightAt(b.toM))
            xs += x(b.toM) + 0.6f; ys += base
            shapes += Fill(xs.toFloatArray(), ys.toFloatArray(), s.palette.colour(b.gradePct))
        }
        val n = a.heights.size
        shapes += Stroke(FloatArray(n) { x(a.distAt(it)) }, FloatArray(n) { y(a.heights[it]) }, 0x8CFFFFFF.toInt(), 1.5f)

        // Where the picture stops short of the distance asked for, say why.
        val reason = when (a.end) {
            End.TURN -> "junction"
            End.ROAD_ENDS -> "road ends"
            End.NO_MAP -> "map ends"
            End.NO_HEIGHTS -> "no elevation"
            End.FAR_ENOUGH, End.LOOP -> null
        }
        if (reason != null && a.lengthM < a.wantM - 1.0) {
            val ex = x(a.lengthM)
            shapes += Stroke(floatArrayOf(ex, ex), floatArrayOf(top, base), WHITE, 2f, dashed = true)
            val (num, unit) = Units.distance(a.lengthM, s.imperial)
            val text = when {
                a.end == End.NO_MAP || a.end == End.NO_HEIGHTS -> reason
                narrow -> "$num $unit"
                else -> "$reason in $num $unit"
            }
            val size = if (tall) 20f else if (tiny) 14f else 17f
            val ty = top + plotH / 2f + size * 0.4f
            val leftSide = ex > w * 0.55f
            if (a.end == End.TURN && !tiny) {
                // A small T: the road you're on meeting the one that crosses it.
                val gx = if (leftSide) ex - 22f else ex + 22f
                val gy = top + plotH / 2f
                val arm = if (tall) 13f else 10f
                shapes += Stroke(floatArrayOf(gx, gx), floatArrayOf(gy + arm * 1.4f, gy - arm * 0.4f), WHITE, 4f)
                shapes += Stroke(floatArrayOf(gx - arm, gx + arm), floatArrayOf(gy - arm * 0.4f, gy - arm * 0.4f), WHITE, 4f)
                shapes += Label(listOf(Run(text, size, SOFT, bold = false)), if (leftSide) gx - arm - 10f else gx + arm + 10f, ty, if (leftSide) Align.RIGHT else Align.LEFT)
            } else {
                shapes += Label(listOf(Run(text, size, SOFT, bold = false)), if (leftSide) ex - 10f else ex + 10f, ty, if (leftSide) Align.RIGHT else Align.LEFT)
            }
        }
        // You are here.
        shapes += Dot(5f, y(a.heights[0]), if (tiny) 4f else 5f, WHITE)
        return Picture(w, h, shapes)
    }
}
