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
 *   replay <roads.tsv or file.map> <folder of .hgt files> <ride.csv> [--want 2000] [--cut] [--png fix]
 *
 * The ride file is `time_s,lat,lng,altitude_m` a line. `--cut` chops the roads into map tiles
 * first, the way the Karoo's map files hand them over.
 */
fun main(args: Array<String>) {
    if (args.isNotEmpty() && args[0] == "map") return mapCheck(args)
    if (args.size < 3) {
        System.err.println("usage: replay <roads.tsv> <hgt folder> <ride.csv> [--want metres] [--cut] [--moments out.json]")
        exitProcess(2)
    }
    fun flag(name: String) = args.indexOf(name).let { if (it >= 0 && it + 1 < args.size) args[it + 1] else null }
    val wantM = flag("--want")?.toDouble() ?: 2000.0
    val cut = "--cut" in args

    // A `.map` file is read the way the Karoo app reads it; anything else is the text extract.
    val source = if (args[0].endsWith(".map")) au.com.fjellconsulting.roadahead.core.MapRoads(listOf(File(args[0]))) else {
        var roads = readRoads(File(args[0]))
        if (cut) roads = cutIntoTiles(roads)
        GridRoads(roads)
    }
    // The app carries on through junctions on a guess. `--stop` replays the older rule, which stops there.
    val tracker = Tracker(source, HgtFolder(File(args[1]))).also { it.through = "--stop" !in args }
    val pngAt = flag("--png")?.toInt() ?: -1

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
        if (pngAt == i) {
            val set = au.com.fjellconsulting.roadahead.core.FieldSettings()
            val paint = { l: Look, w: Int, h: Int -> au.com.fjellconsulting.roadahead.core.FieldPainter.paint(l, w, h, set) }
            val out = File("out/field-$i.png")
            writePng(listOf(paint(look, 480, 150), paint(look, 480, 300), paint(look, 480, 96), paint(look, 240, 150),
                paint(Look.Waiting, 480, 150), paint(Look.NoHeights("S36E149.hgt"), 480, 96)), out)
            println("drew fix $i to $out")
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


/**
 * Open a Mapsforge map file and say what's in it around a point: which tags the roads carry,
 * whether pieces stop at the map tile's edge, and where the engine would go from there.
 *
 *   replay map <file.map> <lat> <lng> <course>
 */
private fun mapCheck(args: Array<String>) {
    val file = File(args[1])
    val at = Pt(args[2].toDouble(), args[3].toDouble())
    val course = args[4].toDouble()
    val map = org.mapsforge.map.reader.MapFile(file)
    val info = map.mapFileInfo
    println("${file.name}: version ${info.fileVersion}, zoom ${info.zoomLevelMin} to ${info.zoomLevelMax}, made ${java.util.Date(info.mapDate)}, by ${info.createdBy}")
    println("covers ${info.boundingBox}")
    val n = 1 shl 14
    val x = Math.floor((at.lng + 180.0) / 360.0 * n).toInt()
    val r = Math.toRadians(at.lat)
    val y = Math.floor((1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * n).toInt()
    val tile = org.mapsforge.core.model.Tile(x, y, 14.toByte(), 256)
    val box = tile.boundingBox
    val ways = map.readMapData(tile).ways
    val keys = HashMap<String, Int>()
    val kinds = HashMap<String, Int>()
    var roads = 0
    var named = 0
    var outside = 0
    var farOutside = 0
    for (w in ways) {
        val highway = w.tags.firstOrNull { it.key == "highway" } ?: continue
        roads++
        kinds.merge(highway.value, 1, Int::plus)
        for (t in w.tags) keys.merge(t.key, 1, Int::plus)
        if (w.tags.any { it.key == "name" }) named++
        // How far past the tile's edge does the furthest point of the way reach?
        var reach = 0.0
        for (line in w.latLongs) for (ll in line) {
            val dLat = maxOf(box.minLatitude - ll.latitude, ll.latitude - box.maxLatitude, 0.0) * 111_320.0
            val dLng = maxOf(box.minLongitude - ll.longitude, ll.longitude - box.maxLongitude, 0.0) * 111_320.0 * Math.cos(r)
            reach = maxOf(reach, dLat, dLng)
        }
        if (reach > 1.0) outside++
        if (reach > 60.0) farOutside++
    }
    println("tile 14/$x/$y: ${ways.size} ways, $roads with a highway tag, $named of those named")
    println("  kinds: " + kinds.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" })
    println("  tags on roads: " + keys.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" })
    println("  roads reaching past the tile's edge: $outside, more than 60 m past: $farOutside")
    // A wider look for the tags that matter and are rarer: 49 tiles around the point.
    val wide = HashMap<String, Int>()
    for (dx in -3..3) for (dy in -3..3) {
        val t = org.mapsforge.core.model.Tile(x + dx, y + dy, 14.toByte(), 256)
        for (w in map.readMapData(t).ways) if (w.tags.any { it.key == "highway" }) {
            for (tag in w.tags) if (tag.key in setOf("junction", "bridge", "tunnel", "oneway", "ref", "name")) wide.merge("${tag.key}=${if (tag.key in setOf("name", "ref")) "*" else tag.value}", 1, Int::plus)
        }
    }
    println("  across 49 tiles: " + wide.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" })
    map.close()

    val source = au.com.fjellconsulting.roadahead.core.MapRoads(listOf(file))
    // Start on the longest named road in the tile and follow it, as a check that pieces cut at
    // tile edges join up.
    val around = source.near(at, 3000.0).orEmpty()
    println("engine: ${around.size} pieces within 3 km, ${around.count { it.roundabout }} of them roundabouts told by shape or tag")
    val start = around.filter { it.kind in setOf("primary", "secondary", "trunk") && !it.roundabout }.maxByOrNull { it.pts.size }
    if (start != null) {
        println("engine: that road starts at %.6f %.6f heading %.0f".format(start.pts[0].lat, start.pts[0].lng, Geo.bearing(start.pts[0], start.pts[1])))
        for (forward in listOf(true, false)) {
            if (!forward && start.oneway) continue
            val spot = au.com.fjellconsulting.roadahead.core.Spot(start, if (forward) 0 else start.pts.size - 2, if (forward) 0.0 else 1.0, forward)
            val p = au.com.fjellconsulting.roadahead.core.RoadFollow(source).follow(spot, 8000.0)
            // An end right on a tile's edge would mean two pieces of one road failed to join.
            val end = p.points.last().pt
            val fx = (end.lng + 180.0) / 360.0 * n
            val er = Math.toRadians(end.lat)
            val fy = (1.0 - Math.log(Math.tan(er) + 1.0 / Math.cos(er)) / Math.PI) / 2.0 * n
            val tileM = 40_075_000.0 * Math.cos(er) / n
            val edgeM = minOf(fx - Math.floor(fx), Math.ceil(fx) - fx, fy - Math.floor(fy), Math.ceil(fy) - fy) * tileM
            var crossed = 0
            for (i in 1 until p.points.size) {
                val a = p.points[i - 1].pt
                val b = p.points[i].pt
                if (Math.floor((a.lng + 180.0) / 360.0 * n) != Math.floor((b.lng + 180.0) / 360.0 * n)) crossed++
            }
            println("engine: along %s (%s) %s: followed %.0f m, ended %s, %.0f m from a tile edge, crossed %d tile edges east-west".format(
                start.label ?: "(no name)", start.kind, if (forward) "forwards" else "backwards", p.lengthM, p.end, edgeM, crossed))
        }
    }
    val near = source.near(at, 45.0)
    println("engine: ${near?.size} pieces within 45 m of the point")
    val snapped = near?.let { au.com.fjellconsulting.roadahead.core.RoadSnap().snap(it, at, course) }
    if (snapped == null) {
        println("engine: no road to snap to here")
        return
    }
    val path = au.com.fjellconsulting.roadahead.core.RoadFollow(source).follow(snapped.spot, 5000.0)
    println("engine: snapped %.1f m away to %s (%s), followed %.0f m, ended %s".format(
        snapped.offM, path.roadName ?: "(no name)", path.roadKind, path.lengthM, path.end))
}
