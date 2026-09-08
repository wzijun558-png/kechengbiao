package com.coursetable.app.parser

/** 与具体格式无关的文本工作表。行/列均从 0 开始。 */
class SheetGrid(val name: String) {
    class CellRef(val r: Int, val c: Int) {
        override fun hashCode(): Int = r * 31 + c
        override fun equals(other: Any?): Boolean =
            other is CellRef && other.r == r && other.c == c
    }

    val cells = HashMap<CellRef, String>()
    /** 合并区域: (r1, r2, c1, c2) 四元组数组。 */
    val merged = ArrayList<IntArray>()
    var maxR = 0
    var maxC = 0

    fun put(r: Int, c: Int, text: String) {
        val t = text.trimEnd('\r', '\n', ' ', '\t')
        if (t.isEmpty()) return
        cells[CellRef(r, c)] = t
        if (r > maxR) maxR = r
        if (c > maxC) maxC = c
    }

    fun text(r: Int, c: Int): String = cells[CellRef(r, c)] ?: ""
}

class WorkbookGrid(val sheets: List<SheetGrid>)
