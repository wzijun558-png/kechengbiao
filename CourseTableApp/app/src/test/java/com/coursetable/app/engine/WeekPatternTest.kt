package com.coursetable.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekPatternTest {

    @Test
    fun `parse continuous with gap`() {
        val r = WeekPattern.parse("【1-9,11-16,18周】")
        assertTrue(r.ok)
        val expect = (1..9).toSet() + (11..16).toSet() + setOf(18)
        assertEquals(expect, r.weeks)
        assertFalse(r.weeks.contains(10))
        assertFalse(r.weeks.contains(17))
    }

    @Test
    fun `parse double weeks`() {
        val r = WeekPattern.parse("【2-8(双),12-16(双)周】")
        assertTrue(r.ok)
        assertEquals(setOf(2, 4, 6, 8, 12, 14, 16), r.weeks)
    }

    @Test
    fun `parse single weeks`() {
        val r = WeekPattern.parse("【1-15(单)周】")
        assertTrue(r.ok)
        assertEquals(setOf(1, 3, 5, 7, 9, 11, 13, 15), r.weeks)
    }

    @Test
    fun `parse sparse and single`() {
        assertEquals(setOf(4, 9, 13), WeekPattern.parse("【4,9,13周】").weeks)
        assertEquals(setOf(17), WeekPattern.parse("【17周】").weeks)
    }

    @Test
    fun `unparsable token flagged`() {
        val r = WeekPattern.parse("【1-9,疑难,13周】")
        assertFalse(r.ok)
        assertTrue(r.weeks.contains(1))
        assertTrue(r.weeks.contains(9))
        assertTrue(r.weeks.contains(13))
    }
}
