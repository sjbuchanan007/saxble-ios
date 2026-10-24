package com.shj.saxble

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---- Parsed commissioning data (ported from iOS Report.swift) ----

data class GasChannel(
    val index: Int,
    var type: String? = null,
    var bottle: String? = null,
    var pressure: String? = null,
    var condition: String? = null,
    var highAlarm: String? = null,
    var lowAlarm: String? = null,
    var dropAlarm: String? = null,
    var hiDiff: String? = null,
    var loDiff: String? = null,
    var pdDiff: String? = null,
    var units: String? = null,
    var display: String? = null,
    var name: String? = null,
) {
    val enabled: Boolean get() = type != null
}

class CommissioningData {
    val channels = mutableListOf<GasChannel>()
    val settings = mutableListOf<Pair<String, String>>()
    fun setting(key: String): String? =
        settings.firstOrNull { it.first.equals(key, ignoreCase = true) }?.second
}

object Report {

    private val BRAND_BLUE = Color.rgb(0, 160, 220)

    private val gasKeys = setOf(
        "Gas Type", "Bottle Color", "Pressure", "High Alarm", "Low Alarm",
        "Drop Alarm", "Hi_Diff", "Lo_Diff", "Pd_Diff", "Display", "Units",
        "Demo", "Gas Name", "Condition",
    )
    private val settingKeys = setOf(
        "Location", "Password", "Engineer No", "Supply", "Battery",
        "Modbus Addr", "Modbus speed", "Tone Type", "Screen saver",
        "Mute timer", "Logout Time", "Log Time", "EEPROM", "Bluetooth Ver",
        "Hardware Ver", "Software Ver", "Build", "Serial",
    )

    // ---- Parse ----

    fun parse(rxLines: List<String>): CommissioningData {
        val data = CommissioningData()
        val byIndex = linkedMapOf<Int, GasChannel>()
        var currentGas: Int? = null
        var awaitingCondition: Int? = null

        for (raw in rxLines) {
            val line = raw.trim().trim('>', ' ', '\t')   // strip any "> " prompt
            if (line.isEmpty()) continue

            val gasMatch = Regex("^\\*+\\s*Gas:\\s*(\\d+)").find(line)
            if (gasMatch != null) {
                gasMatch.groupValues[1].toIntOrNull()?.let { n ->
                    currentGas = n
                    byIndex.getOrPut(n) { GasChannel(n) }
                }
                awaitingCondition = null
                continue
            }
            if (line.startsWith("*")) continue

            val awaiting = awaitingCondition
            if (awaiting != null && !line.contains(":")) {
                if (byIndex[awaiting]?.condition == null) byIndex[awaiting]?.condition = clean(line)
                awaitingCondition = null
                continue
            }

            val colon = line.indexOf(':')
            if (colon < 0) continue
            val key = line.substring(0, colon).trim()
            val value = clean(line.substring(colon + 1))

            val g = currentGas
            if (key in gasKeys && g != null) {
                applyGas(key, value, byIndex[g]!!) { awaitingCondition = g }
            } else if (key in settingKeys) {
                if (data.setting(key) == null) data.settings.add(key to value)
            }
        }
        data.channels.addAll(byIndex.toSortedMap().values)
        return data
    }

    private inline fun applyGas(key: String, value: String, ch: GasChannel, setAwaiting: () -> Unit) {
        when (key) {
            "Gas Type" -> ch.type = value
            "Bottle Color" -> ch.bottle = value
            "Pressure" -> ch.pressure = value
            "High Alarm" -> ch.highAlarm = value
            "Low Alarm" -> ch.lowAlarm = value
            "Drop Alarm" -> ch.dropAlarm = value
            "Hi_Diff" -> ch.hiDiff = value
            "Lo_Diff" -> ch.loDiff = value
            "Pd_Diff" -> ch.pdDiff = value
            "Units" -> ch.units = value
            "Display" -> ch.display = value
            "Gas Name" -> ch.name = value
            "Condition" -> if (ch.condition == null) {
                when {
                    value.isEmpty() -> setAwaiting()
                    value != "on" && value != "off" -> ch.condition = value
                    ch.type == null -> ch.condition = value
                }
            }
        }
    }

    private fun clean(s: String): String {
        var v = s.trim()
        if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length - 1)
        return v.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    // ---- Build PDF ----

    /** Build a structured A4 PDF and return the File (in cacheDir/reports). */
    fun buildPdf(context: Context, rxLines: List<String>, transcript: String, fallbackName: String): File? {
        val data = parse(rxLines)
        val now = Date()
        val dateText = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.UK).format(now)

