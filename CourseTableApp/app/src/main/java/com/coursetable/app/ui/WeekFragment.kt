package com.coursetable.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.coursetable.app.MainActivity
import com.coursetable.app.R
import com.coursetable.app.engine.TermCalendar
import com.coursetable.app.model.Schedule
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 周课表视图：自绘天网格（星期/日期表头 + 课程块，已取消节次·时间显示）+ 周切换。 */
class WeekFragment : Fragment() {

    companion object {
        const val TAG = "week"
    }

    private var root: View? = null
    private var curWeek = 1
    private var sched: Schedule? = null
    private var cal: TermCalendar? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_week, container, false)
        root = v
        v.findViewById<View>(R.id.btnImportNow).setOnClickListener {
            (activity as? MainActivity)?.openImport()
        }
        v.findViewById<View>(R.id.btnPrevWeek).setOnClickListener { moveWeek(-1) }
        v.findViewById<View>(R.id.btnNextWeek).setOnClickListener { moveWeek(1) }
        v.findViewById<View>(R.id.btnGoToday).setOnClickListener {
            val act = activity as? MainActivity ?: return@setOnClickListener
            act.store.selectedDate = LocalDate.now()
            refreshData()
        }
        val grid = v.findViewById<WeekGridView>(R.id.weekGrid)
        grid.onDayClick = { date -> (activity as? MainActivity)?.openDay(date) }
        grid.onCourseClick = { date, _ -> (activity as? MainActivity)?.openDay(date) }
        refreshData()
        return v
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    private fun moveWeek(delta: Int) {
        val act = activity as? MainActivity ?: return
        val c = cal ?: act.currentCalendar()
        val newWeek = (curWeek + delta).coerceIn(1, c.weekCount)
        if (newWeek == curWeek) return
        curWeek = newWeek
        act.store.selectedDate = c.mondayOf(newWeek)
        refreshData()
    }

    fun refreshData() {
        val v = root ?: return
        val act = activity as? MainActivity ?: return
        sched = act.currentSchedule()
        val content = v.findViewById<View>(R.id.contentRoot)
        val empty = v.findViewById<View>(R.id.emptyState)
        val grid = v.findViewById<WeekGridView>(R.id.weekGrid)
        val schedule = sched
        if (schedule == null) {
            content.visibility = View.GONE
            empty.visibility = View.VISIBLE
            return
        }
        content.visibility = View.VISIBLE
        empty.visibility = View.GONE

        val calendar = TermCalendar(schedule.week1Monday, schedule.weekCount)
        cal = calendar
        val sel = act.store.selectedDate
        curWeek = calendar.weekOf(sel)
            ?: if (sel.isBefore(schedule.week1Monday)) 1 else calendar.weekCount
        val weekStart = calendar.mondayOf(curWeek)

        v.findViewById<TextView>(R.id.weekTitle).text = getString(R.string.week_of_term, curWeek)
        val fmt = DateTimeFormatter.ofPattern("M月d日")
        val info = v.findViewById<TextView>(R.id.termInfo)
        val rangeText = "${weekStart.format(fmt)} - ${weekStart.plusDays(6).format(fmt)}"
        info.text = buildString {
            if (schedule.termLabel.isNotEmpty()) append(schedule.termLabel).append(" · ")
            append(rangeText)
            schedule.infoLine?.let { append("\n").append(it) }
        }

        grid.configure(
            schedule = schedule,
            weekStart = weekStart,
            week = curWeek,
            today = LocalDate.now(),
            selectedDate = sel,
            showWeekend = act.store.showWeekend,
            weekendHasCourses = schedule.hasWeekendEntries()
        )
    }
}
