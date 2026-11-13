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
        cache[id] = roads
        return roads
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
    }
}
