package com.example.jack

import java.util.UUID

/**
 * A user-created alert: when a message from the ESP32 contains [triggerText]
 * (anywhere in the line, ignoring upper/lower case), Jack plays [toneUri].
 * Jack has no built-in alerts — every rule comes from the user.
 */
data class AlertRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val triggerText: String,
    val toneUri: String
)

/** Pure Kotlin (no Android types) so it can be unit-tested on the JVM. */
object AlertMatcher {

    /** Every rule whose trigger appears in [line], in the order the user created them. */
    fun matching(line: String, rules: List<AlertRule>): List<AlertRule> =
        rules.filter { rule ->
            val trigger = rule.triggerText.trim()
            trigger.isNotEmpty() && line.contains(trigger, ignoreCase = true)
        }
}
