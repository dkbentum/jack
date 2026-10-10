package com.example.jack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserIdRulesTest {

    @Test
    fun takenIgnoresCaseAndSpaces() {
        val existing = listOf("007", "AB12")
        assertTrue(UserIdRules.isTaken("ab12", existing))
        assertTrue(UserIdRules.isTaken(" 007 ", existing))
        assertFalse(UserIdRules.isTaken("008", existing))
    }

    @Test
    fun findsDuplicatesOnly() {
        assertEquals(setOf("007", "ab12"), UserIdRules.findDuplicates(listOf("007", "AB12", "42", "007 ", "ab12")))
        assertEquals(emptySet<String>(), UserIdRules.findDuplicates(listOf("1", "2", "3")))
    }
}
