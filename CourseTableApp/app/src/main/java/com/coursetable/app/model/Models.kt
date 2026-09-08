package com.coursetable.app.model

import java.time.LocalDate

/** 一大节(两小节)的时间定义。slot 1..N；一对覆盖 2k-1 与 2k 两个小节。 */
data class TimePair(
    val label: String,      // "1-2节"
    val start: String,      // "8:20"
    val end: String,        // "10:00"
    val display: String     // "8:20-10:00"
)

/** 默认按用户提供的 12 小时制注释换算为 24 小时制。 */
val DEFAULT_TIME_PAIRS: List<TimePair> = listOf(
    TimePair("1-2节", "8:20", "10:00", "8:20-10:00"),
    TimePair("3-4节", "10:20", "12:00", "10:20-12:00"),
    TimePair("5-6节", "13:20", "15:00", "13:20-15:00"),
    TimePair("7-8节", "15:20", "17:00", "15:20-17:00"),
    TimePair("9-10节", "18:00", "19:30", "18:00-19:30")
)

val DEFAULT_WEEKDAY_NAMES = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
val WEEKDAY_SHORT = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 一条排课记录。day: 0=周一..6=周日；startSlot: 1 起小节序号；slotSpan: 占用小节数。 */
data class CourseEntry(
    val day: Int,
    val name: String,
    val teacher: String,
    /** 原样周次注解文本，如 "1-9,11-16,18周"（无注解为空串）。 */
    val weeksText: String,
    /** 解析后的周次集合；无注解时为空。 */
    val weekSet: Set<Int>,
    val hasWeekAnnotation: Boolean,
    /** true 表示注解里存在无法解析的片段（保留 raw，按全集处理）。 */
    val unparsedWeekTokens: Boolean,
    val room: String,
    val startSlot: Int,
    val slotSpan: Int,
    /** 源格完整多行文本，保证"详尽呈现"。 */
    val raw: String
) {
    fun endSlot(): Int = startSlot + slotSpan - 1

    /** 该记录在某一周是否开课。无周次注解 ⇒ 每周都开。 */
    fun activeOnWeek(w: Int): Boolean {
        if (!hasWeekAnnotation) return true
        if (unparsedWeekTokens && weekSet.isEmpty()) return true
        return weekSet.contains(w)
    }

    /** 是否在给定日期(周+星期)开课。 */
    fun activeOn(week: Int, day: Int): Boolean = this.day == day && activeOnWeek(week)
}

/** 一份完整课表（一次导入的内容）。 */
data class Schedule(
    /** 表头上方的标题行（含学期/姓名等信息，原样保留）。 */
    val titleLines: List<String>,
    val week1Monday: LocalDate,
    val weekCount: Int,
    val comment: String,
    val weekdayNames: List<String>,
    val entries: List<CourseEntry>
) {
    val termLabel: String
        get() = titleLines.firstOrNull { it.contains("学期") || it.contains("课表") } ?: "课程表"

    val infoLine: String?
        get() = titleLines.firstOrNull { it.contains("年级") || it.contains("院系") || it.contains("专业") || it.contains("班级") }

    fun maxUsedSlot(): Int = entries.maxOfOrNull { it.endSlot() } ?: 0

    /** 该周该天(0=周一)开课的记录，按开始小节排序。 */
    fun entriesOn(week: Int, day: Int): List<CourseEntry> =
        entries.filter { it.activeOnWeek(week) && it.day == day }
            .sortedBy { it.startSlot }

    /** 聚合：同一门课(名称+教师)的所有记录，键值稳定有序。 */
    fun groupByCourse(): List<Pair<String, List<CourseEntry>>> {
        val m = LinkedHashMap<String, MutableList<CourseEntry>>()
        for (e in entries) {
            val k = courseKey(e.name, e.teacher)
            m.getOrPut(k) { mutableListOf() }.add(e)
        }
        return m.map { it.key to it.value }
    }

    fun hasWeekendEntries(): Boolean = entries.any { it.day >= 5 }

    companion object {
        fun courseKey(name: String, teacher: String): String =
            name.trim() + "\u0000" + teacher.trim()
    }
}

fun courseDisplayName(e: CourseEntry): String =
    e.name.trim().removeSuffix("-").removeSuffix("-").trim()

fun courseSubtitle(e: CourseEntry): String =
    buildString {
        if (e.teacher.isNotBlank()) append(e.teacher.trim())
        if (e.room.isNotBlank()) { if (isNotEmpty()) append(" · "); append(e.room.trim()) }
    }
