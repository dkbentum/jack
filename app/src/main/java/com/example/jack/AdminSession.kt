package com.example.jack

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import java.util.UUID

/**
 * Admin access for Settings, Alerts and the Terminal.
 *  - Unlocked by the admin PIN (default 1234 until changed; stored only as a salted hash).
 *  - Locks again as soon as all admin screens are closed, or after 2 minutes without a touch.
 *  - 5 wrong PINs → 30-second pause before the next try.
 */
object AdminSession {
    private const val PREF_PIN_HASH = "pref_admin_pin_hash"
    private const val PREF_PIN_SALT = "pref_admin_pin_salt"
    private const val PREF_LEGACY_PIN = "pref_admin_pin"   // plain-text key from before v2.1 (never actually written)

    var isUnlocked by mutableStateOf(false)
        private set

    private var lastActivityAt = 0L
    private var failedAttempts = 0
    private var lastFailureAt = 0L

    private fun now() = SystemClock.elapsedRealtime()
    private fun prefs(context: Context) = context.getSharedPreferences(JackCore.PREF_NAME, Context.MODE_PRIVATE)

    /** True while the admin still uses the default PIN (shown as a hint, and a nudge to change it). */
    fun isDefaultPin(context: Context): Boolean = prefs(context).getString(PREF_PIN_HASH, null) == null &&
        prefs(context).getString(PREF_LEGACY_PIN, null) == null

    /** Milliseconds until another PIN attempt is allowed. */
    fun lockoutRemainingMs(): Long = AdminPinPolicy.lockoutRemaining(failedAttempts, lastFailureAt, now())

    /** Verifies the PIN; unlocks on success. */
    fun tryUnlock(context: Context, pin: String): Boolean {
        if (lockoutRemainingMs() > 0) return false
        if (verify(context, pin)) {
            failedAttempts = 0
            isUnlocked = true
            touch()
            return true
        }
        failedAttempts++
        lastFailureAt = now()
        return false
    }

    fun verify(context: Context, pin: String): Boolean {
        val p = prefs(context)
        // Honour a plain-text PIN if one was ever set by an older build, then it gets hashed on next change.
        p.getString(PREF_LEGACY_PIN, null)?.let { legacy -> if (p.getString(PREF_PIN_HASH, null) == null) return pin == legacy }
        return AdminPinPolicy.matches(pin, p.getString(PREF_PIN_HASH, null), p.getString(PREF_PIN_SALT, null))
    }

    /** Saves a new PIN (as salted hash). Caller must have verified the current PIN. */
    fun setPin(context: Context, newPin: String) {
        val salt = UUID.randomUUID().toString()
        prefs(context).edit()
            .putString(PREF_PIN_SALT, salt)
            .putString(PREF_PIN_HASH, AdminPinPolicy.hash(newPin, salt))
            .remove(PREF_LEGACY_PIN)
            .apply()
    }

    fun lock() {
        isUnlocked = false
    }

    /** Records admin activity (any touch on an admin screen) to postpone the idle lock. */
    fun touch() {
        lastActivityAt = now()
    }

    fun isIdleExpired(): Boolean = isUnlocked && AdminPinPolicy.isIdleExpired(lastActivityAt, now())
}

/** Counts any touch inside this element as admin activity (doesn't consume the touch). */
fun Modifier.adminActivity(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial)
            AdminSession.touch()
        }
    }
}
