package com.coursetable.app.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.coursetable.app.MainActivity
import com.coursetable.app.R
import com.coursetable.app.notify.DailyReminder
import com.coursetable.app.util.CalendarSync
import com.coursetable.app.util.UpdateChecker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 设置：课程通知（锁屏可见，可选「时钟/手机日历」）、更新检查、学期起点、导入等。 */
class SettingsFragment : Fragment() {

    companion object {
        const val TAG = "settings"
        private const val REQ_CALENDAR = 501
    }

    private var root: View? = null
    private var viaGuard = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_settings, container, false)
        root = v
        val act = activity as? MainActivity ?: return v
        val store = act.store

        val swNotify = v.findViewById<MaterialSwitch>(R.id.swNotify)
        val swWeekend = v.findViewById<MaterialSwitch>(R.id.swWeekend)
        val rgVia = v.findViewById<RadioGroup>(R.id.rgVia)
        val rbClock = v.findViewById<RadioButton>(R.id.rbClock)
        val rbCalendar = v.findViewById<RadioButton>(R.id.rbCalendar)
        val rbApp = v.findViewById<RadioButton>(R.id.rbApp)
        val rowViaHint = v.findViewById<TextView>(R.id.rowViaHint)
        val rowClearCalSync = v.findViewById<TextView>(R.id.rowClearCalSync)
        val rowClockSync = v.findViewById<TextView>(R.id.rowClockSync)
        val rowTimingLabel = v.findViewById<TextView>(R.id.rowTimingLabel)
        val rowTime = v.findViewById<TextView>(R.id.rowNotifyTime)
        val rowTerm = v.findViewById<TextView>(R.id.rowTermStart)
        val rowImport = v.findViewById<View>(R.id.rowImport)
        val rowClear = v.findViewById<View>(R.id.rowClear)
        val rowAbout = v.findViewById<View>(R.id.rowAbout)
        val rowLead = v.findViewById<TextView>(R.id.rowLead)
        val rowModeHint = v.findViewById<TextView>(R.id.rowModeHint)
        val rowUpdate = v.findViewById<TextView>(R.id.rowUpdate)
        val rgMode = v.findViewById<RadioGroup>(R.id.rgMode)
        val rbDaily = v.findViewById<RadioButton>(R.id.rbDaily)
        val rbClass = v.findViewById<RadioButton>(R.id.rbClass)

        fun applyModeUi() {
            val via = store.notifyVia
            viaGuard = true
            rbClock.isChecked = via == 0
            rbCalendar.isChecked = via == 1
            rbApp.isChecked = via == 2
            viaGuard = false
            val isCalendar = via == 1
            rowTimingLabel.visibility = if (isCalendar) View.GONE else View.VISIBLE
            rgMode.visibility = if (isCalendar) View.GONE else View.VISIBLE
            rowModeHint.visibility = if (isCalendar) View.GONE else View.VISIBLE
            val mode = store.notifyMode
            rbDaily.isChecked = mode == 0
            rbClass.isChecked = mode == 1
            rowTime.visibility = if (!isCalendar && mode == 0) View.VISIBLE else View.GONE
            rowLead.visibility = if (!isCalendar && mode == 1) View.VISIBLE else View.GONE
            rowLead.text = "课前提醒时间：上课前 ${store.classLeadMin} 分钟（点按自定义 0-120）"
            rowClockSync.visibility = if (via == 0 && store.notifyEnabled) View.VISIBLE else View.GONE
            rowViaHint.text = when (via) {
                1 -> "已选择手机日历：将课程写入系统日历，由日历在每节课前 ${store.classLeadMin} 分钟提醒。"
                2 -> "已选择软件消息：应用内消息通知（不依赖日历与时钟），锁屏可见。"
                else -> "已选择时钟：本机闹钟逐条设置闹铃并附课程备注；北京时间 24:00 自动清理已发生闹钟。"
            }
            rowModeHint.text = if (mode == 0) {
                "每天在设定时间推送当天全部课程（锁屏可见）。"
            } else {
                "每节课在其开始前 N 分钟单独提醒；课程当天自动生效，含锁屏显示。"
            }
        }

        fun calendarGranted(): Boolean =
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED

        /** 日历模式下显示“消除同步”入口（同步了事件才可见）。 */
        fun refreshCalSyncRow() {
            val count = CalendarSync.syncedEventCount(requireContext())
            val show = store.notifyVia == 1 && count > 0
            rowClearCalSync.visibility = if (show) View.VISIBLE else View.GONE
            rowClearCalSync.text = if (show) "消除日历同步（已同步 $count 条课程事件）" else "消除日历同步"
        }

        /** 按「启用开关 + 通知方式」统一协调：时钟→本机闹钟；手机日历→系统日历。 */
        fun syncNotifications() {
            if (!store.notifyEnabled) {
                DailyReminder.cancel(requireContext())
                Thread { CalendarSync.removeAll(requireContext()) }.start()
                refreshCalSyncRow()
                return
            }
            if (store.notifyVia == 1) {
                DailyReminder.cancel(requireContext())
                val s = act.currentSchedule()
                if (s == null) {
                    store.notifyVia = 0
                    applyModeUi()
                    Toast.makeText(requireContext(), "请先导入课表，再选择手机日历提醒", Toast.LENGTH_LONG).show()
                    return
                }
                if (calendarGranted()) runCalendarSync()
                else requestPermissions(
                    arrayOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR),
                    REQ_CALENDAR
                )
            } else {
                Thread { CalendarSync.removeAll(requireContext()) }.start()
                DailyReminder.reschedule(requireContext())
                refreshCalSyncRow()
            }
        }

        fun refreshTexts() {
            val s = act.currentSchedule()
            val info = v.findViewById<TextView>(R.id.settingsScheduleInfo)
            info.text = buildString {
                if (s != null) {
                    append("当前课表：").append(s.termLabel)
                    s.infoLine?.let { append("\n").append(it) }
                    append("\n共 ").append(s.entries.size).append(" 条排课 · ")
                    append(s.weekCount).append(" 周 · 来源：").append(act.store.sourceName() ?: "导入文件")
                } else {
                    append(getString(R.string.no_schedule_hint))
                }
            }
            val (h, m) = store.notifyTime
            rowTime.text = getString(R.string.pref_notify_time) + "（" +
                String.format("%02d:%02d", h, m) + "）"
            rowTerm.text = getString(R.string.pref_term_start) + "（" +
                store.termStart.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) +
                "，周一）"
            applyModeUi()
            rowUpdate.text = "检查更新（当前版本 ${com.coursetable.app.BuildConfig.VERSION_NAME}）"
            refreshCalSyncRow()
        }

        swNotify.isChecked = store.notifyEnabled
        swWeekend.isChecked = store.showWeekend
        applyModeUi()

        swNotify.setOnCheckedChangeListener { _, checked ->
            store.notifyEnabled = checked
            applyModeUi()
            syncNotifications()
        }

        rgVia.setOnCheckedChangeListener { _, checkedId ->
            if (viaGuard) return@setOnCheckedChangeListener
            store.notifyVia = when (checkedId) {
                R.id.rbCalendar -> 1
                R.id.rbApp -> 2
                else -> 0
            }
            applyModeUi()
            syncNotifications()
        }

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            store.notifyMode = if (checkedId == R.id.rbClass) 1 else 0
            applyModeUi()
            if (store.notifyEnabled) DailyReminder.reschedule(requireContext())
        }

        rowLead.setOnClickListener {
            val input = android.widget.EditText(requireContext()).apply {
                setText(store.classLeadMin.toString())
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                hint = "0-120 分钟"
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("课前提醒时间")
                .setMessage("上课前多少分钟提醒？（0-120，0 表示上课时提醒）")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定") { _, _ ->
                    val v = input.text.toString().trim()
                    if (v.isNotEmpty()) {
                        store.classLeadMin = v.toIntOrNull()?.coerceIn(0, 120) ?: store.classLeadMin
                        applyModeUi()
                        if (store.notifyEnabled) {
                            if (store.notifyVia == 1) syncNotifications() else DailyReminder.reschedule(requireContext())
                        }
                    }
                }
                .show()
        }

        rowTime.setOnClickListener {
            val (h, m) = store.notifyTime
            TimePickerDialog(requireContext(), { _, hh, mm ->
                store.notifyTime = hh to mm
                refreshTexts()
                if (store.notifyEnabled) DailyReminder.reschedule(requireContext())
            }, h, m, true).show()
        }

        rowTerm.setOnClickListener {
            val d = store.termStart
            android.app.DatePickerDialog(requireContext(), { _, yy, mm, dd ->
                val newStart = LocalDate.of(yy, mm + 1, dd)
                store.termStart = newStart
                refreshTexts()
                val s = act.currentSchedule()
                if (s != null) {
                    val ns = s.copy(week1Monday = newStart)
                    act.store.saveSchedule(ns, act.store.sourceName())
                    act.refreshAll()
                }
                DailyReminder.reschedule(requireContext())
                if (store.notifyEnabled && store.notifyVia == 1) syncNotifications()
            }, d.year, d.monthValue - 1, d.dayOfMonth).show()
        }

        swWeekend.setOnCheckedChangeListener { _, checked ->
            store.showWeekend = checked
            act.refreshAll()
        }

        rowClearCalSync.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("消除日历同步")
                .setMessage("将从系统日历中删除本应用同步的全部课程事件（含历史遗留）。课表数据不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("消除") { _, _ ->
                    Thread {
                        val removed = CalendarSync.removeAll(requireContext())
                        act.runOnUiThread {
                            refreshCalSyncRow()
                            Toast.makeText(requireContext(), "已消除日历同步（删除 $removed 条事件）", Toast.LENGTH_SHORT).show()
                        }
                    }.start()
                }
                .show()
        }

        rowClockSync.setOnClickListener {
            val n = DailyReminder.openSystemClock(requireContext())
            if (n > 0) {
                Toast.makeText(
                    requireContext(),
                    "已向本机时钟发起 $n 个闹铃，请在时钟 App 中逐个确认保存",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(requireContext(), "当前没有可设置的闹铃", Toast.LENGTH_SHORT).show()
            }
        }

        rowUpdate.setOnClickListener {
            rowUpdate.text = "正在检查更新…"
            UpdateChecker.checkAsync(requireContext()) { info ->
                rowUpdate.text = "检查更新（当前版本 ${com.coursetable.app.BuildConfig.VERSION_NAME}）"
                if (info == null) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("检查更新")
                        .setMessage(
                            "无法获取更新信息。\n当前版本 v" + com.coursetable.app.BuildConfig.VERSION_NAME +
                                "\n（更新清单地址在源码 UpdateChecker 顶部配置 GitHub 仓库）"
                        )
                        .setPositiveButton("好", null)
                        .show()
                } else if (!info.isNewer()) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("检查更新")
                        .setMessage("已是最新版本 v" + com.coursetable.app.BuildConfig.VERSION_NAME)
                        .setPositiveButton("好", null)
                        .show()
                } else {
                    val builder = MaterialAlertDialogBuilder(requireContext())
                        .setTitle("发现新版本 v${info.versionName}")
                        .setMessage(info.promptText())
                        .setPositiveButton("下载更新") { _, _ ->
                            UpdateChecker.downloadAndInstall(requireContext(), info)
                        }
                        .setNegativeButton("稍后", null)
                    builder.show()
                }
            }
        }

        rowImport.setOnClickListener { act.openImport() }

        rowClear.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pref_clear)
                .setMessage("将删除已导入的全部课程数据（设置保留）。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除") { _, _ -> act.onScheduleCleared() }
                .show()
        }

        rowAbout.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pref_about)
                .setMessage(getString(R.string.time_rule) + "\n\n课程表 v" +
                    com.coursetable.app.BuildConfig.VERSION_NAME)
                .setPositiveButton("好", null)
                .show()
        }

        refreshTexts()
        return v
    }

    /** 把当前课表全部课程写入系统日历（含提醒分钟），完成后 Toast 汇总。 */
    private fun runCalendarSync() {
        val act = activity as? MainActivity ?: return
        val schedule = act.currentSchedule() ?: return
        val rgVia = root?.findViewById<RadioGroup>(R.id.rgVia) ?: return
        rgVia.isEnabled = false
        Toast.makeText(requireContext(), "正在同步到系统日历…", Toast.LENGTH_SHORT).show()
        Thread {
            val msg = runCatching {
                CalendarSync.resync(requireContext(), schedule, act.store.classLeadMin)
                "已同步 ${CalendarSync.syncedEventCount(requireContext())} 条课程事件到系统日历"
            }.getOrElse { e -> "同步失败：${e.message}" }
            act.runOnUiThread {
                rgVia.isEnabled = true
                val row = root?.findViewById<TextView>(R.id.rowClearCalSync)
                val count = CalendarSync.syncedEventCount(requireContext())
                if (row != null) {
                    val show = act.store.notifyVia == 1 && count > 0
                    row.visibility = if (show) View.VISIBLE else View.GONE
                    row.text = if (show) "消除日历同步（已同步 $count 条课程事件）" else "消除日历同步"
                }
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CALENDAR) {
            val ok = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            val act = activity as? MainActivity ?: return
            if (!ok) {
                act.store.notifyVia = 0
                val v = root ?: return
                viaGuard = true
                v.findViewById<RadioButton>(R.id.rbClock).isChecked = true
                v.findViewById<RadioButton>(R.id.rbCalendar).isChecked = false
                viaGuard = false
                Toast.makeText(requireContext(), "需要日历权限才能使用手机日历提醒，已切回时钟方式", Toast.LENGTH_LONG).show()
                return
            }
            if (act.store.notifyEnabled && act.store.notifyVia == 1) runCalendarSync()
        }
    }
}
