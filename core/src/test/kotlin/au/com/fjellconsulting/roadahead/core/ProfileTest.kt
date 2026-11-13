package au.com.fjellconsulting.roadahead.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

/** Heights, the profile along a road, and the tracker that carries a prediction between fixes. */
class ProfileTest {
    @get:Rule val tmp = TemporaryFolder()

    private val o = Pt(-35.30, 149.10)
    private fun east(m: Double) = Geo.offset(o, 90.0, m)

    /** Ground that climbs at 5% going east and is level north to south. */
    private val slope = HeightSource { p -> 600.0 + Geo.metres(Pt(p.lat, o.lng), p) * (if (p.lng >= o.lng) 0.05 else -0.05) }

    private fun road(fromM: Double, toM: Double, name: String = "Main", bridge: Boolean = false): Road {
        val pts = ArrayList<Pt>()
        var d = fromM
        while (d < toM) {
            pts += east(d)
            d += 50.0
        }
        pts += east(toM)
        return Road(pts, name = name, bridge = bridge)
    }

    private fun pathAlong(vararg roads: Road) =
        RoadFollow(RoadList(roads.toList())).follow(Spot(roads[0], 0, 0.0, true), 5000.0)

    // Heights.

    private fun hgt(n: Int, value: (row: Int, col: Int) -> Int): File {
        val file = File(tmp.root, "S36E149.hgt")
        val buf = ByteBuffer.allocate(n * n * 2)
        for (row in 0 until n) for (col in 0 until n) buf.putShort(value(row, col).toShort())
        file.writeBytes(buf.array())
        return file
    }

    @Test fun namesTheFileThatCoversAPoint() {
        assertEquals("S36E149.hgt", HgtFile.nameFor(Pt(-35.30, 149.10)))
        assertEquals("N51W001.hgt", HgtFile.nameFor(Pt(51.5, -0.12)))
        assertEquals("N00E006.hgt", HgtFile.nameFor(Pt(0.4, 6.7)))
    }

    @Test fun readsAGridHeightAndBlendsBetweenThem() {
        // An 11 by 11 grid: rows run north to south from 35 S to 36 S, a tenth of a degree apart.
        val file = HgtFile(hgt(11) { row, col -> 100 + row * 10 + col })
        assertEquals(100.0, file.at(Pt(-35.0, 149.0))!!, 0.001)
        assertEquals(130.0 + 4.0, file.at(Pt(-35.3, 149.4))!!, 0.001)
        assertEquals(135.5, file.at(Pt(-35.35, 149.05))!!, 0.001)
        assertNull(file.at(Pt(-34.9, 149.5)))
    }

    @Test fun aHoleInTheGridHasNoHeight() {
        val file = HgtFile(hgt(11) { row, col -> if (row == 3 && col == 4) -32768 else 500 })
        assertNull(file.at(Pt(-35.31, 149.41)))
        assertEquals(500.0, file.at(Pt(-35.71, 149.71))!!, 0.001)
    }

    @Test fun aFolderAnswersOnlyWhereItHoldsAFile() {
        hgt(11) { _, _ -> 500 }
        val folder = HgtFolder(tmp.root)
        assertEquals(500.0, folder.at(Pt(-35.5, 149.5))!!, 0.001)
        assertNull(folder.at(Pt(-34.5, 149.5)))
    }

    // The profile.

    @Test fun aSteadySlopeGivesItsGradeInEveryBlock() {
        val profile = ProfileBuilder(slope).build(pathAlong(road(0.0, 1000.0)))!!
        assertEquals(1000.0, profile.lengthM, 0.1)
        val ahead = Ahead(profile.stepM, profile.heights, profile.lengthM, 1000.0, profile.end, profile.roadName)
        val blocks = ahead.blocks(100.0)
        assertEquals(10, blocks.size)
        for (b in blocks) assertEquals(5.0, b.gradePct, 0.05)
        assertEquals(50.0, ahead.climbM, 0.1)
        assertEquals(5.0, ahead.steepestPct(100.0)!!, 0.05)
    }

