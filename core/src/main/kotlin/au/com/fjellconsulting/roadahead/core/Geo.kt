package au.com.fjellconsulting.roadahead.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A position in degrees. */
data class Pt(val lat: Double, val lng: Double)

/** Where a point lands on a segment: how far along it (0 to 1), the spot, and the gap in metres. */
data class Landing(val t: Double, val at: Pt, val offM: Double)

/**
 * Distances and bearings over a few kilometres. Flat-earth maths around the points in hand, which
 * is out by well under a metre at this scale.
 */
object Geo {
    private const val M_PER_DEG = 111_320.0

    private fun rad(deg: Double) = deg * PI / 180.0

    private fun dx(a: Pt, b: Pt) = (b.lng - a.lng) * cos(rad((a.lat + b.lat) / 2)) * M_PER_DEG
    private fun dy(a: Pt, b: Pt) = (b.lat - a.lat) * M_PER_DEG

    fun metres(a: Pt, b: Pt): Double {
        val x = dx(a, b)
        val y = dy(a, b)
        return sqrt(x * x + y * y)
    }

    /** Compass bearing from a to b: 0 is north, 90 is east. */
    fun bearing(a: Pt, b: Pt): Double {
        val deg = atan2(dx(a, b), dy(a, b)) * 180.0 / PI
        return (deg + 360.0) % 360.0
    }

    /** The angle between two bearings, 0 to 180. */
    fun turn(b1: Double, b2: Double): Double {
        val d = abs(b1 - b2) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    fun between(a: Pt, b: Pt, t: Double) = Pt(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)

    /** The point a distance and bearing away. */
    fun offset(p: Pt, bearingDeg: Double, m: Double): Pt {
        val north = cos(rad(bearingDeg)) * m
        val east = sin(rad(bearingDeg)) * m
        return Pt(p.lat + north / M_PER_DEG, p.lng + east / (M_PER_DEG * cos(rad(p.lat))))
    }

    /** The nearest spot to p on the segment a to b. */
    fun land(p: Pt, a: Pt, b: Pt): Landing {
        val sx = dx(a, b)
        val sy = dy(a, b)
        val len2 = sx * sx + sy * sy
        val t = if (len2 == 0.0) 0.0 else ((dx(a, p) * sx + dy(a, p) * sy) / len2).coerceIn(0.0, 1.0)
        val at = between(a, b, t)
        return Landing(t, at, metres(p, at))
    }
}
