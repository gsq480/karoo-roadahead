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

/**
 * What the picture does where the road splits and no rule says which way: a T-junction, or a fork
 * with no road carrying straight on. Every choice but [STOP] carries on, so the picture always
 * reaches the distance asked for.
 */
enum class AtJunction(val label: String) {
    /** The piece with the same name, otherwise the smallest turn. */
    LIKELIEST("Carry on along the likeliest road"),
    /** The leftmost way on: a left turn if there is one, then straight on, then right. */
    LEFT_FIRST("Carry on: left, then straight on, then right"),
    /** The rightmost way on: a right turn if there is one, then straight on, then left. */
    RIGHT_FIRST("Carry on: right, then straight on, then left"),
    STOP("Stop the picture there"),
}

class Path(
    val points: List<PathPoint>,
    val end: End,
    /** The name of the road the prediction starts on, and what kind of way it is. */
    val roadName: String?,
    val roadKind: String = "",
    /**
     * How far along each junction is that the prediction went through on a guess, nearest first.
     * Up to the first of these the road is known; past it, it is the likeliest way on.
     */
    val guessesM: List<Double> = emptyList(),
    /**
     * Where the circuit that a [End.LOOP] path goes round begins, as a distance along it. Past it
     * you're riding the circuit itself, and a new prediction from where you are shows more of it.
     * Short of it, a new prediction would only end in the same place.
     */
    val loopFromM: Double = 0.0,
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
 *
 * Unless told to [AtJunction.STOP], it doesn't stop at a T-junction or a fork. It takes the way on
 * the rider picked (the likeliest, or the leftmost or rightmost) and notes where it guessed, so
 * the picture always reaches the distance asked for. Where the way it took stops on the map just
 * past the junction, it goes back and guesses another way on there.
 */
class RoadFollow(private val source: RoadSource, private val rules: Rules = Rules()) {

    /** One way on. [side] is the turn with its direction: below zero to the left, above to the right. */
    private class Option(val spot: Spot, val bearing: Double, val turn: Double, val side: Double) {
        val road: Road get() = spot.road
    }

    /** One direction a road leaves a point in, whether or not you're allowed to ride it. */
    private class Arm(val bearing: Double, val roadClass: RoadClass)

    /**
     * What meets at a point: the ways you could ride on, and every arm of every road that leaves
     * it, other than the one you arrived along.
     */
    private class Joins(val options: List<Option>, val arms: List<Arm>)

    /**
     * A junction passed with other ways on left untaken: how long the path was there, and what
     * it needs to take one of the others instead.
     */
    private class Fork(val outSize: Int, val distM: Double, val guessCount: Int, val from: Road, val arriving: Double, val others: Joins)

    fun follow(start: Spot, maxM: Double, at: AtJunction = AtJunction.STOP): Path {
        val out = ArrayList<PathPoint>()
        val guesses = ArrayList<Double>()
        var loopFrom = 0.0
        fun done(end: End) = Path(out, end, name(start), start.road.kind, guesses, loopFrom)
        out += PathPoint(start.at, 0.0, false)
        // Each piece ridden so far, by piece and direction: where the prediction joined it, and
        // how far along the path that was.
        val joined = HashMap<Long, Pair<Spot, Double>>()
        var spot = start
        var dist = 0.0
        // The last junction passed where another way on was left untaken.
        var fork: Fork? = null

        // Adds points to the path. True once it reaches the distance asked for.
        fun ride(pts: List<Pt>, flat: Boolean): Boolean {
            var prev = out.last().pt
            for (pt in pts) {
                val d = Geo.metres(prev, pt)
                if (d < 0.01) continue
                if (dist + d >= maxM) {
                    out += PathPoint(Geo.between(prev, pt, (maxM - dist) / d), maxM, flat)
                    return true
                }
                dist += d
                out += PathPoint(pt, dist, flat)
                prev = pt
            }
            return false
        }

        while (true) {
            val flat = spot.road.bridge || spot.road.tunnel
            val key = spot.road.key * 2 + (if (spot.forward) 1 else 0)
            val first = joined[key]
            if (first != null || joined.size >= rules.maxPieces) {
                // Back on a piece already ridden: the road comes round in a circuit. Finish the lap
                // to where the prediction first joined this piece, so the picture holds the whole
                // circuit once and not just up to the start of a piece.
                loopFrom = first?.second ?: dist
                val lap = first?.let { lapTo(spot, it.first) }
                if (lap != null && ride(lap, flat)) return done(End.FAR_ENOUGH)
                return done(End.LOOP)
            }
            joined[key] = Pair(spot, dist)
            // Ride this piece to its end.
            if (ride(spot.ahead(), flat)) return done(End.FAR_ENOUGH)
            val prev = out.last().pt

            var from = spot.road
            var arriving = arrivalBearing(out) ?: spot.bearing(rules.bearingLookM) ?: return done(End.ROAD_ENDS)
            val met = joins(prev, from, arriving) ?: return done(End.NO_MAP)
            val guessesBefore = guesses.size
            var next = choose(from, met)
                ?: (if (at != AtJunction.STOP) guess(from, met, at)?.also { guesses += dist } else null)
            if (next != null) {
                val taken = next.road.key
                val others = met.options.filter {
                    it.road.key != taken && (it.turn <= rules.maxTurn || (it.turn <= rules.sharpTurn && !from.sameRoadAs(it.road)))
                }
                if (others.isNotEmpty()) fork = Fork(out.size, dist, guessesBefore, from, arriving, Joins(others, emptyList()))
            } else {
                if (met.options.any { it.turn <= rules.maxTurn }) return done(End.TURN)
                // A circuit mapped as one closed piece: a lap is as far as it goes.
                if (Geo.metres(from.pts.first(), from.pts.last()) < 0.5) {
                    loopFrom = joined.getValue(key).second
                    return done(End.LOOP)
                }
                // The Karoo's map leaves out service roads, so a road can stop on the map where it
                // really carries on. That gets guessed past; anything else is a dead end.
                val back = fork?.takeIf { dist - it.distM <= rules.gapM }
                if (back != null) {
                    // The way taken at the last junction stops within a short gap of it, where it
                    // carries on as a service road. Go back and take another way on there, even a
                    // sharp turn onto another road: Eucumbene Drive bends 111 degrees into
                    // Hindmarsh Drive, and the only way on under the usual limit is a stub.
                    fork = null
                    while (out.size > back.outSize) out.removeAt(out.lastIndex)
                    while (guesses.size > back.guessCount) guesses.removeAt(guesses.lastIndex)
                    dist = back.distM
                    if (at == AtJunction.STOP) return done(End.TURN)
                    from = back.from
                    arriving = back.arriving
                    next = guess(from, back.others, at, rules.sharpTurn) ?: return done(End.ROAD_ENDS)
                    guesses += dist
                } else {
                    return done(End.ROAD_ENDS)
                }
            }

            if (next.road.roundabout) {
                val ring = roundabout(next.spot, from, arriving) ?: return done(End.TURN)
                for (pt in ring.first) {
                    val d = Geo.metres(out.last().pt, pt)
                    if (d < 0.01) continue
                    dist += d
                    out += PathPoint(pt, dist, false)
                }
                next = ring.second
                if (dist >= maxM) return done(End.FAR_ENOUGH)
            }
            spot = next.spot
        }
    }

    private fun name(start: Spot): String? = start.road.label

    /**
     * The points from [back], where the prediction comes back onto a piece, to [first], where it
     * first joined that piece going the same way. Null when it comes back on past that place,
     * so there is no stretch of the lap left to add.
     */
    private fun lapTo(back: Spot, first: Spot): List<Pt>? {
        val segs = if (first.forward) first.seg - back.seg else back.seg - first.seg
        val behind = segs > 0 || (segs == 0 && (if (first.forward) back.t <= first.t else back.t >= first.t))
        return if (behind) back.ahead().take(segs) + first.at else null
    }

    /**
     * No rule settles it: a T-junction or a fork. Whichever way the rider picked, a road wins over
     * a driveway or path when you're on a road. The likeliest way on is the piece with the same
     * name, otherwise the smallest turn. Left first takes the leftmost way on, so a left turn when
     * there is one, then straight on, then right; right first is the mirror of it.
     */
    private fun guess(current: Road, met: Joins, at: AtJunction, maxTurn: Double = rules.maxTurn): Option? {
        val onward = met.options.filter { it.turn <= maxTurn }
        val pool = if (current.roadClass == RoadClass.ROAD) onward.filter { it.road.roadClass == RoadClass.ROAD }.ifEmpty { onward } else onward
        return when (at) {
            AtJunction.LEFT_FIRST -> pool.minByOrNull { it.side }
            AtJunction.RIGHT_FIRST -> pool.maxByOrNull { it.side }
            else -> pool.minByOrNull {
                it.turn + (if (current.sameRoadAs(it.road)) -1000.0 else 0.0) + (if (it.road.kind != current.kind) 10.0 else 0.0)
            }
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
                found += Option(spot, bearing, Geo.turn(arriving, bearing), (bearing - arriving + 540.0) % 360.0 - 180.0)
            }
        }
        return Joins(found, arms)
    }

    private fun choose(current: Road, met: Joins): Option? {
        val onward = met.options.filter { it.turn <= rules.maxTurn }
        if (onward.isEmpty()) return null
        // Arriving at a roundabout, you go round it. Which exit is worked out on the way round, so
        // the way back round the ring, which you can't ride, doesn't make this a junction.
        if (!current.roundabout) onward.firstOrNull { it.road.roundabout }?.let { return it }
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

        val centre = entry.road.pts.let { pts -> Pt(pts.sumOf { it.lat } / pts.size, pts.sumOf { it.lng } / pts.size) }
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
                // Back where you joined it: no way out there is one you'd take.
                if (ringM > 10.0 && Geo.metres(pt, entry.at) < 3.0) break@walk
                val here = joins(pt, spot.road, arriving)?.options ?: return null
                for (o in here) {
                    // A way out leads away from the ring. The road you came in on, which can pass
                    // close by the ring just short of where you joined it, is not one.
                    if (o.road.roundabout || o.turn > 150.0 || o.road.key == from.key) continue
                    if (Geo.metres(centre, Geo.offset(pt, o.bearing, 10.0)) <= Geo.metres(centre, pt)) continue
                    exits += Exit(ring.size - 1, o)
                }
                if (ringM > 500.0) break@walk
            }
            // A roundabout drawn as one closed loop carries on round from where it was drawn from.
            // Entered part way round, the walk reaches the loop's last point long before the way
            // back to the entry, and the exits in between are only found by going on.
            spot = if (Geo.metres(spot.road.pts.first(), spot.road.pts.last()) < 0.5) {
                Spot(spot.road, 0, 0.0, true)
            } else {
                val onward = joins(ring.last(), spot.road, arriving)?.options ?: return null
                onward.firstOrNull { it.road.roundabout && it.spot.forward }?.spot ?: break
            }
        }
        if (exits.isEmpty()) return null
        val pool = if (from.roadClass == RoadClass.ROAD) {
            exits.filter { it.option.road.roadClass == RoadClass.ROAD }.ifEmpty { exits }
        } else exits
        // Your road's name doesn't win an exit that takes you back the way you came: where a road
        // meets the ring by a pair of one-way slip lanes, the lane out has the same name as the
        // lane in, and the R125's roundabout at Dunshaughlin sent the picture round and back out.
        val chosen = pool.filter { from.sameRoadAs(it.option.road) && it.option.turn <= rules.backTurn }.minByOrNull { it.option.turn }
            ?: pool.minByOrNull { it.option.turn }
            ?: return null
        return Pair(ring.subList(1, chosen.index + 1), chosen.option)
    }
}
