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
import java.time.YearMonth

/** 月视图：学期内各月日历（课程彩色圆点 = 当天有课数）+ 月切换。 */
class MonthFragment : Fragment() {

    companion object {
        const val TAG = "month"
    }

    private var root: View? = null
    private var shown: YearMonth = YearMonth.now()
    /** 用户手动定位到的月份；为 null 时跟随选中日期所在月（学期内） */
    private var anchor: YearMonth? = null
    private var sched: Schedule? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_month, container, false)
        root = v
        v.findViewById<View>(R.id.btnPrevMonth).setOnClickListener {
            anchor = (anchor ?: shown).minusMonths(1)
            refreshData()
        }
        v.findViewById<View>(R.id.btnNextMonth).setOnClickListener {
            anchor = (anchor ?: shown).plusMonths(1)
            refreshData()
        }
        val cal = v.findViewById<MonthCalendarView>(R.id.monthCal)
        cal.onDateClick = { date -> (activity as? MainActivity)?.openDay(date) }
        refreshData()
        return v
    }

    fun refreshData() {
        val v = root ?: return
        val act = activity as? MainActivity ?: return
        sched = act.currentSchedule()
        val schedule = sched
        val monthView = v.findViewById<MonthCalendarView>(R.id.monthCal)
        val title = v.findViewById<TextView>(R.id.monthTitle)
        val legend = v.findViewById<TextView>(R.id.monthLegend)

        if (schedule == null) {
            anchor = null
            shown = YearMonth.now()
            title.text = shown.year.toString() + "年" + shown.monthValue + "月"
            monthView.configure(shown, null, LocalDate.now(), null) { false }
            legend.text = getString(R.string.no_schedule_hint)
            return
        }
        val cal = TermCalendar(schedule.week1Monday, schedule.weekCount)
        val sel = act.store.selectedDate
        // 只在没有手动定位时，初始化到“选中日期所在月”（限制在学期首末月之间）
        if (anchor == null) {
            val mStart = cal.monthStart()
            val mEnd = cal.monthEnd()
            val selMonth = YearMonth.from(sel)
            anchor = when {
                selMonth.isBefore(mStart) -> mStart
                selMonth.isAfter(mEnd) -> mEnd
                else -> selMonth
            }
        }
        shown = anchor!!
        title.text = shown.year.toString() + "年" + shown.monthValue + "月"

        val inTerm: (LocalDate) -> Boolean = { d -> cal.isInTerm(d) }
        monthView.configure(shown, schedule, LocalDate.now(), sel, inTerm)
        legend.text = buildString {
            append(getString(R.string.week_of_term, 1)).append(" ")
            append(schedule.week1Monday.format(java.time.format.DateTimeFormatter.ofPattern("M月d日")))
            append(" - ")
            append(schedule.termLabel)
        }
    }
}
