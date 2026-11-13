package au.com.fjellconsulting.roadahead

import io.hammerhead.karooext.extension.KarooExtension

/**
 * Registers Road Ahead with the Karoo. It carries one field for ride pages. The Karoo starts
 * this when it starts, which starts the app and with it the engine.
 */
class RoadAheadExtension : KarooExtension("roadahead", "0.1.0") {
    override val types by lazy { listOf(RoadAheadField(extension)) }
}
