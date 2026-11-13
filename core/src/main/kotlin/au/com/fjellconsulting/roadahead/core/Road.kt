package au.com.fjellconsulting.roadahead.core

/** What sort of way a piece is, as far as the follow rule cares. */
enum class RoadClass { ROAD, MINOR, PATH }

/**
 * One mapped piece of road or path. A real road is usually several pieces end to end, and a map
 * that cuts its data into tiles hands the same road over as overlapping pieces with no shared
 * point. Nothing here relies on pieces sharing points or having ids.
 */
class Road(
    val pts: List<Pt>,
    val name: String? = null,
    val ref: String? = null,
    /** OpenStreetMap's `highway` value: residential, cycleway, footway and so on. */
    val kind: String = "residential",
    oneway: Boolean = false,
    val roundabout: Boolean = false,
    val bridge: Boolean = false,
    val tunnel: Boolean = false,
) {
    /** True when traffic only runs from the first point to the last. A roundabout always does. */
    val oneway: Boolean = oneway || roundabout

    val roadClass: RoadClass = when (kind) {
        "cycleway", "path", "footway", "pedestrian", "bridleway" -> RoadClass.PATH
        "service", "track" -> RoadClass.MINOR
        else -> RoadClass.ROAD
    }

    /** The same piece read twice (from two map tiles, or after the cache dropped it) has the same key. */
    val key: Long = run {
        var h = pts.size.toLong()
        for (p in listOf(pts.first(), pts[pts.size / 2], pts.last())) {
            h = h * 1_000_003L + (p.lat * 1e7).toLong()
            h = h * 1_000_003L + (p.lng * 1e7).toLong()
        }
        h
    }

    val minLat = pts.minOf { it.lat }
    val maxLat = pts.maxOf { it.lat }
    val minLng = pts.minOf { it.lng }
    val maxLng = pts.maxOf { it.lng }

    val label: String? get() = name ?: ref

    /** Two pieces of one road: they share a name, or a route number when neither has a name. */
    fun sameRoadAs(other: Road): Boolean =
        (name != null && name == other.name) || (name == null && other.name == null && ref != null && ref == other.ref)

    /** Whether any part of the piece could be within a margin (in degrees) of a point. */
    fun mightBeNear(p: Pt, marginLat: Double, marginLng: Double): Boolean =
        p.lat >= minLat - marginLat && p.lat <= maxLat + marginLat &&
            p.lng >= minLng - marginLng && p.lng <= maxLng + marginLng

    companion object {
        /** The `highway` values worth following on a bike. Everything else is left out of the map. */
        val RIDEABLE = setOf(
            "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
            "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified",
            "residential", "living_street", "road", "service", "track",
            "cycleway", "path", "footway", "pedestrian", "bridleway",
        )
    }
}

/** Where the roads come from: the Karoo's map files, or a test's hand-made streets. */
fun interface RoadSource {
    /** Every piece with any part inside the radius. Null when there is no map for that spot. */
    fun near(p: Pt, radiusM: Double): List<Road>?
}

/** A fixed set of roads, for tests and small extracts. */
class RoadList(private val roads: List<Road>) : RoadSource {
    override fun near(p: Pt, radiusM: Double): List<Road> {
        val marginLat = radiusM / 111_320.0
        val marginLng = marginLat / Math.cos(Math.toRadians(p.lat))
        return roads.filter { it.mightBeNear(p, marginLat, marginLng) }
    }
}
