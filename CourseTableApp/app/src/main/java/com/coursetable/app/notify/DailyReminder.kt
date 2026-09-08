package com.coursetable.app.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.coursetable.app.MainActivity
import com.coursetable.app.R
import com.coursetable.app.data.Store
import com.coursetable.app.engine.TermCalendar
import com.coursetable.app.engine.TimeText
import com.coursetable.app.model.CourseEntry
import com.coursetable.app.model.Schedule
import com.coursetable.app.model.WEEKDAY_SHORT
import com.coursetable.app.model.courseDisplayName
import org.json.JSONArray
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 课程通知中心：
 *  - 锁屏可显示的高优先级通知频道；
 *  - 模式0：每天定时汇总当天课程；
 *  - 模式1：每节课前 N 分钟单独提醒（逐节精确闹钟，滚动 7 天窗口，开机/时区变更自动恢复）。
 */
object DailyReminder {

    const val CHANNEL_ID = "course_reminders"
    const val ACTION_DAILY = "com.coursetable.app.action.DAILY_FIRE"
    const val ACTION_CLASS = "com.coursetable.app.action.CLASS_FIRE"
    const val ACTION_REPLAN = "com.coursetable.app.action.REPLAN"
    private const val RC_DAILY = 1024
    private const val RC_BASE = 3000
    private const val KEY_CODES = "class_alarm_codes"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val cur = nm.getNotificationChannel(CHANNEL_ID)
            // 升级为“锁屏可见 + 高优先级”：旧频道若已存在且重要性过低则重建
            if (cur != null && cur.importance < NotificationManager.IMPORTANCE_HIGH) {
                nm.deleteNotificationChannel(CHANNEL_ID)
            }
            val ch = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.lesson_notification_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.lesson_notification_channel_desc)
                enableVibration(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC   // 锁屏可显示内容
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setShowBadge(false)
                }
            }
            nm.createNotificationChannel(ch)
        }
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** 依设置重新调度：模式0=每日汇总；模式1=逐节课前提醒。 */
    fun reschedule(context: Context) {
        ensureChannel(context)
        val store = Store(context)
        cancelAll(context)
        val schedule = store.schedule() ?: return
        if (!store.notifyEnabled) return
        if (store.notifyVia == 1) return   // 手机日历模式：由系统日历负责提醒，不排本机闹钟
        when (store.notifyMode) {
            1 -> planClassAlarms(context, store, schedule)
            else -> planDailyAlarm(context, store)
        }
    }

    fun cancelAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(dailyPending(context))
        val codes = loadCodes(context)
        for (code in codes) {
            am.cancel(classPending(context, code, null))
        }
        clearCodes(context)
    }

    fun cancel(context: Context) = cancelAll(context)

    // ---------------- 模式0：每日定时汇总 ----------------
    private fun planDailyAlarm(context: Context, store: Store) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val (h, m) = store.notifyTime
        val next = nextTrigger(LocalTime.of(h, m))
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        setAlarm(am, at, dailyPending(context), false)
    }

    private fun nextTrigger(time: LocalTime): LocalDateTime {
        val now = LocalDateTime.now()
        val today = LocalDateTime.of(now.toLocalDate(), time)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    private fun dailyPending(context: Context): PendingIntent {
        val i = Intent(context, DailySummaryReceiver::class.java).setAction(ACTION_DAILY)
        return PendingIntent.getBroadcast(
            context, RC_DAILY, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ---------------- 模式1：每节课前提醒（滚动 7 天窗口） ----------------
    private fun planClassAlarms(context: Context, store: Store, schedule: Schedule) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val lead = store.classLeadMin
        val cal = TermCalendar(schedule.week1Monday, schedule.weekCount)
        val today = LocalDate.now()
        val codes = ArrayList<Int>()
        val nowMs = System.currentTimeMillis()
        for (offset in 0L until 7L) {
            val date = today.plusDays(offset)
            val week = cal.weekOf(date) ?: continue
            val dIdx = TermCalendar.indexOfDay(date.dayOfWeek)
            val list = schedule.entriesOn(week, dIdx)
            for (e in list) {
                val startMin = TimeText.startMinutes(e.startSlot) ?: continue
                if (e.startSlot > 10) continue
                val start = LocalDateTime.of(date, LocalTime.of(startMin / 60, startMin % 60))
                val fire = start.minusMinutes(lead.toLong())
                if (fire.isBefore(LocalDateTime.now())) continue
                val code = RC_BASE + codes.size
                codes.add(code)
                val extra = courseExtras(e, date)
                val at = fire.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (at < nowMs) continue
                setAlarm(am, at, classPending(context, code, extra), true)
            }
        }
        clearCodes(context)
        storeCodes(context, codes)
    }

    private fun courseExtras(e: CourseEntry, date: LocalDate): Bundle {
        val (label, range) = TimeText.slotLabelAndRange(e.startSlot, e.slotSpan)
        return Bundle().apply {
            putString("name", courseDisplayName(e))
            putString("label", label)
            putString("range", range)
            putString("teacher", e.teacher.trim())
            putString("room", e.room.trim())
            putString("date", date.toString())
        }
    }

    private fun setAlarm(am: AlarmManager, at: Long, pi: PendingIntent, exact: Boolean) {
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (se: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun classPending(context: Context, code: Int, extra: Bundle?): PendingIntent {
        val i = Intent(context, ClassReminderReceiver::class.java).setAction(ACTION_CLASS)
        if (extra != null) i.putExtras(extra)
        return PendingIntent.getBroadcast(
            context, code, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun storeCodes(context: Context, codes: List<Int>) {
        val arr = JSONArray()
        codes.forEach { arr.put(it) }
        context.getSharedPreferences("coursetable", Context.MODE_PRIVATE)
            .edit().putString(KEY_CODES, arr.toString()).apply()
    }

    private fun loadCodes(context: Context): List<Int> {
        val s = context.getSharedPreferences("coursetable", Context.MODE_PRIVATE)
            .getString(KEY_CODES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(s)
            (0 until arr.length()).map { arr.getInt(it) }
        }.getOrDefault(emptyList())
    }

    private fun clearCodes(context: Context) {
        context.getSharedPreferences("coursetable", Context.MODE_PRIVATE)
            .edit().remove(KEY_CODES).apply()
    }

    // ---------------- 通知构建与发送 ----------------
    fun openDayIntent(context: Context, date: String?): PendingIntent {
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (date != null) putExtra(MainActivity.EXTRA_OPEN_DAY, date)
        }
        return PendingIntent.getActivity(
            context, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun postDaily(context: Context) {
        ensureChannel(context)
        if (!hasPermission(context)) return
        val store = Store(context)
        val schedule = store.schedule() ?: return
        val today = LocalDate.now()
        val body = buildSummary(context, schedule, today)
        val fmt = DateTimeFormatter.ofPattern("M月d日")
        val dayName = WEEKDAY_SHORT[TermCalendar.indexOfDay(today.dayOfWeek)]
        val cal = TermCalendar(schedule.week1Monday, schedule.weekCount)
        val week = cal.weekOf(today)
        val title = if (week != null) {
            context.getString(R.string.notify_title, "$dayName ${today.format(fmt)} · 第${week}周")
        } else context.getString(R.string.notify_title, dayName + today.format(fmt))
        notify(
            context, 2001, title,
            body ?: context.getString(R.string.notify_no_class),
            openDayIntent(context, today.toString()), false
        )
    }

    fun postClass(context: Context, extra: Bundle?) {
        ensureChannel(context)
        if (!hasPermission(context)) return
        val name = extra?.getString("name") ?: "课程"
        val label = extra?.getString("label") ?: ""
        val range = extra?.getString("range") ?: ""
        val teacher = extra?.getString("teacher") ?: ""
        val room = extra?.getString("room") ?: ""
        val date = extra?.getString("date")
        val title = "上课提醒 · $name"
        val sb = StringBuilder("即将上课：$name")
        if (label.isNotEmpty() || range.isNotEmpty()) sb.append("\n[$label ${range.trim()}]")
        if (teacher.isNotEmpty()) sb.append("\n教师：").append(teacher)
        if (room.isNotEmpty()) sb.append("\n教室：").append(room)
        notify(context, 2002, title, sb.toString(), openDayIntent(context, date), true)
    }

    private fun notify(
        context: Context,
        id: Int,
        title: String,
        content: String,
        contentIntent: PendingIntent,
        fullScreen: Boolean
    ) {
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(content.lines().firstOrNull() ?: content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (fullScreen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                b.setFullScreenIntent(contentIntent, true) // 熄屏/锁屏可全屏提醒（部分机型受系统限制自动降级）
            } catch (ignored: Throwable) {
            }
        }
        try {
            NotificationManagerCompat.from(context).notify(id, b.build())
        } catch (se: SecurityException) {
        }
    }

    fun showToastIfDenied(context: Context) {
        if (!hasPermission(context)) {
            Toast.makeText(context, R.string.notify_permission_hint, Toast.LENGTH_LONG).show()
        }
    }

    fun buildSummary(context: Context, schedule: Schedule, date: LocalDate): String? {
        val cal = TermCalendar(schedule.week1Monday, schedule.weekCount)
        val week = cal.weekOf(date) ?: return null
        val dayIdx = TermCalendar.indexOfDay(date.dayOfWeek)
        val list = schedule.entriesOn(week, dayIdx)
        if (list.isEmpty()) return null
        val sb = StringBuilder()
        for (e in list) {
            val (label, range) = TimeText.slotLabelAndRange(e.startSlot, e.slotSpan)
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("[$label ${range.trim()}] ").append(courseDisplayName(e))
            val det = mutableListOf<String>()
            if (e.teacher.isNotBlank()) det.add(e.teacher.trim())
            if (e.room.isNotBlank()) det.add(e.room.trim())
            if (det.isNotEmpty()) sb.append("\n").append(det.joinToString(" · "))
        }
        return sb.toString()
    }
}

private typealias Bundle = android.os.Bundle

/** 每日汇总到点触发。 */
class DailySummaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DailyReminder.ACTION_DAILY) {
            DailyReminder.postDaily(context)
            DailyReminder.reschedule(context) // 续排次日
        }
    }
}

/** 每节课前提醒到点触发（并滚动续排窗口）。 */
class ClassReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DailyReminder.ACTION_CLASS) {
            DailyReminder.postClass(context, intent.extras)
            DailyReminder.reschedule(context)
        }
    }
}

/** 开机/升级/时区变化/日期变化后恢复调度。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED -> DailyReminder.reschedule(context)
        }
    }
}
