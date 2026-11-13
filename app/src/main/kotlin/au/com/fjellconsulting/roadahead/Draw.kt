package au.com.fjellconsulting.roadahead

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import au.com.fjellconsulting.roadahead.core.Align
import au.com.fjellconsulting.roadahead.core.Dot
import au.com.fjellconsulting.roadahead.core.Fill
import au.com.fjellconsulting.roadahead.core.Label
import au.com.fjellconsulting.roadahead.core.Picture
import au.com.fjellconsulting.roadahead.core.Stroke

/**
 * Turns the engine's picture into a bitmap. The engine decides every shape, colour and position;
 * this only puts them on a canvas, so the Karoo shows what the Mac's test pictures show.
 */
object Draw {
    private val bold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val plain = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val dashes = DashPathEffect(floatArrayOf(5f, 4f), 0f)

    fun bitmap(picture: Picture): Bitmap {
        val bitmap = Bitmap.createBitmap(picture.w, picture.h, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (shape in picture.shapes) when (shape) {
            is Fill -> {
                paint.reset()
                paint.isAntiAlias = true
                paint.style = Paint.Style.FILL
                paint.color = shape.argb
                canvas.drawPath(path(shape.xs, shape.ys, close = true), paint)
            }
            is Stroke -> {
                paint.reset()
                paint.isAntiAlias = true
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = shape.width
                paint.strokeCap = if (shape.dashed) Paint.Cap.BUTT else Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = shape.argb
                if (shape.dashed) paint.pathEffect = dashes
                canvas.drawPath(path(shape.xs, shape.ys, close = false), paint)
            }
            is Dot -> {
                paint.reset()
                paint.isAntiAlias = true
                paint.color = shape.argb
                canvas.drawCircle(shape.x, shape.y, shape.r, paint)
            }
            is Label -> {
                paint.reset()
                paint.isAntiAlias = true
                var width = 0f
                for (run in shape.runs) {
                    paint.typeface = if (run.bold) bold else plain
                    paint.textSize = run.size
                    width += paint.measureText(run.text)
                }
                var x = when (shape.align) {
                    Align.LEFT -> shape.x
                    Align.CENTRE -> shape.x - width / 2f
                    Align.RIGHT -> shape.x - width
                }
                for (run in shape.runs) {
                    paint.typeface = if (run.bold) bold else plain
                    paint.textSize = run.size
                    paint.color = run.argb
                    canvas.drawText(run.text, x, shape.y, paint)
                    x += paint.measureText(run.text)
                }
            }
        }
        return bitmap
    }

    private fun path(xs: FloatArray, ys: FloatArray, close: Boolean): Path {
        val path = Path()
        path.moveTo(xs[0], ys[0])
        for (i in 1 until xs.size) path.lineTo(xs[i], ys[i])
        if (close) path.close()
        return path
    }
}
