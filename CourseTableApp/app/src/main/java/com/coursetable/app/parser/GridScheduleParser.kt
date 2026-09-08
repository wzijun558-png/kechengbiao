package com.coursetable.app.parser

import com.coursetable.app.engine.WeekPattern
import com.coursetable.app.model.CourseEntry
import com.coursetable.app.model.DEFAULT_WEEKDAY_NAMES
import com.coursetable.app.model.Schedule
import java.time.LocalDate

/**
 * 通用课表识别与转换：
 *  1. 横向布局（星期为列、节次为行，如教务打印版）；支持整表转置的竖向布局；
 *  2. 格内多课程块解析：课程名 / 教师【周次】/ 教室；
 *  3. 周次表达式解析（连续、单双周、零星周）；
 *  4. 同一课程块在相邻小节的竖向重复自动合并为一条排课记录（避免无意义重复）。
 */
object GridScheduleParser {

    data class Layout(
        val transposed: Boolean,
        val headerRow: Int,          // 视图中表头所在行
        val slotCol: Int,            // 视图中节次列
        val dayColOf: Map<Int, Int>, // weekday idx(0..6) -> 视图中列
        val slotOfRow: Map<Int, Int> // 视图中行 -> 小节序号
    )

    private class View(val g: SheetGrid, val transposed: Boolean) {
        fun maxRow(): Int = if (!transposed) g.maxR else g.maxC
        fun maxCol(): Int = if (!transposed) g.maxC else g.maxR
        fun text(r: Int, c: Int): String = if (!transposed) g.text(r, c) else g.text(c, r)
    }

    /** 视图坐标下的识别（约定：星期沿列增长、节次沿行增长）。 */
    private fun detectHorizontal(v: View): Layout? {
        val maxR = v.maxRow()
        val maxC = v.maxCol()
        for (hr in 0..maxR) {
            var slotCol = -1
            for (c in 0..maxC) {
                val t = v.text(hr, c).trim()
                if (isSlotHeader(t)) { slotCol = c; break }
            }
            if (slotCol < 0) continue
            val dayCols = ArrayList<Pair<Int, Int>>() // (weekday idx, col)
            for (c in slotCol + 1..maxC) {
                val d = normalizeWeekday(v.text(hr, c).trim())
                if (d != null && !dayCols.any { it.first == d }) dayCols.add(d to c)
            }
            if (dayCols.size < 3) continue
            // 节次行：在 slotCol 上找严格递增的 1..60 数值
            val slotOfRow = HashMap<Int, Int>()
            var last = 0
            for (r in hr + 1..maxR) {
                val txt = v.text(r, slotCol).trim()
                val n = txt.toIntOrNull() ?: continue
                if (n in 1..60 && n > last) {
                    slotOfRow[r] = n
                    last = n
                }
            }
            if (slotOfRow.isEmpty()) continue
            return Layout(false, hr, slotCol, dayCols.toMap(), slotOfRow)
        }
        return null
    }

    private fun detectLayout(g: SheetGrid): Layout? {
        val v = View(g, false)
        detectHorizontal(v)?.let { return it }
        val vt = View(g, true)
        detectHorizontal(vt)?.let {
            return it.copy(transposed = true)
        }
        return null
    }

    private fun isSlotHeader(t: String): Boolean = t == "节次" || t == "节次/时间" || t == "节次时间"

    /** 把 "星期一/周X/礼拜X/X" 等归一到 0..6(0=周一)。 */
    fun normalizeWeekday(s: String): Int? {
        if (s.isEmpty()) return null
        var t = s
        t = t.replace("礼拜", "").replace("星期", "").replace("周", "")
        val order = listOf('一' to 0, '二' to 1, '三' to 2, '四' to 3, '五' to 4, '六' to 5, '日' to 6, '天' to 6)
        for ((ch, idx) in order) if (t.contains(ch)) return idx
        return null
    }

    class Block(
        val name: String,
        val teacher: String,
        val weeksText: String,      // "1-9,11-16,18周"（无则 ""）
        val room: String,
        val raw: String,
        val hasAnnotation: Boolean,
        val unparsed: Boolean,
        val weekSet: Set<Int>
    )

