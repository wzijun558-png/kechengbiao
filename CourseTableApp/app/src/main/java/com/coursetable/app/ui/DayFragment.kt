package com.coursetable.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.coursetable.app.MainActivity
import com.coursetable.app.R
import com.coursetable.app.engine.TermCalendar
import com.coursetable.app.engine.TimeText
import com.coursetable.app.model.CourseEntry
import com.coursetable.app.model.Schedule
import com.coursetable.app.model.WEEKDAY_SHORT
import com.coursetable.app.model.courseDisplayName
import com.coursetable.app.model.courseSubtitle
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 日视图：某一天的全部课程卡片列表 + 前后日切换。 */
class DayFragment : Fragment() {

    companion object {
        const val TAG = "day"
    }

    private var root: View? = null
    private lateinit var adapter: DayAdapter
    private var date: LocalDate = LocalDate.now()
    private var sched: Schedule? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_day, container, false)
        root = v
        val list = v.findViewById<RecyclerView>(R.id.dayList)
        list.layoutManager = LinearLayoutManager(requireContext())
        adapter = DayAdapter()
        list.adapter = adapter

        v.findViewById<View>(R.id.btnPrevDay).setOnClickListener { moveDay(-1) }
        v.findViewById<View>(R.id.btnNextDay).setOnClickListener { moveDay(1) }
        v.findViewById<View>(R.id.btnGoTodayDay).setOnClickListener {
            date = LocalDate.now()
            (activity as? MainActivity)?.store?.selectedDate = date
            refreshData()
        }
        refreshData()
        return v
    }

    private fun moveDay(delta: Long) {
        date = date.plusDays(delta)
        (activity as? MainActivity)?.store?.selectedDate = date
        refreshData()
    }

    fun refreshData() {
        val v = root ?: return
        val act = activity as? MainActivity ?: return
        sched = act.currentSchedule()
        date = act.store.selectedDate
        val schedule = sched

        val title = v.findViewById<TextView>(R.id.dayTitle)
        val sub = v.findViewById<TextView>(R.id.daySub)
        val termInfo = v.findViewById<TextView>(R.id.dayTermInfo)
        val empty = v.findViewById<TextView>(R.id.dayEmpty)
        val list = v.findViewById<RecyclerView>(R.id.dayList)

        val dIdx = TermCalendar.indexOfDay(date.dayOfWeek)
        title.text = WEEKDAY_SHORT[dIdx]
        sub.text = date.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))

        if (schedule == null) {
            adapter.attachColors(emptyMap())
            adapter.submit(emptyList())
            empty.visibility = View.VISIBLE
            empty.text = getString(R.string.no_schedule_hint)
            list.visibility = View.GONE
            termInfo.text = ""
            return
        }
        list.visibility = View.VISIBLE
        adapter.attachColors(CourseStyle.indexMap(schedule))
        val calendar = TermCalendar(schedule.week1Monday, schedule.weekCount)
        val week = calendar.weekOf(date)
        val items: List<CourseEntry> =
            if (week != null) schedule.entriesOn(week, dIdx) else emptyList()

        if (week != null) {
            termInfo.text = getString(R.string.week_of_term, week) +
                " · " + schedule.week1Monday.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) +
                " 起 共" + schedule.weekCount + "周"
        } else {
            termInfo.text = getString(R.string.out_of_term)
        }

        if (items.isEmpty()) {
            adapter.submit(emptyList())
            empty.visibility = View.VISIBLE
            empty.text = getString(R.string.no_class_today)
            if (week == null) empty.text = getString(R.string.out_of_term)
        } else {
            empty.visibility = View.GONE
            adapter.submit(items)
        }
    }

    private class DayAdapter : RecyclerView.Adapter<DayAdapter.Holder>() {
        private val items = ArrayList<CourseEntry>()
        private var colors: Map<String, Int> = emptyMap()

        fun attachColors(map: Map<String, Int>) {
            colors = map
        }

        fun submit(list: List<CourseEntry>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_course_day, parent, false)
            return Holder(view)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val e = items[position]
            val key = Schedule.courseKey(e.name, e.teacher)
            holder.strip.setBackgroundColor(CourseStyle.mainOf(colors, key))
            holder.name.text = courseDisplayName(e)
            val (label, range) = TimeText.slotLabelAndRange(e.startSlot, e.slotSpan)
            holder.time.text = if (range.isNotEmpty()) "$label  $range" else label

            val meta = buildString {
                if (e.weeksText.isNotEmpty()) append(e.weeksText)
                if (e.room.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(e.room.trim())
                }
            }
            holder.meta.text = courseSubtitle(e).let { base ->
                when {
                    base.isEmpty() && meta.isEmpty() -> e.raw
                    base.isEmpty() -> meta
                    else -> base + if (meta.isNotEmpty()) " ｜ $meta" else ""
                }
            }
        }

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val strip: View = itemView.findViewById(R.id.dayStrip)
            val name: TextView = itemView.findViewById(R.id.dayName)
            val meta: TextView = itemView.findViewById(R.id.dayMeta)
            val time: TextView = itemView.findViewById(R.id.dayTime)
        }
    }
}
