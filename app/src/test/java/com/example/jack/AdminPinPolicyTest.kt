package com.example.jack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminPinPolicyTest {

    @Test
    fun defaultPinWorksUntilAPinIsSet() {
        assertTrue(AdminPinPolicy.matches("1234", null, null))
        assertFalse(AdminPinPolicy.matches("0000", null, null))
    }

    @Test
    fun customPinReplacesDefault() {
        val salt = "abc123"
        val stored = AdminPinPolicy.hash("4821", salt)
        assertTrue(AdminPinPolicy.matches("4821", stored, salt))
        assertFalse(AdminPinPolicy.matches("1234", stored, salt))
    }

    @Test
    fun hashDoesNotContainThePinAndDependsOnSalt() {
        val h1 = AdminPinPolicy.hash("4821", "s1")
        val h2 = AdminPinPolicy.hash("4821", "s2")
        assertFalse(h1.contains("4821"))
        assertFalse(h1 == h2)
        assertEquals(64, h1.length)
    }

    @Test
    fun newPinValidation() {
        assertNull(AdminPinPolicy.validateNewPin("4821", "4821"))
        assertNull(AdminPinPolicy.validateNewPin("123456", "123456"))
        assertEquals("PIN must be 4–6 digits.", AdminPinPolicy.validateNewPin("123", "123"))
        assertEquals("PIN must be 4–6 digits.", AdminPinPolicy.validateNewPin("1234567", "1234567"))
        assertEquals("PIN must contain digits only.", AdminPinPolicy.validateNewPin("12a4", "12a4"))
        assertEquals("The two new PINs don't match.", AdminPinPolicy.validateNewPin("4821", "4822"))
    }

    @Test
    fun lockoutAfterFiveFailuresForThirtySeconds() {
        assertEquals(0L, AdminPinPolicy.lockoutRemaining(4, 1_000, 1_500))
        assertEquals(29_500L, AdminPinPolicy.lockoutRemaining(5, 1_000, 1_500))
        assertEquals(0L, AdminPinPolicy.lockoutRemaining(5, 1_000, 31_000))
    }

    @Test
    fun idleExpiryAfterTwoMinutes() {
        assertFalse(AdminPinPolicy.isIdleExpired(0, 119_999))
        assertTrue(AdminPinPolicy.isIdleExpired(0, 120_000))
    }
}
