package com.coursetable.app.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.coursetable.app.engine.TimeText
import com.coursetable.app.model.Schedule
import org.json.JSONArray
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 系统日历同步：把每节课（按周展开）写入系统日历，并附“课前提醒”。
 * 同步事件 ID 记入偏好，可一键清除或重新同步（不重复）。
 */
object CalendarSync {

    private const val PREF = "coursetable"
    private const val KEY_IDS = "calendar_event_ids"

    class SyncException(message: String) : Exception(message)

    /** 找到可写日历 id；找不到抛出。 */
    @Throws(SyncException::class)
    fun findWritableCalendar(context: Context): Long {
        val cr = context.contentResolver
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        var best: Long = -1
        cr.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection, null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val level = c.getInt(1)
                if (level >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                    best = id
                    break
                }
                if (best < 0 && level > CalendarContract.Calendars.CAL_ACCESS_NONE) best = id
            }
        } ?: throw SyncException("本机无日历账户，请先在系统日历中添加账户")
        if (best < 0) throw SyncException("没有可写入的系统日历")
        return best
    }

    fun syncedEventCount(context: Context): Int = loadIds(context).size

    /** 清除历史同步事件（幂等）。 */
    fun removeAll(context: Context) {
        val cr = context.contentResolver
        for (id in loadIds(context)) {
            runCatching {
                cr.delete(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
                    null, null
                )
            }
        }
        clearIds(context)
    }

    /** 全量重新同步：先清除旧事件，再按周展开写入（带课前提醒）。 */
    @Throws(SyncException::class)
    fun resync(context: Context, schedule: Schedule, leadMin: Int) {
        removeAll(context)
        val calId = findWritableCalendar(context)
        val cr = context.contentResolver
        val zone = ZoneId.systemDefault()
        val inserted = ArrayList<Long>()
        var total = 0
        for (e in schedule.entries) {
            if (e.day > 6) continue
            val startMin = TimeText.startMinutes(e.startSlot) ?: continue
            val endMin = TimeText.endMinutes(e.endSlot()) ?: continue
            val weeks = if (e.hasWeekAnnotation && e.weekSet.isNotEmpty()) e.weekSet.sorted() else (1..schedule.weekCount)
            for (w in weeks) {
                val date = schedule.week1Monday.plusDays(((w - 1) * 7L) + e.day)
                val startMs = LocalDateTime.of(
                    date,
                    java.time.LocalTime.of(startMin / 60, startMin % 60)
                ).atZone(zone).toInstant().toEpochMilli()
                val endMs = LocalDateTime.of(
                    date,
                    java.time.LocalTime.of(endMin / 60, endMin % 60)
                ).atZone(zone).toInstant().toEpochMilli()
                val values = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calId)
                    put(CalendarContract.Events.TITLE, e.name.trim() + " " + TimeText.slotLabelAndRange(e.startSlot, e.slotSpan).first)
                    put(CalendarContract.Events.DESCRIPTION, buildString {
                        if (e.teacher.isNotBlank()) append("教师：").append(e.teacher.trim()).append('\n')
                        if (e.room.isNotBlank()) append("教室：").append(e.room.trim()).append('\n')
                        if (e.weeksText.isNotEmpty()) append("周次：").append(e.weeksText)
                    })
                    put(CalendarContract.Events.DTSTART, startMs)
                    put(CalendarContract.Events.DTEND, endMs)
                    put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
                    put(CalendarContract.Events.HAS_ALARM, 1)
                }
                val uri = cr.insert(CalendarContract.Events.CONTENT_URI, values)
                    ?: continue
                val eventId = ContentUris.parseId(uri)
                val rem = ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, leadMin)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
                runCatching { cr.insert(CalendarContract.Reminders.CONTENT_URI, rem) }
                inserted.add(eventId)
                total++
            }
        }
        storeIds(context, inserted)
    }

    private fun loadIds(context: Context): List<Long> {
        val s = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_IDS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(s)
            (0 until arr.length()).map { arr.getLong(it) }
        }.getOrDefault(emptyList())
    }

    private fun storeIds(context: Context, ids: List<Long>) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_IDS, arr.toString()).apply()
    }

    private fun clearIds(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().remove(KEY_IDS).apply()
    }
}
