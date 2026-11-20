package au.com.fjellconsulting.roadahead.core

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** What the field has to show after a GPS fix. */
sealed interface Look {
    /** Not moving yet, so there's no way to tell which direction is "ahead". */
    data object Waiting : Look

    /** No mapped road or path within reach. */
    data object NoRoad : Look

    /** No map file covers this spot. */
    data object NoMap : Look

    /** The road is known but its elevation file isn't on the device. */
    data class NoHeights(val file: String) : Look

    class Road(val ahead: Ahead) : Look
}

/**
 * Keeps the prediction going from one GPS fix to the next. It holds the predicted road while you
 * stay on it and only works a new one out when you leave it, or when the road in hand runs short.
 */
class Tracker(
    private val roads: RoadSource,
    heights: HeightSource,
    private val rules: Rules = Rules(),
) {
    private val snap = RoadSnap(rules)
    private val follow = RoadFollow(roads, rules)
    private val builder = ProfileBuilder(heights, rules)
    private val trail = Trail()

    private var anchor: Pt? = null
    private var course: Double? = null
    private var path: Path? = null
    private var profile: Profile? = null
    private var posM = 0.0

    /**
     * How far along the roads followed so far the prediction in hand starts. The coloured blocks
     * are laid from here, so their edges stay at the same places on the road from fix to fix.
     */
    private var baseM = 0.0

    /** Each block's grade once it is known, by its number along the road. See [colourBlocks]. */
    private val grades = HashMap<Long, Double>()
    private var gradesBlockM = 0.0

    /**
     * The rider's choice at a T-junction or a fork: carry on along the likeliest road, or the
     * leftmost or rightmost, so the picture always reaches the distance asked for, or stop there.
     * Changing it drops the prediction in hand.
     */
    var junction: AtJunction = AtJunction.STOP
        set(value) {
            if (field != value) {
                field = value
                forget()
            }
        }

    /** How many times a new road has been worked out. For the status screen and the tests. */
    var predictions = 0
        private set

    /** Why the last new road was worked out. For the status screen and the replay tool. */
    var why = ""
        private set

    /** The predicted road in hand, for the status screen and the replay tool. */
    val currentPath: Path? get() = path
    val positionM: Double get() = posM

    /** How far along the roads followed you are, counted from where the current road began. For the replay tool. */
    val alongM: Double get() = baseM + posM

    /**
     * @param orientation the Karoo's own heading, used only until you've moved far enough for the
     *   fixes themselves to give a direction.
     * @param wantM how far ahead the rider has asked to see.
     */
    fun update(p: Pt, orientation: Double?, wantM: Double, behindM: Double = 0.0): Look {
        val a = anchor
        if (a == null) {
            anchor = p
        } else if (Geo.metres(a, p) >= COURSE_M) {
            course = Geo.bearing(a, p)
            anchor = p
        }
        val heading = course ?: orientation ?: return Look.Waiting

        val current = path
        val held = hold(p, heading)
        // A lap of a circuit runs on too, once you're on the circuit: without this its end crept
        // towards you with no label.
        val short = current != null && current.lengthM - posM < wantM &&
            (current.end == End.FAR_ENOUGH || (current.end == End.LOOP && posM > current.loopFromM))
        // Stopped at the lights or the cafe, GPS wanders. Nothing new is worked out until you move.
        val moved = predictedAt?.let { Geo.metres(it, p) >= COURSE_M } ?: true
        if ((!held || short) && (moved || current == null)) {
            why = if (current == null) "start" else if (held) "ran short" else lost
            predictedAt = p
            val near = roads.near(p, rules.snapRadiusM + 10.0) ?: return forget(Look.NoMap)
            val snapped = snap.snap(near, p, heading) ?: return forget(Look.NoRoad)
            val next = follow.follow(snapped.spot, wantM + EXTRA_M, junction)
            predictions++
            path = next
            // Running on along the same road, the blocks keep their places and their colours.
            // On a new road they start again.
            if (held && current != null) {
                baseM += posM
            } else {
                baseM = 0.0
                grades.clear()
            }
            posM = 0.0
            profile = builder.build(next)
        }
        val prof = profile ?: return Look.NoHeights(HgtFile.nameFor(p))
        trail.add(p, prof.heightAt(posM))
        return Look.Road(slice(prof, wantM, behindM))
    }

    /** Drop the prediction in hand, so the next fix works out a new one. For when new map or elevation files arrive. */
    fun forget() {
        path = null
        profile = null
        predictedAt = null
        grades.clear()
    }

    private fun forget(look: Look): Look {
        path = null
        profile = null
        grades.clear()
        return look
    }

    /** True when the fix is still on the predicted road and heading along it. Moves the position up. */
    private var lost = ""
    private var predictedAt: Pt? = null

    private fun hold(p: Pt, heading: Double): Boolean {
        lost = "off the road"
        val pts = path?.points ?: return false
        // The nearest stretch of the predicted road that runs the way you're heading. On a
        // switchback the nearest stretch of all can be the next leg up the hill, going the other way.
        var best: Landing? = null
        var bestSeg = -1
        var nearAny = false
        for (i in 0 until pts.size - 1) {
            if (pts[i + 1].distM < posM - BEHIND_M) continue
            if (pts[i].distM > posM + AHEAD_M) break
            val land = Geo.land(p, pts[i].pt, pts[i + 1].pt)
            if (land.offM > rules.holdRadiusM) continue
            nearAny = true
            if (Geo.metres(pts[i].pt, pts[i + 1].pt) < 0.01) continue
            if (Geo.turn(heading, Geo.bearing(pts[i].pt, pts[i + 1].pt)) > rules.holdMaxAngle) continue
            if (best == null || land.offM < best.offM) {
                best = land
                bestSeg = i
            }
        }
        if (best == null) {
            if (nearAny) lost = "heading across it"
            return false
        }
        val a = pts[bestSeg]
        val b = pts[bestSeg + 1]
        // At the very end of the road in hand there's nothing left to hold on to.
        if (bestSeg == pts.size - 2 && best.t >= 1.0 && best.offM > rules.joinM) {
            lost = "past its end"
            return false
        }
        posM = a.distM + (b.distM - a.distM) * best.t
        return true
    }

    private fun slice(prof: Profile, wantM: Double, behindM: Double): Ahead {
        val left = prof.lengthM - posM
        val shown = min(wantM, left).coerceAtLeast(0.0)
        val dists = sampleDists(shown, prof.stepM)
        val heights = DoubleArray(dists.size) { prof.heightAt(posM + dists[it]) }
        val end = if (left > wantM + 0.5) End.FAR_ENOUGH else prof.end
        // The first junction still ahead that was taken on a guess, if it is in view.
        val guess = prof.guessesM.firstOrNull { it > posM + 1.0 }?.minus(posM)?.takeIf { it < shown - 1.0 }
        return Ahead(prof.stepM, heights, shown, wantM, end, prof.roadName, guess,
            if (behindM > 0.0) trail.behind(prof.stepM, behindM) else DoubleArray(0), colourBlocks(prof, wantM / Ahead.BLOCKS, shown))
    }

    /**
     * The coloured blocks in view. Their edges sit at fixed places along the road, not at fixed
     * distances from the rider, and each block's grade is worked out once over its whole length
     * and kept. So a block keeps its colour as you ride towards it and through it, even when the
     * prediction is worked out again further along the same road. The grades start again on a
     * new road, and when the distance shown, and so the length of a block, changes.
     */
    private fun colourBlocks(prof: Profile, blockM: Double, shown: Double): List<Block> {
        if (blockM != gradesBlockM) {
            grades.clear()
            gradesBlockM = blockM
        }
        val here = baseM + posM
        val first = floor(here / blockM).toLong()
        grades.keys.removeIf { it < first }
        val out = ArrayList<Block>()
        var k = first
        while (k * blockM < here + shown - 0.5) {
            // The block's ends along the profile in hand.
            val from = k * blockM - baseM
            val to = from + blockM
            val grade = grades[k] ?: run {
                val a = from.coerceAtLeast(0.0)
                val b = min(to, prof.lengthM)
                val g = if (b - a < 1.0) 0.0 else (prof.heightAt(b) - prof.heightAt(a)) / (b - a) * 100.0
                // Near the far end of a prediction that runs on, the smoothing still reaches past
                // the end and the heights there can move a little, so a grade there isn't kept yet.
                if (to <= prof.lengthM - SETTLED_M || prof.end != End.FAR_ENOUGH) grades[k] = g
                g
            }
            out += Block(max(from - posM, 0.0), min(to - posM, shown), grade)
            k++
        }
        return out
    }

    private companion object {
        /** Direction comes from fixes at least this far apart, so GPS jitter at a standstill doesn't spin it. */
        const val COURSE_M = 8.0
        /** A new prediction reaches this far past what's asked for, so it lasts a while. */
        const val EXTRA_M = 1500.0
        const val BEHIND_M = 60.0
        const val AHEAD_M = 400.0
        /** Heights this far from the end of a prediction that runs on are final: past the smoothing's reach. */
        const val SETTLED_M = 150.0
    }
}
