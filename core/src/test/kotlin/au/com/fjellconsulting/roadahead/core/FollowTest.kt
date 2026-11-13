package au.com.fjellconsulting.roadahead.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The follow rule and the snap rule on small hand-made street layouts. Every layout starts at
 * `o` and the rider heads east along a road called Main for 300 m to the junction `x`.
 */
class FollowTest {
    private val o = Pt(-35.30, 149.10)
    private val x = Geo.offset(o, 90.0, 300.0)

    private fun line(from: Pt, bearing: Double, m: Double, stepM: Double = 50.0): List<Pt> {
        val pts = ArrayList<Pt>()
        var d = 0.0
        while (d < m) {
            pts += Geo.offset(from, bearing, d)
            d += stepM
        }
        pts += Geo.offset(from, bearing, m)
        return pts
    }

    private val main = Road(line(o, 90.0, 300.0), name = "Main")

    /** A street crossing the junction from 200 m north to 200 m south, with a point on it. */
    private fun cross(name: String = "Cross") =
        Road(listOf(Geo.offset(x, 0.0, 200.0), x, Geo.offset(x, 180.0, 200.0)), name = name)

    private fun follow(vararg roads: Road, maxM: Double = 5000.0): Path =
        RoadFollow(RoadList(roads.toList())).follow(Spot(roads[0], 0, 0.0, true), maxM)

    private fun assertEndsNear(path: Path, want: Pt) =
        assertTrue("ended ${Geo.metres(path.points.last().pt, want)} m away", Geo.metres(path.points.last().pt, want) < 2.0)

    @Test fun carriesStraightOnThroughACrossroads() {
        val path = follow(main, Road(line(x, 90.0, 500.0), name = "Main"), cross())
        assertEquals(800.0, path.lengthM, 1.0)
        assertEquals(End.ROAD_ENDS, path.end)
    }

    @Test fun stopsAtATJunction() {
        val path = follow(main, cross())
        assertEquals(300.0, path.lengthM, 1.0)
        assertEquals(End.TURN, path.end)
    }

    @Test fun stopsAtTheDistanceAskedFor() {
        val path = follow(main, Road(line(x, 90.0, 500.0), name = "Main"), maxM = 450.0)
        assertEquals(450.0, path.lengthM, 0.01)
        assertEquals(End.FAR_ENOUGH, path.end)
    }

    @Test fun carriesStraightOnWhenTheNameChanges() {
        val path = follow(main, Road(line(x, 100.0, 300.0), name = "Other"), cross())
        assertEquals(600.0, path.lengthM, 1.0)
    }

    @Test fun followsTheNameRoundABendRatherThanTheSideStreetStraightAhead() {
        val bend = Road(line(x, 150.0, 300.0), name = "Main")
        val path = follow(main, bend, Road(line(x, 90.0, 300.0), name = "Side"))
        assertEndsNear(path, Geo.offset(x, 150.0, 300.0))
    }

    @Test fun takesTheOnlyWayOnwardAtASharpBend() {
        val path = follow(main, Road(line(x, 160.0, 300.0), name = "Other"))
        assertEquals(600.0, path.lengthM, 1.0)
    }

    @Test fun stopsAtATJunctionOntoADividedRoad() {
        // Main ends on the near carriageway of a divided road, which is one-way to the right.
        // Turning left means crossing to the far carriageway further along, so the map shows
        // one way onward. It is still a T-junction.
        val near = Road(listOf(Geo.offset(x, 0.0, 200.0), x, Geo.offset(x, 180.0, 200.0)), name = "Divided", oneway = true)
        val path = follow(main, near)
        assertEquals(End.TURN, path.end)
        assertEquals(300.0, path.lengthM, 1.0)
    }

    @Test fun stopsAtATJunctionWhereTheDividedRoadIsMappedInTwoPieces() {
        val upstream = Road(listOf(Geo.offset(x, 0.0, 200.0), x), name = "Divided", oneway = true)
        val downstream = Road(listOf(x, Geo.offset(x, 180.0, 200.0)), name = "Divided", oneway = true)
        assertEquals(End.TURN, follow(main, upstream, downstream).end)
    }

    @Test fun stopsWhereTheSameNameGoesBothWays() {
        val path = follow(main, Road(line(x, 0.0, 300.0), name = "Main"), Road(line(x, 180.0, 300.0), name = "Main"))
        assertEquals(End.TURN, path.end)
        assertEquals(300.0, path.lengthM, 1.0)
    }

    @Test fun doesNotGoTheWrongWayDownAOneWayStreet() {
        val against = Road(line(Geo.offset(x, 90.0, 300.0), 270.0, 300.0), name = "Other", oneway = true)
        val path = follow(main, against)
        assertEquals(End.ROAD_ENDS, path.end)
        assertEquals(300.0, path.lengthM, 1.0)
    }

