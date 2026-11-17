package au.com.fjellconsulting.roadahead

import android.content.Context
import au.com.fjellconsulting.roadahead.core.Behind
import au.com.fjellconsulting.roadahead.core.FieldSettings
import au.com.fjellconsulting.roadahead.core.GraphHeight
import au.com.fjellconsulting.roadahead.core.Labels
import au.com.fjellconsulting.roadahead.core.Palette
import au.com.fjellconsulting.roadahead.core.TextSize
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Everything a rider can change, kept on the Karoo. The settings screen writes these with
 * pickers, so no value can be mistyped.
 */
class Prefs(context: Context) {
    private val store = context.getSharedPreferences("roadahead", Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    /** Call back whenever a setting changes. Returns a way to stop listening. */
    fun listen(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    private fun changed() = listeners.forEach { it() }

    /** The distances a tap on the field steps through, in kilometres, shortest first. */
    var distancesKm: List<Int>
        get() = (store.getString("distances", null) ?: "2,5,10").split(',')
            .mapNotNull { it.toIntOrNull() }.filter { it in DISTANCES }.distinct().sorted().ifEmpty { listOf(2) }
        set(value) {
            store.edit().putString("distances", value.sorted().joinToString(",")).apply()
            changed()
        }

    /** The distance the field shows right now. */
    var wantKm: Int
        get() = store.getInt("want", 2).let { if (it in distancesKm) it else distancesKm.first() }
        set(value) {
            store.edit().putInt("want", value).apply()
            changed()
        }

    /** A tap on the field: on to the next distance, and round to the first after the last. */
    fun step() {
        val all = distancesKm
        wantKm = all[(all.indexOf(wantKm) + 1) % all.size]
    }

    var palette: Palette
        get() = runCatching { Palette.valueOf(store.getString("palette", null) ?: "KAROO") }.getOrDefault(Palette.KAROO)
        set(value) {
            store.edit().putString("palette", value.name).apply()
            changed()
        }

    var showClimb: Boolean
        get() = store.getBoolean("show_climb", true)
        set(value) {
            store.edit().putBoolean("show_climb", value).apply()
            changed()
        }

    var showSteepest: Boolean
        get() = store.getBoolean("show_steepest", true)
        set(value) {
            store.edit().putBoolean("show_steepest", value).apply()
            changed()
        }

    /** When the field carries its words and numbers. A half-width tile is all graph unless this says Always. */
    var labels: Labels
        get() = runCatching { Labels.valueOf(store.getString("labels", null) ?: "BIG_TILES") }.getOrDefault(Labels.BIG_TILES)
        set(value) {
            store.edit().putString("labels", value.name).apply()
            changed()
        }

    /** How big the field's words and numbers are. */
    var textSize: TextSize
        get() = runCatching { TextSize.valueOf(store.getString("text_size", null) ?: "LARGE") }.getOrDefault(TextSize.LARGE)
        set(value) {
            store.edit().putString("text_size", value.name).apply()
            changed()
        }

    /** Show the distance in view along the top. On unless the rider unticks it. */
    var showDistance: Boolean
        get() = store.getBoolean("show_distance", true)
        set(value) { store.edit().putBoolean("show_distance", value).apply(); changed() }

    /** Show the average grade to the highest point ahead. Off until the rider ticks it. */
    var showAverage: Boolean
        get() = store.getBoolean("show_average", false)
        set(value) { store.edit().putBoolean("show_average", value).apply(); changed() }

    /** Whether the graph fills the tile or keeps its true scale. */
    var graphHeight: GraphHeight
        get() = runCatching { GraphHeight.valueOf(store.getString("graph_height", null) ?: "FILL") }.getOrDefault(GraphHeight.FILL)
        set(value) { store.edit().putString("graph_height", value.name).apply(); changed() }

    /** How much of the field shows the road already ridden. */
    var behind: Behind
        get() = runCatching { Behind.valueOf(store.getString("behind", null) ?: "OFF") }.getOrDefault(Behind.OFF)
        set(value) { store.edit().putString("behind", value.name).apply(); changed() }

    /** Everything the field's look depends on, for the field and both previews alike. */
    fun fieldSettings(imperial: Boolean) = FieldSettings(
        palette, showClimb, showSteepest, imperial, labels, textSize, showDistance, showAverage, graphHeight, behind)

    /**
     * At a T-junction or a fork: carry on along the likeliest road, so the field always shows the
     * distance asked for, or stop the picture there.
     */
    var through: Boolean
        get() = store.getBoolean("through", true)
        set(value) {
            store.edit().putBoolean("through", value).apply()
            changed()
        }

    /** Elevation files are fetched for everywhere within this many kilometres of the Karoo. */
    var fetchKm: Int
        get() = store.getInt("fetch_km", 30).let { if (it in FETCH) it else 30 }
        set(value) {
            store.edit().putInt("fetch_km", value).apply()
            changed()
        }

    companion object {
        val DISTANCES = listOf(1, 2, 5, 10, 20)
        val FETCH = listOf(10, 30, 60)
    }
}
