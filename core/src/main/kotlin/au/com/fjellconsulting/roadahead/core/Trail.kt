package au.com.fjellconsulting.roadahead.core

/**
 * The road already ridden, as heights along the way, so the field can show it greyed behind the
 * rider. Kept apart from the prediction: nothing here changes which road is shown ahead.
 */
class Trail(
    /** Minimum distance between recorded fixes, to ignore GPS wander. */
    private val stepM: Double = 10.0,
    /** Distance to keep behind the newest recorded point. */
    private val keepM: Double = 10_000.0,
    /** A larger jump clears the trail and starts it again. */
    private val jumpM: Double = 300.0,
) {
    private data class Point(val p: Pt, val height: Double, val distance: Double)
    private val points = ArrayList<Point>()

    /** Record a fix and the road's height there, if the rider has moved far enough since the last. */
    fun add(p: Pt, height: Double) {
        val last = points.lastOrNull()
        if (last == null) {
            points += Point(p, height, 0.0)
            return
        }
        val moved = Geo.metres(last.p, p)
        if (moved < stepM) return
        if (moved > jumpM) {
            clear()
            points += Point(p, height, 0.0)
            return
        }
        val next = last.distance + moved
        points += Point(p, height, next)
        while (points.size > 2 && next - points[1].distance > keepM) points.removeAt(0)
    }

    fun clear() = points.clear()

    /**
     * Heights every [sampleM] back from the newest point, nearest first, out to [maxM] or as far
     * as the trail goes, whichever is less.
     */
    fun behind(sampleM: Double, maxM: Double): DoubleArray {
        if (sampleM <= 0.0 || maxM <= 0.0 || points.size < 2) return DoubleArray(0)
        val available = points.last().distance - points.first().distance
        val count = kotlin.math.floor(minOf(maxM, available) / sampleM).toInt()
        return DoubleArray(count) { i ->
            val target = points.last().distance - (i + 1) * sampleM
            var j = points.lastIndex
            while (j > 1 && points[j - 1].distance > target) j--
            val a = points[j - 1]
            val b = points[j]
            val t = ((target - a.distance) / (b.distance - a.distance)).coerceIn(0.0, 1.0)
            a.height + (b.height - a.height) * t
        }
    }
}
