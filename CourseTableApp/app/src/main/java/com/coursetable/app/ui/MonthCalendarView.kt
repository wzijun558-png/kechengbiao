package com.coursetable.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.coursetable.app.R
import com.coursetable.app.engine.TermCalendar
import com.coursetable.app.model.Schedule
import java.time.LocalDate
import java.time.YearMonth

/**
 * 月历视图（自绘，周一起始）。每月页面内：在学期内的日期高亮 + 底部课程彩色圆点计数。
 */
class MonthCalendarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onDateClick: ((LocalDate) -> Unit)? = null

    private var month: YearMonth = YearMonth.now()
    private var schedule: Schedule? = null
    private var colorIndexMap: Map<String, Int> = emptyMap()
    private var today: LocalDate = LocalDate.now()
    private var selected: LocalDate? = null
    private var inTerm: ((LocalDate) -> Boolean)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val headH = dp(34f)
    private val rowH = dp(58f)

    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.ink) }
    private val paintSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.ink_3) }
    private val paintTodayBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.today_bg) }
    private val paintSel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        color = ContextCompat.getColor(context, R.color.brand)
    }
    private val paintOut = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.ink_3) }
    private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG)

    fun configure(
        month: YearMonth,
        schedule: Schedule?,
        today: LocalDate,
        selected: LocalDate?,
        inTerm: ((LocalDate) -> Boolean)?
    ) {
        this.month = month
        this.schedule = schedule
        this.colorIndexMap = CourseStyle.indexMap(schedule)
        this.today = today
        this.selected = selected
        this.inTerm = inTerm
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).takeIf { it > 0 } ?: suggestedMinimumWidth
        val h = (headH + rowH * 6).toInt()
        setMeasuredDimension(w, h)
    }

    private val weekdayShort = listOf("一", "二", "三", "四", "五", "六", "日")

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        val w = width.toFloat()
        val cellW = w / 7f
        val first = month.atDay(1)
        val startOffset = TermCalendar.indexOfDay(first.dayOfWeek)
        val daysInMonth = month.lengthOfMonth()

        // 星期表头
        paintText.textSize = dp(12f)
        paintText.textAlign = Paint.Align.CENTER
        paintText.isFakeBoldText = false
        for (i in 0 until 7) {
            val cx = i * cellW + cellW / 2f
            val col = if (i == 5 || i == 6) ContextCompat.getColor(context, R.color.ink_3) else ContextCompat.getColor(context, R.color.ink_2)
            paintText.color = col
            canvas.drawText(weekdayShort[i], cx, dp(22f), paintText)
        }
        // 底部线
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.line)
            canvas.drawRect(0f, headH - dp(1f), w, headH, this)
        }

        // 每天
        val sched = schedule
        val entries = sched?.entries ?: emptyList()
        for (d in 1..daysInMonth) {
            val date = month.atDay(d)
            val cellIndex = startOffset + d - 1
            val row = cellIndex / 7
            val col = cellIndex % 7
            val x0 = col * cellW
            val y0 = headH + row * rowH
            val cx = x0 + cellW / 2f
            val cy = y0 + dp(20f)

            val isTerm = inTerm?.invoke(date) ?: false
            val keys = if (isTerm && entries.isNotEmpty() && sched != null) {
                val cal = TermCalendar(sched.week1Monday, sched.weekCount)
                val week = cal.weekOf(date)
                if (week != null) {
                    entries.filter { it.activeOnWeek(week) && it.day == TermCalendar.indexOfDay(date.dayOfWeek) }
                        .map { com.coursetable.app.model.Schedule.courseKey(it.name, it.teacher) }
                        .distinct()
                } else emptyList()
            } else emptyList()

            val isToday = date == today
            val isSel = date == selected

            if (isToday) {
                paintTodayBg.color = ContextCompat.getColor(context, R.color.brand_light)
                canvas.drawCircle(cx, y0 + dp(14f), dp(13f), paintTodayBg)
            }
            // 非学期内日期弱化
            if (!isTerm) paintOut.color = ContextCompat.getColor(context, R.color.ink_3) else paintOut.color = ContextCompat.getColor(context, R.color.ink)

            paintOut.textSize = dp(15f)
            paintOut.textAlign = Paint.Align.CENTER
            paintOut.isFakeBoldText = isToday
            if (isToday) paintOut.color = ContextCompat.getColor(context, R.color.brand_dark)
            canvas.drawText(d.toString(), cx, y0 + dp(26f), paintOut)

            if (isTerm) {
                val dotsMax = 4
                val n = minOf(keys.size, dotsMax)
                if (keys.isNotEmpty()) {
                    val dotR = dp(2.6f)
                    val spacing = dp(10f)
                    val totalW = (n - 1) * spacing
                    var dx = cx - totalW / 2f
                    for (k in 0 until n) {
                        paintDot.color = CourseStyle.mainOf(colorIndexMap, keys[k])
                        canvas.drawCircle(dx, y0 + dp(40f), dotR, paintDot)
                        dx += spacing
                    }
                    if (keys.size > dotsMax) {
                        paintSub.textSize = dp(9f)
                        paintSub.textAlign = Paint.Align.LEFT
                        canvas.drawText("+${keys.size - dotsMax}", dx - spacing / 2f + dp(2f), y0 + dp(44f), paintSub)
                    }
                }
                // 周次小字
                if (sched != null) {
                    val cal = TermCalendar(sched.week1Monday, sched.weekCount)
                    val week = cal.weekOf(date)
                    if (week != null) {
                        paintSub.textSize = dp(8f)
                        paintSub.textAlign = Paint.Align.RIGHT
                        paintSub.color = ContextCompat.getColor(context, R.color.ink_3)
                        canvas.drawText("W$week", x0 + cellW - dp(3f), y0 + dp(9f), paintSub)
                    }
                }
            }

            if (isSel) {
                paintSel.color = ContextCompat.getColor(context, R.color.brand)
                canvas.drawCircle(cx, y0 + dp(14f), dp(13f), paintSel)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val cellW = width / 7f
            val col = (event.x / cellW).toInt()
            val row = ((event.y - headH) / rowH).toInt()
            if (col in 0..6 && row in 0..5) {
                val cellIndex = row * 7 + col
                val startOffset = TermCalendar.indexOfDay(month.atDay(1).dayOfWeek)
                val day = cellIndex - startOffset + 1
                if (day in 1..month.lengthOfMonth()) {
                    onDateClick?.invoke(month.atDay(day))
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
