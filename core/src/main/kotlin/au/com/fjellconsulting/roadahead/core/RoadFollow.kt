package au.com.fjellconsulting.roadahead.core

/** One point of the predicted road. `flat` marks a stretch on a bridge or in a tunnel. */
class PathPoint(val pt: Pt, val distM: Double, val flat: Boolean)

/** Why the prediction stops where it does. */
enum class End {
    /** It reached the distance asked for. */
    FAR_ENOUGH,
    /** A T-junction or a fork: you have to choose. */
    TURN,
    /** A dead end. */
    ROAD_ENDS,
    /** The road comes back to where the prediction started. */
    LOOP,
    /** The map stops here. */
    NO_MAP,
    /** The road carries on into an area with no elevation file. */
    NO_HEIGHTS,
}

class Path(
    val points: List<PathPoint>,
    val end: End,
    /** The name of the road the prediction starts on, and what kind of way it is. */
    val roadName: String?,
    val roadKind: String = "",
) {
    val lengthM: Double get() = points.last().distM

    /** The position a distance along the path. */
    fun at(distM: Double): Pt {
        if (distM <= 0.0) return points.first().pt
        for (i in 1 until points.size) {
            val b = points[i]
            if (b.distM >= distM) {
                val a = points[i - 1]
                val span = b.distM - a.distM
                return if (span <= 0.0) b.pt else Geo.between(a.pt, b.pt, (distM - a.distM) / span)
            }
        }
        return points.last().pt
    }

    /** Whether the path is on a bridge or in a tunnel at a distance along it. */
    fun flatAt(distM: Double): Boolean {
        for (i in 1 until points.size) if (points[i].distM >= distM) return points[i].flat
        return false
    }
}

/**
 * Rule 2: follow the road you're on. At the end of a piece it carries on along the piece with
 * the same name, otherwise the one that runs straightest, otherwise the only way onward. With none
 * of those it stops, because you have to turn. A roundabout takes the exit that carries on the
 * way you came in.
 */
class RoadFollow(private val source: RoadSource, private val rules: Rules = Rules()) {

    private class Option(val spot: Spot, val bearing: Double, val turn: Double) {
        val road: Road get() = spot.road
    }

    /** One direction a road leaves a point in, whether or not you're allowed to ride it. */
    private class Arm(val bearing: Double, val roadClass: RoadClass)

    /**
     * What meets at a point: the ways you could ride on, and every arm of every road that leaves
     * it, other than the one you arrived along.
     */
    private class Joins(val options: List<Option>, val arms: List<Arm>)

    fun follow(start: Spot, maxM: Double): Path {
        val out = ArrayList<PathPoint>()
        out += PathPoint(start.at, 0.0, false)
        val name = start.road.label
        val kind = start.road.kind
        val seen = HashSet<Long>()
        var spot = start
        var dist = 0.0

        while (true) {
            if (!seen.add(spot.road.key * 2 + (if (spot.forward) 1 else 0)) || seen.size > rules.maxPieces) {
                return Path(out, End.LOOP, name, kind)
            }
            // Ride this piece to its end.
            val flat = spot.road.bridge || spot.road.tunnel
            var prev = out.last().pt
            for (pt in spot.ahead()) {
                val d = Geo.metres(prev, pt)
                if (d < 0.01) continue
                if (dist + d >= maxM) {
                    out += PathPoint(Geo.between(prev, pt, (maxM - dist) / d), maxM, flat)
                    return Path(out, End.FAR_ENOUGH, name, kind)
                }
                dist += d
                out += PathPoint(pt, dist, flat)
                prev = pt
            }

            val arriving = arrivalBearing(out) ?: spot.bearing(rules.bearingLookM) ?: return Path(out, End.ROAD_ENDS, name, kind)
            val met = joins(prev, spot.road, arriving) ?: return Path(out, End.NO_MAP, name, kind)
            var next = choose(spot.road, met)
                ?: return Path(out, when {
                    met.options.any { it.turn <= rules.maxTurn } -> End.TURN
                    // A circuit mapped as one closed piece: a lap is as far as it goes.
                    Geo.metres(spot.road.pts.first(), spot.road.pts.last()) < 0.5 -> End.LOOP
                    else -> End.ROAD_ENDS
                }, name, kind)

            if (next.road.roundabout) {
                val ring = roundabout(next.spot, spot.road, arriving) ?: return Path(out, End.TURN, name, kind)
                for (pt in ring.first) {
                    val d = Geo.metres(out.last().pt, pt)
                    if (d < 0.01) continue
                    dist += d
                    out += PathPoint(pt, dist, false)
                }
                next = ring.second
                if (dist >= maxM) return Path(out, End.FAR_ENOUGH, name, kind)
            }
            spot = next.spot
        }
    }

    /** The direction of travel over the last few metres of the path so far. */
    private fun arrivalBearing(out: List<PathPoint>): Double? {
        val last = out.last()
        for (i in out.size - 2 downTo 0) {
            if (last.distM - out[i].distM >= rules.bearingLookM || i == 0) {
                return if (Geo.metres(out[i].pt, last.pt) < 1.0) null else Geo.bearing(out[i].pt, last.pt)
            }
        }
        return null
    }

