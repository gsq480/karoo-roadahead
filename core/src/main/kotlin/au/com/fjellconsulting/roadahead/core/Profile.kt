package au.com.fjellconsulting.roadahead.core

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Where heights are read along a length of road: at the start, then every step, then at the very
 * end, so the last few metres before a junction aren't lost.
 */
internal fun sampleDists(lengthM: Double, stepM: Double): DoubleArray {
    val out = ArrayList<Double>()
    var d = 0.0
    while (d < lengthM - 1.0) {
        out += d
        d += stepM
    }
    out += lengthM.coerceAtLeast(0.0)
    return out.toDoubleArray()
}

/** The height a distance along a run of heights laid out by [sampleDists]. */
internal fun heightAlong(heights: DoubleArray, stepM: Double, lengthM: Double, distM: Double): Double {
    if (heights.size == 1) return heights[0]
    val d = distM.coerceIn(0.0, lengthM)
    val i = min(floor(d / stepM).toInt(), heights.size - 2)
    val from = i * stepM
    val to = if (i + 1 == heights.size - 1) lengthM else (i + 1) * stepM
    return if (to <= from) heights[i + 1] else heights[i] + (heights[i + 1] - heights[i]) * (d - from) / (to - from)
}

/** The heights along a predicted road: one every `stepM` from its start, and one at its end. */
class Profile(
    val stepM: Double, val heights: DoubleArray, val lengthM: Double, val end: End, val roadName: String?,
    /** Where the road was followed through a junction on a guess. See [Path.guessesM]. */
    val guessesM: List<Double> = emptyList(),
) {
    fun heightAt(distM: Double): Double = heightAlong(heights, stepM, lengthM, distM)
}

/**
 * Rule 3: read the heights along the road. A 30 m grid of a whole continent is noisy on the scale
 * of a street, so the heights are smoothed, and a bridge or tunnel is drawn as a straight line
 * between its ends, because the grid follows the valley floor or the hilltop instead.
 */
class ProfileBuilder(private val heights: HeightSource, private val rules: Rules = Rules()) {

    /** Null when there's no elevation file for this road. */
    fun build(path: Path): Profile? {
        val step = rules.sampleM
        val dists = sampleDists(path.lengthM, step)
        var n = dists.size
        var lengthM = path.lengthM
        var end = path.end
        val raw = arrayOfNulls<Double>(n)
        var gap = 0
        for (i in 0 until n) {
            val flat = path.flatAt(dists[i]) && i > 0
            raw[i] = if (flat) null else heights.at(path.at(dists[i]))
            // A few missing heights are holes in the grid and get drawn across. A long run is
            // the edge of the elevation files on the device, and the picture stops there.
            gap = if (raw[i] == null && !flat) gap + 1 else 0
            if (gap > MAX_GAP) {
                n = i - gap + 1
                lengthM = (n - 1) * step
                end = End.NO_HEIGHTS
                break
            }
        }
        if (n < 1) return null
        val filled = bridge(raw.copyOf(n)) ?: return null
        var out = filled
        repeat(2) { out = smooth(out, rules.smoothSamples) }
        return Profile(step, out, lengthM, end, path.roadName, path.guessesM.filter { it < lengthM })
    }

    private companion object {
        const val MAX_GAP = 8
    }

    /** Draw a straight line across each run of missing heights. Null when nothing is known at all. */
    private fun bridge(raw: Array<Double?>): DoubleArray? {
        if (raw.all { it == null }) return null
        val out = DoubleArray(raw.size)
        var i = 0
        while (i < raw.size) {
            val v = raw[i]
            if (v != null) {
                out[i] = v
                i++
                continue
            }
            var j = i
            while (j < raw.size && raw[j] == null) j++
            val before = if (i > 0) out[i - 1] else null
            val after = if (j < raw.size) raw[j] else null
            for (k in i until j) {
                out[k] = when {
                    before != null && after != null -> before + (after - before) * (k - i + 1) / (j - i + 1).toDouble()
                    before != null -> before
                    else -> after!!
                }
            }
            i = j
        }
        return out
    }

