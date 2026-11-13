package au.com.fjellconsulting.roadahead.replay

import au.com.fjellconsulting.roadahead.core.Ahead
import au.com.fjellconsulting.roadahead.core.End
import au.com.fjellconsulting.roadahead.core.Geo
import au.com.fjellconsulting.roadahead.core.HgtFolder
import au.com.fjellconsulting.roadahead.core.Look
import au.com.fjellconsulting.roadahead.core.Path
import au.com.fjellconsulting.roadahead.core.Pt
import au.com.fjellconsulting.roadahead.core.Tracker
import java.io.File
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Run a recorded ride through the engine, fix by fix, and score what the field would have shown
 * against what the ride then did. Mac only.
 *
 *   replay <roads.tsv> <folder of .hgt files> <ride.csv> [--want 2000] [--cut] [--moments out.json]
 *
 * The ride file is `time_s,lat,lng,altitude_m` a line. `--cut` chops the roads into map tiles
 * first, the way the Karoo's map files hand them over.
 */
fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println("usage: replay <roads.tsv> <hgt folder> <ride.csv> [--want metres] [--cut] [--moments out.json]")
        exitProcess(2)
    }
    fun flag(name: String) = args.indexOf(name).let { if (it >= 0 && it + 1 < args.size) args[it + 1] else null }
    val wantM = flag("--want")?.toDouble() ?: 2000.0
    val cut = "--cut" in args

    var roads = readRoads(File(args[0]))
    if (cut) roads = cutIntoTiles(roads)
    val tracker = Tracker(GridRoads(roads), HgtFolder(File(args[1])))

    class Fix(val pt: Pt, val alt: Double, var cumM: Double = 0.0)
    val ride = File(args[2]).readLines().filter { it.isNotBlank() }.map {
        val f = it.split(',')
        Fix(Pt(f[1].toDouble(), f[2].toDouble()), f[3].toDouble())
    }
    for (i in 1 until ride.size) ride[i].cumM = ride[i - 1].cumM + Geo.metres(ride[i - 1].pt, ride[i].pt)

    var scored = 0
    var right = 0
    var matchedSum = 0.0
    val shown = ArrayList<Double>()
    val errors = ArrayList<Double>()
    val climbErrors = ArrayList<Double>()
    val ends = HashMap<End, Int>()
    val looks = HashMap<String, Int>()
    val moments = ArrayList<String>()
    val whys = HashMap<String, Int>()
    val verdicts = LinkedHashMap<String, Int>()
    val trace = "--trace" in args
    var lastCount = 0
    val dumpAt = flag("--dump")?.toInt() ?: -1
    var lastScoredM = -1000.0

    /** How much the ride changes direction around a fix: the 50 m before it against 50 to 130 m after. */
    fun rideTurn(k: Int): Double? {
        var a = k
        while (a > 0 && ride[k].cumM - ride[a].cumM < 50.0) a--
        var b = k
        while (b < ride.size - 1 && ride[b].cumM - ride[k].cumM < 50.0) b++
        var c = b
        while (c < ride.size - 1 && ride[c].cumM - ride[k].cumM < 130.0) c++
        if (ride[k].cumM - ride[a].cumM < 15.0 || ride[c].cumM - ride[b].cumM < 25.0) return null
        return Geo.turn(Geo.bearing(ride[a].pt, ride[k].pt), Geo.bearing(ride[b].pt, ride[c].pt))
    }

    /** The same measure along the guessed road, around a distance along it. */
    fun guessTurn(path: Path, atM: Double): Double? {
        if (atM < 15.0 || path.lengthM - atM < 75.0) return null
        return Geo.turn(Geo.bearing(path.at(maxOf(atM - 50.0, 0.0)), path.at(atM)),
            Geo.bearing(path.at(atM + 50.0), path.at(minOf(atM + 130.0, path.lengthM))))
    }

    for (i in ride.indices) {
        val look = tracker.update(ride[i].pt, null, wantM)
        looks.merge(look::class.simpleName ?: "?", 1, Int::plus)
        if (tracker.predictions != lastCount) {
            lastCount = tracker.predictions
            whys.merge(tracker.why, 1, Int::plus)
            if (trace) {
                val path = tracker.currentPath
                println("  fix %4d at %.0f m: %-17s -> %s, %.0f m, %s".format(i, ride[i].cumM, tracker.why,
                    path?.roadName ?: "(no name)", path?.lengthM ?: 0.0, path?.end))
            }
        }
        if (look !is Look.Road) continue
        val ahead = look.ahead
        if (dumpAt == i) {
            val out = File("out/dump-$i.txt")
            out.writeText(tracker.currentPath!!.points.joinToString("\n") { "P,${it.pt.lat},${it.pt.lng},${it.distM}" } + "\n" +
                (i until ride.size).takeWhile { ride[it].cumM - ride[i].cumM < ahead.lengthM + 300 }.joinToString("\n") { "R,${ride[it].pt.lat},${ride[it].pt.lng},${ride[it].cumM - ride[i].cumM}" } + "\n")
            println("dumped fix $i to $out (position on path ${tracker.positionM})")
        }
        // Score a fix only where the ride goes on at least as far as the picture reaches.
        // One moment every 25 m ridden, so a stop at the lights doesn't count fifty times.
        if (ride[i].cumM - lastScoredM < 25.0 || ahead.lengthM < 25.0 || ride.last().cumM - ride[i].cumM < ahead.lengthM) continue
        lastScoredM = ride[i].cumM
        val path = tracker.currentPath!!
        val pos = tracker.positionM

        var matchedM = ahead.lengthM
        var gain = 0.0
        var k = i + 1
        var leftAt = -1
        while (k < ride.size && ride[k].cumM - ride[i].cumM <= ahead.lengthM) {
            val alongM = ride[k].cumM - ride[i].cumM
            if (offPath(path, pos, ride[k].pt) > OFF_M) {
                matchedM = ride[k - 1].cumM - ride[i].cumM
                leftAt = k - 1
                break
            }
            // Both pinned to where the rider is, as the field pins the picture to the Karoo's own elevation.
            errors += abs((ahead.heightAt(alongM) - ahead.heights[0]) - (ride[k].alt - ride[i].alt))
            if (ride[k].alt > ride[k - 1].alt) gain += ride[k].alt - ride[k - 1].alt
            k++
        }
        // Sort the moment: was the picture right, and if not, whose doing was it.
        val verdict = when {
            leftAt >= 0 -> {
                val rider = rideTurn(leftAt)
                val guess = guessTurn(path, pos + matchedM)
                when {
                    rider == null || guess == null -> "parted ways, can't tell who turned"
                    rider >= guess + 20.0 -> "rider turned off the road shown"
                    guess >= rider + 20.0 -> "guess turned, rider went straight on"
                    else -> "a fork: both carried on about as straight"
                }
            }
            ahead.end == End.FAR_ENOUGH -> "right for the full distance"
            ahead.end == End.TURN -> when (val t = rideTurn(k.coerceAtMost(ride.size - 1))) {
                null -> "stopped at a junction"
                else -> if (t >= 30.0) "stopped at a junction, rider did turn" else "stopped at a junction, rider went straight on"
            }
            else -> "right to where the road ends"
        }
        verdicts.merge(verdict, 1, Int::plus)
        if (trace && (verdict.startsWith("guess turned") || verdict.startsWith("a fork") || verdict.endsWith("straight on"))) {
            println("  MISS fix %4d at %.0f m on %s (%s): %s, after %.0f m of %.0f".format(
                i, ride[i].cumM, path.roadName ?: "(no name)", path.roadKind, verdict, matchedM, ahead.lengthM))
        }
        scored++
        matchedSum += matchedM / ahead.lengthM
        shown += ahead.lengthM
        ends.merge(ahead.end, 1, Int::plus)
        if (matchedM >= ahead.lengthM - 50.0) {
            right++
            climbErrors += abs(ahead.climbM - gain)
        }
        if (scored % 12 == 0) moments += moment(i, ride[i].pt, ahead, matchedM)
    }

    fun pct(part: Int, whole: Int) = if (whole == 0) "n/a" else "%.0f%%".format(100.0 * part / whole)
    fun mid(v: List<Double>, q: Double) = if (v.isEmpty()) Double.NaN else v.sorted()[((v.size - 1) * q).toInt()]

    println("ride ${File(args[2]).name}: ${ride.size} fixes, %.1f km, asked for %.0f m ahead%s".format(
        ride.last().cumM / 1000, wantM, if (cut) ", roads cut into map tiles" else ""))
    println("what the field showed, by fix: " + looks.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${pct(it.value, ride.size)}" })
    println("new predictions worked out: ${tracker.predictions} (" + whys.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" } + ")")
    println("scored moments: $scored")
    println("  the whole picture was the road then ridden: ${pct(right, scored)}")
    for ((v, n) in verdicts.entries.sortedByDescending { it.value }) println("    %-46s %s".format(v, pct(n, scored)))
    println("  share of the picture that was the road then ridden, on average: %.0f%%".format(100.0 * matchedSum / scored))
    println("  road shown: median %.0f m, a quarter of the time under %.0f m, full distance %s of the time".format(
        mid(shown, 0.5), mid(shown, 0.25), pct(shown.count { it >= wantM - 1 }, scored)))
    println("  where the picture stopped: " + ends.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${pct(it.value, scored)}" })
    println("  height against the ride's own altitude, on road actually ridden: median %.1f m out, 90%% within %.1f m".format(
        mid(errors, 0.5), mid(errors, 0.9)))
    println("  climbing in view against the ride's own, where the whole picture was right: median %.1f m out, 90%% within %.1f m".format(
        mid(climbErrors, 0.5), mid(climbErrors, 0.9)))

    flag("--moments")?.let { File(it).writeText("[\n" + moments.joinToString(",\n") + "\n]\n") }
}

/** Further than this from the predicted road, the ride has gone somewhere else. */
private const val OFF_M = 30.0

/** How far a fix is from the predicted road ahead of the rider. */
private fun offPath(path: Path, posM: Double, p: Pt): Double {
    var best = Double.MAX_VALUE
    val pts = path.points
    for (i in 0 until pts.size - 1) {
        if (pts[i + 1].distM < posM - 30.0) continue
        val off = Geo.land(p, pts[i].pt, pts[i + 1].pt).offM
        if (off < best) best = off
    }
    return best
}

private fun moment(index: Int, at: Pt, ahead: Ahead, matchedM: Double): String {
    val heights = ahead.heights.joinToString(",") { "%.1f".format(it) }
    val name = ahead.roadName?.replace("\"", "'")
    return """  {"fix":$index,"lat":${at.lat},"lng":${at.lng},"road":${if (name == null) "null" else "\"$name\""},""" +
        """"end":"${ahead.end}","lengthM":${"%.0f".format(ahead.lengthM)},"stepM":${ahead.stepM},"climbM":${"%.1f".format(ahead.climbM)},""" +
        """"riddenM":${"%.0f".format(matchedM)},"heights":[$heights]}"""
}
