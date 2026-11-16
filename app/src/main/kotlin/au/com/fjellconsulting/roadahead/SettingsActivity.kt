package au.com.fjellconsulting.roadahead

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import au.com.fjellconsulting.roadahead.core.FieldPainter
import au.com.fjellconsulting.roadahead.core.FieldSettings
import au.com.fjellconsulting.roadahead.core.Labels
import au.com.fjellconsulting.roadahead.core.Palette

/**
 * The app's one screen. The top says what Road Ahead has found on this Karoo: maps, elevation
 * files, and the road it thinks you're on. Under that is everything a rider can change, all as
 * tick boxes and choices, so nothing can be mistyped.
 */
class SettingsActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var allow: Button
    private lateinit var files: LinearLayout
    private val clock = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            showStatus()
            clock.postDelayed(this, 2_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = roadAhead
        val prefs = app.prefs
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(24, 24, 24, 48)
        }

        page.addView(text("Road Ahead", 26f, Color.WHITE, bold = true))
        // The field as it would look on a ride page right now.
        preview = ImageView(this).apply {
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        page.addView(preview)
        status = text("", 17f, Color.WHITE)
        page.addView(status)
        allow = Button(this).apply {
            text = "Allow map access"
            setOnClickListener {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            }
        }
        page.addView(allow)

        page.addView(heading("Distances a tap steps through"))
        val distanceBoxes = ArrayList<CheckBox>()
        for (km in Prefs.DISTANCES) {
            val box = check("$km km", km in prefs.distancesKm)
            box.setOnClickListener {
                val picked = Prefs.DISTANCES.filterIndexed { i, _ -> distanceBoxes[i].isChecked }
                // The field always needs one distance to show.
                if (picked.isEmpty()) box.isChecked = true else prefs.distancesKm = picked
            }
            distanceBoxes += box
            page.addView(box)
        }

        page.addView(heading("Colours"))
        page.addView(choices(Palette.values().map { it.label }, Palette.values().indexOf(prefs.palette)) { prefs.palette = Palette.values()[it] })

        page.addView(heading("Words and numbers on the field"))
        page.addView(choices(Labels.values().map { it.label }, Labels.values().indexOf(prefs.labels)) { prefs.labels = Labels.values()[it] })
        page.addView(text("A big tile is a full-width one. A half-width tile then shows the graph alone.", 14f, GREY))

        page.addView(heading("At a junction"))
        page.addView(choices(listOf("Carry on along the likeliest road", "Stop the picture there"), if (prefs.through) 0 else 1) { prefs.through = it == 0 })
        page.addView(text("Carrying on always shows the full distance. Past the first guess the picture is dimmer, with a tick where the guessing starts.", 14f, GREY))

        page.addView(heading("Numbers on the field"))
        page.addView(check("Climbing ahead", prefs.showClimb).apply { setOnClickListener { prefs.showClimb = isChecked } })
        page.addView(check("Steepest pitch ahead", prefs.showSteepest).apply { setOnClickListener { prefs.showSteepest = isChecked } })

        page.addView(heading("Fetch elevation for everywhere within"))
        page.addView(choices(Prefs.FETCH.map { "$it km of the Karoo" }, Prefs.FETCH.indexOf(prefs.fetchKm)) { prefs.fetchKm = Prefs.FETCH[it] })

        page.addView(heading("Elevation files on this Karoo"))
        files = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(files)
        page.addView(Button(this).apply {
            text = "Fetch now"
            setOnClickListener {
                val at = app.engine.lastAt
                if (at != null) app.download.consider(at, force = true)
                showStatus()
            }
        })

        page.addView(heading("Where the data comes from"))
        page.addView(text(
            "Roads: the Karoo's own offline maps, which are built from OpenStreetMap (© OpenStreetMap contributors, ODbL).\n" +
                "Elevation: Terrain Tiles on AWS Open Data, built from SRTM, Geoscience Australia and other public sources.\n" +
                "Map reading: Mapsforge (LGPL-3.0).",
            14f, GREY))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(page)
        })
        take(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    /**
     * Two switches for checking the field at the desk (the README has the commands): put the
     * Karoo at a place and heading for a minute, and fetch elevation files now.
     */
    private fun take(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra("demo", false)) {
            roadAhead.engine.demo(
                intent.getFloatExtra("lat", 0f).toDouble(), intent.getFloatExtra("lng", 0f).toDouble(),
                intent.getFloatExtra("course", 0f).toDouble())
        }
        if (intent.getBooleanExtra("fetch", false)) roadAhead.engine.lastAt?.let { roadAhead.download.consider(it, force = true) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        roadAhead.engine.reopenMaps()
        showStatus()
    }

    override fun onResume() {
        super.onResume()
        clock.post(tick)
    }

    override fun onPause() {
        super.onPause()
        clock.removeCallbacks(tick)
    }

    private fun showStatus() {
        val app = roadAhead
        val engine = app.engine
        val maps = when {
            !engine.mapsAllowed -> "Maps: not allowed yet. Tap Allow map access."
            engine.mapFiles.isEmpty() -> "Maps: none found. Download a region in the Karoo's own settings."
            else -> "Maps: ${engine.mapFiles.size} found."
        }
        val held = app.download.held()
        val elevation = if (held.isEmpty()) "Elevation: no files yet." else "Elevation: ${held.size} file${if (held.size == 1) "" else "s"}."
        status.text = "$maps\n$elevation ${app.download.status}\n\nRight now: ${engine.describe(engine.look.value)}.\n" +
            "Showing ${app.prefs.wantKm} km. Add the Road ahead field to a ride page under ROAD AHEAD."
        allow.visibility = if (engine.mapsAllowed) View.GONE else View.VISIBLE
        val settings = FieldSettings(app.prefs.palette, app.prefs.showClimb, app.prefs.showSteepest, engine.imperial, app.prefs.labels)
        preview.setImageBitmap(Draw.bitmap(FieldPainter.paint(engine.look.value, 480, 170, settings)))

        // The list of files is only rebuilt when it changes, or a Delete button would move under a finger.
        val names = held.map { it.name }
        if (files.tag != names) {
            files.tag = names
            files.removeAllViews()
            if (held.isEmpty()) files.addView(text("None. They arrive by themselves when the Karoo is on Wi-Fi.", 15f, GREY))
            for (file in held) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(text("${file.name.removeSuffix(".hgt")}  ${file.length() / 1_000_000} MB", 17f, Color.WHITE).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(Button(this).apply {
                    text = "Delete"
                    setOnClickListener {
                        app.download.delete(file.name)
                        showStatus()
                    }
                })
                files.addView(row)
            }
        }
    }

    // Small helpers so the page reads top to bottom.

    private fun text(value: String, size: Float, colour: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(colour)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 8, 0, 8)
    }

    private fun heading(value: String) = text(value.uppercase(), 13f, GREY, bold = true).apply { setPadding(0, 36, 0, 4) }

    private fun check(label: String, on: Boolean) = CheckBox(this).apply {
        text = label
        textSize = 18f
        setTextColor(Color.WHITE)
        isChecked = on
    }

    private fun choices(labels: List<String>, picked: Int, onPick: (Int) -> Unit) = RadioGroup(this).apply {
        orientation = RadioGroup.VERTICAL
        labels.forEachIndexed { i, label ->
            addView(RadioButton(this@SettingsActivity).apply {
                id = i + 1
                text = label
                textSize = 18f
                setTextColor(Color.WHITE)
            })
        }
        check(picked.coerceAtLeast(0) + 1)
        setOnCheckedChangeListener { _, id -> onPick(id - 1) }
    }

    private companion object {
        val GREY = Color.parseColor("#9AA5A0")
    }
}
