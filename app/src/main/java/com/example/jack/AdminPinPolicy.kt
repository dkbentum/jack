package com.example.jack

import java.security.MessageDigest

/** Pure Kotlin rules for the admin PIN (no Android types, so they can be unit-tested on the JVM). */
object AdminPinPolicy {
    const val DEFAULT_PIN = "1234"
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 6
    const val MAX_FAILED_ATTEMPTS = 5
    const val LOCKOUT_MS = 30_000L
    const val IDLE_TIMEOUT_MS = 2 * 60 * 1000L

    /** SHA-256 of salt + PIN, hex encoded. The PIN itself is never stored. */
    fun hash(pin: String, salt: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((salt + pin).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Checks a PIN against the stored hash, or against the default PIN if none has been set yet. */
    fun matches(pin: String, storedHash: String?, salt: String?): Boolean =
        if (storedHash.isNullOrEmpty() || salt.isNullOrEmpty()) pin == DEFAULT_PIN
        else hash(pin, salt) == storedHash

    /** Returns an error message for an invalid new PIN, or null if it's acceptable. */
    fun validateNewPin(newPin: String, confirmPin: String): String? = when {
        newPin.length < MIN_LENGTH || newPin.length > MAX_LENGTH -> "PIN must be $MIN_LENGTH–$MAX_LENGTH digits."
        !newPin.all { it.isDigit() } -> "PIN must contain digits only."
        newPin != confirmPin -> "The two new PINs don't match."
        else -> null
    }

    /** Milliseconds still to wait before another attempt is allowed (0 = allowed now). */
    fun lockoutRemaining(failedAttempts: Int, lastFailureAt: Long, now: Long): Long {
        if (failedAttempts < MAX_FAILED_ATTEMPTS) return 0
        return (lastFailureAt + LOCKOUT_MS - now).coerceAtLeast(0)
    }

    /** True if an unlocked admin session has gone unused for too long. */
    fun isIdleExpired(lastActivityAt: Long, now: Long): Boolean = now - lastActivityAt >= IDLE_TIMEOUT_MS
}
