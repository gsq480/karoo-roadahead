package au.com.fjellconsulting.roadahead.core

import org.mapsforge.core.model.Tag
import org.mapsforge.core.model.Tile
import org.mapsforge.map.reader.MapFile
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Roads read from Mapsforge map files, which is what the Karoo keeps its offline maps in
 * (the `.map` files in `/sdcard/offline/maps`). The files are read one map tile at a time, as the rider reaches
 * them, and the last few dozen tiles are kept.
 */
class MapRoads(files: List<File>, private val zoom: Int = 14, private val keep: Int = 48) : RoadSource {

    private class Source(val name: String, val map: MapFile)

    private val sources: List<Source> = files.mapNotNull { f ->
        runCatching { Source(f.name, MapFile(f)) }.getOrNull()
    }

    /** How many map files opened. For the status screen. */
    val fileCount: Int get() = sources.size
    val fileNames: List<String> get() = sources.map { it.name }

    private val cache = object : LinkedHashMap<Long, List<Road>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Road>>?) = size > keep
    }

    private val n = 1 shl zoom
    private fun tileX(lng: Double) = floor((lng + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    private fun tileY(lat: Double): Int {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
    }

    @Synchronized
    override fun near(p: Pt, radiusM: Double): List<Road>? {
        if (sources.none { covers(it, p) }) return null
        val dLat = radiusM / 111_320.0
        val dLng = dLat / cos(Math.toRadians(p.lat))
        val seen = HashSet<Long>()
        val out = ArrayList<Road>()
        for (x in tileX(p.lng - dLng)..tileX(p.lng + dLng)) for (y in tileY(p.lat + dLat)..tileY(p.lat - dLat)) {
            for (road in tile(x, y)) {
                if (road.mightBeNear(p, dLat, dLng) && seen.add(road.key)) out += road
            }
        }
        return out
    }

    private fun covers(s: Source, p: Pt): Boolean = runCatching { s.map.boundingBox().contains(p.lat, p.lng) }.getOrDefault(false)

    private fun tile(x: Int, y: Int): List<Road> {
        val id = x.toLong() * n + y
        cache[id]?.let { return it }
        val roads = ArrayList<Road>()
        val tile = Tile(x, y, zoom.toByte(), 256)
        for (s in sources) {
            val result = runCatching { if (s.map.supportsTile(tile)) s.map.readMapData(tile) else null }.getOrNull() ?: continue
            for (way in result.ways) {
                for (line in way.latLongs) {
                    if (line.size < 2) continue
                    roadFrom(way.tags, line.map { Pt(it.latitude, it.longitude) })?.let { roads += it }
                }
            }
            // Where two files overlap at a border, the first that has the tile is enough.
            if (roads.isNotEmpty()) break
        }
        val joined = joinRings(roads)
        cache[id] = joined
        return joined
    }

    @Synchronized
    fun close() {
        sources.forEach { runCatching { it.map.close() } }
        cache.clear()
    }

    companion object {
        /** A mapped way as a road, or null when it isn't something to ride along. */
        fun roadFrom(tags: List<Tag>, pts: List<Pt>): Road? {
            var kind: String? = null
            var name: String? = null
            var ref: String? = null
            var oneway = false
            var reversed = false
            var roundabout = false
            var bridge = false
            var tunnel = false
            for (t in tags) {
                when (t.key) {
                    "highway" -> kind = t.value
                    "name" -> name = t.value?.takeIf { it.isNotBlank() }
                    "ref" -> ref = t.value?.takeIf { it.isNotBlank() }
                    "oneway" -> when (t.value) {
                        "yes", "true", "1" -> oneway = true
                        "-1" -> { oneway = true; reversed = true }
                    }
                    "junction" -> roundabout = t.value == "roundabout" || t.value == "circular"
                    "bridge" -> bridge = t.value != "no"
                    "tunnel" -> tunnel = t.value != "no"
                    "area" -> if (t.value == "yes") return null
                }
            }
            if (kind == null || kind !in Road.RIDEABLE) return null
            // Stock Mapsforge maps drop the roundabout tag, so a roundabout is told by its shape.
            val ring = roundabout || (kind !in PATHS && looksLikeARoundabout(pts))
            return Road(if (reversed) pts.reversed() else pts, name, ref, kind, oneway, ring, bridge, tunnel)
        }

        private val PATHS = setOf("cycleway", "path", "footway", "pedestrian", "bridleway", "track", "service")

        /**
         * A small closed circle of road. Mappers draw a roundabout in the direction traffic goes
         * round it, so the order of the points is the way to ride it.
         */
        fun looksLikeARoundabout(pts: List<Pt>): Boolean {
            if (pts.size < 6 || Geo.metres(pts.first(), pts.last()) > 1.0) return false
            var around = 0.0
            for (i in 1 until pts.size) around += Geo.metres(pts[i - 1], pts[i])
            if (around < 20.0 || around > 400.0) return false
            val centre = Pt(pts.dropLast(1).map { it.lat }.average(), pts.dropLast(1).map { it.lng }.average())
            val radii = pts.map { Geo.metres(centre, it) }
            val mean = radii.average()
            return radii.all { it > mean * 0.65 && it < mean * 1.35 }
        }

        /**
         * Marks the pieces of roundabouts drawn in several pieces. Many roundabouts are mapped as
         * one piece between each pair of roads that meet them (both at M3 Junction 6 in Ireland
         * since 2026-08-18), so no single piece is a closed circle and, with the roundabout tag
         * dropped, nothing marks them. Pieces that join end to end, each drawn on from where the
         * last one stops with no sharp turn between them, and that close into a small circle,
         * are a roundabout. A slip lane out and back in again turns sharply where the two meet,
         * so it never counts as part of the ring.
         */
        fun joinRings(roads: List<Road>): List<Road> {
            fun spot(p: Pt) = Math.round(p.lat * 1e6) * 1_000_000_000L + Math.round(p.lng * 1e6)
            fun lengthOf(r: Road): Double {
                var m = 0.0
                for (i in 1 until r.pts.size) m += Geo.metres(r.pts[i - 1], r.pts[i])
                return m
            }
            val lengths = roads.map { lengthOf(it) }
            val candidate = roads.indices.filter { i ->
                val r = roads[i]
                !r.roundabout && r.kind !in PATHS && lengths[i] <= RING_MAX_M &&
                    Geo.metres(r.pts.first(), r.pts.last()) > 1.0
            }
            if (candidate.size < 2) return roads
            val startingAt = HashMap<Long, MutableList<Int>>()
            for (i in candidate) startingAt.getOrPut(spot(roads[i].pts.first())) { ArrayList() } += i

            val ring = HashSet<Int>()
            for (first in candidate) {
                if (first in ring) continue
                val home = spot(roads[first].pts.first())
                var found: List<Int>? = null
                // Each chain of pieces on from this one, up to a small circle's length, until one closes.
                fun extend(chain: MutableList<Int>, m: Double) {
                    if (found != null || chain.size > RING_MAX_PIECES) return
                    val last = roads[chain.last()]
                    val n = last.pts.size
                    val leaving = Geo.bearing(last.pts[n - 2], last.pts[n - 1])
                    for (next in startingAt[spot(last.pts.last())].orEmpty()) {
                        val r = roads[next]
                        if (next in chain || m + lengths[next] > RING_MAX_M) continue
                        if (Geo.turn(leaving, Geo.bearing(r.pts[0], r.pts[1])) > RING_JOIN_TURN) continue
                        chain += next
                        if (spot(r.pts.last()) == home) {
                            // The join that closes the circle has to be as smooth as the rest.
                            val m2 = r.pts.size
                            val start = roads[first].pts
                            if (Geo.turn(Geo.bearing(r.pts[m2 - 2], r.pts[m2 - 1]), Geo.bearing(start[0], start[1])) > RING_JOIN_TURN) {
                                chain.removeAt(chain.lastIndex)
                                continue
                            }
                            val pts = ArrayList<Pt>()
                            for (i in chain) pts += if (pts.isEmpty()) roads[i].pts else roads[i].pts.drop(1)
                            if (looksLikeARoundabout(pts)) found = chain.toList()
                        } else {
                            extend(chain, m + lengths[next])
                        }
                        if (found != null) return
                        chain.removeAt(chain.lastIndex)
                    }
                }
                extend(mutableListOf(first), lengths[first])
                found?.let { ring += it }
            }
            if (ring.isEmpty()) return roads
            return roads.mapIndexed { i, r ->
                if (i in ring) Road(r.pts, r.name, r.ref, r.kind, r.oneway, roundabout = true, bridge = r.bridge, tunnel = r.tunnel) else r
            }
        }

        /** The most a roundabout's pieces add up to, as for one drawn whole. */
        private const val RING_MAX_M = 400.0
        private const val RING_MAX_PIECES = 10
        /** The sharpest turn from one piece of a ring onto the next, in degrees. */
        private const val RING_JOIN_TURN = 60.0
    }
}
