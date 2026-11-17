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

/** When the field carries its words and numbers. Without them it is the graph alone. */
enum class Labels(val label: String) {
    ALWAYS("Always"),
    /** A tile at least three quarters of the page wide. A half-width tile is all graph. */
    BIG_TILES("Only on big tiles"),
    NEVER("Never"),
}

/** How big the words and numbers are. Large reads at a glance on the bike; Small is the size up to 0.2.0. */
enum class TextSize(val label: String) {
    LARGE("Large"),
    SMALL("Small"),
}

/** What a rider can change about the field, from the app's settings screen. */
data class FieldSettings(
    val palette: Palette = Palette.KAROO,
    val showClimb: Boolean = true,
    val showSteepest: Boolean = true,
    val imperial: Boolean = false,
    val labels: Labels = Labels.BIG_TILES,
    val textSize: TextSize = TextSize.LARGE,
    val showDistance: Boolean = true,
    val showAverage: Boolean = false,
    val graphHeight: GraphHeight = GraphHeight.FILL,
    val behind: Behind = Behind.OFF,
)

enum class GraphHeight(val label: String) { FILL("Fill the tile"), TRUE("True to the slope") }

enum class Behind(val label: String, val share: Double) {
    OFF("Off", 0.0), FIFTH("A fifth of the field", 0.2), THIRD("A third of the field", 1.0 / 3.0);
    /** How far back the field shows for [wantM] ahead, at the same scale as the road ahead. */
    fun behindM(wantM: Double): Double = if (share <= 0.0) 0.0 else wantM * share / (1 - share)
}

// The picture is a list of plain shapes, so the Karoo and the Mac draw exactly the same thing.

sealed interface Shape
class Fill(val xs: FloatArray, val ys: FloatArray, val argb: Int) : Shape
class Stroke(val xs: FloatArray, val ys: FloatArray, val argb: Int, val width: Float, val dashed: Boolean = false) : Shape
class Dot(val x: Float, val y: Float, val r: Float, val argb: Int) : Shape
class Run(val text: String, val size: Float, val argb: Int, val bold: Boolean = true)
enum class Align { LEFT, CENTRE, RIGHT }

/**
 * A line of text made of runs in different sizes and colours, such as a number and its unit.
 * With a [panel] colour, whatever draws it puts a box of that colour behind the words, sized to
 * the words as that screen's own font sets them.
 */
class Label(val runs: List<Run>, val x: Float, val y: Float, val align: Align, val panel: Int? = null) : Shape

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
 * the rider at the left edge, or a little in from it when the road behind is shown. Sizes are in
 * the field's own pixels, and the layout tightens as the field gets shorter or narrower.
 */
object FieldPainter {
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val GREY = 0xFF9AA5A0.toInt()
    private const val SOFT = 0xFFB9C2BD.toInt()
    private const val PANEL = 0xB8000000.toInt()
    /** The road already ridden: darker than the grey of a descent ahead. */
    private const val RIDDEN = 0xFF262B30.toInt()
    /** The least room between two numbers along the top. */
    private const val NUMBER_GAP = 8f

    private enum class ReadingKind { DISTANCE, CLIMB, AVERAGE, STEEPEST }
    private class Reading(val value: Pair<String, String>, val kind: ReadingKind)

    /**
     * About how wide a bold character is, as a share of the text's size: the wider of the font
     * the Karoo draws with (Roboto) and the one the Mac draws the website's pictures with
     * (Helvetica Neue), measured 2026-11-17. Never narrower than the real thing, so numbers
     * placed by it can't touch.
     */
    private fun charWidth(c: Char): Float = when (c) {
        in '0'..'9' -> 0.58f
        '+' -> 0.6f
        '-' -> 0.41f
        '.' -> 0.29f
        ' ' -> 0.28f
        '%' -> 1.0f
        'm' -> 0.91f
        'i' -> 0.27f
        'f', 't' -> 0.36f
        'g' -> 0.62f
        else -> 0.58f
    }

    private fun textWidth(text: String, size: Float): Float = text.sumOf { charWidth(it).toDouble() }.toFloat() * size

    fun paint(look: Look, w: Int, h: Int, settings: FieldSettings): Picture = when (look) {
        is Look.Road -> road(look.ahead, w, h, settings)
        Look.Waiting -> message(w, h, "Start riding", "to see the road ahead", settings.textSize)
        Look.NoRoad -> message(w, h, "No road here", "nothing mapped nearby", settings.textSize)
        Look.NoMap -> message(w, h, "No map here", "download this region's map", settings.textSize)
        is Look.NoHeights -> message(w, h, "No elevation here", "connect to Wi-Fi to fetch it", settings.textSize)
    }

