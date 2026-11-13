package au.com.fjellconsulting.roadahead

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import au.com.fjellconsulting.roadahead.core.Geo
import au.com.fjellconsulting.roadahead.core.HgtFile
import au.com.fjellconsulting.roadahead.core.Pt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Fetches elevation files for wherever the Karoo is, over Wi-Fi. One file covers a square one
 * degree on a side (about 110 km north to south), is about 12 MB to fetch and 26 MB on the Karoo.
 * The source is Terrain Tiles on AWS Open Data: free, public, no key.
 *
 * The Karoo turns Wi-Fi off during a ride, so this does its work at home, or at the hotel when
 * you travel.
 */
class HeightsDownload(private val app: RoadAheadApp, private val dir: File) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)
    private var lastTryMs = -RETRY_MS

    /** What the fetcher last did, in words, for the status screen. */
    @Volatile var status: String = "Nothing fetched yet."
        private set

    /** The elevation files on the Karoo. */
    fun held(): List<File> = dir.listFiles { f -> f.name.endsWith(".hgt") }?.sortedBy { it.name }.orEmpty()

    fun delete(name: String) {
        File(dir, name).delete()
        app.engine.heightsChanged()
    }

    /** The files that cover everywhere within a distance of a point: its own square and any close neighbour. */
    fun needed(p: Pt, radiusKm: Int): List<String> {
        val out = ArrayList<String>()
        val lat0 = floor(p.lat).toInt()
        val lng0 = floor(p.lng).toInt()
        for (dLat in -1..1) for (dLng in -1..1) {
            val south = (lat0 + dLat).toDouble()
            val west = (lng0 + dLng).toDouble()
            val nearest = Pt(p.lat.coerceIn(south, south + 1.0), p.lng.coerceIn(west, west + 1.0))
            if (Geo.metres(p, nearest) <= radiusKm * 1000.0) out += HgtFile.nameFor(Pt(south + 0.5, west + 0.5))
        }
        return out
    }

    /** Called with each GPS fix. Fetches what's missing, if there's Wi-Fi, and not more than once a minute. */
    fun consider(p: Pt, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastTryMs < RETRY_MS) return
        lastTryMs = now
        val missing = needed(p, app.prefs.fetchKm).filter { !File(dir, it).isFile && !knownMissing(it) }
        if (missing.isEmpty()) {
            if (force) status = "Nothing to fetch: every file for here is on the Karoo."
            return
        }
        if (!onWifi()) {
            status = "Waiting for Wi-Fi to fetch ${missing.joinToString()}."
            return
        }
        if (!busy.compareAndSet(false, true)) return
        scope.launch {
            try {
                for (name in missing) fetch(name)
            } finally {
                busy.set(false)
            }
        }
    }

    /** A square of open sea has no file. That's remembered for a month, so it isn't asked for on every ride. */
    private fun knownMissing(name: String): Boolean {
        val mark = File(dir, "$name.none")
        return mark.isFile && System.currentTimeMillis() - mark.lastModified() < 30L * 24 * 3600 * 1000
    }

    private fun onWifi(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun fetch(name: String) {
        status = "Fetching $name..."
        val part = File(dir, "$name.part")
        try {
            val conn = URL("$BASE/${name.substring(0, 3)}/$name.gz").openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            // The file is gzipped on the server as a file, not as a transfer encoding.
            conn.setRequestProperty("Accept-Encoding", "identity")
            when (val code = conn.responseCode) {
                200 -> {
                    GZIPInputStream(conn.inputStream).use { input -> part.outputStream().use { input.copyTo(it) } }
                    val n = sqrt(part.length() / 2.0).roundToInt()
                    check(n >= 2 && n.toLong() * n * 2 == part.length()) { "the file isn't a whole grid" }
                    val bytes = part.length()
                    check(part.renameTo(File(dir, name))) { "couldn't save the file" }
                    status = "Fetched $name."
                    Log.i(Engine.TAG, "fetched $name, $bytes bytes")
                    app.engine.heightsChanged()
                }
                403, 404 -> {
                    File(dir, "$name.none").writeText("")
                    status = "There's no elevation file for $name. It's probably open sea."
                }
                else -> status = "Couldn't fetch $name: the server answered $code."
            }
            conn.disconnect()
        } catch (e: Exception) {
            status = "Couldn't fetch $name: ${e.message ?: e.javaClass.simpleName}."
            Log.w(Engine.TAG, "fetch of $name failed", e)
        } finally {
            part.delete()
        }
    }

    private companion object {
        const val BASE = "https://elevation-tiles-prod.s3.amazonaws.com/skadi"
        const val RETRY_MS = 60_000L
    }
}
