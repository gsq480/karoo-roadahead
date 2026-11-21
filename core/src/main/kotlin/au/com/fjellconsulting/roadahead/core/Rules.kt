package au.com.fjellconsulting.roadahead.core

/**
 * Every number the prediction turns on, in one place. These are the values that still need a new
 * build to change; what a rider can change lives in the app's settings.
 */
data class Rules(
    /** A GPS fix snaps to a road no further away than this. */
    val snapRadiusM: Double = 35.0,
    /** ...and only to one running within this many degrees of the way you're heading. */
    val snapMaxAngle: Double = 50.0,
    /** A road pointing the wrong way costs this many metres per degree when roads compete. */
    val snapAngleCostM: Double = 0.3,
    /** Footpaths and driveways lose to a road beside them by this many metres. */
    val snapMinorCostM: Double = 8.0,
    /**
     * While you're within this of the predicted road, the prediction holds. It has to be wider
     * than the snap radius, or a fix between the two snaps to a road and loses it straight away.
     */
    val holdRadiusM: Double = 45.0,
    val holdMaxAngle: Double = 60.0,
    /** Two pieces join when one ends within this of the other. */
    val joinM: Double = 2.5,
    /**
     * Where the way taken at a junction stops on the map no further on than this, the picture
     * goes back and takes another road there, as a guess. The Karoo's map leaves out service
     * roads, so a stub that carries on as one seems to end (29 m at Eucumbene Drive's end).
     */
    val gapM: Double = 35.0,
    /**
     * ...turning up to this sharply. Never onto the same road going back, such as the far side of
     * a divided road (125 degrees at Eucumbene Drive's end, where the turn into Hindmarsh Drive is
     * 111).
     */
    val sharpTurn: Double = 150.0,
    /**
     * A roundabout exit turning more than this from the way you came in takes you back, so your
     * road's name doesn't win it (142 degrees at Drumree Road's slip lane out, Dunshaughlin).
     */
    val backTurn: Double = 120.0,
    /** "Straight on" at the end of a piece means a turn no bigger than this. */
    val straightAngle: Double = 30.0,
    /** Anything sharper than this is going back the way you came, never a way onward. */
    val maxTurn: Double = 100.0,
    /** A road's direction at a join is read over this many metres, so one short kinked segment doesn't decide it. */
    val bearingLookM: Double = 15.0,
    /** The prediction gives up after this many pieces, as a guard against going in circles. */
    val maxPieces: Int = 400,
    /** Heights are read this often along the road. */
    val sampleM: Double = 25.0,
    /** Each height is averaged with this many neighbours on each side, twice over. */
    val smoothSamples: Int = 2,
)
