package com.coursetable.app.model

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Schedule <-> JSON 编解码（org.json，无第三方依赖）。 */
object ScheduleCodec {

    fun toJson(s: Schedule): String = toJsonObject(s).toString()

    fun toJsonObject(s: Schedule): JSONObject {
        val o = JSONObject()
        o.put("formatVersion", 1)
        o.put("titleLines", JSONArray(s.titleLines))
        o.put("week1Monday", s.week1Monday.toString())
        o.put("weekCount", s.weekCount)
        o.put("comment", s.comment)
        o.put("weekdayNames", JSONArray(s.weekdayNames))
        val arr = JSONArray()
        for (e in s.entries) {
            val jo = JSONObject()
            jo.put("day", e.day)
            jo.put("name", e.name)
            jo.put("teacher", e.teacher)
            jo.put("weeksText", e.weeksText)
            jo.put("weeks", JSONArray(e.weekSet.toList().sorted()))
            jo.put("hasWeekAnnotation", e.hasWeekAnnotation)
            jo.put("unparsedWeekTokens", e.unparsedWeekTokens)
            jo.put("room", e.room)
            jo.put("startSlot", e.startSlot)
            jo.put("slotSpan", e.slotSpan)
            jo.put("raw", e.raw)
            arr.put(jo)
        }
        o.put("entries", arr)
        return o
    }

    fun fromJson(text: String): Schedule = fromJsonObject(JSONObject(text))

    fun fromJsonObject(o: JSONObject): Schedule {
        val titleLines = mutableListOf<String>()
        val tl = o.optJSONArray("titleLines")
        if (tl != null) for (i in 0 until tl.length()) titleLines.add(tl.getString(i))
        val week1 = if (o.has("week1Monday")) LocalDate.parse(o.getString("week1Monday")) else LocalDate.now()
        val weekCount = o.optInt("weekCount", 18)
        val comment = o.optString("comment", "")
        val wdNames = mutableListOf<String>()
        val wn = o.optJSONArray("weekdayNames")
        if (wn != null) for (i in 0 until wn.length()) wdNames.add(wn.getString(i))
        if (wdNames.isEmpty()) wdNames.addAll(DEFAULT_WEEKDAY_NAMES)

        val entries = mutableListOf<CourseEntry>()
        val arr = o.optJSONArray("entries") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val jo = arr.getJSONObject(i)
            val weeks = mutableSetOf<Int>()
            val wa = jo.optJSONArray("weeks")
            if (wa != null) for (j in 0 until wa.length()) weeks.add(wa.getInt(j))
            entries.add(
                CourseEntry(
                    day = jo.optInt("day", 0),
                    name = jo.optString("name", ""),
                    teacher = jo.optString("teacher", ""),
                    weeksText = jo.optString("weeksText", ""),
                    weekSet = weeks,
                    hasWeekAnnotation = jo.optBoolean("hasWeekAnnotation", false),
                    unparsedWeekTokens = jo.optBoolean("unparsedWeekTokens", false),
                    room = jo.optString("room", ""),
                    startSlot = jo.optInt("startSlot", 1),
                    slotSpan = jo.optInt("slotSpan", 1),
                    raw = jo.optString("raw", "")
                )
            )
        }
        return Schedule(titleLines, week1, weekCount, comment, wdNames, entries)
    }

    /** 标准示例/导出 JSON 兼容导入：文件可能直接用我们生成的 canonical 结构。 */
    fun isCanonicalJson(text: String): Boolean {
        return try {
            val o = JSONObject(text)
            o.has("entries") && (o.has("weekdayNames") || o.has("term"))
        } catch (t: Throwable) {
            false
        }
    }
}
