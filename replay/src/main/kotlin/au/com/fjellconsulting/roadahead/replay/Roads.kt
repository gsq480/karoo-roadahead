package au.com.fjellconsulting.roadahead.replay

import au.com.fjellconsulting.roadahead.core.Geo
import au.com.fjellconsulting.roadahead.core.Pt
import au.com.fjellconsulting.roadahead.core.Road
import au.com.fjellconsulting.roadahead.core.RoadSource
import java.io.File
import java.util.IdentityHashMap
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Roads from an OpenStreetMap extract, one per line: kind, name, ref, flags (o one-way, r
 * roundabout, b bridge, t tunnel) and the points, tab-separated. The README says how the file is
 * made. The Karoo's maps are built from the same data, so this stands in for them on the Mac.
 */
fun readRoads(file: File): List<Road> = file.useLines { lines ->
    lines.mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 5) return@mapNotNull null
        val pts = f[4].split(' ').map { val (lat, lng) = it.split(','); Pt(lat.toDouble(), lng.toDouble()) }
        if (pts.size < 2) return@mapNotNull null
        Road(pts, f[1].ifEmpty { null }, f[2].ifEmpty { null }, f[0],
            oneway = 'o' in f[3], roundabout = 'r' in f[3], bridge = 'b' in f[3], tunnel = 't' in f[3])
    }.toList()
}

/** Roads sorted into a grid of small squares, so "what's near here" doesn't scan the whole city. */
class GridRoads(roads: List<Road>, private val cellDeg: Double = 0.004) : RoadSource {
    private val cells = HashMap<Long, MutableList<Road>>()

    init {
        for (r in roads) {
            for (y in cell(r.minLat)..cell(r.maxLat)) for (x in cell(r.minLng)..cell(r.maxLng)) {
                cells.getOrPut(key(x, y)) { ArrayList() } += r
            }
        }
    }

    private fun cell(deg: Double) = floor(deg / cellDeg).toInt()
    private fun key(x: Int, y: Int) = x.toLong() * 1_000_000L + y

    override fun near(p: Pt, radiusM: Double): List<Road> {
        val dLat = radiusM / 111_320.0
        val dLng = dLat / cos(Math.toRadians(p.lat))
        val found = IdentityHashMap<Road, Boolean>()
        for (y in cell(p.lat - dLat)..cell(p.lat + dLat)) for (x in cell(p.lng - dLng)..cell(p.lng + dLng)) {
            cells[key(x, y)]?.forEach { if (it.mightBeNear(p, dLat, dLng)) found[it] = true }
        }
        return found.keys.toList()
    }
}

/**
 * Cut every road the way a tiled map does: into one piece per map tile, each running a little
 * past the tile's edge, so neighbouring pieces overlap and share no point. The engine has to
 * give the same answer on these as on the whole roads.
 */
fun cutIntoTiles(roads: List<Road>, zoom: Int = 14, overlapM: Double = 20.0): List<Road> {
    val n = 1 shl zoom
    fun tileX(lng: Double) = floor((lng + 180.0) / 360.0 * n).toInt()
    fun tileY(lat: Double) = floor((1.0 - ln(tan(Math.toRadians(lat)) + 1.0 / cos(Math.toRadians(lat))) / PI) / 2.0 * n).toInt()
    fun lngOf(x: Int) = x.toDouble() / n * 360.0 - 180.0
    fun latOf(y: Int) = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / n))))

    val out = ArrayList<Road>()
    for (r in roads) {
        val xs = tileX(r.minLng)..tileX(r.maxLng)
        val ys = tileY(r.maxLat)..tileY(r.minLat)
        if (xs.first == xs.last && ys.first == ys.last) {
            out += r
            continue
        }
        for (x in xs) for (y in ys) {
            val dLat = overlapM / 111_320.0
            val dLng = dLat / cos(Math.toRadians(r.minLat))
            val south = latOf(y + 1) - dLat
            val north = latOf(y) + dLat
            val west = lngOf(x) - dLng
            val east = lngOf(x + 1) + dLng
            for (piece in clip(r.pts, south, north, west, east)) {
                if (piece.size >= 2) {
                    out += Road(piece, r.name, r.ref, r.kind, r.oneway && !r.roundabout, r.roundabout, r.bridge, r.tunnel)
                }
            }
        }
    }
    return out
}

/** The parts of a line inside a box, in order. */
private fun clip(pts: List<Pt>, south: Double, north: Double, west: Double, east: Double): List<List<Pt>> {
    val pieces = ArrayList<List<Pt>>()
    var current = ArrayList<Pt>()
    for (i in 0 until pts.size - 1) {
        val a = pts[i]
        val b = pts[i + 1]
        // Liang-Barsky: the stretch of a to b, as fractions t0 to t1, that lies inside the box.
        var t0 = 0.0
        var t1 = 1.0
        val dLat = b.lat - a.lat
        val dLng = b.lng - a.lng
        var inside = true
        for ((p, q) in listOf(-dLng to a.lng - west, dLng to east - a.lng, -dLat to a.lat - south, dLat to north - a.lat)) {
            if (p == 0.0) {
                if (q < 0) inside = false
            } else {
                val t = q / p
                if (p < 0) { if (t > t1) inside = false else if (t > t0) t0 = t } else { if (t < t0) inside = false else if (t < t1) t1 = t }
            }
        }
        if (!inside || t0 > t1) {
            if (current.size >= 2) pieces += current
            current = ArrayList()
            continue
        }
        val from = if (t0 > 0.0) Geo.between(a, b, t0) else a
        val to = if (t1 < 1.0) Geo.between(a, b, t1) else b
        if (current.isEmpty()) current += from
        current += to
        if (t1 < 1.0) {
            if (current.size >= 2) pieces += current
            current = ArrayList()
        }
    }
    if (current.size >= 2) pieces += current
    return pieces
}
