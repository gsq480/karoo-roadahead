package au.com.fjellconsulting.roadahead.core

/** A place on a piece of road, and which way along it you're travelling. */
data class Spot(val road: Road, val seg: Int, val t: Double, val forward: Boolean) {
    val at: Pt get() = Geo.between(road.pts[seg], road.pts[seg + 1], t)

    /** The piece's remaining points in the direction of travel, nearest first. */
    fun ahead(): List<Pt> =
        if (forward) road.pts.subList(seg + 1, road.pts.size) else road.pts.subList(0, seg + 1).asReversed()

    /** Metres of this piece still in front. */
    fun remainingM(): Double {
        var from = at
        var m = 0.0
        for (pt in ahead()) {
            m += Geo.metres(from, pt)
            from = pt
        }
        return m
    }

    /** The direction the piece heads from here, read over a short stretch. Null at its very end. */
    fun bearing(lookM: Double): Double? {
        val start = at
        var from = start
        var m = 0.0
        var last: Pt? = null
        for (pt in ahead()) {
            val d = Geo.metres(from, pt)
            if (d < 0.01) continue
            m += d
            last = pt
            from = pt
            if (m >= lookM) break
        }
        if (last == null || m < 1.0) return null
        return Geo.bearing(start, last)
    }
}

/** A GPS fix placed on a road. */
data class Snapped(val spot: Spot, val offM: Double)

/**
 * Rule 1: find the road you're on. The nearest piece that runs the way you're heading wins, with
 * a footpath or driveway losing to a road beside it.
 */
class RoadSnap(private val rules: Rules = Rules()) {
    fun snap(roads: List<Road>, p: Pt, course: Double): Snapped? {
        var best: Snapped? = null
        var bestScore = Double.MAX_VALUE
        val marginLat = rules.snapRadiusM / 111_320.0
        val marginLng = marginLat / Math.cos(Math.toRadians(p.lat))
        for (road in roads) {
            if (!road.mightBeNear(p, marginLat, marginLng)) continue
            val minor = if (road.roadClass != RoadClass.ROAD && road.kind != "cycleway") rules.snapMinorCostM else 0.0
            for (i in 0 until road.pts.size - 1) {
                val a = road.pts[i]
                val b = road.pts[i + 1]
                if (Geo.metres(a, b) < 0.01) continue
                val land = Geo.land(p, a, b)
                if (land.offM > rules.snapRadiusM) continue
                val off = Geo.turn(course, Geo.bearing(a, b))
                val forward = off <= 90.0
                if (!forward && road.oneway) continue
                val angle = if (forward) off else 180.0 - off
                if (angle > rules.snapMaxAngle) continue
                val score = land.offM + angle * rules.snapAngleCostM + minor
                if (score < bestScore) {
                    bestScore = score
                    best = Snapped(Spot(road, i, land.t, forward), land.offM)
                }
            }
        }
        return best
    }
}
