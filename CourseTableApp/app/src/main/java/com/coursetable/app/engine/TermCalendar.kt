package com.coursetable.app.engine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** 学期日历：把"学期第 N 周、星期几"与具体日期互转。第 1 周周一起算，共 weekCount 周。 */
class TermCalendar(val week1Monday: LocalDate, val weekCount: Int) {

    init {
        require(weekCount > 0)
    }

    /** 该周的周一日期。 */
    fun mondayOf(week: Int): LocalDate = week1Monday.plusDays((week - 1L) * 7L)

    /** 某周某天(0=周一..6=周日)的日期。 */
    fun dateOf(week: Int, dayOfWeekIndex: Int): LocalDate = mondayOf(week).plusDays(dayOfWeekIndex.toLong())

    /** 日期所在周次(1..weekCount)，不在学期内返回 null。 */
    fun weekOf(date: LocalDate): Int? {
        val days = java.time.temporal.ChronoUnit.DAYS.between(week1Monday, date)
        if (days < 0) return null
        val w = (days / 7).toInt() + 1
        return if (w in 1..weekCount) w else null
    }

    fun isInTerm(date: LocalDate): Boolean = weekOf(date) != null

    /** 学期最后一天(最后一周周日)。 */
    fun lastDay(): LocalDate = dateOf(weekCount, 6)

    /** 覆盖学期的首/末自然月（用于月视图分页）。 */
    fun monthStart(): YearMonth = YearMonth.from(week1Monday)
    fun monthEnd(): YearMonth = YearMonth.from(lastDay())

    companion object {
        fun indexOfDay(d: DayOfWeek): Int = (d.value + 6) % 7 // Mon=0
    }
}

/** 常用时间工具。 */
object TimeText {
    /** 把某条排课转成 "第3-4节 10:20-12:00" 形式的时间文本。 */
    fun slotLabelAndRange(startSlot: Int, slotSpan: Int): Pair<String, String> {
        val end = startSlot + slotSpan - 1
        val label = if (slotSpan == 1) "第${startSlot}节" else "第${startSlot}-${end}节"
        val range = timeRange(startSlot, end)
        return label to range
    }

    private val PAIR_START = listOf("8:20", "10:20", "13:20", "15:20", "18:00")
    private val PAIR_END = listOf("10:00", "12:00", "15:00", "17:00", "19:30")

    /** 返回 "8:20-10:00"；未知时段返回空串。跨多个大节的课取“起始大节起点-最末大节终点”。 */
    fun timeRange(startSlot: Int, endSlot: Int): String {
        val p1 = (startSlot - 1) / 2
        val p2 = (endSlot - 1) / 2
        if (p1 !in PAIR_START.indices) return ""
        if (p2 < p1) return ""
        val pe = if (p2 >= PAIR_END.size) PAIR_END.size - 1 else p2
        return PAIR_START[p1] + "-" + PAIR_END[pe]
    }

    /** 时段文本中代表开始时刻的分钟数（当日）。 */
    fun startMinutes(slot: Int): Int? {
        val p = (slot - 1) / 2
        if (p !in PAIR_START.indices) return null
        return toMinutes(PAIR_START[p])
    }

    fun endMinutes(slot: Int): Int? {
        val p = (slot - 1) / 2
        if (p !in PAIR_START.indices) return null
        return toMinutes(PAIR_END[p])
    }

    fun toMinutes(hhmm: String): Int {
        val p = hhmm.split(":")
        return p[0].toInt() * 60 + p[1].toInt()
    }
}