        val sb = SpannableStringBuilder()
        fun span(text: String, sizePx: Int, bold: Boolean = false, mono: Boolean = false, color: Int = Color.BLACK) {
            val s = sb.length
            sb.append(text)
            val e = sb.length
            sb.setSpan(AbsoluteSizeSpan(sizePx, false), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(color), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (mono) sb.setSpan(TypefaceSpan("monospace"), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val loc = data.setting("Location") ?: fallbackName
        span("SAXBLE Commissioning Report\n", 18, bold = true, color = BRAND_BLUE)
        span("$loc   ·   $dateText\n\n", 10, color = Color.DKGRAY)

        val facts = listOf(
            "Location" to data.setting("Location"),
            "Engineer" to data.setting("Engineer No"),
            "Serial" to data.setting("Serial"),
            "Software" to data.setting("Software Ver"),
            "Hardware" to data.setting("Hardware Ver"),
            "Supply" to data.setting("Supply"),
            "Battery" to data.setting("Battery"),
        )
        for ((k, v) in facts) if (v != null) span("${k.padEnd(10)} $v\n", 9, mono = true)

        span("\nGas Channels\n", 13, bold = true, color = BRAND_BLUE)
        span(gasTable(data), 8, mono = true)
        span("Hi± / Lo± / Pd± = alarm differentials (hysteresis).\n", 8, color = Color.DKGRAY)

        span("\nSystem Settings\n", 13, bold = true, color = BRAND_BLUE)
        span(systemSettings(data), 9, mono = true)

        span("\n\nConsole Transcript\n\n", 13, bold = true, color = BRAND_BLUE)
        span(transcript + "\n", 8, mono = true)

        // Lay out and paginate onto A4 pages (points = px at 72dpi).
        val pageW = 595; val pageH = 842; val margin = 40
        val contentW = pageW - 2 * margin
        val contentH = pageH - 2 * margin
        val paint = TextPaint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 9f }
        val layout = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, contentW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()

        val logo = loadLogo(context)
        val doc = PdfDocument()
        var startLine = 0
        var pageNum = 1
        val totalLines = layout.lineCount
        while (startLine < totalLines) {
            val info = PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create()
            val page = doc.startPage(info)
            val c = page.canvas
            c.drawColor(Color.WHITE)

            // Letterhead logo on page 1 (if an "shj_logo" drawable exists).
            var topInset = 0f
            if (pageNum == 1 && logo != null) {
                val logoW = minOf(contentW.toFloat(), 240f)
                val scale = logoW / logo.width
                val logoH = logo.height * scale
                c.drawBitmap(logo, null, RectF(margin.toFloat(), margin.toFloat(), margin + logoW, margin + logoH), null)
                topInset = logoH + 16f
            }

            val avail = contentH - topInset
            val startY = layout.getLineTop(startLine)
            var endLine = startLine
            while (endLine < totalLines && layout.getLineBottom(endLine) - startY <= avail) endLine++
            if (endLine == startLine) endLine = startLine + 1   // never stall

            c.save()
            c.translate(margin.toFloat(), margin + topInset - startY)
            c.clipRect(0f, startY.toFloat(), contentW.toFloat(), layout.getLineBottom(endLine - 1).toFloat())
            layout.draw(c)
            c.restore()
            doc.finishPage(page)

            startLine = endLine
            pageNum++
        }

        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val dateStamp = SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(now)
        val file = File(dir, "SAXBLE-${slug(loc)}-$dateStamp.pdf")
        return try {
            FileOutputStream(file).use { doc.writeTo(it) }
            doc.close()
            file
        } catch (e: Exception) {
            doc.close()
            null
        }
    }

    // ---- Share ----

    /** Pop the system share sheet with the PDF attached. */
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Send commissioning report")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)   // launched from app context
        context.startActivity(chooser)
    }

    // ---- Helpers ----

    private fun gasTable(data: CommissioningData): String {
        val cols = listOf(
            "Ch" to 3, "Type" to 6, "Pressure" to 10, "High" to 10, "Hi±" to 6,
            "Low" to 10, "Lo±" to 6, "Drop" to 10, "Pd±" to 6, "Display" to 18,
        )
        val b = StringBuilder()
        b.append(row(cols)).append('\n')
        b.append("─".repeat(cols.sumOf { it.second })).append('\n')
        if (data.channels.isEmpty()) {
            b.append("(no `gas a list` output captured in this session)\n")
        } else for (ch in data.channels) {
            if (ch.enabled) {
                b.append(
                    row(
                        listOf(
                            ch.index.toString() to 3, (ch.type ?: "—") to 6, (ch.pressure ?: "—") to 10,
                            (ch.highAlarm ?: "—") to 10, num(ch.hiDiff) to 6, (ch.lowAlarm ?: "—") to 10,
                            num(ch.loDiff) to 6, (ch.dropAlarm ?: "—") to 10, num(ch.pdDiff) to 6,
                            (ch.display ?: "—") to 18,
                        )
                    )
                ).append('\n')
            } else {
                b.append(row(listOf(ch.index.toString() to 3, "disabled" to 0))).append('\n')
            }
        }
        return b.toString()
    }

    private fun systemSettings(data: CommissioningData): String {
        val order = listOf(
            "Modbus Addr", "Modbus speed", "Tone Type", "Screen saver",
            "Mute timer", "Logout Time", "Log Time", "EEPROM", "Bluetooth Ver", "Build",
        )
        val b = StringBuilder()
        for (k in order) data.setting(k)?.let { b.append(k.padEnd(14)).append(' ').append(it).append('\n') }
        if (b.isEmpty()) b.append("(no `settings` output captured in this session)\n")
        return b.toString()
    }

    /** Fixed-width row; the final cell is written in full (not clipped). */
    private fun row(cells: List<Pair<String, Int>>): String {
        val b = StringBuilder()
        for ((i, cell) in cells.withIndex()) {
            val (text, width) = cell
            when {
                i == cells.size - 1 -> b.append(text)
                text.length >= width -> b.append(text.take(maxOf(0, width - 1))).append(' ')
                else -> b.append(text).append(" ".repeat(width - text.length))
            }
        }
        return b.toString()
    }

    private fun num(s: String?): String = s?.trim()?.split(" ")?.firstOrNull() ?: "—"

    private fun slug(s: String): String =
        s.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
            .split("-").filter { it.isNotEmpty() }.joinToString("-").ifEmpty { "encoder" }

    /** Load the letterhead logo: assets/shj_logo.png first, else an shj_logo
     *  drawable. Returns null if neither exists. */
    private fun loadLogo(context: Context): Bitmap? {
        try {
            context.assets.open("shj_logo.png").use { return BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { /* no asset — try a drawable */ }
        val id = context.resources.getIdentifier("shj_logo", "drawable", context.packageName)
        if (id != 0) {
            try { return BitmapFactory.decodeResource(context.resources, id) } catch (_: Exception) {}
        }
        return null
    }
}