    /** A field with words in place of a picture. */
    fun message(w: Int, h: Int, big: String, small: String, textSize: TextSize = TextSize.LARGE): Picture {
        val shapes = ArrayList<Shape>()
        val scale = min(1f, w / 480f)
        val large = textSize == TextSize.LARGE
        // The Large caption needs a taller tile, or it crowds the words under it.
        if (h >= (if (large) 130 else 110)) shapes += Label(listOf(Run("ROAD AHEAD", if (large) 20f else 13f, GREY)), 10f, if (large) 24f else 17f, Align.LEFT)
        val mid = h / 2f
        val bigSize = (if (large) 36f else 30f) * scale
        val smallSize = (if (large) 22f else 17f) * scale
        // A short tile has no caption above the words, so Large sits a little higher there.
        val drop = if (large && h >= 130) 6f else 0f
        shapes += Label(listOf(Run(big, bigSize, WHITE)), w / 2f, mid + (if (h < 90) 2f else 6f) + drop, Align.CENTRE)
        if (h >= 90) shapes += Label(listOf(Run(small, smallSize, GREY, bold = false)), w / 2f, mid + (if (large) 36f else 32f) + drop, Align.CENTRE)
        return Picture(w, h, shapes)
    }

    private fun road(a: Ahead, w: Int, h: Int, s: FieldSettings): Picture {
        val shapes = ArrayList<Shape>()
        val tall = h >= 220
        val tiny = h < 110
        val narrow = w < 360
        val words = when (s.labels) {
            Labels.ALWAYS -> true
            Labels.BIG_TILES -> !narrow
            Labels.NEVER -> false
        }

        // The words and numbers along the top. Large text is sized to read at a glance on the
        // bike. On a tile of ordinary height it drops the caption and the road name, so the
        // numbers can grow without taking height from the graph.
        val large = s.textSize == TextSize.LARGE
        val caption = words && !tiny && (tall || !large)
        val small = when { !large -> if (tall) 15f else 13f; else -> 22f }
        val big = when {
            !large -> when { tall -> 34f; tiny -> 22f; else -> 26f }
            narrow -> if (tiny) 26f else 32f
            tall -> 44f
            tiny -> 30f
            else -> 40f
        }
        val unitScale = if (large) 0.6f else 0.55f
        val labelY = when { !large -> if (tall) 20f else 17f; else -> 26f }
        val numberBase = when {
            !large -> when { tall -> 54f; tiny -> 24f; else -> 40f }
            tall -> 74f
            tiny -> big + 2f
            else -> big
        }
        val topBase = when {
            !words -> if (tiny) 4f else 8f
            !large -> when { tall -> 66f; tiny -> 30f; else -> 46f }
            tall -> 86f
            else -> numberBase + 9f
        }
        // The numbers the rider ticked, always in the same order. If they don't fit across the
        // tile they shrink, to three quarters of their size at most, and if they still don't fit,
        // distance drops off first, then climbing. A shrunk row gives its height back to the graph.
        val steepest = a.steepestPct(a.wantM / 20.0)?.let { max(0.0, it) }
        val candidates = ArrayList<Reading>()
        if (words && s.showDistance) candidates += Reading(Units.distance(a.wantM, s.imperial), ReadingKind.DISTANCE)
        if (words && s.showClimb) candidates += Reading(Units.climb(a.climbM, s.imperial), ReadingKind.CLIMB)
        if (words && s.showAverage) {
            val rounded = "%.0f".format(a.averageToTopPct()).let { if (it == "-0") "0" else it }
            candidates += Reading(rounded to "% avg", ReadingKind.AVERAGE)
        }
        if (words && s.showSteepest && steepest != null) candidates += Reading("%.0f".format(steepest) to "% max", ReadingKind.STEEPEST)
        fun estimated(n: Reading, size: Float) = textWidth(n.value.first, size) + textWidth(" " + n.value.second, size * unitScale)
        val room = w - 20f
        fun gaps(ns: List<Reading>) = (ns.size - 1) * NUMBER_GAP
        fun required(ns: List<Reading>, size: Float) = ns.sumOf { estimated(it, size).toDouble() }.toFloat() + gaps(ns)
        val numbers = candidates.toMutableList()
        var numberSize: Float
        while (true) {
            if (numbers.size <= 1 || required(numbers, big) <= room + 0.5f) { numberSize = big; break }
            // The text's width grows with its size, so this size fills the room exactly.
            val fit = big * (room - gaps(numbers)) / (required(numbers, big) - gaps(numbers))
            if (fit >= big * 0.75f) { numberSize = fit; break }
            val drop = numbers.indexOfFirst { it.kind == ReadingKind.DISTANCE }.takeIf { it >= 0 }
                ?: numbers.indexOfFirst { it.kind == ReadingKind.CLIMB }
            if (drop < 0) { numberSize = big * 0.75f; break }
            numbers.removeAt(drop)
        }
        val numberY = numberBase + (numberSize - big)
        val top = if (words) topBase + (numberSize - big) else topBase
        if (caption) {
            shapes += Label(listOf(Run("ROAD AHEAD", small, GREY)), 10f, labelY, Align.LEFT)
            val name = a.roadName
            if (name != null && !narrow) {
                val short = if (name.length > 26) name.take(25).trimEnd() + "…" else name
                shapes += Label(listOf(Run(short, small, GREY, bold = false)), w - 10f, labelY, Align.RIGHT)
            }
        }
        if (numbers.isNotEmpty()) {
            val widths = numbers.map { estimated(it, numberSize) }
            if (numbers.size == 1) {
                // One number alone: distance at the left, as it always sat, anything else at the right.
                val align = if (numbers[0].kind == ReadingKind.DISTANCE) Align.LEFT else Align.RIGHT
                val x = if (align == Align.LEFT) 10f else w - 10f
                shapes += Label(listOf(Run(numbers[0].value.first, numberSize, WHITE), Run(" " + numbers[0].value.second, numberSize * unitScale, GREY)), x, numberY, align)
            } else {
                // First at the left edge, last at the right, any between spaced evenly.
                val spare = (room - widths.sum() - gaps(numbers)).coerceAtLeast(0f) / (numbers.size - 1)
                var x = 10f
                numbers.forEachIndexed { i, n ->
                    val align = if (i == numbers.lastIndex) Align.RIGHT else Align.LEFT
                    val at = if (i == numbers.lastIndex) w - 10f else x
                    shapes += Label(listOf(Run(n.value.first, numberSize, WHITE), Run(" " + n.value.second, numberSize * unitScale, GREY)), at, numberY, align)
                    if (i < numbers.lastIndex) x += widths[i] + NUMBER_GAP + spare
                }
            }
        }

        // The profile. True to the slope never zooms in past a minimum, so a flat road looks flat.
        // Fill the tile spreads the profile over the whole graph, with a much lower minimum, so
        // small rises are easy to see. Either way what is in view sits in the middle of the tile:
        // a flat road runs through the middle, a climb starts low and a descent starts high. The
        // road behind, when it is shown, counts towards the scale too.
        val base = h - (if (tiny) 3f else 8f)
        val plotH = base - top
        val behindOn = s.behind.share > 0.0
        val x0 = if (behindOn) w * s.behind.share else 0.0
        val allHeights = if (behindOn) a.heights + a.behind else a.heights
        val lo0 = allHeights.min()
        val hi0 = allHeights.max()
        val range = hi0 - lo0
        val middle = (lo0 + hi0) / 2
        val (lo, hi) = if (s.graphHeight == GraphHeight.TRUE) {
            val span = max(range, max(40.0, a.wantM * 0.02))
            (middle - span * 0.6) to (middle + span * 0.6)
        } else {
            val floor = max(10.0, a.wantM * 0.0075)
            if (range >= floor) (lo0 - range * 0.06) to (hi0 + range * 0.06)
            else (middle - floor * 0.56) to (middle + floor * 0.56)
        }
        val guess = a.guessFromM
        fun x(distM: Double) = (x0 + distM / a.wantM * (w - x0)).toFloat()
        fun y(height: Double) = (top + plotH - (height - lo) / (hi - lo) * plotH).toFloat()

        // The road already ridden, left of the rider, at the same scale as the road ahead: a dark
        // fill under a thin grey line, so it can't be mistaken for a descent ahead.
        if (behindOn && a.behind.isNotEmpty()) {
            val backM = s.behind.behindM(a.wantM)
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            for (i in a.behind.indices.reversed()) {
                val dist = (i + 1) * a.stepM
                if (dist <= backM + 0.01) { xs += (x0 - dist / backM * x0).toFloat(); ys += y(a.behind[i]) }
            }
            xs += x0.toFloat(); ys += y(a.heights[0])
            if (xs.size >= 2) {
                val fillXs = floatArrayOf(xs.first()) + xs.toFloatArray() + floatArrayOf(x0.toFloat())
                val fillYs = floatArrayOf(base) + ys.toFloatArray() + floatArrayOf(base)
                shapes += Fill(fillXs, fillYs, RIDDEN)
                shapes += Stroke(xs.toFloatArray(), ys.toFloatArray(), GREY, 2f)
            }
        }
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
            // A pixel of overlap, so no dark line shows between two blocks wherever the edge falls.
            // The next block paints over the rest.
            xs += x(b.toM) + 1f; ys += y(a.heightAt(b.toM))
            xs += x(b.toM) + 1f; ys += base
            // Past a junction taken on a guess, the blocks are dimmed: likely, not known.
            val colour = s.palette.colour(b.gradePct)
            shapes += Fill(xs.toFloatArray(), ys.toFloatArray(), if (guess != null && (b.fromM + b.toM) / 2 >= guess) dim(colour) else colour)
        }
        val n = a.heights.size
        val lineW = if (tall) 4f else 3f
        shapes += Stroke(FloatArray(n) { x(a.distAt(it)) }, FloatArray(n) { y(a.heights[it]) }, 0xF2FFFFFF.toInt(), lineW)
        if (guess != null) {
            // A tick across the line where the guessing starts.
            val gx = x(guess)
            val gy = y(a.heightAt(guess))
            val tick = max(8f, plotH * 0.12f)
            shapes += Stroke(floatArrayOf(gx, gx), floatArrayOf(gy - tick, gy + tick), WHITE, lineW)
        }

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
            val size = when { !large -> if (tall) 20f else if (tiny) 14f else 17f; tall -> 28f; tiny -> 20f; else -> 24f }
            val ty = top + plotH / 2f + size * 0.4f
            val leftSide = ex > w * 0.55f
            val mark = a.end == End.TURN && !tiny
            val arm = if (tall) 13f else 10f
            // The room beside the mark for the words. The full sentence goes in when it fits,
            // the distance alone when it doesn't. A letter is about half as wide as it is tall.
            val room = (if (leftSide) ex else w - ex) - (if (mark) 22f + arm + 10f else 10f) - 4f
            val full = "$reason in $num $unit"
            val text = when {
                !words -> null
                a.end == End.NO_MAP || a.end == End.NO_HEIGHTS -> reason
                narrow || full.length * size * 0.52f > room -> "$num $unit"
                else -> full
            }
            // To the left of the end the words lie over the graph, so they get a dark panel to
            // be read against. To the right there is only the field's black.
            fun note(words: String, x: Float) {
                shapes += Label(listOf(Run(words, size, if (leftSide) WHITE else SOFT, bold = false)), x, ty,
                    if (leftSide) Align.RIGHT else Align.LEFT, panel = if (leftSide) PANEL else null)
            }
            if (mark) {
                // A small T: the road you're on meeting the one that crosses it.
                val gx = if (leftSide) ex - 22f else ex + 22f
                val gy = top + plotH / 2f
                shapes += Stroke(floatArrayOf(gx, gx), floatArrayOf(gy + arm * 1.4f, gy - arm * 0.4f), WHITE, 4f)
                shapes += Stroke(floatArrayOf(gx - arm, gx + arm), floatArrayOf(gy - arm * 0.4f, gy - arm * 0.4f), WHITE, 4f)
                if (text != null) note(text, if (leftSide) gx - arm - 10f else gx + arm + 10f)
            } else if (text != null) {
                note(text, if (leftSide) ex - 10f else ex + 10f)
            }
        }
        // You are here.
        shapes += Dot(if (behindOn) x0.toFloat() else 5f, y(a.heights[0]), if (tiny) 4f else 5f, WHITE)
        return Picture(w, h, shapes)
    }

    /** The same colour at half strength, over the field's black. */
    internal fun dim(argb: Int): Int {
        fun part(shift: Int) = (((argb shr shift) and 0xFF) * 0.5).toInt() shl shift
        return (0xFF shl 24) or part(16) or part(8) or part(0)
    }
}
