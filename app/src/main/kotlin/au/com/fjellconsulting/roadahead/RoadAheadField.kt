package au.com.fjellconsulting.roadahead

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import au.com.fjellconsulting.roadahead.core.Ahead
import au.com.fjellconsulting.roadahead.core.End
import au.com.fjellconsulting.roadahead.core.FieldPainter
import au.com.fjellconsulting.roadahead.core.FieldSettings
import au.com.fjellconsulting.roadahead.core.Look
import au.com.fjellconsulting.roadahead.core.Picture
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.sin

/**
 * The Road ahead field: the elevation profile of the road in front of you, drawn at whatever
 * size the rider gave the field on their page. A tap steps to the next distance.
 */
class RoadAheadField(extension: String) : DataTypeImpl(extension, TYPE_ID) {
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val app = context.roadAhead
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val w = max(config.viewSize.first, 120)
        val h = max(config.viewSize.second, 48)
        if (config.preview) {
            // The page editor: a made-up hill, so the rider sees what the field looks like.
            emitter.updateView(views(context, FieldPainter.paint(Look.Road(SAMPLE), w, h, settings(app))))
            return
        }
        val job = app.scope.launch {
            app.engine.look.collect { look ->
                val picture = when {
                    !app.engine.mapsAllowed -> FieldPainter.message(w, h, "Open Road Ahead", "to let it read the maps")
                    look == Look.NoMap && app.engine.mapFiles.isEmpty() -> FieldPainter.message(w, h, "No maps found", "download a region on the Karoo")
                    else -> FieldPainter.paint(look, w, h, settings(app))
                }
                val bitmap = withContext(Dispatchers.Default) { Draw.bitmap(picture) }
                emitter.updateView(RemoteViews(context.packageName, R.layout.field_road).apply {
                    setImageViewBitmap(R.id.road_image, bitmap)
                    setOnClickPendingIntent(R.id.road_image, tap(context))
                })
                // The Karoo takes a new picture about once a second. While this waits, only the
                // newest look is kept.
                delay(REDRAW_MS)
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    private fun settings(app: RoadAheadApp) =
        FieldSettings(app.prefs.palette, app.prefs.showClimb, app.prefs.showSteepest, app.engine.imperial)

    private fun views(context: Context, picture: Picture) =
        RemoteViews(context.packageName, R.layout.field_road).apply { setImageViewBitmap(R.id.road_image, Draw.bitmap(picture)) }

    private fun tap(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, TapReceiver::class.java).setAction(TapReceiver.STEP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    companion object {
        const val TYPE_ID = "road"
        private const val REDRAW_MS = 1_000L

        /** A rolling 2 km with one proper pinch in it, for the page editor's preview. */
        private val SAMPLE = Ahead(
            25.0,
            DoubleArray(81) { i -> 600.0 + 22.0 * sin(i / 80.0 * 3.4 - 0.9) + 14.0 * sin(i / 80.0 * 9.0) + i * 0.25 },
            2000.0, 2000.0, End.FAR_ENOUGH, "Sample Road")
    }
}

/** The field's tap lands here, in the app's own process, and steps to the next distance. */
class TapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == STEP) context.roadAhead.prefs.step()
    }

    companion object {
        const val STEP = "au.com.fjellconsulting.roadahead.STEP"
    }
}
