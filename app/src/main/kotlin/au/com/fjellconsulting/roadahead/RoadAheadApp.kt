package au.com.fjellconsulting.roadahead

import android.app.Application
import android.content.Context
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Holds the link to the Karoo's own services, the rider's settings, the engine that predicts
 * the road, and the fetcher for elevation files. Everything runs on the Karoo: there is no
 * server and no account.
 */
class RoadAheadApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var karoo: KarooSystemService
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var engine: Engine
        private set
    lateinit var download: HeightsDownload
        private set

    override fun onCreate() {
        super.onCreate()
        karoo = KarooSystemService(this)
        karoo.connect()
        prefs = Prefs(this)
        engine = Engine(this)
        download = HeightsDownload(this, engine.heightsDir)
        engine.start()
    }
}

val Context.roadAhead: RoadAheadApp get() = applicationContext as RoadAheadApp
