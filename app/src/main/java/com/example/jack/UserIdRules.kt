package com.example.jack

/** Pure Kotlin rules for Attendee IDs (no Android types, so they can be unit-tested on the JVM). */
object UserIdRules {

    /** IDs are compared ignoring surrounding spaces and upper/lower case ("ab12 " == "AB12"). */
    fun normalize(id: String): String = id.trim().lowercase()

    /** Normalized IDs that appear more than once. */
    fun findDuplicates(ids: List<String>): Set<String> =
        ids.groupingBy { normalize(it) }.eachCount().filter { it.value > 1 }.keys

    /** True if [newId] is already used by one of [existingIds]. */
    fun isTaken(newId: String, existingIds: List<String>): Boolean {
        val n = normalize(newId)
        return existingIds.any { normalize(it) == n }
    }
}
