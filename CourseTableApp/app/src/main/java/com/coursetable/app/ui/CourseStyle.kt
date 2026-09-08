package com.coursetable.app.ui

import android.graphics.Color
import com.coursetable.app.model.Schedule

/**
 * 课程配色：为课表内每一门不同课程（名称+教师）分配互不重复、肉眼易区分的颜色。
 * 方案：把全部课程键排序后按「黄金角 137.508°」逐个推进色相 —— 课程越多仍能拉开色距，
 * 同一门课永远同一色；跨文件稳定一致。深色主色用于色块/圆点/描边，浅色同色相底用于卡片。
 */
object CourseStyle {

    /** 依据课表生成稳定映射：课程键 -> 色相索引（按课程名排序，跨设备/跨次打开一致）。 */
    fun indexMap(schedule: Schedule?): Map<String, Int> {
        val s = schedule ?: return emptyMap()
        val keys = s.groupByCourse().map { it.first }.sorted()
        val map = HashMap<String, Int>(keys.size * 2)
        for ((i, k) in keys.withIndex()) map[k] = i
        return map
    }

    /** 找不到映射时的兜底（正常不应触发）。 */
    fun fallbackIndex(key: String): Int = Math.floorMod(key.hashCode(), 360)

    fun indexOf(map: Map<String, Int>, key: String): Int = map[key] ?: fallbackIndex(key)

    /** 便捷：主色。 */
    fun mainOf(map: Map<String, Int>, key: String): Int = mainColorOf(indexOf(map, key))

    /** 便捷：卡片浅底。 */
    fun bgOf(map: Map<String, Int>, key: String): Int = bgColorOf(indexOf(map, key))

    /** 主色：色相按索引推进，饱和度/明度取中高，醒目但不刺眼。 */
    fun mainColorOf(index: Int): Int {
        val hue = ((index * 137.508f) % 360f + 360f) % 360f
        return Color.HSVToColor(floatArrayOf(hue, 0.60f, 0.82f))
    }

    /** 卡片浅底：同色相的柔和底色（衬深色文字，整体保持简洁）。 */
    fun bgColorOf(index: Int): Int {
        val hue = ((index * 137.508f) % 360f + 360f) % 360f
        return Color.HSVToColor(floatArrayOf(hue, 0.22f, 0.99f))
    }
}
