package com.coursetable.app.parser

import com.coursetable.app.model.ScheduleCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 用真实样例(.xls 原生 BIFF8 / .xlsx / canonical JSON)验证解析链路。 */
class WorkbookParserTest {

    private val termStart = LocalDate.of(2026, 8, 31)

    private fun res(name: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).use { it.readBytes() }

    private fun parseXls(): com.coursetable.app.model.Schedule {
        val wb = XlsReader.read(res("sample_original.xls"), "sample_original.xls")
        assertEquals("skb", wb.sheets.first().name)
        // 关键格子抽查（真实几何：首行为空，标题从第 2 行起）
        val g = wb.sheets.first()
        assertTrue(g.text(1, 0).contains("课表"))
        assertTrue(g.text(2, 1).contains("王梓俊"))
        assertTrue(g.text(3, 0) == "节次")
        assertTrue(g.text(3, 1) == "星期一")
        assertEquals("1", g.text(4, 0))
        assertEquals("12", g.text(15, 0))
        assertTrue(g.text(4, 1).contains("劳动技能"))
        val sp = GridScheduleParser.parseWorkbook(wb, termStart, 18, null)
        return sp.schedule ?: error("parse failed: ${sp.note}")
    }

    @Test
    fun `xls real file produces canonical schedule`() {
        val s = parseXls()
        assertEquals(18, s.weekCount)
        assertEquals(21, s.entries.size)
        assertTrue(s.entries.all { it.startSlot >= 1 })
        assertEquals(10, s.groupByCourse().size)
    }

    @Test
    fun `course distribution matches manual verification`() {
        val s = parseXls()
        // 周一(0) 第1周：证券投资学 3-4、心理健康 5-6，且无劳动技能
        val mon = s.entriesOn(1, 0)
        assertEquals(2, mon.size)
        assertEquals("证券投资学(理论)", mon[0].name)
        assertEquals(3, mon[0].startSlot)
        assertEquals("大学生心理健康教育(理论)", mon[1].name)
        // 第17周 周一 只有劳动技能且跨 1-6 节
        val w17mon = s.entriesOn(17, 0)
        assertEquals(1, w17mon.size)
        assertEquals(1, w17mon[0].startSlot)
        assertEquals(6, w17mon[0].slotSpan)
        assertEquals(setOf(17), w17mon[0].weekSet)
        // 劳动技能周三(2) 只占 1-4 节
        val wed17 = s.entriesOn(17, 2).firstOrNull { it.name.startsWith("劳动技能") }
        assertNotNull(wed17)
        assertEquals(1, wed17!!.startSlot)
        assertEquals(4, wed17.slotSpan)
        // 周二(1) 第16周的单小节异常格
        val tue16 = s.entriesOn(16, 1).firstOrNull { it.name.startsWith("证券") }
        assertNotNull(tue16)
        assertEquals(4, tue16!!.startSlot)
        assertEquals(1, tue16.slotSpan)
        assertEquals("三教119", tue16.room)
        // 周三(2) 第16周 国际金融 7-9 节
        val gj16 = s.entriesOn(16, 2).firstOrNull { it.name.startsWith("国际金融") && it.weeksText == "【16周】" }
        assertNotNull(gj16)
        assertEquals(7, gj16!!.startSlot)
        assertEquals(3, gj16.slotSpan)
        // 周五(4) 单双周交替
        val fri1 = s.entriesOn(1, 4)
        assertTrue(fri1.any { it.name.startsWith("线性代数B2") })
        assertTrue(fri1.none { it.name.startsWith("毛泽东") })
        val fri2 = s.entriesOn(2, 4)
        assertTrue(fri2.any { it.name.startsWith("毛泽东") })
        assertTrue(fri2.none { it.name.startsWith("线性代数B2") })
    }

    @Test
    fun `xlsx twin parses identically`() {
        val wb = XlsxReader.read(res("sample_original.xlsx"), "sample_original.xlsx")
        val sp = GridScheduleParser.parseWorkbook(wb, termStart, 18, null)
        val s = sp.schedule ?: error("xlsx parse failed: ${sp.note}")
        assertEquals(21, s.entries.size)
        assertEquals(18, s.weekCount)
        // 与 xls 结果逐条一致性（排序后对比 key 序列）
        val a = parseXls()
        fun keyList(sch: com.coursetable.app.model.Schedule) = sch.entries.sortedWith(
            compareBy({ it.day }, { it.startSlot }, { it.endSlot() }, { it.name }, { it.teacher })
        ).map { "${it.day}|${it.startSlot}|${it.endSlot()}|${it.name}|${it.teacher}|${it.weekSet}" }
        assertEquals(keyList(a), keyList(s))
    }

    @Test
    fun `codec round trip`() {
        val s = parseXls()
        val json = ScheduleCodec.toJson(s)
        val back = ScheduleCodec.fromJson(json)
        assertEquals(s.entries.size, back.entries.size)
        assertEquals(s.week1Monday, back.week1Monday)
        assertEquals(s.termLabel, back.termLabel)
    }

    @Test
    fun `import engine rejects non xls xlsx`() {
        val thrown = try {
            ImportEngine.parse("{bad json}".toByteArray(), "x.json", termStart)
            null
        } catch (e: Exception) {
            e
        }
        assertTrue(thrown is ImportEngine.ImportException)
    }
}
