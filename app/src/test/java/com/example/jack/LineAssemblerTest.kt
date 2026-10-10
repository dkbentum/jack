package com.example.jack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineAssemblerTest {

    @Test
    fun singleCompleteLine() {
        val a = LineAssembler()
        assertEquals(listOf("MOTION DETECTED!"), a.append("MOTION DETECTED!\n"))
        assertFalse(a.hasPending)
    }

    @Test
    fun lineSplitAcrossChunks_isReturnedOnceWhenComplete() {
        val a = LineAssembler()
        assertEquals(emptyList<String>(), a.append("MOTION DET"))
        assertTrue(a.hasPending)
        assertEquals(listOf("MOTION DETECTED! Pixels: 42"), a.append("ECTED! Pixels: 42\n"))
        assertFalse(a.hasPending)
    }

    @Test
    fun motionLineAfterEarlierLines_isNotDuplicated() {
        // The old parser re-logged the whole buffer here, so "Temp: 25" and the motion line appeared twice.
        val a = LineAssembler()
        val out = a.append("Temp: 25\nMOTION DETECTED!\n") + a.append("No motion\n")
        assertEquals(listOf("Temp: 25", "MOTION DETECTED!", "No motion"), out)
    }

    @Test
    fun crlfAndLfCrLineEndings() {
        val a = LineAssembler()
        assertEquals(listOf("A", "B", "C"), a.append("A\r\nB\n\rC\r\n"))
        assertFalse(a.hasPending)
    }

    @Test
    fun crlfSplitBetweenChunks() {
        val a = LineAssembler()
        assertEquals(listOf("A"), a.append("A\r"))
        assertEquals(listOf("B"), a.append("\nB\n"))
    }

    @Test
    fun blankLinesAndWhitespaceAreDropped() {
        val a = LineAssembler()
        assertEquals(listOf("Pixels: 7"), a.append("\n\n   \n  Pixels: 7  \n"))
    }

    @Test
    fun flushReturnsPartialLineOnce() {
        val a = LineAssembler()
        a.append("MOTION DETECTED!")          // ESP32 sent no newline
        assertEquals("MOTION DETECTED!", a.flush())
        assertNull(a.flush())
        assertFalse(a.hasPending)
    }

    @Test
    fun oversizedBufferWithoutNewlineIsFlushed() {
        val a = LineAssembler(maxPendingChars = 10)
        assertEquals(listOf("ABCDEFGHIJKL"), a.append("ABCDEFGHIJKL"))
        assertFalse(a.hasPending)
    }
}
