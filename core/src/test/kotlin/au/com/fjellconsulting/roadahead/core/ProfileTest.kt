package au.com.fjellconsulting.roadahead.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

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
        assertEquals(5.0, ahead.steepestPct()!!, 0.05)
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

    // The picture.

    private fun ahead(lengthM: Double, end: End, wantM: Double = 2000.0): Ahead {
        val dists = sampleDists(lengthM, 25.0)
        return Ahead(25.0, DoubleArray(dists.size) { 600.0 + dists[it] * 0.05 }, lengthM, wantM, end, "Main")
    }

    private fun texts(p: Picture) = p.shapes.filterIsInstance<Label>().map { l -> l.runs.joinToString("") { it.text } }

    @Test fun drawsTwentyBlocksInTheGradeColourWithTheNumbersOnTop() {
        val p = FieldPainter.paint(Look.Road(ahead(2000.0, End.FAR_ENOUGH)), 480, 150, FieldSettings())
        val fills = p.shapes.filterIsInstance<Fill>()
        assertEquals(20, fills.size)
        // 5% all the way is the Karoo's yellow.
        assertTrue(fills.all { it.argb == 0xFFF0D800.toInt() })
        // Large text on a tile of ordinary height: the numbers alone, no caption or road name.
        assertEquals(listOf("2.0 km", "+100 m", "5 % max"), texts(p))
        assertEquals(listOf("ROAD AHEAD", "Main", "2.0 km", "+100 m", "5 % max"),
            texts(FieldPainter.paint(Look.Road(ahead(2000.0, End.FAR_ENOUGH)), 480, 150, FieldSettings(textSize = TextSize.SMALL))))
        // Nothing is drawn outside the field.
        for (f in fills) assertTrue(f.ys.all { it >= 0f && it <= 150f } && f.xs.all { it >= 0f && it <= 481f })
    }

    @Test fun marksAJunctionAndSaysHowFar() {
        val p = FieldPainter.paint(Look.Road(ahead(1100.0, End.TURN)), 480, 150, FieldSettings(textSize = TextSize.SMALL))
        assertTrue(texts(p).contains("junction in 1.1 km"))
        assertTrue(p.shapes.filterIsInstance<Stroke>().any { it.dashed })
        assertEquals(11, p.shapes.filterIsInstance<Fill>().size)
    }

    @Test fun dropsWhatDoesNotFitANarrowOrShortField() {
        // A half-width tile fits all three by shrinking them, rather than dropping one.
        val narrowPicture = FieldPainter.paint(Look.Road(ahead(2000.0, End.FAR_ENOUGH)), 240, 150, FieldSettings(labels = Labels.ALWAYS))
        assertEquals(listOf("2.0 km", "+100 m", "5 % max"), texts(narrowPicture))
        assertTrue(narrowPicture.shapes.filterIsInstance<Label>().all { it.runs[0].size < 32f && it.runs[0].size >= 24f })
        val narrowSmall = texts(FieldPainter.paint(Look.Road(ahead(2000.0, End.FAR_ENOUGH)), 240, 150, FieldSettings(labels = Labels.ALWAYS, textSize = TextSize.SMALL)))
        assertEquals(listOf("ROAD AHEAD", "2.0 km", "+100 m", "5 % max"), narrowSmall)
        val short = texts(FieldPainter.paint(Look.Road(ahead(2000.0, End.FAR_ENOUGH)), 480, 90, FieldSettings()))
        assertEquals(listOf("2.0 km", "+100 m", "5 % max"), short)
    }

    @Test fun aHalfWidthTileIsAllGraphUnlessTheRiderAsksForWords() {
        val look = Look.Road(ahead(2000.0, End.FAR_ENOUGH))
        assertEquals(emptyList<String>(), texts(FieldPainter.paint(look, 240, 150, FieldSettings())))
        assertEquals(emptyList<String>(), texts(FieldPainter.paint(look, 480, 150, FieldSettings(labels = Labels.NEVER))))
        assertEquals(3, texts(FieldPainter.paint(look, 480, 150, FieldSettings())).size)
        // With no words the graph takes the room they left.
        fun top(p: Picture) = p.shapes.filterIsInstance<Fill>().minOf { f -> f.ys.min() }
        assertTrue(top(FieldPainter.paint(look, 240, 150, FieldSettings())) < top(FieldPainter.paint(look, 240, 150, FieldSettings(labels = Labels.ALWAYS))) - 20f)
    }

    private fun sizes(p: Picture) = p.shapes.filterIsInstance<Label>().map { l -> l.runs.map { it.size } }

    @Test fun largeTextIsBigEnoughToReadAndSmallIsAsItWas() {
        val look = Look.Road(ahead(2000.0, End.FAR_ENOUGH))
        // Ordinary tile: 40 px numbers with 24 px units, where Small has 26 and 14.3.
        assertEquals(listOf(40f, 24f), sizes(FieldPainter.paint(look, 480, 150, FieldSettings()))[0])
        val small = sizes(FieldPainter.paint(look, 480, 150, FieldSettings(textSize = TextSize.SMALL)))
        assertEquals(listOf(13f), small[0])
        assertEquals(26f, small[2][0])
        // A tall tile keeps the caption and the road name, at 22 px, over 44 px numbers.
        val tall = FieldPainter.paint(look, 480, 300, FieldSettings())
        assertEquals(listOf("ROAD AHEAD", "Main", "2.0 km", "+100 m", "5 % max"), texts(tall))
        assertEquals(listOf(22f), sizes(tall)[0])
        assertEquals(44f, sizes(tall)[2][0])
        // Large numbers take no more height from the graph than Small's two rows did.
        fun top(p: Picture) = p.shapes.filterIsInstance<Fill>().minOf { f -> f.ys.min() }
        assertTrue(top(FieldPainter.paint(look, 480, 150, FieldSettings())) <= top(FieldPainter.paint(look, 480, 150, FieldSettings(textSize = TextSize.SMALL))) + 4f)
        // The numbers sit above the graph, never on it.
        val numberY = FieldPainter.paint(look, 480, 150, FieldSettings()).shapes.filterIsInstance<Label>()[0].y
        assertTrue(numberY < top(FieldPainter.paint(look, 480, 150, FieldSettings())))
    }

    @Test fun aJunctionNoteShrinksToTheDistanceWhenTheSentenceWouldNotFit() {
        // Half way across there is no room either side for the sentence at the Large size.
        val mid = texts(FieldPainter.paint(Look.Road(ahead(1100.0, End.TURN)), 480, 150, FieldSettings()))
        assertTrue(mid.toString(), mid.contains("1.1 km") && !mid.contains("junction in 1.1 km"))
        // Far across, the sentence fits to the left of the mark.
        val far = texts(FieldPainter.paint(Look.Road(ahead(1600.0, End.TURN)), 480, 150, FieldSettings()))
        assertTrue(far.toString(), far.contains("junction in 1.6 km"))
        // There the words lie over the graph, so a dark panel goes behind them. To the right of the end there is none.
        fun panels(p: Picture) = p.shapes.filterIsInstance<Label>().count { it.panel != null }
        assertEquals(1, panels(FieldPainter.paint(Look.Road(ahead(1600.0, End.TURN)), 480, 150, FieldSettings())))
        assertEquals(0, panels(FieldPainter.paint(Look.Road(ahead(600.0, End.TURN)), 480, 150, FieldSettings())))
    }

    @Test fun aJunctionWithNoWordsKeepsItsMarkAndDropsTheText() {
        val p = FieldPainter.paint(Look.Road(ahead(1100.0, End.TURN)), 240, 150, FieldSettings())
        assertEquals(emptyList<String>(), texts(p))
        assertTrue(p.shapes.filterIsInstance<Stroke>().any { it.dashed })
    }

    @Test fun theLineIsBoldAndAFlatRoadRunsThroughTheMiddle() {
        val dists = sampleDists(2000.0, 25.0)
        val flat = Ahead(25.0, DoubleArray(dists.size) { 600.0 }, 2000.0, 2000.0, End.FAR_ENOUGH, "Main")
        val p = FieldPainter.paint(Look.Road(flat), 240, 150, FieldSettings())
        val line = p.shapes.filterIsInstance<Stroke>().first { !it.dashed }
        assertTrue("line is ${line.width} px", line.width >= 3f)
        // No words on this tile, so the graph has the whole height: the middle is about 75.
        assertTrue("flat line at ${line.ys[0]}", line.ys.all { it in 65f..85f })
    }

    @Test fun aClimbStartsLowAndADescentStartsHigh() {
        fun line(slope: Double): Stroke {
            val dists = sampleDists(2000.0, 25.0)
            val a = Ahead(25.0, DoubleArray(dists.size) { 600.0 + dists[it] * slope }, 2000.0, 2000.0, End.FAR_ENOUGH, "Main")
            return FieldPainter.paint(Look.Road(a), 240, 150, FieldSettings()).shapes.filterIsInstance<Stroke>().first { !it.dashed }
        }
        val up = line(0.06)
        val down = line(-0.06)
        // y grows downwards: a climb starts near the bottom, a descent near the top.
        assertTrue(up.ys.first() > 110f && up.ys.last() < 40f)
        assertTrue(down.ys.first() < 40f && down.ys.last() > 110f)
    }

    @Test fun pastAGuessedJunctionTheBlocksAreDimmedAndATickMarksIt() {
        val dists = sampleDists(2000.0, 25.0)
        val a = Ahead(25.0, DoubleArray(dists.size) { 600.0 + dists[it] * 0.05 }, 2000.0, 2000.0, End.FAR_ENOUGH, "Main", guessFromM = 1000.0)
        val p = FieldPainter.paint(Look.Road(a), 480, 150, FieldSettings())
        val fills = p.shapes.filterIsInstance<Fill>()
        assertEquals(10, fills.count { it.argb == 0xFFF0D800.toInt() })
        assertEquals(10, fills.count { it.argb == FieldPainter.dim(0xFFF0D800.toInt()) })
        // The tick stands on the line at the junction, half way across.
        assertTrue(p.shapes.filterIsInstance<Stroke>().any { !it.dashed && it.xs.size == 2 && it.xs[0] == it.xs[1] && it.xs[0] in 238f..242f })
        // Nothing known is dimmed when there is no guess in view.
        assertTrue(FieldPainter.paint(Look.Road(ahead(2000.0, End.FAR_ENOUGH)), 480, 150, FieldSettings()).shapes.filterIsInstance<Fill>().all { it.argb == 0xFFF0D800.toInt() })
    }

    @Test fun followsTheRidersSettings() {
        val look = Look.Road(ahead(2000.0, End.FAR_ENOUGH))
        val plain = texts(FieldPainter.paint(look, 480, 150, FieldSettings(showClimb = false, showSteepest = false)))
        assertEquals(listOf("2.0 km"), plain)
        val miles = texts(FieldPainter.paint(look, 480, 150, FieldSettings(imperial = true)))
        assertTrue(miles.contains("1.2 mi") && miles.contains("+328 ft"))
        val wahoo = FieldPainter.paint(look, 480, 150, FieldSettings(palette = Palette.WAHOO))
        assertTrue(wahoo.shapes.filterIsInstance<Fill>().all { it.argb == 0xFFFEFF00.toInt() })
    }

    @Test fun downhillIsGrey() {
        assertEquals(Palette.DOWNHILL, Palette.KAROO.colour(-3.0))
        assertEquals(0xFF1A8C3A.toInt(), Palette.KAROO.colour(0.0))
        assertEquals(0xFF9020A0.toInt(), Palette.KAROO.colour(25.0))
    }

    @Test fun saysWhyItCannotDraw() {
        assertEquals(listOf("ROAD AHEAD", "Start riding", "to see the road ahead"), texts(FieldPainter.paint(Look.Waiting, 480, 150, FieldSettings())))
        assertEquals(22f, FieldPainter.paint(Look.Waiting, 480, 150, FieldSettings()).shapes.filterIsInstance<Label>().last().runs[0].size)
        assertEquals(17f, FieldPainter.paint(Look.Waiting, 480, 150, FieldSettings(textSize = TextSize.SMALL)).shapes.filterIsInstance<Label>().last().runs[0].size)
        assertEquals(listOf("No elevation here"), texts(FieldPainter.paint(Look.NoHeights("S36E149.hgt"), 480, 80, FieldSettings())))
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

    /** Ground that is level for 1,030 m east of `o`, then climbs at 6%. */
    private val levelThenClimb = HeightSource { p ->
        val m = Geo.metres(Pt(p.lat, o.lng), p) * (if (p.lng >= o.lng) 1.0 else -1.0)
        600.0 + max(0.0, m - 1030.0) * 0.06
    }

    @Test fun aBlockKeepsItsPlaceAndItsColourAsYouRide() {
        val t = Tracker(RoadList(listOf(road(0.0, 9000.0))), levelThenClimb)
        // Every grade each block was drawn with, by where on the road the block starts.
        val grades = HashMap<Long, MutableSet<Long>>()
        for (m in 0..2600 step 5) {
            val look = t.update(east(m.toDouble()), null, 2000.0) as? Look.Road ?: continue
            for (b in look.ahead.colourBlocks.drop(1)) {
                grades.getOrPut(Math.round(t.alongM + b.fromM)) { HashSet() } += Math.round(b.gradePct * 100)
            }
        }
        // The ride went past where the first prediction ran short, and a second one took over.
        assertEquals(2, t.predictions)
        // Blocks start every 100 m along the road, and none changed colour.
        val starts = grades.keys.sorted()
        assertTrue(starts.size > 40)
        assertTrue(starts.zipWithNext().all { (a, b) -> b - a == 100L })
        assertTrue(grades.values.all { it.size == 1 })
        // Level before the climb, 6% well into it.
        assertEquals(0.0, grades.getValue(starts.first()).single() / 100.0, 0.05)
        assertEquals(6.0, grades.getValue(starts.last()).single() / 100.0, 0.05)
    }

    @Test fun aNewRoadGetsItsOwnBlocks() {
        val t = tracker()
        for (m in 0..600 step 10) t.update(east(m.toDouble()), null, 2000.0)
        var look: Look = Look.Waiting
        for (m in 10..100 step 10) look = t.update(Geo.offset(east(600.0), 0.0, m.toDouble()), null, 2000.0)
        // Main climbs at 5%, Side is level: none of Main's grades carry over.
        val blocks = (look as Look.Road).ahead.colourBlocks
        assertTrue(blocks.isNotEmpty())
        assertTrue(blocks.all { kotlin.math.abs(it.gradePct) < 0.05 })
    }

    private fun along(from: Pt, bearing: Double, m: Double) = (0..(m / 50.0).toInt()).map { Geo.offset(from, bearing, it * 50.0) }

    @Test fun showsTheWholeDistanceAllTheWayRoundACircuit() {
        // A 1,000 m by 200 m circuit in two pieces, ridden east along its bottom side from `o`.
        val se = east(1000.0)
        val ne = Geo.offset(se, 0.0, 200.0)
        val nw = Geo.offset(o, 0.0, 200.0)
        val t = Tracker(RoadList(listOf(
            Road(along(o, 90.0, 1000.0) + along(se, 0.0, 200.0).drop(1), name = "Circuit"),
            Road(along(ne, 270.0, 1000.0) + along(nw, 180.0, 200.0).drop(1), name = "Circuit"),
        )), slope)
        val shown = ArrayList<Double>()
        for (m in 0..1000 step 10) (t.update(east(m.toDouble()), null, 2000.0) as? Look.Road)?.let { shown += it.ahead.lengthM }
        // A lap is 2,400 m, so 2,000 m of it is always there to show.
        assertTrue(shown.size > 90)
        assertTrue("shortest ${shown.min()}", shown.all { it >= 1999.0 })
    }

    @Test fun doesNotPlanAgainBeforeReachingTheCircuitItEndsOn() {
        // Main runs 1,000 m east into a 400 m ring in two pieces, so the lap ends 1,400 m on.
        val end = east(1000.0)
        val e = Geo.offset(end, 90.0, 100.0)
        val ne = Geo.offset(e, 0.0, 100.0)
        val n = Geo.offset(end, 0.0, 100.0)
        val ring = listOf(Road(listOf(end, e, ne), name = "Ring"), Road(listOf(ne, n, end), name = "Ring"))
        val t = Tracker(RoadList(listOf(road(0.0, 1000.0)) + ring), slope)
        var look: Look = Look.Waiting
        for (m in 0..900 step 10) look = t.update(east(m.toDouble()), null, 2000.0)
        assertEquals(1, t.predictions)
        assertEquals(End.LOOP, (look as Look.Road).ahead.end)
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

    @Test fun averageGradeRunsToTheHighOrLowPoint() {
        fun ahead(heights: DoubleArray) = Ahead(10.0, heights, (heights.size - 1) * 10.0, 100.0, End.FAR_ENOUGH, null)
        assertEquals(50.0, ahead(doubleArrayOf(0.0, 5.0, 10.0, 15.0)).averageToTopPct(), 0.001)
        assertEquals(66.6667, ahead(doubleArrayOf(0.0, 10.0, 5.0, 20.0, 10.0)).averageToTopPct(), 0.001)
        assertEquals(-50.0, ahead(doubleArrayOf(20.0, 15.0, 10.0)).averageToTopPct(), 0.001)
        assertEquals(0.0, ahead(doubleArrayOf(20.0, 21.0, 20.0)).averageToTopPct(), 0.001)
    }

    @Test fun laysOutNumbersInOrderAndDropsDistanceWhenNeeded() {
        val a = ahead(2000.0, End.FAR_ENOUGH)
        fun labels(settings: FieldSettings, w: Int, h: Int) = FieldPainter.paint(Look.Road(a), w, h, settings).shapes.filterIsInstance<Label>()
        // David Garcia's layout D on a half-width tile: climb, average and steepest, all of them.
        val three = labels(FieldSettings(labels = Labels.ALWAYS, showDistance = false, showAverage = true), 240, 128)
        assertEquals(listOf("+100 m", "5 % avg", "5 % max"), three.map { it.runs.joinToString("") { r -> r.text } })
        assertEquals(10f, three.first().x, 0f)
        assertEquals(230f, three.last().x, 0f)
        assertTrue(three.zipWithNext().all { it.first.x < it.second.x })
        val narrow = labels(FieldSettings(labels = Labels.ALWAYS, showAverage = true), 240, 128).map { it.runs.joinToString("") { r -> r.text } }
        assertEquals(listOf("+100 m", "5 % avg", "5 % max"), narrow)
        val wide = labels(FieldSettings(labels = Labels.ALWAYS, showAverage = true), 480, 150).map { it.runs.joinToString("") { r -> r.text } }
        assertEquals(listOf("2.0 km", "+100 m", "5 % avg", "5 % max"), wide)
    }

    @Test fun trailIgnoresWanderInterpolatesAndTrims() {
        val trail = Trail(10.0, 25.0, 300.0)
        trail.add(east(0.0), 0.0)
        trail.add(east(5.0), 5.0)
        trail.add(east(10.0), 10.0)
        trail.add(east(20.0), 20.0)
        trail.add(east(30.0), 30.0)
        val back = trail.behind(10.0, 20.0)
        assertEquals(2, back.size)
        assertEquals(20.0, back[0], 0.01)
        assertEquals(10.0, back[1], 0.01)
        trail.add(east(40.0), 40.0)
        trail.add(east(50.0), 50.0)
        assertTrue(trail.behind(10.0, 100.0).size <= 3)
        trail.add(east(450.0), 450.0)
        assertEquals(0, trail.behind(10.0, 100.0).size)
    }

    @Test fun behindViewDrawsLeftOfTheRiderAndOffKeepsTheOldDot() {
        val base = ahead(2000.0, End.FAR_ENOUGH)
        val a = Ahead(base.stepM, base.heights, base.lengthM, base.wantM, base.end, base.roadName, behind = doubleArrayOf(610.0, 620.0, 630.0))
        val p = FieldPainter.paint(Look.Road(a), 480, 150, FieldSettings(behind = Behind.FIFTH))
        val x0 = 96f
        val fill = p.shapes.filterIsInstance<Fill>().first { it.argb == 0xFF262B30.toInt() }
        assertTrue(fill.xs.all { it <= x0 })
        assertEquals(x0, p.shapes.filterIsInstance<Dot>().single().x, 0f)
        val old = FieldPainter.paint(Look.Road(a), 480, 150, FieldSettings())
        assertEquals(5f, old.shapes.filterIsInstance<Dot>().single().x, 0f)
        assertTrue(old.shapes.filterIsInstance<Fill>().none { it.argb == 0xFF262B30.toInt() })
    }

    @Test fun fillUsesMoreOfTheGraphThanTrueScale() {
        val dists = sampleDists(2000.0, 25.0)
        val a = Ahead(25.0, DoubleArray(dists.size) { 500.0 + dists[it] * 0.01 }, 2000.0, 2000.0, End.FAR_ENOUGH, null)
        // With no words the graph runs from y 8 to the base at y 142, 134 px.
        fun ratio(height: GraphHeight): Double {
            val p = FieldPainter.paint(Look.Road(a), 480, 150, FieldSettings(labels = Labels.NEVER, graphHeight = height))
            val line = p.shapes.filterIsInstance<Stroke>().first { !it.dashed }
            return (line.ys.max() - line.ys.min()) / 134.0
        }
        assertTrue(ratio(GraphHeight.FILL) >= 0.8)
        assertTrue(ratio(GraphHeight.TRUE) < 0.5)
    }

    @Test fun trackerTrailDoesNotChangeItsPredictionOrPosition() {
        val withTrail = tracker()
        val withoutTrail = tracker()
        var look: Look = Look.Waiting
        for (m in 0..600 step 10) {
            look = withTrail.update(east(m.toDouble()), null, 2000.0, 500.0)
            withoutTrail.update(east(m.toDouble()), null, 2000.0)
        }
        val ahead = (look as Look.Road).ahead
        assertTrue(ahead.behind.isNotEmpty())
        assertEquals(withoutTrail.currentPath!!.lengthM, withTrail.currentPath!!.lengthM, 0.0)
        assertEquals(withoutTrail.positionM, withTrail.positionM, 0.0)
    }


    @Test fun shrinkingTheNumberRowGivesHeightBackToTheGraph() {
        val look = Look.Road(ahead(2000.0, End.FAR_ENOUGH))
        val shrunk = FieldPainter.paint(look, 270, 150, FieldSettings(labels = Labels.ALWAYS, showAverage = true))
        val full = FieldPainter.paint(look, 270, 150, FieldSettings(labels = Labels.ALWAYS, showDistance = false, showClimb = true, showAverage = true, showSteepest = false))
        fun plotTop(p: Picture) = p.shapes.filterIsInstance<Fill>().minOf { it.ys.min() }
        assertTrue(plotTop(shrunk) < plotTop(full))
    }

}
