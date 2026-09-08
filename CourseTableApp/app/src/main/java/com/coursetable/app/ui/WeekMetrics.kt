package com.coursetable.app.ui

/**
 * 周课表共享排版度量（dp 值）：周课表已取消左侧“节次·时间”栏，仅保留星期/日期表头与天列网格。
 */
object WeekMetrics {
    /** 表头高度（dp）：星期/日期行 */
    const val HEADER_H = 56f

    /** 单个“小节”行高（dp） */
    const val SLOT_H = 58f

    /** 显示的小节数：第1-10节（第11/12节无排课，不显示） */
    const val LANES = 10
}
