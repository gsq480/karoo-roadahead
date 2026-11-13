package au.com.fjellconsulting.roadahead.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Where ground heights come from. Null means there's no height for that spot. */
fun interface HeightSource {
    fun at(p: Pt): Double?
}

/**
 * One HGT elevation file: a square grid of heights in metres covering one degree of latitude by
 * one of longitude, north row first, two bytes a height. The name gives its south-west corner,
 * so `S36E149.hgt` runs from 36 to 35 south and 149 to 150 east.
 */
class HgtFile(file: File) : HeightSource {
    private val south: Int
    private val west: Int
    private val n: Int
    private val grid: ByteBuffer

    init {
        val m = NAME.find(file.name) ?: throw IllegalArgumentException("Not an HGT file name: ${file.name}")
        south = m.groupValues[2].toInt() * (if (m.groupValues[1].uppercase() == "S") -1 else 1)
        west = m.groupValues[4].toInt() * (if (m.groupValues[3].uppercase() == "W") -1 else 1)
        n = sqrt(file.length() / 2.0).roundToInt()
        require(n >= 2 && n.toLong() * n * 2 == file.length()) { "${file.name} is not a square grid" }
        // Mapped, not loaded: a 30 m file is 26 MB and only a few hundred heights are read.
        grid = RandomAccessFile(file, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()) }
    }

    private fun cell(row: Int, col: Int): Double? {
        val v = grid.getShort((row * n + col) * 2).toInt()
        return if (v == VOID) null else v.toDouble()
    }

    /** The height at a point, blended from the four grid heights around it. */
    override fun at(p: Pt): Double? {
        val row = (south + 1 - p.lat) * (n - 1)
        val col = (p.lng - west) * (n - 1)
        if (row < 0 || col < 0 || row > n - 1 || col > n - 1) return null
        val r0 = floor(row).toInt().coerceAtMost(n - 2)
        val c0 = floor(col).toInt().coerceAtMost(n - 2)
        val fr = row - r0
        val fc = col - c0
        val a = cell(r0, c0) ?: return null
        val b = cell(r0, c0 + 1) ?: return null
        val c = cell(r0 + 1, c0) ?: return null
        val d = cell(r0 + 1, c0 + 1) ?: return null
        return (a * (1 - fc) + b * fc) * (1 - fr) + (c * (1 - fc) + d * fc) * fr
    }

    companion object {
        private const val VOID = -32768
        private val NAME = Regex("^([NSns])(\\d{2})([EWew])(\\d{3})\\.hgt$")

        /** The file that covers a point, such as `S36E149.hgt`. */
        fun nameFor(p: Pt): String {
            val lat = floor(p.lat).toInt()
            val lng = floor(p.lng).toInt()
            return "%s%02d%s%03d.hgt".format(
                if (lat < 0) "S" else "N", Math.abs(lat), if (lng < 0) "W" else "E", Math.abs(lng))
        }
    }
}

/** A folder of HGT files, opened as they're needed. */
class HgtFolder(private val dir: File) : HeightSource {
    private val open = HashMap<String, HgtFile?>()

    override fun at(p: Pt): Double? {
        val name = HgtFile.nameFor(p)
        val file = open.getOrPut(name) {
            val f = File(dir, name)
            if (f.isFile) runCatching { HgtFile(f) }.getOrNull() else null
        }
        return file?.at(p)
    }

    /** Forget which files were missing, after a download adds one. */
    fun refresh() = open.entries.removeIf { it.value == null }
}
