package com.example.jack

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertMatcherTest {

    private fun rule(name: String, trigger: String) = AlertRule(name = name, triggerText = trigger, toneUri = "tone://$name")

    @Test
    fun matchIgnoresCaseAndPosition() {
        val motion = rule("Motion", "motion detected")
        assertEquals(listOf(motion), AlertMatcher.matching("Zone 2: MOTION DETECTED! Pixels: 40", listOf(motion)))
    }

    @Test
    fun noMatchReturnsEmpty() {
        assertEquals(emptyList<AlertRule>(), AlertMatcher.matching("Temp: 25", listOf(rule("Motion", "motion detected"))))
    }

    @Test
    fun allMatchingRulesAreReturnedInOrder() {
        val motion = rule("Motion", "motion")
        val zone = rule("Zone 2", "zone 2")
        val door = rule("Door", "door open")
        assertEquals(listOf(motion, zone), AlertMatcher.matching("ZONE 2 MOTION", listOf(motion, door, zone)))
    }

    @Test
    fun surroundingSpacesInTriggerAreIgnored() {
        val r = rule("Motion", " motion detected ")
        assertEquals(listOf(r), AlertMatcher.matching("MOTION DETECTED!", listOf(r)))
    }

    @Test
    fun blankTriggerNeverMatches() {
        assertEquals(emptyList<AlertRule>(), AlertMatcher.matching("anything", listOf(rule("Empty", "   "))))
    }

    @Test
    fun noBuiltInRules() {
        // With no user rules, nothing fires — not even "motion".
        assertEquals(emptyList<AlertRule>(), AlertMatcher.matching("MOTION DETECTED!", emptyList()))
    }
}
