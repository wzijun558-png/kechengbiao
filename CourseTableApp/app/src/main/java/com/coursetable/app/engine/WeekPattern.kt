package com.coursetable.app.engine

/** 周次表达式解析：把 "1-9,11-16,18周" / "2-8(双),12-16(双)周" / "1-15(单)周" / "17周" 等转为周次集合。 */
object WeekPattern {

    data class Result(val weeks: Set<Int>, val ok: Boolean)

    /** 输入含 【】 或结尾 周 皆可；如 "【1-9,11-16,18周】"。 */
    fun parse(bracket: String): Result {
        var t = bracket.trim()
        if (t.startsWith("【") && t.endsWith("】")) t = t.substring(1, t.length - 1)
        t = t.removeSuffix("周").removeSuffix("周").trim()
        if (t.isEmpty()) return Result(emptySet(), false)
        val weeks = mutableSetOf<Int>()
        var ok = true
        for (rawTok in t.split(Regex("[,，;；、\\s]+"))) {
            val tok = rawTok.trim().removePrefix("第")
            if (tok.isEmpty()) continue
            val m = Regex("^(\\d+)\\s*[-~至到]\\s*(\\d+)\\s*(\\(?单\\)?|\\(?双\\)?)?$").find(tok)
            if (m != null) {
                val a = m.groupValues[1].toInt()
                val b = m.groupValues[2].toInt()
                val oddEven = m.groupValues[3]
                val lo = minOf(a, b)
                val hi = maxOf(a, b)
                when {
                    oddEven.contains("单") -> for (w in lo..hi) if (w % 2 == 1) weeks.add(w)
                    oddEven.contains("双") -> for (w in lo..hi) if (w % 2 == 0) weeks.add(w)
                    else -> for (w in lo..hi) weeks.add(w)
                }
            } else {
                val single = Regex("^(\\d+)$").find(tok)
                if (single != null) {
                    weeks.add(single.groupValues[1].toInt())
                } else {
                    ok = false
                }
            }
        }
        return Result(weeks, ok)
    }
}