    /**
     * A moving average. Past each end the heights are continued at the slope they arrive with,
     * so a steady climb stays a steady climb right up to where you are.
     */
    private fun smooth(h: DoubleArray, k: Int): DoubleArray {
        if (k <= 0 || h.size < 3) return h
        val last = h.size - 1
        fun at(j: Int): Double = when {
            j < 0 -> 2 * h[0] - h[min(-j, last)]
            j > last -> 2 * h[last] - h[max(last - (j - last), 0)]
            else -> h[j]
        }
        return DoubleArray(h.size) { i ->
            var sum = 0.0
            for (j in i - k..i + k) sum += at(j)
            sum / (2 * k + 1)
        }
    }
}

/** One coloured block of the picture: a stretch of road and its average grade. */
class Block(val fromM: Double, val toM: Double, val gradePct: Double)

/**
 * What the field draws: the road from where you are, out to the distance asked for or to where
 * the prediction ends, whichever comes first.
 */
class Ahead(
    val stepM: Double,
    /** One height every `stepM` from where you are, and one at the end of what's shown. */
    val heights: DoubleArray,
    val lengthM: Double,
    /** The distance the rider asked to see. */
    val wantM: Double,
    val end: End,
    val roadName: String?,
    /**
     * How far ahead the first junction is that the prediction went through on a guess. Up to here
     * the road is known; past it, it is the likeliest way on. Null when nothing in view is a guess.
     */
    val guessFromM: Double? = null,
    /** Heights already ridden, nearest first, one every [stepM]. */
    val behind: DoubleArray = DoubleArray(0),
    /**
     * The coloured blocks in view, laid at fixed places along the road by the [Tracker], so their
     * colours stay put as you ride. Null for one made by hand, which cuts them from where you are.
     */
    val fixedBlocks: List<Block>? = null,
) {
    companion object {
        /** The view is coloured in this many blocks, so a block is a twentieth of the distance shown. */
        const val BLOCKS = 20
    }

    /** The blocks the field colours by grade. */
    val colourBlocks: List<Block> by lazy { fixedBlocks ?: blocks(wantM / BLOCKS) }

    /** The average grade from the rider to the highest point in view; when the road only drops, to the lowest point instead, so it comes out negative. */
    fun averageToTopPct(): Double {
        val high = heights.indices.maxByOrNull { heights[it] } ?: return 0.0
        if (heights[high] >= heights[0] + 2.0 && distAt(high) > 0.0) return (heights[high] - heights[0]) / distAt(high) * 100.0
        val low = heights.indices.minByOrNull { heights[it] } ?: return 0.0
        if (heights[low] <= heights[0] - 2.0 && distAt(low) > 0.0) return (heights[low] - heights[0]) / distAt(low) * 100.0
        return 0.0
    }

    /** How far along the road each height is. */
    fun distAt(i: Int): Double = if (i == heights.size - 1) lengthM else i * stepM

    /** Metres of climbing in view. */
    val climbM: Double
        get() {
            var up = 0.0
            for (i in 1 until heights.size) if (heights[i] > heights[i - 1]) up += heights[i] - heights[i - 1]
            return up
        }

    fun heightAt(distM: Double): Double = heightAlong(heights, stepM, lengthM, distM)

    /** The road cut into blocks of a set length, each with its grade. A short last block is kept. */
    fun blocks(blockM: Double): List<Block> {
        val out = ArrayList<Block>()
        var from = 0.0
        while (from < lengthM - 1.0) {
            val to = min(from + blockM, lengthM)
            out += Block(from, to, (heightAt(to) - heightAt(from)) / (to - from) * 100.0)
            from = to
        }
        return out
    }

    /**
     * The steepest coloured block in view, as a grade. A block counts when at least half of it is
     * in view, and so does the one you're on when more follow, because its grade is its whole
     * length's. Null on a road too short to have one.
     */
    fun steepestPct(): Double? {
        val half = wantM / BLOCKS / 2
        val all = colourBlocks
        return all.filterIndexed { i, b -> b.toM - b.fromM >= half || (i == 0 && all.size > 1) }.maxOfOrNull { it.gradePct }
    }
}
