package com.coursetable.app.data

import android.content.Context
import android.content.SharedPreferences
import com.coursetable.app.model.Schedule
import com.coursetable.app.model.ScheduleCodec
import java.time.LocalDate

/** 课表与设置持久化（SharedPreferences）。 */
class Store(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("coursetable", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_TERM_START = "2026-08-31"   // 用户确认：第1周周一
        const val DEFAULT_WEEK_COUNT = 18
        private const val KEY_SCHEDULE = "schedule"
        private const val KEY_SOURCE = "source_name"
        private const val KEY_TERM_START = "term_start"
        private const val KEY_NOTIFY = "notify_enabled"
        private const val KEY_NOTIFY_HOUR = "notify_hour"
        private const val KEY_NOTIFY_MIN = "notify_min"
        private const val KEY_WEEKEND = "show_weekend"
        private const val KEY_SELECTED = "selected_date"
        private const val KEY_DEFAULT_TERM_WEEKS = "default_term_weeks"
        private const val KEY_GUIDE = "import_guide_shown"
        private const val KEY_NOTIFY_MODE = "notify_mode"
        private const val KEY_CLASS_LEAD = "class_lead_min"
        private const val KEY_NOTIFY_VIA = "notify_via"
        private const val KEY_AUTO_UPDATE_DAY = "auto_update_day"
    }

    fun schedule(): Schedule? {
        val json = sp.getString(KEY_SCHEDULE, null) ?: return null
        return runCatching { ScheduleCodec.fromJson(json) }.getOrNull()
    }

    fun saveSchedule(s: Schedule, sourceName: String?) {
        val ed = sp.edit().putString(KEY_SCHEDULE, ScheduleCodec.toJson(s))
        if (sourceName != null) ed.putString(KEY_SOURCE, sourceName)
        ed.apply()
    }

    fun sourceName(): String? = sp.getString(KEY_SOURCE, null)

    fun clearSchedule() {
        sp.edit().remove(KEY_SCHEDULE).remove(KEY_SOURCE).apply()
    }

    var termStart: LocalDate
        get() = runCatching { LocalDate.parse(sp.getString(KEY_TERM_START, DEFAULT_TERM_START)) }
            .getOrDefault(LocalDate.parse(DEFAULT_TERM_START))
        set(v) = sp.edit().putString(KEY_TERM_START, v.toString()).apply()

    /** 无课表时可用的默认周数；有课表时以 schedule.weekCount 为准。 */
    var defaultTermWeeks: Int
        get() = sp.getInt(KEY_DEFAULT_TERM_WEEKS, DEFAULT_WEEK_COUNT)
        set(v) = sp.edit().putInt(KEY_DEFAULT_TERM_WEEKS, v.coerceIn(1, 60)).apply()

    fun activeWeekCount(s: Schedule?): Int = s?.weekCount ?: defaultTermWeeks

    var notifyEnabled: Boolean
        get() = sp.getBoolean(KEY_NOTIFY, true)
        set(v) = sp.edit().putBoolean(KEY_NOTIFY, v).apply()

    /** 每日通知时间(HH:mm)。默认 07:30。 */
    var notifyTime: Pair<Int, Int>
        get() = sp.getInt(KEY_NOTIFY_HOUR, 7) to sp.getInt(KEY_NOTIFY_MIN, 30)
        set(v) = sp.edit().putInt(KEY_NOTIFY_HOUR, v.first.coerceIn(0, 23))
            .putInt(KEY_NOTIFY_MIN, v.second.coerceIn(0, 59)).apply()

    var showWeekend: Boolean
        get() = sp.getBoolean(KEY_WEEKEND, false)
        set(v) = sp.edit().putBoolean(KEY_WEEKEND, v).apply()

    var selectedDate: LocalDate
        get() = runCatching { LocalDate.parse(sp.getString(KEY_SELECTED, LocalDate.now().toString())) }
            .getOrDefault(LocalDate.now())
        set(v) = sp.edit().putString(KEY_SELECTED, v.toString()).apply()

    /** 通知模式：0=每日定时汇总当天课程；1=每节课前提醒。 */
    var notifyMode: Int
        get() = sp.getInt(KEY_NOTIFY_MODE, 0).let { if (it == 1) 1 else 0 }
        set(v) = sp.edit().putInt(KEY_NOTIFY_MODE, if (v == 1) 1 else 0).apply()

    /** 课前提醒提前分钟数（默认10分钟）。 */
    var classLeadMin: Int
        get() = sp.getInt(KEY_CLASS_LEAD, 10).let { if (it in intArrayOf(5, 10, 15, 30)) it else 10 }
        set(v) = sp.edit().putInt(KEY_CLASS_LEAD, if (v in intArrayOf(5, 10, 15, 30)) v else 10).apply()

    /** 通知方式：0=时钟(本机闹钟)；1=手机日历(同步系统日历)；2=软件消息(应用内通知)。 */
    var notifyVia: Int
        get() = sp.getInt(KEY_NOTIFY_VIA, 0).let { if (it in 0..2) it else 0 }
        set(v) = sp.edit().putInt(KEY_NOTIFY_VIA, if (v in 0..2) v else 0).apply()

    /** 自动检查更新的日期（每日一次）。 */
    var lastAutoUpdateDay: String
        get() = sp.getString(KEY_AUTO_UPDATE_DAY, "") ?: ""
        set(v) = sp.edit().putString(KEY_AUTO_UPDATE_DAY, v).apply()

    /** 首次使用引导（无课表时仅提示一次）。 */
    var importGuideShown: Boolean
        get() = sp.getBoolean(KEY_GUIDE, false)
        set(v) = sp.edit().putBoolean(KEY_GUIDE, v).apply()
}