    @Test fun aBridgeIsDrawnStraightAcrossTheValleyUnderIt() {
        // The ground drops 30 m under the bridge between 400 and 600 m; the bridge doesn't.
        val valley = HeightSource { p ->
            val m = Geo.metres(o, p)
            slope.at(p)!! - (if (m > 410.0 && m < 590.0) 30.0 else 0.0)
        }
        val path = pathAlong(road(0.0, 400.0), road(400.0, 600.0, bridge = true), road(600.0, 1000.0))
        val bridged = ProfileBuilder(valley).build(path)!!
        assertEquals(600.0 + 25.0, bridged.heightAt(500.0), 1.0)
        val unbridged = ProfileBuilder(valley).build(pathAlong(road(0.0, 1000.0)))!!
        assertTrue(unbridged.heightAt(500.0) < 600.0)
    }

    @Test fun theProfileStopsWhereTheElevationFilesStop() {
        val half = HeightSource { p -> if (Geo.metres(o, p) > 510.0) null else slope.at(p) }
        val profile = ProfileBuilder(half).build(pathAlong(road(0.0, 1000.0)))!!
        assertEquals(500.0, profile.lengthM, 0.1)
        assertEquals(End.NO_HEIGHTS, profile.end)
    }

    @Test fun noElevationFileMeansNoProfile() {
        assertNull(ProfileBuilder(HeightSource { null }).build(pathAlong(road(0.0, 1000.0))))
    }

    // The tracker.

    private val side = Road(listOf(east(600.0), Geo.offset(east(600.0), 0.0, 400.0)), name = "Side")

    private fun tracker() = Tracker(RoadList(listOf(road(0.0, 3000.0), side)), slope)

    @Test fun waitsUntilItKnowsWhichWayYouAreGoing() {
        assertEquals(Look.Waiting, tracker().update(east(100.0), null, 2000.0))
    }

    @Test fun holdsOnePredictionWhileYouStayOnTheRoad() {
        val t = tracker()
        var look: Look = Look.Waiting
        for (m in 0..400 step 10) look = t.update(east(m.toDouble()), null, 2000.0)
        assertEquals(1, t.predictions)
        val ahead = (look as Look.Road).ahead
        assertEquals(2000.0, ahead.lengthM, 0.1)
        assertEquals(End.FAR_ENOUGH, ahead.end)
        assertEquals("Main", ahead.roadName)
        assertEquals(100.0, ahead.climbM, 0.1)
    }

    @Test fun showsLessRoadAsYouNearItsEnd() {
        val t = tracker()
        var look: Look = Look.Waiting
        for (m in 0..2000 step 10) look = t.update(east(m.toDouble()), null, 2000.0)
        val ahead = (look as Look.Road).ahead
        assertEquals(1000.0, ahead.lengthM, 0.1)
        assertEquals(End.ROAD_ENDS, ahead.end)
    }

    @Test fun predictsAgainWhenYouTurnOff() {
        val t = tracker()
        for (m in 0..600 step 10) t.update(east(m.toDouble()), null, 2000.0)
        assertEquals(1, t.predictions)
        var look: Look = Look.Waiting
        for (m in 10..100 step 10) look = t.update(Geo.offset(east(600.0), 0.0, m.toDouble()), null, 2000.0)
        assertEquals(2, t.predictions)
        val ahead = (look as Look.Road).ahead
        assertEquals("Side", ahead.roadName)
        assertEquals(300.0, ahead.lengthM, 0.1)
        // North to south the test ground is level.
        assertEquals(0.0, ahead.climbM, 0.5)
    }

    @Test fun predictsAgainWhenYouTurnRoundOnTheSameRoad() {
        val t = tracker()
        for (m in 0..600 step 10) t.update(east(m.toDouble()), null, 2000.0)
        var look: Look = Look.Waiting
        for (m in 590 downTo 500 step 10) look = t.update(east(m.toDouble()), null, 2000.0)
        assertEquals(2, t.predictions)
        assertEquals(500.0, (look as Look.Road).ahead.lengthM, 0.1)
    }

    @Test fun saysSoWhenThereIsNoRoad() {
        val t = Tracker(RoadList(emptyList()), slope)
        t.update(east(0.0), null, 2000.0)
        assertEquals(Look.NoRoad, t.update(east(20.0), null, 2000.0))
    }

    @Test fun namesTheMissingElevationFile() {
        val t = Tracker(RoadList(listOf(road(0.0, 3000.0))), HeightSource { null })
        t.update(east(0.0), null, 2000.0)
        assertEquals(Look.NoHeights("S36E149.hgt"), t.update(east(20.0), null, 2000.0))
    }
}
