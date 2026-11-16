package au.com.fjellconsulting.roadahead.replay

import au.com.fjellconsulting.roadahead.core.Align
import au.com.fjellconsulting.roadahead.core.Dot
import au.com.fjellconsulting.roadahead.core.Fill
import au.com.fjellconsulting.roadahead.core.Label
import au.com.fjellconsulting.roadahead.core.Picture
import au.com.fjellconsulting.roadahead.core.Stroke
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Draw several field pictures down one PNG, so the look can be checked on the Mac. */
fun writePng(pictures: List<Picture>, file: File) {
    val gap = 12
    val w = pictures.maxOf { it.w }
    val h = pictures.sumOf { it.h } + gap * (pictures.size - 1)
    val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.color = Color(0x30, 0x30, 0x30)
    g.fillRect(0, 0, w, h)
    var top = 0
    for (p in pictures) {
        val c = g.create(0, top, p.w, p.h) as java.awt.Graphics2D
        c.color = Color.BLACK
        c.fillRect(0, 0, p.w, p.h)
        for (s in p.shapes) when (s) {
            is Fill -> {
                val path = Path2D.Float()
                path.moveTo(s.xs[0], s.ys[0])
                for (i in 1 until s.xs.size) path.lineTo(s.xs[i], s.ys[i])
                path.closePath()
                c.color = Color(s.argb, true)
                c.fill(path)
            }
            is Stroke -> {
                val path = Path2D.Float()
                path.moveTo(s.xs[0], s.ys[0])
                for (i in 1 until s.xs.size) path.lineTo(s.xs[i], s.ys[i])
                c.color = Color(s.argb, true)
                c.stroke = if (s.dashed) BasicStroke(s.width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(5f, 4f), 0f)
                else BasicStroke(s.width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                c.draw(path)
            }
            is Dot -> {
                c.color = Color(s.argb, true)
                c.fill(Ellipse2D.Float(s.x - s.r, s.y - s.r, s.r * 2, s.r * 2))
            }
            is Label -> {
                fun font(bold: Boolean, size: Float) = Font("Helvetica Neue", if (bold) Font.BOLD else Font.PLAIN, 1).deriveFont(size)
                val width = s.runs.sumOf { c.getFontMetrics(font(it.bold, it.size)).stringWidth(it.text) }
                var x = when (s.align) {
                    Align.LEFT -> s.x
                    Align.CENTRE -> s.x - width / 2f
                    Align.RIGHT -> s.x - width
                }
                s.panel?.let { colour ->
                    val tall = s.runs.maxOf { it.size }
                    c.color = Color(colour, true)
                    c.fill(java.awt.geom.Rectangle2D.Float(x - 6f, s.y - tall, width + 12f, tall * 1.35f))
                }
                for (run in s.runs) {
                    c.font = font(run.bold, run.size)
                    c.color = Color(run.argb, true)
                    c.drawString(run.text, x, s.y)
                    x += c.fontMetrics.stringWidth(run.text)
                }
            }
        }
        c.dispose()
        top += p.h + gap
    }
    g.dispose()
    ImageIO.write(image, "png", file)
}
