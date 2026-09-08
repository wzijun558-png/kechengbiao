package com.coursetable.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TermCalendarTest {

    // 用户确认：学期第1周周一 = 2026-08-31，共18周
    private val cal = TermCalendar(LocalDate.of(2026, 8, 31), 18)

    @Test
    fun `week one monday`() {
        assertEquals(1, cal.weekOf(LocalDate.of(2026, 8, 31)))
        assertEquals(1, cal.weekOf(LocalDate.of(2026, 9, 6)))
    }

    @Test
    fun `boundary weeks`() {
        assertEquals(2, cal.weekOf(LocalDate.of(2026, 9, 7)))
        assertEquals(18, cal.weekOf(LocalDate.of(2027, 1, 3)))
        assertNull(cal.weekOf(LocalDate.of(2027, 1, 4)))
        assertNull(cal.weekOf(LocalDate.of(2026, 8, 30)))
    }

    @Test
    fun `dateOf round trip`() {
        assertEquals(LocalDate.of(2026, 9, 7), cal.dateOf(2, 0))
        assertEquals(LocalDate.of(2026, 9, 12), cal.dateOf(2, 5)) // 2026-09-12 周六
    }

    @Test
    fun `day index of weekday`() {
        assertEquals(0, TermCalendar.indexOfDay(java.time.DayOfWeek.MONDAY))
        assertEquals(6, TermCalendar.indexOfDay(java.time.DayOfWeek.SUNDAY))
    }

    @Test
    fun `time text`() {
        assertEquals("第1-2节" to "8:20-10:00", TimeText.slotLabelAndRange(1, 2))
        assertEquals("第3-4节" to "10:20-12:00", TimeText.slotLabelAndRange(3, 2))
        assertEquals("第5-6节" to "13:20-15:00", TimeText.slotLabelAndRange(5, 2))
        assertEquals("第7-8节" to "15:20-17:00", TimeText.slotLabelAndRange(7, 2))
        assertEquals("第9-10节" to "18:00-19:30", TimeText.slotLabelAndRange(9, 2))
        assertEquals("第4节" to "10:20-12:00", TimeText.slotLabelAndRange(4, 1))
        assertEquals("第7-9节" to "15:20-19:30", TimeText.slotLabelAndRange(7, 3))
    }
}
