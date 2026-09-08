package com.coursetable.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.coursetable.app.R
import com.coursetable.app.model.CourseEntry
import com.coursetable.app.model.Schedule
import com.coursetable.app.model.courseDisplayName
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 周课表网格（自绘）：只绘制“星期/日期表头 + 天列网格 + 课程块”。
 * 已取消左侧“节次·时间”显示，课程块仅按小节纵向排布。
 */
class WeekGridView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onDayClick: ((LocalDate) -> Unit)? = null
    var onCourseClick: ((LocalDate, CourseEntry) -> Unit)? = null

    data class DayCol(val dayIdx: Int, val date: LocalDate, val name: String, val isToday: Boolean)

    private var cols: List<DayCol> = emptyList()
    private var entriesByDay: Map<Int, List<CourseEntry>> = emptyMap()
    private var schedule: Schedule? = null
    private var selectedDate: LocalDate? = null
    private var colorIndexMap: Map<String, Int> = emptyMap()

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val headerH = dp(WeekMetrics.HEADER_H)
    private val slotH = dp(WeekMetrics.SLOT_H)
    private val lanes = WeekMetrics.LANES
    private val cellPad = dp(2f)

    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.line); strokeWidth = 1f }
    private val paintTodayBg = Paint().apply { color = ContextCompat.getColor(context, R.color.today_bg) }
    private val paintSelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.brand) }
    private val paintCardBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintAccent = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textSub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.ink_2) }
    private val textHeadToday = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.brand_dark) }
    private val textHead = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.ink) }
    private val textHeadSub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.ink_3) }

    private val cardRect = RectF()

    fun configure(
        schedule: Schedule?,
        weekStart: LocalDate,
        week: Int,
        today: LocalDate,
        selectedDate: LocalDate?,
        showWeekend: Boolean,
        weekendHasCourses: Boolean
    ) {
        this.schedule = schedule
        this.selectedDate = selectedDate
        val entries = schedule?.entries ?: emptyList()
        val activeByDay = HashMap<Int, MutableList<CourseEntry>>()
        for (e in entries) {
            if (!e.activeOnWeek(week)) continue
            activeByDay.getOrPut(e.day) { mutableListOf() }.add(e)
        }
        for ((_, v) in activeByDay) v.sortBy { it.startSlot }
        val days = ArrayList<DayCol>()
        for (d in 0 until 7) {
            val has = activeByDay.containsKey(d)
            if (has || showWeekend || d < 5) {
                days.add(DayCol(d, weekStart.plusDays(d.toLong()), weekdayName(d), today == weekStart.plusDays(d.toLong())))
            }
        }
        cols = days
        entriesByDay = activeByDay
        colorIndexMap = CourseStyle.indexMap(schedule)
        requestLayout()
        invalidate()
    }

    private fun weekdayName(idx: Int): String {
        val s = schedule
        if (s != null && idx < s.weekdayNames.size) return s.weekdayNames[idx]
        return listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")[idx]
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(widthMeasureSpec)
        } else {
            suggestedMinimumWidth
        }
        val h = (headerH + slotH * lanes).toInt()
        setMeasuredDimension(w, h)
    }

    private fun colWidth(): Float {
        val n = maxOf(cols.size, 1)
        return (width - cellPad * 2) / n
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE) // 不透明底，避免下层透出
        if (cols.isEmpty()) return
        val w = width.toFloat()
        val colW = colWidth()
        val bodyH = headerH + slotH * lanes

        // 今天列浅色底
        for ((i, c) in cols.withIndex()) {
            if (c.isToday) {
                canvas.drawRect(i * colW + cellPad, 0f, i * colW + colW + cellPad, bodyH, paintTodayBg)
            }
        }
        // 水平分隔线（小节之间）
        for (lane in 0..lanes) {
            val y = headerH + lane * slotH
            canvas.drawLine(0f, y, w, y, paintLine)
        }
        // 垂直分隔线（天列之间）
        for (i in 0..cols.size) {
            val x = cellPad + i * colW
            canvas.drawLine(x, 0f, x, bodyH, paintLine)
        }

        // 表头：星期 + 日期
        textHead.textSize = dp(13f)
        textHeadSub.textSize = dp(11f)
        for ((i, c) in cols.withIndex()) {
            val cx = cellPad + i * colW + colW / 2f
            val dateStr = c.date.format(DateTimeFormatter.ofPattern("M/d"))
            val isSel = c.date == selectedDate
            val headPaint = if (c.isToday) textHeadToday else textHead
            headPaint.textSize = dp(13f)
            headPaint.isFakeBoldText = true
            headPaint.textAlign = Paint.Align.CENTER
            val yName = headerH / 2f - dp(2f)
            val yDate = headerH / 2f + dp(13f)
            if (isSel) {
                val cy = headerH / 2f
                paintSelBg.color = ContextCompat.getColor(context, R.color.brand)
                canvas.drawRoundRect(cx - dp(22f), cy - dp(18f), cx + dp(22f), cy + dp(20f), dp(10f), dp(10f), paintSelBg)
                headPaint.color = Color.WHITE
                canvas.drawText(c.name, cx, yName + dp(3f), headPaint)
                textHeadSub.color = Color.WHITE
                textHeadSub.textAlign = Paint.Align.CENTER
                canvas.drawText(dateStr, cx, yDate + dp(3f), textHeadSub)
            } else {
                canvas.drawText(c.name, cx, yName, headPaint)
                textHeadSub.color = ContextCompat.getColor(context, R.color.ink_3)
                textHeadSub.textAlign = Paint.Align.CENTER
                canvas.drawText(dateStr, cx, yDate, textHeadSub)
            }
        }

        // 课程卡片
        for ((i, c) in cols.withIndex()) {
            val list = entriesByDay[c.dayIdx] ?: continue
            for ((entry, track, tracks) in layoutTracks(list)) {
                if (entry.startSlot > lanes) continue // 第11/12节不展示
                val x0 = cellPad + i * colW + track * (colW / tracks) + cellPad / 2f
                val x1 = cellPad + i * colW + (track + 1) * (colW / tracks) - cellPad / 2f
                val y0 = headerH + (entry.startSlot - 1) * slotH + cellPad / 2f
                val y1 = headerH + entry.endSlot() * slotH - cellPad / 2f
                drawCourseCard(canvas, entry, x0, y0, x1, y1)
            }
        }
    }

    /** 一列内重叠课程的轨道分配：贪心。返回 (entry, track, trackCount)。 */
    private fun layoutTracks(list: List<CourseEntry>): List<Triple<CourseEntry, Int, Int>> {
        val tracks = ArrayList<MutableList<CourseEntry>>()
        val assign = HashMap<CourseEntry, Int>()
        for (e in list) {
            var placed = -1
            for ((ti, t) in tracks.withIndex()) {
                val last = t.maxByOrNull { it.endSlot() } ?: continue
                if (last.endSlot() < e.startSlot) { placed = ti; break }
            }
            if (placed < 0) {
                placed = tracks.size
                tracks.add(mutableListOf())
            }
            tracks[placed].add(e)
            assign[e] = placed
        }
        return list.map { Triple(it, assign[it] ?: 0, tracks.size) }
    }

    private fun drawCourseCard(canvas: Canvas, e: CourseEntry, x0: Float, y0: Float, x1: Float, y1: Float) {
        if (y1 <= y0 || x1 <= x0) return
        val key = Schedule.courseKey(e.name, e.teacher)
        val main = CourseStyle.mainOf(colorIndexMap, key)
        val bg = CourseStyle.bgOf(colorIndexMap, key)
        val r = dp(6f)
        cardRect.set(x0, y0, x1, y1)
        paintCardBg.color = bg
        canvas.drawRoundRect(cardRect, r, r, paintCardBg)
        paintAccent.color = main
        canvas.drawRoundRect(RectF(x0, y0, x0 + dp(3f), y1), dp(1.5f), dp(1.5f), paintAccent)

        val h = y1 - y0
        val innerLeft = x0 + dp(5f)
        val w = (x1 - innerLeft - dp(2f)).coerceAtLeast(dp(8f))
        val name = courseDisplayName(e).ifEmpty { (e.raw.lines().firstOrNull() ?: "").trim() }
        val teacherLine = e.teacher.trim()
        val roomLine = e.room.trim()
        val subCount = (if (teacherLine.isNotEmpty()) 1 else 0) + (if (roomLine.isNotEmpty()) 1 else 0)

        if (h < dp(30f) || subCount == 0) {
            // 信息很少/极矮卡：退化为单行名称（省略号兜底）
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.ink)
                textSize = dp(10f)
                textAlign = Paint.Align.LEFT
            }
            val ell = TextUtils.ellipsize(name, tp, w, TextUtils.TruncateAt.END)
            canvas.drawText(ell.toString(), innerLeft, y0 + dp(14f), tp)
            return
        }

        fun newPaint(size: Float, color: Int, bold: Boolean): TextPaint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = size
                isFakeBoldText = bold
                textAlign = Paint.Align.LEFT
            }

        fun wrapLines(p: TextPaint, text: String, maxW: Float): List<String> {
            val out = ArrayList<String>()
            var start = 0
            while (start < text.length) {
                val n = p.breakText(text, start, text.length, true, maxW, null)
                if (n <= 0) break
                out.add(text.substring(start, start + n))
                start += n
            }
            if (out.isEmpty()) out.add(text)
            return out
        }

        val subPaint = newPaint(dp(9f), ContextCompat.getColor(context, R.color.ink_2), false)
        val subSpacing = subPaint.fontSpacing
        val reserveSub = if (subCount == 0) 0f
        else subCount * subSpacing + (subCount - 1) * dp(1f) + dp(3f)
        val padTop = dp(4f)
        val availNameH = (h - padTop - dp(3f) - reserveSub).coerceAtLeast(dp(6f))

        var nameSize = 12f
        var namePaint = newPaint(dp(nameSize), ContextCompat.getColor(context, R.color.ink), true)
        var lines = wrapLines(namePaint, name, w)
        while (lines.size * namePaint.fontSpacing > availNameH && nameSize > 8f) {
            nameSize -= 0.5f
            namePaint = newPaint(dp(nameSize), ContextCompat.getColor(context, R.color.ink), true)
            lines = wrapLines(namePaint, name, w)
        }
        val maxLines = (availNameH / namePaint.fontSpacing).toInt().coerceAtLeast(1)
        if (lines.size > maxLines) {
            val keep = ArrayList(lines.subList(0, maxLines))
            val last = keep.size - 1
            keep[last] = TextUtils.ellipsize(keep[last], namePaint, w, TextUtils.TruncateAt.END).toString()
            lines = keep
        }

        var y = y0 + padTop + namePaint.fontSpacing * 0.92f
        for (ln in lines) {
            if (y > y1 - dp(1f)) break
            canvas.drawText(ln, innerLeft, y, namePaint)
            y += namePaint.fontSpacing
        }
        y += dp(1f)
        for (sub in listOf(teacherLine, roomLine)) {
            if (sub.isEmpty() || y > y1 - dp(2f)) continue
            val ell = TextUtils.ellipsize(sub, subPaint, w, TextUtils.TruncateAt.END)
            canvas.drawText(ell.toString(), innerLeft, y, subPaint)
            y += subSpacing
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val x = event.x
            val y = event.y
            if (y > 0) {
                val colW = colWidth()
                val colIdx = ((x - cellPad) / colW).toInt()
                if (colIdx in cols.indices && x in cellPad..(cellPad + cols.size * colW)) {
                    val c = cols[colIdx]
                    val list = entriesByDay[c.dayIdx] ?: emptyList()
                    var hit: CourseEntry? = null
                    if (y > headerH) {
                        for ((entry, track, tracks) in layoutTracks(list)) {
                            if (entry.startSlot > lanes) continue
                            val x0 = cellPad + colIdx * colW + track * (colW / tracks) + cellPad / 2f
                            val x1 = cellPad + colIdx * colW + (track + 1) * (colW / tracks) - cellPad / 2f
                            val y0 = headerH + (entry.startSlot - 1) * slotH + cellPad / 2f
                            val y1 = headerH + entry.endSlot() * slotH - cellPad / 2f
                            if (x in x0..x1 && y in y0..y1) { hit = entry; break }
                        }
                    }
                    if (hit != null) onCourseClick?.invoke(c.date, hit)
                    else onDayClick?.invoke(c.date)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
