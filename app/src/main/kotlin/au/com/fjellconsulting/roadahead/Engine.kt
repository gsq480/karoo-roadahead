package au.com.fjellconsulting.roadahead

import android.Manifest
import android.content.pm.PackageManager
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import au.com.fjellconsulting.roadahead.core.End
import au.com.fjellconsulting.roadahead.core.Geo
import au.com.fjellconsulting.roadahead.core.HgtFolder
import au.com.fjellconsulting.roadahead.core.Look
import au.com.fjellconsulting.roadahead.core.MapRoads
import au.com.fjellconsulting.roadahead.core.Pt
import au.com.fjellconsulting.roadahead.core.Tracker
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

/**
 * Runs the prediction on the Karoo. Every GPS fix goes to the tracker, and what the field should
 * show comes out as [look]. It runs for as long as the app's process does, with or without the
 * field on screen.
 *
 * All of the work happens on one background thread, because reading a map tile off storage is
 * too slow for the main thread, and because the tracker's state wants a single writer.
 */
class Engine(private val app: RoadAheadApp) {
    private class Fix(val pt: Pt, val orientation: Double?)

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "road-ahead").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    // Only the newest fix matters. If the worker is busy reading a map tile, older ones are dropped.
    private val fixes = Channel<Fix>(Channel.CONFLATED)

    val heightsDir: File = File(app.filesDir, "heights").apply { mkdirs() }
    private val heights = HgtFolder(heightsDir)
    private var roads: MapRoads? = null
    private var tracker: Tracker? = null
    private var last: Fix? = null
    private var demoUntilMs = 0L

    private val shown = MutableStateFlow<Look>(Look.Waiting)

    /** What the field should show right now. */
    val look: StateFlow<Look> = shown

    /** Whether the rider's Karoo is set to miles and feet. */
    @Volatile var imperial = false
        private set

    /** The map files found on the Karoo. Empty until the rider allows access. */
    @Volatile var mapFiles: List<String> = emptyList()
        private set

    /** Where the last fix was, for the status screen. */
    @Volatile var lastAt: Pt? = null
        private set

    /** Why the last new prediction was made, and how many there have been. For the status screen. */
    @Volatile var lastWhy: String = ""
        private set

    /** The Karoo keeps its maps in a folder that only the storage permission's group can read. */
    val mapsAllowed: Boolean
        get() = app.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun start() {
        scope.launch {
            openMaps()
            for (fix in fixes) if (SystemClock.elapsedRealtime() >= demoUntilMs) take(fix)
        }
        app.karoo.addConsumer { event: OnLocationChanged ->
            fixes.trySend(Fix(Pt(event.lat, event.lng), event.orientation))
        }
        app.karoo.addConsumer { profile: UserProfile ->
            imperial = profile.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL
            again()
        }
        app.prefs.listen { again() }
    }

    /** Work the picture out again from the last fix: a setting changed. */
    fun again() {
        scope.launch { last?.let { take(it) } }
    }

    /** Look for map files again: the rider has just allowed access. */
    fun reopenMaps() {
        scope.launch {
            openMaps()
            last?.let { take(it) }
        }
    }

    /** An elevation file arrived or was deleted. */
    fun heightsChanged() {
        scope.launch {
            heights.refresh()
            tracker?.forget()
            last?.let { take(it) }
        }
    }

    /**
     * Put the Karoo somewhere, heading somewhere, for a minute: a check at the desk that needs no
     * ride. Real fixes are ignored until the minute is up.
     */
    fun demo(lat: Double, lng: Double, course: Double) {
        scope.launch {
            demoUntilMs = SystemClock.elapsedRealtime() + 60_000L
            tracker?.forget()
            val at = Pt(lat, lng)
            // Two fixes a few metres apart are what tell the tracker which way it's heading.
            take(Fix(Geo.offset(at, course + 180.0, 24.0), null))
            take(Fix(Geo.offset(at, course + 180.0, 12.0), null))
            take(Fix(at, null))
            Log.i(TAG, "demo at $lat,$lng heading $course: ${describe(shown.value)}")
        }
    }

    private fun openMaps() {
        roads?.close()
        roads = null
        tracker = null
        mapFiles = emptyList()
        if (!mapsAllowed) {
            Log.i(TAG, "no access to the map folder yet")
            return
        }
        val dir = File(File(Environment.getExternalStorageDirectory(), "offline"), "maps")
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("map", ignoreCase = true) }?.toList().orEmpty()
        if (files.isEmpty()) {
            Log.w(TAG, "no map files in $dir")
            return
        }
        // 24 map tiles is about 50 km of riding in hand, and a few megabytes of roads.
        val opened = MapRoads(files, keep = 24)
        roads = opened
        mapFiles = opened.fileNames
        tracker = Tracker(opened, heights)
        Log.i(TAG, "opened ${opened.fileCount} of ${files.size} map files")
    }

    private fun take(fix: Fix) {
        last = fix
        lastAt = fix.pt
        val t = tracker
        val before = t?.predictions
        val look = if (t == null) Look.NoMap else runCatching {
            t.update(fix.pt, fix.orientation, app.prefs.wantKm * 1000.0)
        }.getOrElse {
            Log.w(TAG, "the prediction failed", it)
            Look.NoRoad
        }
        if (t != null && t.predictions != before) {
            lastWhy = "${t.predictions}: ${t.why}"
            Log.i(TAG, "prediction ${t.predictions} (${t.why}): ${describe(look)}")
        }
        shown.value = look
        app.download.consider(fix.pt)
    }

    /** One line saying what a look is, for the log and the status screen. */
    fun describe(look: Look): String = when (look) {
        is Look.Road -> look.ahead.let {
            val stop = when (it.end) {
                End.FAR_ENOUGH -> "the road carries on past that"
                End.TURN -> "then a junction"
                End.ROAD_ENDS -> "then the road ends"
                End.LOOP -> "then it comes back round"
                End.NO_MAP -> "then the map ends"
                End.NO_HEIGHTS -> "then the elevation files end"
            }
            "%s, %.0f m of %.0f m shown, %.0f m of climbing, %s".format(it.roadName ?: "a road with no name", it.lengthM, it.wantM, it.climbM, stop)
        }
        Look.Waiting -> "waiting for you to move"
        Look.NoRoad -> "no mapped road nearby"
        Look.NoMap -> "no map for this spot"
        is Look.NoHeights -> "no elevation file here (${look.file})"
    }

    companion object {
        const val TAG = "RoadAhead"
    }
}