    @Test fun joinsPiecesThatOverlapWithNoSharedPoint() {
        // What a tiled map gives you: the same road cut in two, each piece running a little past
        // the cut, with no point in common.
        val first = Road(line(o, 90.0, 320.0, 80.0), name = "Main")
        val second = Road(
            listOf(Geo.offset(o, 90.0, 290.0), Geo.offset(o, 90.0, 345.0), Geo.offset(o, 90.0, 600.0)), name = "Main")
        val path = follow(first, second, cross())
        assertEquals(600.0, path.lengthM, 1.0)
    }

    @Test fun ignoresADrivewayStraightAcrossATJunction() {
        val path = follow(main, cross(), Road(line(x, 90.0, 40.0), kind = "service"))
        assertEquals(End.TURN, path.end)
        assertEquals(300.0, path.lengthM, 1.0)
    }

    @Test fun carriesOnAlongABikePathWhereTheRoadEnds() {
        val path = follow(main, Road(line(x, 95.0, 200.0), kind = "cycleway"))
        assertEquals(500.0, path.lengthM, 1.0)
    }

    @Test fun marksTheStretchOnABridge() {
        val path = follow(main, Road(line(x, 90.0, 200.0), name = "Main", bridge = true), Road(line(Geo.offset(x, 90.0, 200.0), 90.0, 300.0), name = "Main"))
        assertEquals(800.0, path.lengthM, 1.0)
        assertTrue(path.flatAt(400.0))
        assertTrue(!path.flatAt(200.0))
        assertTrue(!path.flatAt(600.0))
    }

    /** A roundabout 20 m across the junction, ridden clockwise as in Australia, with three exits. */
    private fun roundabout(eastName: String): List<Road> {
        val centre = Geo.offset(x, 90.0, 20.0)
        val ringPts = (0..12).map { Geo.offset(centre, (270.0 + it * 30.0) % 360.0, 20.0) }
        val ring = Road(ringPts, roundabout = true)
        fun exit(bearing: Double, name: String) = Road(line(Geo.offset(centre, bearing, 20.0), bearing, 300.0), name = name)
        return listOf(main, ring, exit(0.0, "North"), exit(90.0, eastName), exit(180.0, "South"))
    }

    @Test fun takesTheRoundaboutExitWithTheSameName() {
        val path = follow(*roundabout("Main").toTypedArray())
        assertEndsNear(path, Geo.offset(x, 90.0, 340.0))
    }

    @Test fun takesTheRoundaboutExitStraightAheadWhenNoNameMatches() {
        val path = follow(*roundabout("Other").toTypedArray())
        assertEndsNear(path, Geo.offset(x, 90.0, 340.0))
        // Half way round a 20 m ring is longer than the straight line across it.
        assertTrue(path.lengthM > 650.0)
    }

    @Test fun endsALapOfACircuitMappedAsOnePiece() {
        val a = Geo.offset(o, 90.0, 200.0)
        val b = Geo.offset(a, 0.0, 200.0)
        val c = Geo.offset(o, 0.0, 200.0)
        val path = follow(Road(listOf(o, a, b, c, o), name = "Circuit"))
        assertEquals(End.LOOP, path.end)
        assertEquals(800.0, path.lengthM, 2.0)
    }

    // The snap rule.

    private val snap = RoadSnap()
    private val footpath = Road(line(Geo.offset(o, 0.0, 8.0), 90.0, 300.0), kind = "footway")
    private val nearFootpath = Geo.offset(Geo.offset(o, 90.0, 100.0), 0.0, 6.0)

    @Test fun snapsToTheRoadNotTheFootpathBesideIt() {
        val got = snap.snap(listOf(main, footpath), nearFootpath, 90.0)
        assertNotNull(got)
        assertEquals("Main", got!!.spot.road.name)
        assertTrue(got.spot.forward)
    }

    @Test fun snapsFacingTheWayYouAreHeading() {
        val got = snap.snap(listOf(main, footpath), nearFootpath, 270.0)
        assertTrue(!got!!.spot.forward)
        val path = RoadFollow(RoadList(listOf(main))).follow(got.spot, 5000.0)
        assertEquals(100.0, path.lengthM, 1.0)
    }

    @Test fun doesNotSnapToARoadRunningAcrossYou() {
        assertNull(snap.snap(listOf(main), nearFootpath, 0.0))
    }

    @Test fun doesNotSnapAgainstAOneWayStreet() {
        val oneWay = Road(line(o, 90.0, 300.0), name = "Main", oneway = true)
        assertNull(snap.snap(listOf(oneWay), nearFootpath, 270.0))
        assertNotNull(snap.snap(listOf(oneWay), nearFootpath, 90.0))
    }

    @Test fun doesNotSnapToARoadTooFarAway() {
        assertNull(snap.snap(listOf(main), Geo.offset(nearFootpath, 0.0, 60.0), 90.0))
    }
}