    /** 拆解一个多行课程文本为课程块。 */
    fun splitBlocks(text: String): List<List<String>> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split("\n")
        val blocks = ArrayList<ArrayList<String>>()
        var cur: ArrayList<String>? = null
        for (ln in lines) {
            if (ln.trim().isEmpty()) {
                if (cur != null && cur.isNotEmpty()) {
                    blocks.add(cur)
                    cur = null
                }
            } else {
                if (cur == null) cur = ArrayList()
                cur.add(ln.trim())
            }
        }
        if (cur != null && cur.isNotEmpty()) blocks.add(cur)
        return blocks
    }

    private val BRACKET = Regex("【([^】]*?)(周)】")

    fun parseBlock(lines: List<String>): Block {
        val name = lines[0].trim()
        var teacher = ""
        var weeksRaw = ""
        var weekLineIdx = -1
        for (i in 1 until lines.size) {
            val m = BRACKET.find(lines[i])
            if (m != null && weekLineIdx < 0) {
                weekLineIdx = i
                teacher = lines[i].substring(0, m.range.first).trim()
                weeksRaw = m.groupValues[1]
            }
        }
        var room = ""
        for (i in 1 until lines.size) {
            if (i == weekLineIdx) continue
            val ln = lines[i]
            if (ln.isNotBlank()) room = ln
        }
        val result = if (weeksRaw.isEmpty()) WeekPattern.Result(emptySet(), true) else WeekPattern.parse("【" + weeksRaw + "周】")
        return Block(
            name = name,
            teacher = teacher,
            weeksText = if (weeksRaw.isEmpty()) "" else "【" + weeksRaw + "周】",
            room = room,
            raw = lines.joinToString("\n"),
            hasAnnotation = weeksRaw.isNotEmpty(),
            unparsed = !result.ok,
            weekSet = result.weeks
        )
    }

    data class SheetParse(
        val schedule: Schedule?,
        val sheetName: String?,
        val note: String
    )

    /** 把文本网格解析成 Schedule。week1Monday 由设置提供。 */
    fun parseSheet(g: SheetGrid, week1Monday: LocalDate, weekCountHint: Int): SheetParse {
        val layout = detectLayout(g) ?: return SheetParse(null, g.name, "表「${g.name}」无节次/星期表头，跳过")
        val v = View(g, layout.transposed)

        // 标题/信息行（视图中表头上方的各行文本）
        val titleLines = ArrayList<String>()
        for (r in 0 until layout.headerRow) {
            val parts = ArrayList<String>()
            for (c in 0..v.maxCol()) {
                val t = v.text(r, c).trim()
                if (t.isNotEmpty()) parts.add(t)
            }
            if (parts.isNotEmpty()) titleLines.add(parts.joinToString("  "))
        }

        // 采集 (day, signature) 的连续小节
        data class SigInfo(val block: Block)
        val presence = HashMap<Pair<Int, String>, MutableList<Int>>()
        val sigBlock = HashMap<String, Block>()

        fun signatureOf(b: Block): String = b.name + "\n" + b.teacher + "\n" + b.weeksText + "\n" + b.room

        for ((dayIdx, col) in layout.dayColOf) {
            for ((row, slot) in layout.slotOfRow) {
                val text = v.text(row, col)
                if (text.isBlank()) continue
                for (blkLines in splitBlocks(text)) {
                    val b = parseBlock(blkLines)
                    if (b.name.isEmpty()) continue
                    val sig = signatureOf(b)
                    sigBlock.putIfAbsent(sig, b)
                    presence.getOrPut(dayIdx to sig) { mutableListOf() }.add(slot)
                }
            }
        }

        val entries = ArrayList<CourseEntry>()
        for ((key, slots0) in presence) {
            val dayIdx = key.first
            val sig = key.second
            val slots = slots0.distinct().sorted()
            if (slots.isEmpty()) continue
            val b = sigBlock[sig] ?: continue
            var start = slots[0]
            var prev = slots[0]
            fun flush(end: Int) {
                entries.add(
                    CourseEntry(
                        day = dayIdx,
                        name = b.name,
                        teacher = b.teacher,
                        weeksText = b.weeksText,
                        weekSet = b.weekSet,
                        hasWeekAnnotation = b.hasAnnotation,
                        unparsedWeekTokens = b.unparsed,
                        room = b.room,
                        startSlot = start,
                        slotSpan = end - start + 1,
                        raw = b.raw
                    )
                )
            }
            for (s in slots.drop(1)) {
                if (s == prev + 1) {
                    prev = s
                } else {
                    flush(prev)
                    start = s
                    prev = s
                }
            }
            flush(prev)
        }
        entries.sortWith(compareBy({ it.day }, { it.startSlot }, { it.endSlot() }, { it.name }))

        var weekCount = weekCountHint
        var maxSeen = 0
        for (e in entries) if (e.hasWeekAnnotation && e.weekSet.isNotEmpty()) {
            maxSeen = maxOf(maxSeen, e.weekSet.maxOrNull() ?: 0)
        }
        if (maxSeen > 0) weekCount = maxOf(weekCount, maxSeen)
        if (weekCount <= 0) weekCount = 18

        val weekdayNames = DEFAULT_WEEKDAY_NAMES.toMutableList()
        for ((dayIdx, col) in layout.dayColOf) {
            val h = v.text(layout.headerRow, col).trim()
            if (h.isNotEmpty() && weekdayNames.size > dayIdx) weekdayNames[dayIdx] = h
        }

        val schedule = Schedule(
            titleLines = titleLines,
            week1Monday = week1Monday,
            weekCount = weekCount,
            comment = "",
            weekdayNames = weekdayNames,
            entries = entries
        )
        val note = "表「${g.name}」：识别到 ${entries.size} 条排课记录"
        return SheetParse(schedule, g.name, note)
    }

    /** 整个工作簿：取排课记录最多的表作为结果。 */
    fun parseWorkbook(
        wb: WorkbookGrid,
        week1Monday: LocalDate,
        weekCountHint: Int,
        wantedSheet: String?
    ): SheetParse {
        var best: SheetParse? = null
        var bestCount = -1
        val notes = ArrayList<String>()
        for (g in wb.sheets) {
            if (wantedSheet != null && g.name != wantedSheet) continue
            val sp = parseSheet(g, week1Monday, weekCountHint)
            val n = sp.schedule?.entries?.size ?: 0
            if (n > bestCount) {
                bestCount = n
                best = sp
            }
            notes.add(sp.note)
        }
        val b = best
        if (b == null || b.schedule == null) {
            return SheetParse(null, null, notes.joinToString("；"))
        }
        return b.copy(note = b.note)
    }
}
