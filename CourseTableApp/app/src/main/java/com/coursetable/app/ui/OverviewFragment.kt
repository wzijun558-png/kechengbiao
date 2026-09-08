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
import com.coursetable.app.engine.TimeText
import com.coursetable.app.model.CourseEntry
import com.coursetable.app.model.Schedule
import com.coursetable.app.model.WEEKDAY_SHORT
import com.coursetable.app.model.courseDisplayName
import java.util.LinkedHashMap

/** 全部课程总览：按“课程名+教师”聚合去重后详尽列出每次排课。 */
class OverviewFragment : Fragment() {

    companion object {
        const val TAG = "overview"
    }

    private var root: View? = null
    private lateinit var adapter: OverviewAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_overview, container, false)
        root = v
        val list = v.findViewById<RecyclerView>(R.id.ovList)
        list.layoutManager = LinearLayoutManager(requireContext())
        adapter = OverviewAdapter()
        list.adapter = adapter
        refreshData()
        return v
    }

    fun refreshData() {
        val v = root ?: return
        val act = activity as? MainActivity ?: return
        val schedule = act.currentSchedule()
        val sub = v.findViewById<TextView>(R.id.ovSub)
        if (schedule == null) {
            adapter.submit(emptyList(), emptyMap())
            sub.text = getString(R.string.no_schedule_hint)
            return
        }
        val items = ArrayList<Any>()
        var occTotal = 0
        for ((key, list) in schedule.groupByCourse()) {
            if (list.isEmpty()) continue
            val occ = list
            occTotal += occ.size
            val name = courseDisplayName(occ.first())
            val teachers = occ.map { it.teacher.trim() }.filter { it.isNotEmpty() }.distinct()
            val rooms = occ.map { it.room.trim() }.filter { it.isNotEmpty() }.distinct()
            val meta = buildString {
                if (teachers.isNotEmpty()) append("教师：").append(teachers.joinToString("、"))
                if (rooms.isNotEmpty()) { if (isNotEmpty()) append("　"); append("教室：").append(rooms.joinToString("、")) }
            }
            items.add(GroupItem(name, key, meta, occ.size))
            items.addAll(occ.map { OccItem(it) })
        }
        adapter.submit(items, CourseStyle.indexMap(schedule))
        sub.text = schedule.titleLines.joinToString("  ") + "\n" +
            "共 ${schedule.groupByCourse().size} 门课 · $occTotal 次排课 · ${schedule.weekCount} 周"
    }

    private data class GroupItem(val name: String, val key: String, val meta: String, val count: Int)
    private data class OccItem(val entry: CourseEntry)

    private class OverviewAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val items = ArrayList<Any>()
        private var colors: Map<String, Int> = emptyMap()

        fun submit(list: List<Any>, colorMap: Map<String, Int>) {
            items.clear()
            items.addAll(list)
            colors = colorMap
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int) = if (items[position] is GroupItem) 0 else 1
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                GroupHolder(inflater.inflate(R.layout.item_course_group, parent, false))
            } else {
                OccHolder(inflater.inflate(R.layout.item_course_occ, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val it = items[position]) {
                is GroupItem -> {
                    val h = holder as GroupHolder
                    val ctx = h.itemView.context
                    h.dot.background = null
                    h.dot.setBackgroundColor(CourseStyle.mainOf(colors, it.key))
                    h.name.text = it.name
                    h.count.text = ctx.getString(R.string.occurrences, it.count)
                    h.meta.text = it.meta
                }
                is OccItem -> {
                    val h = holder as OccHolder
                    val e = it.entry
                    val (label, range) = TimeText.slotLabelAndRange(e.startSlot, e.slotSpan)
                    val whenText = WEEKDAY_SHORT[e.day] + " " + label +
                        (if (range.isNotEmpty()) "  $range" else "")
                    h.whenView.text = whenText
                    val detail = buildString {
                        if (e.weeksText.isNotEmpty()) append(e.weeksText)
                        if (e.room.isNotBlank()) { if (isNotEmpty()) append(" · "); append(e.room.trim()) }
                        if (e.teacher.isNotBlank()) { if (isNotEmpty()) append(" · "); append(e.teacher.trim()) }
                    }
                    h.meta.text = if (detail.isNotEmpty()) detail else e.raw.lines().joinToString(" ")
                }
            }
        }

        class GroupHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val dot: View = itemView.findViewById(R.id.groupDot)
            val name: TextView = itemView.findViewById(R.id.groupName)
            val count: TextView = itemView.findViewById(R.id.groupCount)
            val meta: TextView = itemView.findViewById(R.id.groupMeta)
        }

        class OccHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val whenView: TextView = itemView.findViewById(R.id.occWhen)
            val meta: TextView = itemView.findViewById(R.id.occMeta)
        }
    }
}