    /** Every way of carrying on from a point: each piece that touches it, in each direction it allows. */
    private fun joins(at: Pt, current: Road, arriving: Double): Joins? {
        val roads = source.near(at, 30.0) ?: return null
        val marginLat = rules.joinM / 111_320.0
        val marginLng = marginLat / Math.cos(Math.toRadians(at.lat))
        val found = ArrayList<Option>()
        val arms = ArrayList<Arm>()
        val back = (arriving + 180.0) % 360.0
        val counted = HashSet<Long>()
        for (road in roads) {
            if (road.key == current.key || !counted.add(road.key)) continue
            if (!road.mightBeNear(at, marginLat, marginLng)) continue
            var seg = -1
            var land: Landing? = null
            for (i in 0 until road.pts.size - 1) {
                val l = Geo.land(at, road.pts[i], road.pts[i + 1])
                if (land == null || l.offM < land.offM) {
                    land = l
                    seg = i
                }
            }
            if (land == null || land.offM > rules.joinM) continue
            for (forward in listOf(true, false)) {
                val spot = Spot(road, seg, land.t, forward)
                val bearing = spot.bearing(rules.bearingLookM) ?: continue
                // Pieces of one road cut at a map tile's edge overlap, so the same arm can turn up twice.
                if (Geo.turn(bearing, back) > 25.0 && arms.none { Geo.turn(it.bearing, bearing) <= 15.0 && it.roadClass == road.roadClass }) {
                    arms += Arm(bearing, road.roadClass)
                }
                if (!forward && road.oneway) continue
                found += Option(spot, bearing, Geo.turn(arriving, bearing))
            }
        }
        return Joins(found, arms)
    }

    private fun choose(current: Road, met: Joins): Option? {
        val onward = met.options.filter { it.turn <= rules.maxTurn }
        if (onward.isEmpty()) return null
        // On a road, a driveway or footpath straight across a T-junction is not the way onward.
        // They only count when no road meets this one at all.
        if (current.roadClass == RoadClass.ROAD) {
            val roadArms = met.arms.filter { it.roadClass == RoadClass.ROAD }
            if (roadArms.isNotEmpty()) return pick(current, onward.filter { it.road.roadClass == RoadClass.ROAD }, roadArms)
        }
        return pick(current, onward, met.arms)
    }

    private fun pick(current: Road, pool: List<Option>, arms: List<Arm>): Option? {
        if (pool.isEmpty()) return null
        // 1. The same road, by name.
        val same = pool.filter { current.sameRoadAs(it.road) }.sortedBy { it.turn }
        if (same.isNotEmpty()) {
            val best = same[0]
            // The same name going both ways at a T is still a forced turn.
            val rival = same.firstOrNull { Geo.turn(it.bearing, best.bearing) > 20.0 }
            val forked = best.turn > rules.straightAngle && rival != null && rival.turn - best.turn < 20.0
            return if (forked) null else best
        }
        // 2. Straight on.
        val straight = pool.filter { it.turn <= rules.straightAngle }
            .minByOrNull { it.turn + (if (it.road.kind != current.kind) 10.0 else 0.0) }
        if (straight != null) return straight
        // 3. A bend where the name changes: the road leaves this point in one direction only.
        // Any second arm makes it a junction, even one you can't ride into. That is how a T onto
        // a divided road looks on the map: one carriageway to merge onto, with the turn across
        // the median drawn somewhere further along.
        return if (arms.size == 1) pool[0] else null
    }

    /**
     * Ride round a roundabout from where you join it. Returns the points along the ring up to the
     * chosen exit, and the exit. The exit is the one with your road's name, otherwise the one that
     * best carries on the way you came in.
     */
    private fun roundabout(entry: Spot, from: Road, arriving: Double): Pair<List<Pt>, Option>? {
        class Exit(val index: Int, val option: Option)

        val ring = ArrayList<Pt>()
        ring += entry.at
        val exits = ArrayList<Exit>()
        var spot = entry
        var ringM = 0.0
        var pieces = 0
        walk@ while (pieces++ < 12) {
            for (pt in spot.ahead()) {
                val d = Geo.metres(ring.last(), pt)
                if (d < 0.01) continue
                ringM += d
                ring += pt
                val here = joins(pt, spot.road, arriving)?.options ?: return null
                for (o in here) if (!o.road.roundabout && o.turn <= 150.0) exits += Exit(ring.size - 1, o)
                if (ringM > 10.0 && Geo.metres(pt, entry.at) < 3.0) break@walk
                if (ringM > 500.0) break@walk
            }
            val onward = joins(ring.last(), spot.road, arriving)?.options ?: return null
            spot = onward.firstOrNull { it.road.roundabout && it.spot.forward }?.spot ?: break
        }
        if (exits.isEmpty()) return null
        val pool = if (from.roadClass == RoadClass.ROAD) {
            exits.filter { it.option.road.roadClass == RoadClass.ROAD }.ifEmpty { exits }
        } else exits
        val chosen = pool.filter { from.sameRoadAs(it.option.road) }.minByOrNull { it.option.turn }
            ?: pool.minByOrNull { it.option.turn }
            ?: return null
        return Pair(ring.subList(1, chosen.index + 1), chosen.option)
    }
}
